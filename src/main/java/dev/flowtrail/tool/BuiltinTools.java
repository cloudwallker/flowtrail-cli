package dev.flowtrail.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.flowtrail.model.Json;
import dev.flowtrail.policy.ExecutionPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class BuiltinTools {
  private BuiltinTools() {}

  public static void register(ToolRegistry registry, ExecutionPolicy policy, Duration timeout) {
    registry.register(
        new Basic(
            "read_file",
            "Read a UTF-8 file with line numbers; optional startLine and endLine.",
            Tool.Effect.READ,
            schema(
                Map.of(
                    "path",
                    Map.of("type", "string"),
                    "startLine",
                    Map.of("type", "integer"),
                    "endLine",
                    Map.of("type", "integer")),
                List.of("path"))) {
          public void validate(JsonNode a) throws Exception {
            super.validate(a);
            policy.resolve(required(a, "path"));
            for (String field : List.of("startLine", "endLine"))
              if (a.has(field) && (!a.get(field).isInt() || a.get(field).asInt() < 1))
                throw new IllegalArgumentException();
          }

          public String execute(JsonNode a) throws Exception {
            Path p = policy.resolve(required(a, "path"));
            if (!Files.isRegularFile(p) || Files.size(p) > 2_000_000)
              throw new IllegalArgumentException();
            int from = a.path("startLine").asInt(1), to = a.path("endLine").asInt(from + 199);
            if (to < from || to - from > 999) throw new IllegalArgumentException();
            List<String> lines = Files.readAllLines(p);
            StringBuilder result = new StringBuilder(a.path("path").asText() + "\n");
            for (int i = from - 1; i < Math.min(to, lines.size()); i++)
              result.append(i + 1).append(": ").append(lines.get(i)).append('\n');
            return result.toString();
          }
        });
    registry.register(
        new Basic(
            "list_files",
            "List up to 200 project files, excluding protected and build paths.",
            Tool.Effect.READ,
            schema(Map.of("path", Map.of("type", "string")), List.of())) {
          public void validate(JsonNode a) throws Exception {
            super.validate(a);
            policy.resolve(a.path("path").asText("."));
          }

          public String execute(JsonNode a) throws Exception {
            Path p = policy.resolve(a.path("path").asText("."));
            try (var walk = Files.walk(p, 8)) {
              return walk.filter(Files::isRegularFile)
                  .filter(
                      f -> {
                        try {
                          policy.resolve(policy.root().relativize(f).toString());
                          return !f.toString()
                              .matches(".*[\\\\/](target|node_modules|\\.cache)[\\\\/].*");
                        } catch (Exception e) {
                          return false;
                        }
                      })
                  .limit(200)
                  .map(f -> policy.root().relativize(f).toString().replace('\\', '/'))
                  .reduce("", (x, y) -> x + y + "\n");
            }
          }
        });
    registry.register(
        new Basic(
            "apply_patch",
            "Replace exactly one occurrence after showing a diff; expected text is a concurrency"
                + " guard.",
            Tool.Effect.WRITE,
            schema(
                Map.of(
                    "path",
                    Map.of("type", "string"),
                    "before",
                    Map.of("type", "string"),
                    "after",
                    Map.of("type", "string")),
                List.of("path", "before", "after"))) {
          public void validate(JsonNode a) throws Exception {
            super.validate(a);
            Path p = policy.resolve(required(a, "path"));
            required(a, "before");
            required(a, "after");
            if (Files.exists(p) && Files.size(p) > 1_000_000) throw new IllegalArgumentException();
          }

          public String preview(JsonNode a) {
            return "--- "
                + a.path("path").asText()
                + "\n+++ "
                + a.path("path").asText()
                + "\n-"
                + a.path("before").asText().replace("\n", "\n-")
                + "\n+"
                + a.path("after").asText().replace("\n", "\n+");
          }

          public String execute(JsonNode a) throws Exception {
            synchronized (policy) {
              Path p = policy.resolve(required(a, "path"));
              String before = a.path("before").asText(), after = a.path("after").asText();
              String current = Files.exists(p) ? Files.readString(p) : "";
              if (before.isEmpty() && !current.isEmpty()
                  || !before.isEmpty()
                      && (!current.contains(before)
                          || current.indexOf(before) != current.lastIndexOf(before)))
                throw new IllegalArgumentException("Expected content mismatch");
              String next = before.isEmpty() ? after : current.replace(before, after);
              Path parent = p.getParent();
              if (!Files.isDirectory(parent)) throw new IllegalArgumentException();
              Path temp = Files.createTempFile(parent, ".flowtrail-edit-", ".tmp");
              try {
                Files.writeString(temp, next);
                policy.resolve(a.path("path").asText());
                Files.move(
                    temp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
              } finally {
                Files.deleteIfExists(temp);
              }
              return "Updated " + a.path("path").asText();
            }
          }
        });
    registry.register(
        new Basic(
            "run_command",
            "Run one exact user-allowlisted program and argument vector. Shells are not implicit.",
            Tool.Effect.COMMAND,
            schema(
                Map.of("command", Map.of("type", "array", "items", Map.of("type", "string"))),
                List.of("command"))) {
          public void validate(JsonNode a) throws Exception {
            super.validate(a);
            List<String> cmd = command(a);
            if (!policy.allowsCommand(cmd)) throw new SecurityException("Command not allowlisted");
          }

          public String execute(JsonNode a) throws Exception {
            synchronized (policy) {
              List<String> cmd = command(a);
              if (!policy.allowsCommand(cmd)) throw new SecurityException();
              Process process =
                  new ProcessBuilder(cmd)
                      .directory(policy.root().toFile())
                      .redirectErrorStream(true)
                      .start();
              var reader = Executors.newVirtualThreadPerTaskExecutor();
              var output =
                  reader.submit(
                      () -> {
                        try (var input = process.getInputStream()) {
                          byte[] buf = new byte[4096];
                          java.io.ByteArrayOutputStream kept = new java.io.ByteArrayOutputStream();
                          int count;
                          while ((count = input.read(buf)) != -1) {
                            if (kept.size() < 16000)
                              kept.write(buf, 0, Math.min(count, 16000 - kept.size()));
                          }
                          return kept.toString(StandardCharsets.UTF_8);
                        }
                      });
              try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
                  throw new java.util.concurrent.TimeoutException();
                return Json.encode(
                    Map.of(
                        "exitCode",
                        process.exitValue(),
                        "output",
                        output.get(2, TimeUnit.SECONDS)));
              } finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                if (process.isAlive()) process.destroyForcibly();
                output.cancel(true);
                reader.shutdownNow();
              }
            }
          }
        });
  }

  private static List<String> command(JsonNode a) {
    JsonNode items = a.path("command");
    if (!items.isArray() || items.isEmpty() || items.size() > 64)
      throw new IllegalArgumentException();
    List<String> cmd = new ArrayList<>();
    for (JsonNode s : items) {
      if (!s.isTextual() || s.asText().contains("\u0000")) throw new IllegalArgumentException();
      cmd.add(s.asText());
    }
    return List.copyOf(cmd);
  }

  public static String required(JsonNode a, String key) {
    if (!a.has(key) || !a.get(key).isTextual())
      throw new IllegalArgumentException("Missing string field");
    return a.get(key).asText();
  }

  public static Map<String, Object> schema(Map<String, Object> fields, List<String> required) {
    return Map.of(
        "type",
        "object",
        "properties",
        fields,
        "required",
        required,
        "additionalProperties",
        false);
  }

  public abstract static class Basic implements Tool {
    private final String name, description;
    private final Effect effect;
    private final Map<String, Object> schema;

    protected Basic(String name, String description, Effect effect, Map<String, Object> schema) {
      this.name = name;
      this.description = description;
      this.effect = effect;
      this.schema = schema;
    }

    public String name() {
      return name;
    }

    public String description() {
      return description;
    }

    public Effect effect() {
      return effect;
    }

    public Map<String, Object> schema() {
      return schema;
    }

    public void validate(JsonNode args) throws Exception {
      dev.flowtrail.mcp.SchemaValidator.validate(Json.MAPPER.valueToTree(schema), args);
    }
  }
}
