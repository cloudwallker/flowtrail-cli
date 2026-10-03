package dev.flowtrail;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpCommandsTest {
  @TempDir Path root;

  @Test
  void httpNegotiatesSessionDiscoversAndCallsThroughPolicyThenCloses() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean initialized = new AtomicBoolean(),
        headers = new AtomicBoolean(true),
        closed = new AtomicBoolean();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/mcp",
        ex -> {
          if (ex.getRequestMethod().equals("DELETE")) {
            closed.set(true);
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
          }
          var req = AgentCommandsTest.JSON.readTree(ex.getRequestBody());
          String method = req.path("method").asText();
          Object result;
          if (!method.equals("initialize"))
            headers.compareAndSet(
                true,
                "session-test".equals(ex.getRequestHeaders().getFirst("Mcp-Session-Id"))
                    && "2025-11-25"
                        .equals(ex.getRequestHeaders().getFirst("Mcp-Protocol-Version")));
          if (method.equals("notifications/initialized")) {
            initialized.set(true);
            ex.sendResponseHeaders(202, -1);
            ex.close();
            return;
          }
          if (method.equals("initialize")) {
            ex.getResponseHeaders().set("Mcp-Session-Id", "session-test");
            result =
                Map.of(
                    "protocolVersion",
                    "2025-11-25",
                    "capabilities",
                    Map.of("tools", Map.of()),
                    "serverInfo",
                    Map.of("name", "fixture", "version", "1"));
          } else if (method.equals("tools/list"))
            result =
                Map.of(
                    "tools",
                    List.of(
                        Map.of(
                            "name",
                            "echo",
                            "description",
                            "fixture",
                            "inputSchema",
                            Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("text", Map.of("type", "string")),
                                "required",
                                List.of("text")))));
          else {
            calls.incrementAndGet();
            result =
                Map.of(
                    "content",
                    List.of(
                        Map.of(
                            "type",
                            "text",
                            "text",
                            req.path("params").path("arguments").path("text").asText())));
          }
          String body =
              AgentCommandsTest.JSON.writeValueAsString(
                  Map.of("jsonrpc", "2.0", "id", req.path("id").asLong(), "result", result));
          boolean sse = method.equals("tools/call");
          if (sse) body = "event: message\ndata: " + body + "\n\n";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders()
              .set("Content-Type", sse ? "text/event-stream" : "application/json");
          ex.sendResponseHeaders(200, bytes.length);
          ex.getResponseBody().write(bytes);
          ex.close();
        });
    server.start();
    try {
      Path config =
          save(
              "http.json",
              Map.of(
                  "name",
                  "demo",
                  "transport",
                  "http",
                  "url",
                  "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"));
      var denied =
          AgentCommandsTest.invoke(
              "mcp",
              config.toString(),
              "--project",
              root.toString(),
              "--tool",
              "demo__echo",
              "--arguments",
              "{\"text\":\"hello\"}",
              "--json");
      assertEquals(1, denied.code());
      assertEquals(0, calls.get());
      assertTrue(denied.out().contains("approval_required"));
      var accepted =
          AgentCommandsTest.invoke(
              "mcp",
              config.toString(),
              "--project",
              root.toString(),
              "--tool",
              "demo__echo",
              "--arguments",
              "{\"text\":\"hello\"}",
              "--allow-remote",
              "--json");
      assertEquals(0, accepted.code(), accepted.err());
      assertEquals(1, calls.get());
      assertTrue(accepted.out().contains("hello"));
      assertTrue(initialized.get());
      assertTrue(headers.get());
      assertTrue(closed.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void stdioHandshakeAndTimeoutCleanUpTheChildProcess() throws Exception {
    Path pid = root.resolve("child.pid");
    Path config =
        save(
            "stdio.json",
            Map.of(
                "name",
                "local",
                "transport",
                "stdio",
                "command",
                List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    McpFixture.class.getName(),
                    pid.toString())));
    var discovered =
        AgentCommandsTest.invoke("mcp", config.toString(), "--project", root.toString(), "--json");
    assertEquals(0, discovered.code(), discovered.err());
    assertTrue(discovered.out().contains("local__echo"));
    long first = Long.parseLong(Files.readString(pid));
    assertFalse(ProcessHandle.of(first).map(ProcessHandle::isAlive).orElse(false));
    var timed =
        AgentCommandsTest.invoke(
            "mcp",
            config.toString(),
            "--project",
            root.toString(),
            "--tool",
            "local__echo",
            "--arguments",
            "{\"text\":\"slow\"}",
            "--allow-remote",
            "--timeout-seconds",
            "1",
            "--json");
    assertEquals(1, timed.code());
    long second = Long.parseLong(Files.readString(pid));
    assertFalse(ProcessHandle.of(second).map(ProcessHandle::isAlive).orElse(false));
  }

  Path save(String name, Object value) throws Exception {
    Path p = root.resolve(name);
    Files.writeString(p, AgentCommandsTest.JSON.writeValueAsString(value));
    return p;
  }

  public static class McpFixture {
    public static void main(String[] args) throws Exception {
      Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
      var reader =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        var req = AgentCommandsTest.JSON.readTree(line);
        if (!req.has("id")) continue;
        Object result;
        switch (req.path("method").asText()) {
          case "initialize" ->
              result =
                  Map.of(
                      "protocolVersion",
                      "2025-11-25",
                      "capabilities",
                      Map.of("tools", Map.of()),
                      "serverInfo",
                      Map.of("name", "fixture", "version", "1"));
          case "tools/list" ->
              result =
                  Map.of(
                      "tools",
                      List.of(
                          Map.of(
                              "name",
                              "echo",
                              "description",
                              "fixture",
                              "inputSchema",
                              Map.of(
                                  "type",
                                  "object",
                                  "properties",
                                  Map.of("text", Map.of("type", "string")),
                                  "required",
                                  List.of("text")))));
          default -> {
            if (req.path("params").path("arguments").path("text").asText().equals("slow"))
              Thread.sleep(10000);
            result = Map.of("content", List.of(Map.of("type", "text", "text", "hello")));
          }
        }
        System.out.println(
            AgentCommandsTest.JSON.writeValueAsString(
                Map.of("jsonrpc", "2.0", "id", req.path("id").asLong(), "result", result)));
        System.out.flush();
      }
    }
  }
}
