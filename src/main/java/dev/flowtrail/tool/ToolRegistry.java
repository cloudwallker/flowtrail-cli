package dev.flowtrail.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.flowtrail.model.Json;
import dev.flowtrail.model.ModelProvider.ToolCall;
import dev.flowtrail.policy.AuditLog;
import dev.flowtrail.policy.ExecutionPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class ToolRegistry {
  public record Result(String status, String output, String preview, boolean truncated) {
    public boolean success() {
      return status.equals("ok");
    }
  }

  private final Map<String, Tool> tools = new LinkedHashMap<>();
  private final ExecutionPolicy policy;
  private final AuditLog audit;
  private final Duration timeout;
  private final Consumer<String> preview;

  public ToolRegistry(ExecutionPolicy policy, Duration timeout, Consumer<String> preview)
      throws Exception {
    this.policy = policy;
    this.timeout = timeout;
    this.preview = preview;
    this.audit = new AuditLog(policy.root());
  }

  public synchronized void register(Tool tool) {
    if (tools.putIfAbsent(tool.name(), tool) != null)
      throw new IllegalArgumentException("Duplicate tool name");
  }

  public List<Map<String, Object>> definitions(Set<String> allowed) {
    List<Map<String, Object>> defs = new ArrayList<>();
    for (Tool tool : tools.values()) {
      if (allowed == null || allowed.contains(tool.name()))
        defs.add(
            Map.of(
                "type",
                "function",
                "function",
                Map.of(
                    "name",
                    tool.name(),
                    "description",
                    tool.description(),
                    "parameters",
                    tool.schema())));
    }
    return defs;
  }

  public Tool.Effect effect(String name) {
    Tool t = tools.get(name);
    if (t == null) throw new IllegalArgumentException("Unknown tool");
    return t.effect();
  }

  public Set<String> names() {
    return Set.copyOf(tools.keySet());
  }

  public Result execute(String session, ToolCall call, Set<String> allowed) throws Exception {
    long start = System.nanoTime();
    Result result;
    try {
      result = run(call, allowed);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw e;
    } finally {
      /* Audit is emitted below only after a definite outcome. */
    }
    audit.record(
        session,
        tools.containsKey(call.name()) ? call.name() : "unknown",
        result.status(),
        (System.nanoTime() - start) / 1_000_000);
    return result;
  }

  private Result run(ToolCall call, Set<String> allowed) throws InterruptedException {
    Tool tool = tools.get(call.name());
    if (tool == null || (allowed != null && !allowed.contains(call.name())))
      return new Result("denied", "Tool is not allowed for this task", "", false);
    String diff = "";
    try {
      if (call.arguments().length() > 65536)
        return new Result("invalid_arguments", "Arguments exceed limit", "", false);
      JsonNode args = Json.MAPPER.readTree(call.arguments());
      if (args == null || !args.isObject())
        return new Result("invalid_arguments", "Tool arguments must be an object", "", false);
      tool.validate(args);
      diff = tool.preview(args);
      if (!diff.isEmpty()) preview.accept(diff);
      if (tool.effect() == Tool.Effect.WRITE && !policy.allowWrite()
          || tool.effect() == Tool.Effect.REMOTE && !policy.allowRemote())
        return new Result("approval_required", "Explicit user authorization required", diff, false);
      var executor = Executors.newVirtualThreadPerTaskExecutor();
      var future = executor.submit(() -> tool.execute(args));
      try {
        String output = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        boolean cut = output.length() > 16000;
        return new Result(
            "ok",
            cut ? output.substring(0, 16000) + "\n[truncated; read a narrower range]" : output,
            diff,
            cut);
      } catch (TimeoutException e) {
        future.cancel(true);
        return new Result("timeout", "Tool timed out", diff, false);
      } catch (java.util.concurrent.ExecutionException e) {
        if (e.getCause() instanceof SecurityException)
          return new Result("denied", "Policy rejected operation", diff, false);
        return new Result("error", "Tool execution failed", diff, false);
      } finally {
        future.cancel(true);
        executor.shutdownNow();
      }
    } catch (SecurityException e) {
      return new Result("denied", "Policy rejected operation", diff, false);
    } catch (InterruptedException e) {
      throw e;
    } catch (Exception e) {
      return new Result(
          "invalid_arguments", "Invalid tool arguments or inaccessible input", diff, false);
    }
  }
}
