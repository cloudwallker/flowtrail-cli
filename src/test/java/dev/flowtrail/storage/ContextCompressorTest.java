package dev.flowtrail.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.flowtrail.memory.ContextCompressor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextCompressorTest {
  @Test
  void compressionKeepsConstraintsAndCurrentGoalWithoutOrphanToolResults() {
    List<Map<String, Object>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", "never write without permission"));
    messages.add(Map.of("role", "user", "content", "old investigation"));
    messages.add(
        Map.of(
            "role",
            "assistant",
            "content",
            "",
            "tool_calls",
            List.of(
                Map.of(
                    "id",
                    "call-1",
                    "type",
                    "function",
                    "function",
                    Map.of("name", "read_file", "arguments", "{}")))));
    messages.add(
        Map.of(
            "role", "tool", "tool_call_id", "call-1", "content", "old file content ".repeat(400)));
    messages.add(Map.of("role", "assistant", "content", "old result"));
    messages.add(Map.of("role", "user", "content", "current goal: inspect idempotency"));
    var result = new ContextCompressor().compress(messages, 300);
    assertTrue(result.compressed());
    assertTrue(result.estimatedTokens() <= 300);
    assertTrue(result.messages().contains(messages.getFirst()));
    assertTrue(result.messages().contains(messages.getLast()));
    boolean hasCall = result.messages().stream().anyMatch(m -> m.containsKey("tool_calls"));
    boolean hasResult = result.messages().stream().anyMatch(m -> "tool".equals(m.get("role")));
    assertEquals(hasCall, hasResult);
    assertFalse(result.summary().isBlank());
  }

  @Test
  void pendingCallsRemainAndImpossibleProtectedBudgetFailsExplicitly() {
    var call =
        Map.<String, Object>of(
            "role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "pending")));
    var messages =
        List.of(
            Map.<String, Object>of("role", "system", "content", "rules"),
            Map.<String, Object>of("role", "user", "content", "goal"),
            call);
    assertTrue(new ContextCompressor().compress(messages, 200).messages().contains(call));
    assertThrows(
        IllegalArgumentException.class, () -> new ContextCompressor().compress(messages, 1));
  }
}
