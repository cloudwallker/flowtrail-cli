package dev.flowtrail;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanCommandsTest {
  @TempDir Path root;

  @Test
  void rejectsCycleBeforeAnyModelOrToolCall() throws Exception {
    Path plan =
        save(
            "cycle.json",
            Map.of(
                "version",
                1,
                "tasks",
                List.of(task("a", "a", List.of("b")), task("b", "b", List.of("a")))));
    var result =
        AgentCommandsTest.invoke("plan", plan.toString(), "--project", root.toString(), "--json");
    assertEquals(2, result.code());
    assertTrue(result.err().contains("cycle"));
    assertFalse(Files.exists(root.resolve(".flowtrail/audit.jsonl")));
  }

  @Test
  void rejectsUnknownDependencyAndNonAncestorReference() throws Exception {
    Path plan =
        save(
            "unknown.json",
            Map.of("version", 1, "tasks", List.of(task("a", "a", List.of("missing")))));
    var result =
        AgentCommandsTest.invoke("plan", plan.toString(), "--project", root.toString(), "--json");
    assertEquals(2, result.code());
    assertTrue(result.err().contains("dependency"));
    plan =
        save(
            "reference.json",
            Map.of(
                "version",
                1,
                "tasks",
                List.of(task("a", "a", List.of()), task("b", "${a.output}", List.of()))));
    result =
        AgentCommandsTest.invoke("plan", plan.toString(), "--project", root.toString(), "--json");
    assertEquals(2, result.code());
    assertTrue(result.err().contains("reference"));
  }

  @Test
  void readBranchesOverlapAndJoinRunsOnceAfterBoth() throws Exception {
    CountDownLatch branches = new CountDownLatch(2);
    AtomicInteger joined = new AtomicInteger();
    AtomicInteger overlap = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/chat/completions",
        exchange -> {
          String request =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String text;
          if (request.contains("BRANCH_")) {
            branches.countDown();
            try {
              if (branches.await(4, TimeUnit.SECONDS)) overlap.incrementAndGet();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            text = request.contains("BRANCH_A") ? "A evidence" : "B evidence";
          } else {
            joined.incrementAndGet();
            text =
                request.contains("A evidence") && request.contains("B evidence")
                    ? "joined evidence"
                    : "MISSING";
          }
          String body =
              "data: "
                  + AgentCommandsTest.JSON.writeValueAsString(
                      Map.of("choices", List.of(Map.of("delta", Map.of("content", text)))))
                  + "\n\ndata: [DONE]\n\n";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      Path plan =
          save(
              "parallel.json",
              Map.of(
                  "version",
                  1,
                  "tasks",
                  List.of(
                      task("a", "BRANCH_A", List.of()),
                      task("b", "BRANCH_B", List.of()),
                      task("join", "Join ${a.output} and ${b.output}", List.of("a", "b")))));
      var result =
          AgentCommandsTest.invoke(
              "plan",
              plan.toString(),
              "--project",
              root.toString(),
              "--provider",
              "openai",
              "--base-url",
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "--model",
              "test",
              "--parallelism",
              "2",
              "--json");
      assertEquals(0, result.code(), result.err());
      assertEquals(2, overlap.get());
      assertEquals(1, joined.get());
      assertTrue(result.out().contains("joined evidence"));
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void reviewerRejectsFailedVerificationAndDoesNotReplayCompletedWrite() throws Exception {
    Files.writeString(root.resolve("file.txt"), "old");
    Path script =
        save(
            "patch.json",
            List.of(
                Map.of(
                    "toolCalls",
                    List.of(
                        Map.of(
                            "id",
                            "patch",
                            "name",
                            "apply_patch",
                            "arguments",
                            Map.of("path", "file.txt", "before", "old", "after", "new")))),
                Map.of("content", "updated")));
    Map<String, Object> task =
        Map.of(
            "id",
            "write",
            "goal",
            "patch file",
            "dependsOn",
            List.of(),
            "tools",
            List.of("apply_patch"),
            "expectedOutput",
            "patch result",
            "script",
            script.toString(),
            "requiredTools",
            List.of("run_command"));
    Path plan = save("review.json", Map.of("version", 1, "tasks", List.of(task)));
    var result =
        AgentCommandsTest.invoke(
            "plan",
            plan.toString(),
            "--project",
            root.toString(),
            "--allow-write",
            "--max-repairs",
            "2",
            "--json");
    assertEquals(1, result.code());
    assertEquals("new", Files.readString(root.resolve("file.txt")));
    assertTrue(result.out().contains("review_rejected"));
    String audit = Files.readString(root.resolve(".flowtrail/audit.jsonl"));
    assertEquals(1, audit.lines().filter(line -> line.contains("apply_patch")).count());
  }

  private Map<String, Object> task(String id, String goal, List<String> depends) {
    return Map.of(
        "id",
        id,
        "goal",
        goal,
        "dependsOn",
        depends,
        "tools",
        List.of("read_file", "list_files"),
        "expectedOutput",
        "analysis");
  }

  private Path save(String file, Object value) throws Exception {
    Path p = root.resolve(file);
    Files.writeString(p, AgentCommandsTest.JSON.writeValueAsString(value));
    return p;
  }
}
