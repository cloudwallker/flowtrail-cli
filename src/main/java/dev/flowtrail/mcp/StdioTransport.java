package dev.flowtrail.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.flowtrail.model.Json;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class StdioTransport implements McpTransport {
  private final Process child;
  private final BufferedReader reader;
  private final BufferedWriter writer;

  public StdioTransport(List<String> command, Path root) throws Exception {
    if (command == null
        || command.isEmpty()
        || command.stream().anyMatch(s -> s == null || s.isBlank()))
      throw new IllegalArgumentException("Invalid MCP command");
    child =
        new ProcessBuilder(command)
            .directory(root.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    reader =
        new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
    writer =
        new BufferedWriter(new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8));
  }

  @Override
  public JsonNode exchange(JsonNode message) throws Exception {
    write(message);
    if (!message.has("id")) return Json.MAPPER.nullNode();
    while (true) {
      String line = readLine(reader);
      if (line == null) throw new IllegalStateException("MCP child disconnected");
      JsonNode response = Json.MAPPER.readTree(line);
      if (response.has("method")) {
        if (response.has("id")) {
          Object reply =
              response.path("method").asText().equals("ping")
                  ? Map.of("jsonrpc", "2.0", "id", response.get("id"), "result", Map.of())
                  : Map.of(
                      "jsonrpc",
                      "2.0",
                      "id",
                      response.get("id"),
                      "error",
                      Map.of("code", -32601, "message", "Unsupported client capability"));
          write(Json.MAPPER.valueToTree(reply));
        }
        continue;
      }
      if (response.path("id").asText().equals(message.path("id").asText())) return response;
      throw new IllegalArgumentException("Unexpected MCP response id");
    }
  }

  private synchronized void write(JsonNode message) throws Exception {
    writer.write(message.toString());
    writer.newLine();
    writer.flush();
  }

  static String readLine(BufferedReader reader) throws Exception {
    StringBuilder line = new StringBuilder();
    int ch;
    while ((ch = reader.read()) != -1) {
      if (ch == '\n') break;
      if (ch != '\r') line.append((char) ch);
      if (line.length() > 1_000_000)
        throw new IllegalArgumentException("MCP message exceeds limit");
    }
    return ch == -1 && line.isEmpty() ? null : line.toString();
  }

  @Override
  public void version(String version) {}

  @Override
  public void close() {
    child.descendants().forEach(ProcessHandle::destroyForcibly);
    child.destroy();
    try {
      if (!child.waitFor(200, TimeUnit.MILLISECONDS)) {
        child.destroyForcibly();
        child.waitFor(2, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      child.destroyForcibly();
      Thread.currentThread().interrupt();
    }
    try {
      writer.close();
      reader.close();
    } catch (Exception ignored) {
    }
  }
}
