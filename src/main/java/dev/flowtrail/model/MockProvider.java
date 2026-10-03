package dev.flowtrail.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Deterministic offline fixture provider. Its outputs are always labelled mock by the command. */
public final class MockProvider implements ModelProvider {
  private final JsonNode script;
  private int cursor;

  public MockProvider(Path scriptFile) throws Exception {
    script =
        scriptFile == null
            ? Json.MAPPER.readTree(
                "[{\"toolCalls\":[{\"id\":\"list\",\"name\":\"list_files\",\"arguments\":{}}]},{\"content\":\"Mock"
                    + " analysis completed using the file listing. Configure a live provider for"
                    + " generated analysis.\"}]")
            : Json.MAPPER.readTree(Files.readString(scriptFile));
    if (!script.isArray() || script.isEmpty() || script.size() > 100)
      throw new IllegalArgumentException("Mock script requires 1..100 responses");
  }

  @Override
  public synchronized Response complete(
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      Consumer<String> output) {
    JsonNode item = script.get(Math.min(cursor++, script.size() - 1));
    List<ToolCall> calls = new ArrayList<>();
    for (JsonNode call : item.path("toolCalls")) {
      JsonNode args = call.path("arguments");
      calls.add(
          new ToolCall(
              call.path("id").asText(),
              call.path("name").asText(),
              args.isTextual() ? args.asText() : args.toString()));
    }
    String content = item.path("content").asText("");
    output.accept(content);
    return new Response(content, calls);
  }
}
