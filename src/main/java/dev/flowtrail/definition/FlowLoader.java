package dev.flowtrail.definition;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flowtrail.FlowException;
import dev.flowtrail.definition.FlowDefinition.Step;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class FlowLoader {
  private static final int MAX_FILE_BYTES = 1_048_576;
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Set<String> RESTRICTED_HEADERS =
      Set.of("connection", "content-length", "expect", "host", "upgrade");

  public FlowDefinition load(Path path) {
    byte[] bytes;
    try (var input = Files.newInputStream(path)) {
      bytes = input.readNBytes(MAX_FILE_BYTES + 1);
    } catch (IOException exception) {
      throw new FlowException(1, "Cannot read task file. Check its path and permissions.");
    }
    if (bytes.length > MAX_FILE_BYTES) {
      throw new FlowException(2, "Task file exceeds the 1 MiB limit.");
    }
    JsonNode root;
    try {
      root = JSON.readTree(bytes);
    } catch (IOException exception) {
      throw new FlowException(2, "Task file is not valid JSON.");
    }
    fields(root, Set.of("name", "steps"));
    String name = text(root, "name");
    require(!name.isBlank(), "Task name must not be blank.");
    JsonNode steps = root.get("steps");
    require(
        steps != null && steps.isArray() && !steps.isEmpty(),
        "Task must contain a nonempty steps array.");
    List<Step> definitions = new ArrayList<>();
    Set<String> previous = new HashSet<>();
    for (JsonNode node : steps) {
      fields(
          node, Set.of("id", "type", "text", "url", "method", "headers", "body", "timeoutSeconds"));
      String id = text(node, "id");
      require(
          id.matches("[A-Za-z][A-Za-z0-9_]*"),
          "Step id must start with a letter and contain only letters, digits or underscores.");
      require(!previous.contains(id), "Step ids must be unique.");
      String type = text(node, "type");
      require(type.equals("text") || type.equals("http"), "Step type must be text or http.");
      if (type.equals("text")) {
        fields(node, Set.of("id", "type", "text"));
        String value = text(node, "text");
        References.validate(value, previous);
        definitions.add(new Step(id, type, value, null, null, Map.of(), null, 10));
      } else {
        fields(node, Set.of("id", "type", "url", "method", "headers", "body", "timeoutSeconds"));
        String url = text(node, "url");
        References.validate(url, previous);
        if (!url.contains("${")) {
          validateUrl(url);
        }
        String method = node.has("method") ? text(node, "method") : "GET";
        require(method.equals("GET") || method.equals("POST"), "HTTP method must be GET or POST.");
        String body = node.has("body") ? text(node, "body") : "";
        require(!method.equals("GET") || !node.has("body"), "GET steps cannot contain a body.");
        References.validate(body, previous);
        int timeout = 10;
        if (node.has("timeoutSeconds")) {
          JsonNode number = node.get("timeoutSeconds");
          require(
              number.isIntegralNumber() && number.canConvertToInt(),
              "timeoutSeconds must be an integer.");
          timeout = number.intValue();
          require(timeout >= 1 && timeout <= 300, "timeoutSeconds must be between 1 and 300.");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (node.has("headers")) {
          JsonNode values = node.get("headers");
          require(values.isObject(), "headers must be an object with string values.");
          Set<String> names = new HashSet<>();
          var iterator = values.properties().iterator();
          while (iterator.hasNext()) {
            var entry = iterator.next();
            require(entry.getValue().isTextual(), "Header values must be strings.");
            String value = entry.getValue().textValue();
            String key = entry.getKey();
            require(
                names.add(key.toLowerCase(Locale.ROOT)),
                "Header names must be unique ignoring case.");
            validateHeader(key, value);
            References.validate(value, previous);
            headers.put(key, value);
          }
        }
        definitions.add(new Step(id, type, null, url, method, Map.copyOf(headers), body, timeout));
      }
      previous.add(id);
    }
    return new FlowDefinition(name, List.copyOf(definitions));
  }

  public static URI validateUrl(String value) {
    try {
      URI uri = URI.create(value);
      require(
          ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
              && uri.getHost() != null
              && uri.getUserInfo() == null
              && uri.getFragment() == null
              && uri.getPort() >= -1
              && uri.getPort() <= 65535,
          "HTTP URL must have an http/https host and no credentials or fragment.");
      return uri;
    } catch (IllegalArgumentException exception) {
      throw new FlowException(2, "HTTP URL is invalid.");
    }
  }

  public static void validateHeader(String name, String value) {
    require(
        name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
            && !RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT)),
        "HTTP header name is invalid or managed by the HTTP client.");
    require(
        value
            .chars()
            .allMatch(
                character ->
                    character == '\t' || character >= 32 && character <= 255 && character != 127),
        "HTTP header value contains unsupported characters.");
  }

  private static void fields(JsonNode node, Set<String> allowed) {
    require(node != null && node.isObject(), "Task and step definitions must be JSON objects.");
    node.fieldNames()
        .forEachRemaining(
            name -> require(allowed.contains(name), "Definition contains an unsupported field."));
  }

  private static String text(JsonNode node, String name) {
    JsonNode value = node.get(name);
    require(value != null && value.isTextual(), name + " must be a string.");
    return value.textValue();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new FlowException(2, message);
    }
  }
}
