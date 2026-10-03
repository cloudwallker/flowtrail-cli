package dev.flowtrail.plan;

import dev.flowtrail.FlowException;
import dev.flowtrail.model.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public record PlanDefinition(int version, List<Task> tasks) {
  public record Task(
      String id,
      String goal,
      List<String> dependsOn,
      List<String> tools,
      String expectedOutput,
      String script,
      List<String> requiredTools) {
    public Task {
      dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
      tools = tools == null ? List.of() : List.copyOf(tools);
      requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
    }
  }

  private static final Pattern REFERENCE =
      Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_-]*)\\.output}");

  public static PlanDefinition load(Path path) {
    try {
      if (Files.size(path) > 1_000_000) throw new IllegalArgumentException();
      return parse(Files.readString(path));
    } catch (FlowException e) {
      throw e;
    } catch (Exception e) {
      throw new FlowException(2, "Invalid plan JSON");
    }
  }

  public static PlanDefinition parse(String json) {
    try {
      var plan = Json.MAPPER.readValue(json, PlanDefinition.class);
      plan.validate();
      return plan;
    } catch (FlowException e) {
      throw e;
    } catch (Exception e) {
      throw new FlowException(2, "Invalid plan JSON");
    }
  }

  public Map<String, Task> validate() {
    if (version != 1 || tasks == null || tasks.isEmpty() || tasks.size() > 30)
      throw new FlowException(2, "Plan requires version 1 and 1..30 tasks");
    Map<String, Task> nodes = new LinkedHashMap<>();
    for (Task t : tasks) {
      if (t.id() == null
          || !t.id().matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
          || t.goal() == null
          || t.goal().isBlank()
          || t.expectedOutput() == null
          || t.expectedOutput().isBlank()
          || nodes.putIfAbsent(t.id(), t) != null)
        throw new FlowException(2, "Invalid or duplicate plan task");
      if (new HashSet<>(t.dependsOn()).size() != t.dependsOn().size())
        throw new FlowException(2, "Duplicate plan dependency");
    }
    for (Task t : tasks)
      for (String dep : t.dependsOn())
        if (!nodes.containsKey(dep)) throw new FlowException(2, "Unknown plan dependency");
    Map<String, Integer> colors = new HashMap<>();
    for (String id : nodes.keySet()) visit(id, nodes, colors);
    for (Task t : tasks) {
      Set<String> ancestors = new HashSet<>();
      collect(t.id(), nodes, ancestors);
      var matcher = REFERENCE.matcher(t.goal());
      while (matcher.find())
        if (!ancestors.contains(matcher.group(1)))
          throw new FlowException(2, "Plan result reference must target an ancestor");
      if (matcher.reset().replaceAll("").contains("${"))
        throw new FlowException(2, "Invalid plan result reference");
    }
    return nodes;
  }

  private static void visit(String id, Map<String, Task> nodes, Map<String, Integer> colors) {
    if (colors.getOrDefault(id, 0) == 1)
      throw new FlowException(2, "Plan contains a dependency cycle");
    if (colors.getOrDefault(id, 0) == 2) return;
    colors.put(id, 1);
    for (String dep : nodes.get(id).dependsOn()) visit(dep, nodes, colors);
    colors.put(id, 2);
  }

  private static void collect(String id, Map<String, Task> nodes, Set<String> ancestors) {
    for (String dep : nodes.get(id).dependsOn())
      if (ancestors.add(dep)) collect(dep, nodes, ancestors);
  }

  public static String render(Task task, Map<String, String> results) {
    var matcher = REFERENCE.matcher(task.goal());
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String value = results.get(matcher.group(1));
      if (value == null) throw new IllegalStateException("Missing ancestor output");
      matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value));
    }
    matcher.appendTail(out);
    return out.toString();
  }
}
