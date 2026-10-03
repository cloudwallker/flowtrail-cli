package dev.flowtrail.plan;

import dev.flowtrail.agent.AgentLoop;
import dev.flowtrail.model.Json;
import dev.flowtrail.tool.Tool;
import dev.flowtrail.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class PlanRunner {
  @FunctionalInterface
  public interface Worker {
    AgentLoop.Result execute(PlanDefinition.Task task, String goal, String feedback)
        throws Exception;
  }

  @FunctionalInterface
  public interface Reviewer {
    Review review(PlanDefinition.Task task, AgentLoop.Result result);
  }

  @FunctionalInterface
  public interface Replanner {
    PlanDefinition revise(
        PlanDefinition prior, Map<String, AgentLoop.Result> completed, String feedback, int attempt)
        throws Exception;
  }

  public record Review(boolean accepted, String feedback) {}

  public record Attempt(String taskId, int attempt, AgentLoop.Result result, Review review) {}

  public record Result(
      boolean ok,
      String status,
      Map<String, String> outputs,
      List<Attempt> attempts,
      int revisions) {}

  private final int parallelism, maxRepairs;
  private final ToolRegistry tools;

  public PlanRunner(ToolRegistry tools, int parallelism, int maxRepairs) {
    if (parallelism < 1 || parallelism > 8 || maxRepairs < 0 || maxRepairs > 5)
      throw new IllegalArgumentException("Invalid plan budget");
    this.tools = tools;
    this.parallelism = parallelism;
    this.maxRepairs = maxRepairs;
  }

  public Result run(PlanDefinition plan, Worker worker, Reviewer reviewer, Replanner replanner)
      throws Exception {
    validatePermissions(plan);
    Map<String, AgentLoop.Result> completed = new LinkedHashMap<>();
    Map<String, String> outputs = new LinkedHashMap<>();
    List<Attempt> attempts = new ArrayList<>();
    Map<String, Integer> retries = new HashMap<>();
    int revisions = 0;
    var pool =
        new ThreadPoolExecutor(
            parallelism, parallelism, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32));
    try {
      while (completed.size() < plan.tasks().size()) {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        List<PlanDefinition.Task> ready = new ArrayList<>();
        for (var task : plan.tasks())
          if (!completed.containsKey(task.id()) && completed.keySet().containsAll(task.dependsOn()))
            ready.add(task);
        if (ready.isEmpty()) throw new IllegalStateException("No ready plan tasks");
        List<PlanDefinition.Task> batch = new ArrayList<>();
        for (var task : ready) {
          if (mutating(task)) {
            if (batch.isEmpty()) batch.add(task);
            break;
          }
          batch.add(task);
          if (batch.size() == parallelism) break;
        }
        Map<String, String> snapshot = Map.copyOf(outputs);
        List<CompletableFuture<Attempt>> futures = new ArrayList<>();
        for (var task : batch) {
          int attempt = retries.getOrDefault(task.id(), 0) + 1;
          futures.add(
              CompletableFuture.supplyAsync(
                  () -> {
                    try {
                      AgentLoop.Result result =
                          worker.execute(
                              task,
                              PlanDefinition.render(task, snapshot),
                              attempt > 1
                                  ? "Previous review failed. Provide the missing real verification"
                                      + " evidence."
                                  : "");
                      return new Attempt(task.id(), attempt, result, reviewer.review(task, result));
                    } catch (Exception e) {
                      throw new java.util.concurrent.CompletionException(e);
                    }
                  },
                  pool));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
            .handle((v, e) -> null)
            .join();
        boolean failed = false;
        boolean reviewRejected = false;
        String feedback = "";
        for (int i = 0; i < batch.size(); i++) {
          var task = batch.get(i);
          Attempt attempt;
          try {
            attempt = futures.get(i).join();
          } catch (java.util.concurrent.CompletionException e) {
            failed = true;
            continue;
          }
          attempts.add(attempt);
          retries.put(task.id(), attempt.attempt());
          if (attempt.result().ok() && attempt.review().accepted()) {
            completed.put(task.id(), attempt.result());
            outputs.put(task.id(), attempt.result().answer());
          } else {
            feedback = attempt.review().feedback();
            if (mutating(
                task)) { // Never replay a task that may already have changed external state.
              completed.put(task.id(), attempt.result());
              outputs.put(task.id(), attempt.result().answer());
              reviewRejected = true;
            } else if (attempt.attempt() > maxRepairs) reviewRejected = true;
          }
        }
        if (failed)
          return new Result(
              false, "worker_failed", Map.copyOf(outputs), List.copyOf(attempts), revisions);
        if (reviewRejected) {
          if (replanner == null || revisions >= maxRepairs)
            return new Result(
                false, "review_rejected", Map.copyOf(outputs), List.copyOf(attempts), revisions);
          PlanDefinition next =
              replanner.revise(plan, Map.copyOf(completed), feedback, ++revisions);
          validatePermissions(next);
          Map<String, PlanDefinition.Task> old = plan.validate(), replacement = next.validate();
          for (String id : completed.keySet())
            if (!old.get(id).equals(replacement.get(id)))
              throw new IllegalArgumentException("Replan cannot change or remove completed tasks");
          if (next.tasks().size() == plan.tasks().size())
            return new Result(
                false, "review_rejected", Map.copyOf(outputs), List.copyOf(attempts), revisions);
          plan = next;
        }
      }
      return new Result(true, "completed", Map.copyOf(outputs), List.copyOf(attempts), revisions);
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private void validatePermissions(PlanDefinition plan) {
    plan.validate();
    for (var task : plan.tasks())
      if (!tools.names().containsAll(task.tools()))
        throw new dev.flowtrail.FlowException(2, "Unknown plan tool permission");
  }

  private boolean mutating(PlanDefinition.Task task) {
    return task.tools().stream().anyMatch(name -> tools.effect(name) != Tool.Effect.READ);
  }

  public static Review reviewEvidence(PlanDefinition.Task task, AgentLoop.Result result) {
    if (!result.ok()) return new Review(false, "Worker did not complete: " + result.status());
    for (String required : task.requiredTools()) {
      boolean found = false;
      for (var trace : result.trace())
        if (trace.tool().equals(required) && trace.result().success()) {
          if (required.equals("run_command")) {
            try {
              if (Json.MAPPER.readTree(trace.result().output()).path("exitCode").asInt(-1) == 0)
                found = true;
            } catch (Exception ignored) {
            }
          } else found = true;
        }
      if (!found) return new Review(false, "Missing successful verification: " + required);
    }
    return new Review(
        !result.answer().isBlank(),
        "Evidence checks passed; semantic correctness still requires review");
  }
}
