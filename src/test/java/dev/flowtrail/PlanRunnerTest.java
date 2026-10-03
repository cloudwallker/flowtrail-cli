package dev.flowtrail;

import static org.junit.jupiter.api.Assertions.*;

import dev.flowtrail.agent.AgentLoop;
import dev.flowtrail.plan.PlanDefinition;
import dev.flowtrail.plan.PlanRunner;
import dev.flowtrail.policy.ExecutionPolicy;
import dev.flowtrail.tool.BuiltinTools;
import dev.flowtrail.tool.ToolRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanRunnerTest {
  @TempDir Path root;

  @Test
  void mutationWorkersAreSerializedAndReadRepairsHaveAnExactBound() throws Exception {
    var policy = new ExecutionPolicy(root, true, false, Set.of());
    var tools = new ToolRegistry(policy, Duration.ofSeconds(1), s -> {});
    BuiltinTools.register(tools, policy, Duration.ofSeconds(1));
    var plan =
        new PlanDefinition(
            1, List.of(task("a", List.of("apply_patch")), task("b", List.of("run_command"))));
    AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
    var result =
        new PlanRunner(tools, 4, 0)
            .run(
                plan,
                (task, goal, feedback) -> {
                  int n = active.incrementAndGet();
                  peak.accumulateAndGet(n, Math::max);
                  Thread.sleep(50);
                  active.decrementAndGet();
                  return success(task.id());
                },
                (t, r) -> new PlanRunner.Review(true, "ok"),
                null);
    assertTrue(result.ok());
    assertEquals(1, peak.get());
    AtomicInteger attempts = new AtomicInteger();
    result =
        new PlanRunner(tools, 2, 2)
            .run(
                new PlanDefinition(1, List.of(task("read", List.of("read_file")))),
                (task, goal, feedback) -> {
                  attempts.incrementAndGet();
                  return success("read");
                },
                (t, r) -> new PlanRunner.Review(false, "missing test evidence"),
                null);
    assertFalse(result.ok());
    assertEquals(3, attempts.get());
    assertEquals(3, result.attempts().size());
  }

  @Test
  void explicitReplanPreservesCompletedWriteAndValidatesNewDependencies() throws Exception {
    var policy = new ExecutionPolicy(root, true, false, Set.of());
    var tools = new ToolRegistry(policy, Duration.ofSeconds(1), s -> {});
    BuiltinTools.register(tools, policy, Duration.ofSeconds(1));
    var write = task("write", List.of("apply_patch"));
    AtomicInteger writes = new AtomicInteger();
    var result =
        new PlanRunner(tools, 2, 1)
            .run(
                new PlanDefinition(1, List.of(write)),
                (task, goal, feedback) -> {
                  if (task.id().equals("write")) writes.incrementAndGet();
                  return success(task.id());
                },
                (t, r) -> new PlanRunner.Review(t.id().equals("verify"), "verify the change"),
                (prior, completed, feedback, attempt) ->
                    new PlanDefinition(
                        1,
                        List.of(
                            write,
                            new PlanDefinition.Task(
                                "verify",
                                "verify ${write.output}",
                                List.of("write"),
                                List.of("read_file"),
                                "evidence",
                                null,
                                List.of()))));
    assertTrue(result.ok());
    assertEquals(1, writes.get());
    assertEquals(1, result.revisions());
    assertEquals(2, result.attempts().size());
  }

  private PlanDefinition.Task task(String id, List<String> tools) {
    return new PlanDefinition.Task(id, id, List.of(), tools, "output", null, List.of());
  }

  private AgentLoop.Result success(String text) {
    return new AgentLoop.Result(true, "session", "completed", text, 1, List.of(), List.of());
  }
}
