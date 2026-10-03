package dev.flowtrail.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.flowtrail.model.Json;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

public final class StreamableHttpTransport implements McpTransport {
  private final URI endpoint;
  private final Duration timeout;
  private final HttpClient client;
  private final String token;
  private volatile String session, protocol;
  private volatile java.io.InputStream active;

  public StreamableHttpTransport(String url, String token, Duration timeout) {
    endpoint = URI.create(url);
    if (!List.of("http", "https").contains(endpoint.getScheme()) || endpoint.getUserInfo() != null)
      throw new IllegalArgumentException("Invalid MCP URL");
    this.token = token;
    this.timeout = timeout;
    client =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  private HttpRequest.Builder request() {
    var b =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json");
    if (session != null) b.header("Mcp-Session-Id", session);
    if (protocol != null) b.header("Mcp-Protocol-Version", protocol);
    if (token != null && !token.isBlank()) b.header("Authorization", "Bearer " + token);
    return b;
  }

  @Override
  public JsonNode exchange(JsonNode message) throws Exception {
    var response =
        client.send(
            request().POST(HttpRequest.BodyPublishers.ofString(message.toString())).build(),
            HttpResponse.BodyHandlers.ofInputStream());
    active = response.body();
    try (var stream = response.body()) {
      if (response.statusCode() == 404 && session != null) {
        session = null;
        throw new IllegalStateException(
            "MCP session expired; operation outcome unknown; reconnect explicitly");
      }
      if (response.statusCode() / 100 != 2)
        throw new IllegalStateException("MCP HTTP request failed");
      if (message.path("method").asText().equals("initialize")) {
        String id = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        if (id != null && !id.matches("[\\x21-\\x7E]{1,256}"))
          throw new IllegalArgumentException("Invalid MCP session id");
        session = id;
      }
      if (!message.has("id")) {
        if (response.statusCode() != 202)
          throw new IllegalArgumentException("MCP notification was not accepted");
        return Json.MAPPER.nullNode();
      }
      String type = response.headers().firstValue("Content-Type").orElse("");
      if (type.startsWith("application/json")) {
        byte[] bytes = stream.readNBytes(1_000_001);
        if (bytes.length > 1_000_000) throw new IllegalArgumentException();
        return Json.MAPPER.readTree(bytes);
      }
      if (!type.startsWith("text/event-stream"))
        throw new IllegalArgumentException("Unsupported MCP content type");
      try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        StringBuilder data = new StringBuilder();
        String line;
        int size = 0;
        while ((line = StdioTransport.readLine(reader)) != null) {
          size += line.length();
          if (size > 1_000_000) throw new IllegalArgumentException();
          if (line.isEmpty()) {
            if (!data.isEmpty()) {
              JsonNode event = Json.MAPPER.readTree(data.toString());
              data.setLength(0);
              if (event.path("id").asText().equals(message.path("id").asText())
                  && !event.has("method")) return event;
              if (event.has("id"))
                throw new IllegalArgumentException("Unsupported MCP server request");
            }
          } else if (line.startsWith("data:")) {
            if (!data.isEmpty()) data.append('\n');
            data.append(line.substring(5).stripLeading());
          }
        }
      }
      throw new IllegalStateException(
          "MCP stream disconnected before response; request is not replayed");
    } finally {
      active = null;
    }
  }

  @Override
  public void version(String value) {
    protocol = value;
  }

  @Override
  public void close() {
    if (active != null)
      try {
        active.close();
      } catch (Exception ignored) {
      }
    if (session != null)
      try {
        var termination =
            client.sendAsync(
                request().timeout(Duration.ofSeconds(2)).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
        try {
          termination.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
          termination.cancel(true);
        }
      } catch (Exception ignored) {
      }
    client.shutdownNow();
  }
}
