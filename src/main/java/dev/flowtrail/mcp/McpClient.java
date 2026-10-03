package dev.flowtrail.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.flowtrail.model.Json;
import dev.flowtrail.tool.Tool;
import dev.flowtrail.tool.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class McpClient implements AutoCloseable {
  public static final String VERSION = "2025-11-25";
  private final String name;
  private final McpTransport transport;
  private final Duration timeout;
  private long sequence;

  private McpClient(String name, McpTransport transport, Duration timeout) {
    this.name = name;
    this.transport = transport;
    this.timeout = timeout;
  }

  public static McpClient connect(Path config, Path root, Duration timeout) throws Exception {
    if (Files.size(config) > 65536) throw new IllegalArgumentException();
    JsonNode c = Json.MAPPER.readTree(Files.readString(config));
    String name = c.path("name").asText();
    if (!name.matches("[A-Za-z][A-Za-z0-9_]{0,23}"))
      throw new IllegalArgumentException("Invalid MCP namespace");
    McpTransport transport;
    switch (c.path("transport").asText()) {
      case "stdio" -> {
        List<String> args = new ArrayList<>();
        if (!c.path("command").isArray()) throw new IllegalArgumentException();
        for (JsonNode item : c.path("command")) {
          if (!item.isTextual()) throw new IllegalArgumentException();
          args.add(item.asText());
        }
        transport = new StdioTransport(args, root);
      }
      case "http" ->
          transport =
              new StreamableHttpTransport(
                  c.path("url").asText(),
                  c.has("tokenEnv") ? System.getenv(c.path("tokenEnv").asText()) : null,
                  timeout);
      default -> throw new IllegalArgumentException("MCP transport must be stdio or http");
    }
    McpClient client = new McpClient(name, transport, timeout);
    try {
      JsonNode init =
          client.request(
              "initialize",
              Map.of(
                  "protocolVersion",
                  VERSION,
                  "capabilities",
                  Map.of(),
                  "clientInfo",
                  Map.of("name", "FlowTrail", "version", "0.2.0")));
      if (!VERSION.equals(init.path("protocolVersion").asText())
          || !init.path("capabilities").has("tools"))
        throw new IllegalArgumentException("Unsupported MCP capabilities or version");
      transport.version(VERSION);
      client.notify("notifications/initialized", Map.of());
      return client;
    } catch (Exception e) {
      client.close();
      throw e;
    }
  }

  private synchronized JsonNode request(String method, Object params) throws Exception {
    long id = ++sequence;
    JsonNode message =
        Json.MAPPER.valueToTree(
            Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var future = executor.submit(() -> transport.exchange(message));
    try {
      JsonNode result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!"2.0".equals(result.path("jsonrpc").asText())
          || !result.path("id").isIntegralNumber()
          || result.path("id").asLong(-1) != id
          || result.has("error")
          || !result.has("result"))
        throw new IllegalArgumentException("MCP request failed protocol validation");
      return result.get("result");
    } catch (java.util.concurrent.TimeoutException e) {
      future.cancel(true);
      try {
        notify("notifications/cancelled", Map.of("requestId", id, "reason", "Client timeout"));
      } catch (Exception ignored) {
      }
      transport.close();
      throw e;
    } catch (InterruptedException e) {
      future.cancel(true);
      transport.close();
      Thread.currentThread().interrupt();
      throw e;
    } finally {
      future.cancel(true);
      executor.shutdownNow();
    }
  }

  private void notify(String method, Object params) throws Exception {
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var future =
        executor.submit(
            () ->
                transport.exchange(
                    Json.MAPPER.valueToTree(
                        Map.of("jsonrpc", "2.0", "method", method, "params", params))));
    try {
      future.get(Math.min(timeout.toMillis(), 1000), TimeUnit.MILLISECONDS);
    } finally {
      future.cancel(true);
      executor.shutdownNow();
    }
  }

  public List<String> register(ToolRegistry registry) throws Exception {
    List<String> names = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      JsonNode listing =
          request("tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor));
      if (!listing.path("tools").isArray()) throw new IllegalArgumentException();
      for (JsonNode tool : listing.path("tools")) {
        String remote = tool.path("name").asText();
        if (!remote.matches("[A-Za-z][A-Za-z0-9_-]{0,39}"))
          throw new IllegalArgumentException("Unsupported MCP tool name");
        String qualified = name + "__" + remote;
        JsonNode schema = tool.path("inputSchema");
        if (!schema.isObject()) throw new IllegalArgumentException();
        @SuppressWarnings("unchecked")
        Map<String, Object> input = Json.MAPPER.convertValue(schema, Map.class);
        registry.register(
            new Tool() {
              public String name() {
                return qualified;
              }

              public String description() {
                return "External MCP tool " + qualified + " (untrusted result)";
              }

              public Effect effect() {
                return Effect.REMOTE;
              }

              public Map<String, Object> schema() {
                return input;
              }

              public void validate(JsonNode args) {
                SchemaValidator.validate(schema, args);
              }

              public String execute(JsonNode args) throws Exception {
                JsonNode result = request("tools/call", Map.of("name", remote, "arguments", args));
                if (result.path("isError").asBoolean())
                  throw new IllegalStateException("MCP tool reported an error");
                return result.toString();
              }
            });
        names.add(qualified);
        if (names.size() > 128) throw new IllegalArgumentException("Too many MCP tools");
      }
      cursor = listing.has("nextCursor") ? listing.path("nextCursor").asText() : null;
      if (++pages > 10) throw new IllegalArgumentException("MCP pagination exceeded budget");
    } while (cursor != null && !cursor.isBlank());
    return List.copyOf(names);
  }

  @Override
  public void close() {
    transport.close();
  }
}
