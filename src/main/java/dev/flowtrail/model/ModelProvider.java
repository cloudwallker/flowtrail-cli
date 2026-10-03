package dev.flowtrail.model;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface ModelProvider {
  Response complete(
      List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<String> output)
      throws Exception;

  record ToolCall(String id, String name, String arguments) {}

  record Response(String content, List<ToolCall> toolCalls) {
    public Response {
      content = content == null ? "" : content;
      toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
  }
}
