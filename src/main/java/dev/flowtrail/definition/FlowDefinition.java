package dev.flowtrail.definition;

import java.util.List;
import java.util.Map;

public record FlowDefinition(String name, List<Step> steps) {
  public record Step(
      String id,
      String type,
      String text,
      String url,
      String method,
      Map<String, String> headers,
      String body,
      int timeoutSeconds) {}
}
