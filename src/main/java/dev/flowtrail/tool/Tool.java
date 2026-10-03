package dev.flowtrail.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public interface Tool {
  enum Effect {
    READ,
    WRITE,
    COMMAND,
    REMOTE
  }

  String name();

  String description();

  Effect effect();

  Map<String, Object> schema();

  default String preview(JsonNode arguments) throws Exception {
    return "";
  }

  void validate(JsonNode arguments) throws Exception;

  String execute(JsonNode arguments) throws Exception;
}
