package dev.flowtrail.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Conservative character estimate, not a provider tokenizer. Call before each model request. */
public final class ContextCompressor {
  private static final ObjectMapper JSON = new ObjectMapper();

  public record Result(
      List<Map<String, Object>> messages,
      String summary,
      int estimatedTokens,
      boolean compressed) {}

  private record Group(List<Map<String, Object>> messages, boolean protectedGroup) {}

  public Result compress(List<Map<String, Object>> messages, int inputBudgetTokens) {
    if (inputBudgetTokens < 1)
      throw new IllegalArgumentException("Input token budget must be positive");
    int currentUser = -1;
    for (int i = 0; i < messages.size(); i++)
      if ("user".equals(messages.get(i).get("role"))) currentUser = i;
    var groups = new ArrayList<Group>();
    for (int i = 0; i < messages.size(); i++) {
      var message = messages.get(i);
      if ("tool".equals(message.get("role")))
        throw new IllegalArgumentException("Orphan tool result");
      var grouped = new ArrayList<Map<String, Object>>();
      grouped.add(message);
      boolean keep = "system".equals(message.get("role")) || i == currentUser;
      if (message.get("tool_calls") instanceof List<?> calls && !calls.isEmpty()) {
        Set<String> pending = new HashSet<>();
        for (Object call : calls) {
          String id = String.valueOf(((Map<?, ?>) call).get("id"));
          if (id.equals("null") || !pending.add(id))
            throw new IllegalArgumentException("Invalid tool call IDs");
        }
        while (i + 1 < messages.size() && "tool".equals(messages.get(i + 1).get("role"))) {
          var result = messages.get(++i);
          if (!pending.remove(String.valueOf(result.get("tool_call_id"))))
            throw new IllegalArgumentException("Unmatched tool result");
          grouped.add(result);
        }
        keep |= !pending.isEmpty();
      }
      groups.add(new Group(List.copyOf(grouped), keep));
    }
    if (estimate(messages) <= inputBudgetTokens)
      return new Result(List.copyOf(messages), "", estimate(messages), false);
    var removed = new ArrayList<Map<String, Object>>();
    String summary = "";
    List<Map<String, Object>> output = List.copyOf(messages);
    for (var group : List.copyOf(groups)) {
      if (group.protectedGroup()) continue;
      groups.remove(group);
      removed.addAll(group.messages());
      summary = summarize(removed, Math.min(512, inputBudgetTokens / 3));
      output = flatten(groups, summary);
      if (estimate(output) <= inputBudgetTokens)
        return new Result(output, summary, estimate(output), true);
    }
    output = flatten(groups, "");
    if (estimate(output) > inputBudgetTokens)
      throw new IllegalArgumentException(
          "Protected system, current goal or pending tool calls exceed the input budget");
    int available = inputBudgetTokens - estimate(output) - 48;
    summary = available > 40 ? summarize(removed, available) : "";
    output = flatten(groups, summary);
    if (estimate(output) > inputBudgetTokens) {
      summary = "";
      output = flatten(groups, summary);
    }
    return new Result(output, summary, estimate(output), true);
  }

  private static List<Map<String, Object>> flatten(List<Group> groups, String summary) {
    var result = new ArrayList<Map<String, Object>>();
    boolean inserted = false;
    for (var group : groups) {
      if (!inserted && !"system".equals(group.messages().getFirst().get("role"))) {
        if (!summary.isEmpty()) result.add(Map.of("role", "assistant", "content", summary));
        inserted = true;
      }
      result.addAll(group.messages());
    }
    return List.copyOf(result);
  }

  private static String summarize(List<Map<String, Object>> removed, int max) {
    StringBuilder text = new StringBuilder("Earlier conversation notes (untrusted data): ");
    for (var message : removed) {
      Object content = message.get("content");
      if (content != null && !content.toString().isBlank()) {
        String value = content.toString().replaceAll("\\s+", " ");
        text.append(message.get("role"))
            .append(": ")
            .append(value, 0, Math.min(value.length(), 100))
            .append("; ");
      }
    }
    return text.substring(0, Math.min(text.length(), Math.max(0, max)));
  }

  public static int estimate(List<Map<String, Object>> messages) {
    try {
      return JSON.writeValueAsString(messages).length() + messages.size() * 8;
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("Cannot estimate messages", e);
    }
  }
}
