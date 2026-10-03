package dev.flowtrail;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentCommandsTest {
  static final ObjectMapper JSON = new ObjectMapper();
  @TempDir Path root;

  @Test
  void directToolLoopKeepsPairing() throws Exception {
    var policy = new dev.flowtrail.policy.ExecutionPolicy(root, false, false, java.util.Set.of());
    var tools =
        new dev.flowtrail.tool.ToolRegistry(policy, java.time.Duration.ofSeconds(2), s -> {});
    dev.flowtrail.tool.BuiltinTools.register(tools, policy, java.time.Duration.ofSeconds(2));
    var result =
        new dev.flowtrail.agent.AgentLoop(
                new dev.flowtrail.model.MockProvider(null), tools, 3, 24000, s -> {})
            .run("list files", null);
    assertTrue(result.ok());
    assertEquals("tool", result.messages().get(3).get("role"));
  }

  @Test
  void readsFileAndReturnsToolTraceAsOneJsonObject() throws Exception {
    Files.writeString(root.resolve("Example.java"), "class Example {}\n");
    Result result =
        agent(
            """
            [{"toolCalls":[{"id":"read1","name":"read_file","arguments":{"path":"Example.java"}}]},
             {"content":"Example.java:1 defines Example."}]
            """);
    assertEquals(0, result.code, result.err);
    JsonNode data = JSON.readTree(result.out);
    assertEquals("completed", data.path("status").asText());
    assertTrue(
        data.path("trace").get(0).path("result").path("output").asText().contains("class Example"));
    assertEquals("Example.java:1 defines Example.", data.path("answer").asText());
    assertTrue(result.err.isEmpty());
  }

  @Test
  void unapprovedPatchReturnsPreviewAndCannotWrite() throws Exception {
    Files.writeString(root.resolve("file.txt"), "old");
    Result result = agent(patchScript());
    assertEquals(1, result.code);
    JsonNode data = JSON.readTree(result.out);
    assertEquals(
        "approval_required", data.path("trace").get(0).path("result").path("status").asText());
    assertTrue(data.path("trace").get(0).path("result").path("preview").asText().contains("+new"));
    assertEquals("old", Files.readString(root.resolve("file.txt")));
  }

  @Test
  void approvedPatchChecksExpectedContentAndWritesOnce() throws Exception {
    Files.writeString(root.resolve("file.txt"), "old");
    Result result = agent(patchScript(), "--allow-write");
    assertEquals(0, result.code, result.err);
    assertEquals("new", Files.readString(root.resolve("file.txt")));
    String audit = Files.readString(root.resolve(".flowtrail/audit.jsonl"));
    assertFalse(audit.contains("\"before\""));
    assertFalse(audit.contains("\"after\""));
  }

  @Test
  void approvalDoesNotPermitTraversal() throws Exception {
    Path outside = root.getParent().resolve("outside-" + root.getFileName() + ".txt");
    Files.writeString(outside, "untouched");
    try {
      String script =
          JSON.writeValueAsString(
              List.of(
                  java.util.Map.of(
                      "toolCalls",
                      List.of(
                          java.util.Map.of(
                              "id",
                              "bad",
                              "name",
                              "apply_patch",
                              "arguments",
                              java.util.Map.of(
                                  "path",
                                  "../" + outside.getFileName(),
                                  "before",
                                  "untouched",
                                  "after",
                                  "changed"))))));
      Result result = agent(script, "--allow-write");
      assertEquals(1, result.code);
      assertEquals(
          "denied",
          JSON.readTree(result.out).path("trace").get(0).path("result").path("status").asText());
      assertEquals("untouched", Files.readString(outside));
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void malformedArgumentsNeverExecuteAndRepeatedFailuresStop() throws Exception {
    Result result =
        agent(
            """
            [{"toolCalls":[{"id":"bad","name":"apply_patch","arguments":"{broken"}]}]
            """,
            "--allow-write",
            "--max-rounds",
            "8");
    assertEquals(1, result.code);
    JsonNode data = JSON.readTree(result.out);
    assertEquals("repeated_failure", data.path("status").asText());
    assertEquals(3, data.path("trace").size());
    assertFalse(Files.exists(root.resolve("file.txt")));
  }

  @Test
  void modelWithoutFinalAnswerStopsAtRoundBudget() throws Exception {
    Result result =
        agent(
            "[{\"toolCalls\":[{\"id\":\"list\",\"name\":\"list_files\",\"arguments\":{}}]}]",
            "--max-rounds",
            "2");
    assertEquals(1, result.code);
    assertEquals("round_budget", JSON.readTree(result.out).path("status").asText());
    assertEquals(2, JSON.readTree(result.out).path("trace").size());
  }

  @Test
  void commandCannotRunWithoutExactUserAllowlist() throws Exception {
    Result result =
        agent(
            """
[{"toolCalls":[{"id":"exec","name":"run_command","arguments":{"command":["java","-version"]}}]}]
""");
    assertEquals(1, result.code);
    assertEquals(
        "denied",
        JSON.readTree(result.out).path("trace").get(0).path("result").path("status").asText());
  }

  @Test
  void searchToolUsesPersistedIndexAndSessionResumesAsData() throws Exception {
    Files.writeString(
        root.resolve("Pricing.java"), "class Pricing { int calculateDiscount() { return 2; } }");
    new dev.flowtrail.rag.CodeIndex(root, dev.flowtrail.rag.Embeddings.mock()).update();
    String script =
        """
[{"toolCalls":[{"id":"search","name":"search_code","arguments":{"query":"calculateDiscount","limit":3}}]},
 {"content":"Pricing.java:1 defines calculateDiscount."}]
""";
    var result = agent(script, "--session", "review-session");
    assertEquals(0, result.code, result.err);
    assertTrue(result.out.contains("calculateDiscount"));
    var stored =
        new dev.flowtrail.memory.MemoryStore(root).loadSession("review-session").orElseThrow();
    assertTrue(stored.messagesJson().contains("Pricing.java"));
    result = agent("[{\"content\":\"continued\"}]", "--session", "review-session");
    assertEquals(0, result.code, result.err);
    stored = new dev.flowtrail.memory.MemoryStore(root).loadSession("review-session").orElseThrow();
    assertTrue(stored.messagesJson().contains("continued"));
    assertTrue(stored.messagesJson().contains("calculateDiscount"));
  }

  private String patchScript() {
    return """
[{"toolCalls":[{"id":"write","name":"apply_patch","arguments":{"path":"file.txt","before":"old","after":"new"}}]},
{"content":"Updated file.txt."}]
""";
  }

  private Result agent(String script, String... extra) throws Exception {
    Path fixture = root.resolve("model-script.json");
    Files.writeString(fixture, script);
    List<String> args =
        new ArrayList<>(
            List.of(
                "agent",
                "inspect code",
                "--project",
                root.toString(),
                "--provider",
                "mock",
                "--script",
                fixture.toString(),
                "--json"));
    args.addAll(List.of(extra));
    return invoke(args.toArray(String[]::new));
  }

  static Result invoke(String... args) {
    StringWriter out = new StringWriter(), err = new StringWriter();
    int code = FlowTrail.execute(args, new PrintWriter(out, true), new PrintWriter(err, true));
    return new Result(code, out.toString(), err.toString());
  }

  record Result(int code, String out, String err) {}
}
