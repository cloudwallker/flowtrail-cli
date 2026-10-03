package dev.flowtrail;

import static org.junit.jupiter.api.Assertions.*;

import dev.flowtrail.agent.AgentLoop;
import dev.flowtrail.model.ModelProvider;
import dev.flowtrail.policy.ExecutionPolicy;
import dev.flowtrail.tool.BuiltinTools;
import dev.flowtrail.tool.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentBoundaryTest {
  @TempDir Path root;

  @Test
  void toolSchemasCountTowardsInputBudgetBeforeCallingModel() throws Exception {
    var policy = new ExecutionPolicy(root, false, false, Set.of());
    var registry = new ToolRegistry(policy, Duration.ofSeconds(1), s -> {});
    registry.register(
        new BuiltinTools.Basic(
            "large",
            "x".repeat(6000),
            dev.flowtrail.tool.Tool.Effect.READ,
            BuiltinTools.schema(java.util.Map.of(), List.of())) {
          public String execute(com.fasterxml.jackson.databind.JsonNode args) {
            return "ok";
          }
        });
    AtomicInteger calls = new AtomicInteger();
    ModelProvider provider =
        (m, t, o) -> {
          calls.incrementAndGet();
          return new ModelProvider.Response("answer", List.of());
        };
    var result = new AgentLoop(provider, registry, 1, 4000, s -> {}).run("inspect", null);
    assertEquals("context_budget", result.status());
    assertEquals(0, calls.get());
  }

  @Test
  void wrongTypedOptionalArgumentsFailBeforeToolExecution() throws Exception {
    var policy = new ExecutionPolicy(root, false, false, Set.of());
    var registry = new ToolRegistry(policy, Duration.ofSeconds(1), s -> {});
    BuiltinTools.register(registry, policy, Duration.ofSeconds(1));
    var result =
        registry.execute(
            "session", new ModelProvider.ToolCall("list", "list_files", "{\"path\":3}"), null);
    assertEquals("invalid_arguments", result.status());
  }

  @Test
  void symbolicAliasCannotBypassProtectedPaths() throws Exception {
    Path secret = root.resolve(".git/config");
    Files.createDirectories(secret.getParent());
    Files.writeString(secret, "private-config");
    try {
      Files.createSymbolicLink(root.resolve("alias"), secret.getParent());
    } catch (Exception e) {
      org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable in this environment");
    }
    var policy = new ExecutionPolicy(root, true, true, Set.of());
    assertThrows(SecurityException.class, () -> policy.resolve("alias/config"));
  }

  @Test
  void cancellationPreventsTheNextToolCallAndPreservesInterrupt() throws Exception {
    var policy = new ExecutionPolicy(root, false, false, Set.of());
    var registry = new ToolRegistry(policy, Duration.ofSeconds(1), s -> {});
    BuiltinTools.register(registry, policy, Duration.ofSeconds(1));
    AtomicInteger calls = new AtomicInteger();
    ModelProvider provider =
        (messages, tools, output) -> {
          calls.incrementAndGet();
          Thread.currentThread().interrupt();
          return new ModelProvider.Response(
              "", List.of(new ModelProvider.ToolCall("call", "list_files", "{}")));
        };
    try {
      assertThrows(
          InterruptedException.class,
          () -> new AgentLoop(provider, registry, 2, 10000, s -> {}).run("inspect", null));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(1, calls.get());
      assertFalse(Files.exists(root.resolve(".flowtrail/audit.jsonl")));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void duplicateToolIdsRejectWholeTurnBeforeAWrite() throws Exception {
    Files.writeString(root.resolve("file.txt"), "old");
    var policy = new ExecutionPolicy(root, true, false, Set.of());
    var registry = new ToolRegistry(policy, Duration.ofSeconds(1), s -> {});
    BuiltinTools.register(registry, policy, Duration.ofSeconds(1));
    ModelProvider provider =
        (m, t, o) ->
            new ModelProvider.Response(
                "",
                List.of(
                    new ModelProvider.ToolCall(
                        "same",
                        "apply_patch",
                        "{\"path\":\"file.txt\",\"before\":\"old\",\"after\":\"new\"}"),
                    new ModelProvider.ToolCall("same", "list_files", "{}")));
    var result = new AgentLoop(provider, registry, 1, 10000, s -> {}).run("change", null);
    assertFalse(result.ok());
    assertEquals("old", Files.readString(root.resolve("file.txt")));
    assertEquals("invalid_tool_calls", result.status());
  }
}
