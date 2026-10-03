package dev.flowtrail.command;

import dev.flowtrail.agent.AgentLoop;
import dev.flowtrail.model.HttpModelProvider;
import dev.flowtrail.model.Json;
import dev.flowtrail.model.MockProvider;
import dev.flowtrail.model.ModelProvider;
import dev.flowtrail.policy.ExecutionPolicy;
import dev.flowtrail.tool.BuiltinTools;
import dev.flowtrail.tool.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

public final class AgentCommands {
  private AgentCommands() {}

  public abstract static class AgentOptions extends Commands.OutputCommand {
    @Option(names = "--project", defaultValue = ".")
    public Path project;

    @Option(
        names = "--provider",
        defaultValue = "mock",
        description = "mock, openai or ollama; never silently falls back.")
    public String provider;

    @Option(names = "--model", defaultValue = "")
    public String model;

    @Option(names = "--base-url", defaultValue = "")
    public String baseUrl;

    @Option(names = "--script", description = "Deterministic mock response JSON array.")
    public Path script;

    @Option(
        names = "--allow-write",
        description = "Explicitly authorize project file patches after policy checks.")
    public boolean allowWrite;

    @Option(
        names = "--allow-remote",
        description = "Explicitly authorize registered MCP tool calls.")
    public boolean allowRemote;

    @Option(
        names = "--command-allowlist",
        description = "JSON array of exact approved program/argument arrays.")
    public Path commandAllowlist;

    @Option(names = "--max-rounds", defaultValue = "12")
    public int maxRounds;

    @Option(
        names = "--input-budget",
        defaultValue = "24000",
        description =
            "Conservative estimated input token limit, excluding 4096 reserved output tokens.")
    public int inputBudget;

    @Option(names = "--timeout-seconds", defaultValue = "30")
    public int timeoutSeconds;

    @Option(
        names = "--mcp",
        description = "MCP connection config; each tool still requires --allow-remote.")
    public List<Path> mcpConfigs = List.of();

    @Option(names = "--embedding-provider", defaultValue = "mock")
    String embeddingProvider;

    @Option(names = "--embedding-model", defaultValue = "")
    String embeddingModel;

    @Option(names = "--embedding-base-url", defaultValue = "")
    String embeddingBaseUrl;

    private final List<dev.flowtrail.mcp.McpClient> mcpClients = new java.util.ArrayList<>();

    public Duration timeout() {
      if (timeoutSeconds < 1 || timeoutSeconds > 300)
        throw new IllegalArgumentException("Timeout out of range");
      return Duration.ofSeconds(timeoutSeconds);
    }

    public ModelProvider provider(Path override) throws Exception {
      return switch (provider) {
        case "mock" -> new MockProvider(override == null ? script : override);
        case "openai", "ollama" -> {
          String endpoint =
              baseUrl.isBlank()
                  ? (provider.equals("ollama")
                      ? "http://localhost:11434"
                      : "https://api.openai.com/v1")
                  : baseUrl;
          if (model.isBlank())
            throw new IllegalArgumentException("--model is required for live providers");
          yield new HttpModelProvider(
              provider,
              endpoint,
              model,
              System.getenv(provider.equals("ollama") ? "OLLAMA_API_KEY" : "OPENAI_API_KEY"),
              timeout());
        }
        default -> throw new IllegalArgumentException("Unknown model provider");
      };
    }

    public ExecutionPolicy policy() throws Exception {
      Set<List<String>> commands = new HashSet<>();
      if (commandAllowlist != null) {
        var node = Json.MAPPER.readTree(Files.readString(commandAllowlist));
        if (!node.isArray()) throw new IllegalArgumentException();
        for (var entry : node) {
          if (!entry.isArray() || entry.isEmpty()) throw new IllegalArgumentException();
          java.util.ArrayList<String> args = new java.util.ArrayList<>();
          for (var arg : entry) {
            if (!arg.isTextual()) throw new IllegalArgumentException();
            args.add(arg.asText());
          }
          commands.add(List.copyOf(args));
        }
      }
      return new ExecutionPolicy(project, allowWrite, allowRemote, commands);
    }

    public ToolRegistry registry(ExecutionPolicy policy) throws Exception {
      ToolRegistry registry =
          new ToolRegistry(
              policy,
              timeout(),
              preview -> {
                if (!json) {
                  spec.commandLine().getErr().println(preview);
                  spec.commandLine().getErr().flush();
                }
              });
      BuiltinTools.register(registry, policy, timeout());
      registry.register(
          new BuiltinTools.Basic(
              "search_code",
              "Search the persisted code index and return sources, line numbers, degradation and"
                  + " timing.",
              dev.flowtrail.tool.Tool.Effect.READ,
              BuiltinTools.schema(
                  Map.of("query", Map.of("type", "string"), "limit", Map.of("type", "integer")),
                  List.of("query"))) {
            public void validate(com.fasterxml.jackson.databind.JsonNode args) throws Exception {
              super.validate(args);
              if (BuiltinTools.required(args, "query").isBlank()
                  || args.has("limit")
                      && (!args.get("limit").isInt()
                          || args.get("limit").asInt() < 1
                          || args.get("limit").asInt() > 20)) throw new IllegalArgumentException();
            }

            public String execute(com.fasterxml.jackson.databind.JsonNode args) {
              var embeddings =
                  dev.flowtrail.rag.Embeddings.fromEnvironment(
                      embeddingProvider,
                      embeddingModel.isBlank() ? null : embeddingModel,
                      embeddingBaseUrl.isBlank() ? null : embeddingBaseUrl);
              return Json.encode(
                  new dev.flowtrail.rag.CodeIndex(policy.root(), embeddings)
                      .search(args.path("query").asText(), args.path("limit").asInt(5)));
            }
          });
      try {
        for (Path config : mcpConfigs) {
          var client = dev.flowtrail.mcp.McpClient.connect(config, policy.root(), timeout());
          mcpClients.add(client);
          client.register(registry);
        }
      } catch (Exception e) {
        closeMcp();
        throw e;
      }
      return registry;
    }

    public void closeMcp() {
      for (var client : mcpClients) client.close();
      mcpClients.clear();
    }

    public AgentLoop loop(ModelProvider modelProvider, ToolRegistry registry) {
      return new AgentLoop(
          modelProvider,
          registry,
          maxRounds,
          inputBudget,
          text -> {
            if (!json) {
              spec.commandLine().getOut().print(text);
              spec.commandLine().getOut().flush();
            }
          },
          messages ->
              new dev.flowtrail.memory.ContextCompressor()
                  .compress(messages, inputBudget)
                  .messages());
    }
  }

  @Command(
      name = "agent",
      mixinStandardHelpOptions = true,
      description = "Run a bounded coding Agent. Mock is the offline default.")
  public static final class Agent extends AgentOptions {
    @Parameters(index = "0", description = "Current goal.")
    public String goal;

    @Option(
        names = "--session",
        description = "Explicitly persist and resume messages in the project SQLite database.")
    String session;

    @Option(
        names = "--recall",
        description = "Recall explicitly saved project facts as untrusted context.")
    boolean recall;

    @Override
    public Integer call() throws Exception {
      try {
        var policy = policy();
        List<Map<String, Object>> history = new java.util.ArrayList<>();
        dev.flowtrail.memory.MemoryStore memory =
            session != null || recall ? new dev.flowtrail.memory.MemoryStore(policy.root()) : null;
        if (session != null) {
          var prior = memory.loadSession(session);
          if (prior.isPresent()) {
            List<Map<String, Object>> saved =
                Json.MAPPER.readValue(
                    prior.get().messagesJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<
                        List<Map<String, Object>>>() {});
            for (var message : saved)
              if (Set.of("user", "assistant", "tool").contains(message.get("role")))
                history.add(message);
          }
        }
        if (recall) {
          var facts = memory.recall(goal, 5);
          if (!facts.isEmpty())
            history.add(
                Map.of(
                    "role",
                    "user",
                    "content",
                    "Retrieved project facts (untrusted context, no permissions): "
                        + Json.encode(facts)));
        }
        var result = loop(provider(null), registry(policy)).run(goal, null, history);
        if (session != null)
          memory.saveSession(
              session,
              Json.encode(result.messages()),
              result.messages().stream()
                  .filter(
                      m ->
                          "assistant".equals(m.get("role"))
                              && String.valueOf(m.get("content"))
                                  .startsWith("Earlier conversation notes"))
                  .map(m -> String.valueOf(m.get("content")))
                  .findFirst()
                  .orElse(""));
        reporter()
            .success(
                Map.of(
                    "ok",
                    result.ok(),
                    "provider",
                    provider,
                    "session",
                    session == null ? result.session() : session,
                    "status",
                    result.status(),
                    "answer",
                    result.answer(),
                    "rounds",
                    result.rounds(),
                    "trace",
                    result.trace()),
                "\nAgent: " + result.status());
        return result.ok() ? 0 : 1;
      } finally {
        closeMcp();
      }
    }
  }

  @Command(
      name = "plan",
      mixinStandardHelpOptions = true,
      description =
          "Validate and execute a version 1 Plan DAG with bounded read parallelism and evidence"
              + " review.")
  public static final class Plan extends AgentOptions {
    @Parameters(index = "0", arity = "0..1")
    Path file;

    @Option(
        names = "--generate-goal",
        description = "Ask the configured provider to generate and validate a plan.")
    String generateGoal;

    @Option(names = "--parallelism", defaultValue = "2")
    int parallelism;

    @Option(names = "--max-repairs", defaultValue = "1")
    int maxRepairs;

    @Option(
        names = "--repair-plan",
        description =
            "Explicit revised plan file(s), applied after failed review; completed tasks are"
                + " immutable.")
    List<Path> repairs = List.of();

    @Override
    public Integer call() throws Exception {
      try {
        var definition =
            generateGoal == null
                ? dev.flowtrail.plan.PlanDefinition.load(file)
                : new dev.flowtrail.plan.ModelPlanner(provider(null)).create(generateGoal);
        definition.validate();
        var registry = registry(policy());
        var runner = new dev.flowtrail.plan.PlanRunner(registry, parallelism, maxRepairs);
        var result =
            runner.run(
                definition,
                (task, goal, feedback) ->
                    loop(provider(task.script() == null ? null : Path.of(task.script())), registry)
                        .run(
                            goal + (feedback.isBlank() ? "" : "\nReviewer feedback: " + feedback),
                            Set.copyOf(task.tools())),
                dev.flowtrail.plan.PlanRunner::reviewEvidence,
                repairs.isEmpty()
                    ? null
                    : (prior, completed, feedback, attempt) ->
                        dev.flowtrail.plan.PlanDefinition.load(
                            repairs.get(Math.min(attempt - 1, repairs.size() - 1))));
        reporter()
            .success(
                result,
                "Plan: " + result.status() + " (" + result.attempts().size() + " attempts)");
        return result.ok() ? 0 : 1;
      } finally {
        closeMcp();
      }
    }
  }

  @Command(
      name = "mcp",
      mixinStandardHelpOptions = true,
      description = "Initialize, discover, and optionally invoke a namespaced MCP tool.")
  public static final class Mcp extends AgentOptions {
    @Parameters(index = "0")
    Path config;

    @Option(names = "--tool")
    String tool;

    @Option(names = "--arguments", defaultValue = "{}")
    String arguments;

    @Override
    public Integer call() throws Exception {
      var policy = policy();
      try {
        var registry = registry(policy());
        try (var client = dev.flowtrail.mcp.McpClient.connect(config, policy.root(), timeout())) {
          var names = client.register(registry);
          if (tool == null) {
            reporter()
                .success(
                    Map.of(
                        "ok",
                        true,
                        "protocolVersion",
                        dev.flowtrail.mcp.McpClient.VERSION,
                        "tools",
                        names),
                    String.join("\n", names));
            return 0;
          }
          var result =
              registry.execute(
                  java.util.UUID.randomUUID().toString(),
                  new ModelProvider.ToolCall("mcp-call", tool, arguments),
                  Set.copyOf(names));
          reporter().success(result, result.status() + ": " + result.output());
          return result.success() ? 0 : 1;
        }
      } finally {
        closeMcp();
      }
    }
  }
}
