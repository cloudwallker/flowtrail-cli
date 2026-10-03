import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Local protocol fixture: java -cp target/flowtrail.jar examples/McpDemoServer.java [--http 8088].
 */
public class McpDemoServer {
  static final ObjectMapper JSON = new ObjectMapper();

  public static void main(String[] args) throws Exception {
    if (args.length == 2 && args[0].equals("--http")) {
      var server =
          HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[1])), 0);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.createContext(
          "/mcp",
          exchange -> {
            if (exchange.getRequestHeaders().getFirst("Origin") != null) {
              exchange.sendResponseHeaders(403, -1);
              exchange.close();
              return;
            }
            if (exchange.getRequestMethod().equals("DELETE")) {
              exchange.sendResponseHeaders(204, -1);
              exchange.close();
              return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
              exchange.sendResponseHeaders(405, -1);
              exchange.close();
              return;
            }
            try {
              byte[] input = exchange.getRequestBody().readNBytes(65537);
              if (input.length > 65536) throw new IllegalArgumentException();
              JsonNode request = JSON.readTree(input);
              String response = respond(request);
              if (response == null) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
              }
              if (request.path("method").asText().equals("initialize"))
                exchange.getResponseHeaders().set("Mcp-Session-Id", "local-demo-session");
              byte[] body = response.getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, body.length);
              exchange.getResponseBody().write(body);
            } catch (Exception e) {
              exchange.sendResponseHeaders(400, -1);
            } finally {
              exchange.close();
            }
          });
      server.start();
      System.err.println("Local MCP fixture listening on " + server.getAddress());
    } else {
      var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        String response = respond(JSON.readTree(line));
        if (response != null) {
          System.out.println(response);
          System.out.flush();
        }
      }
    }
  }

  static String respond(JsonNode request) throws Exception {
    if (!request.has("id")) return null;
    Object result =
        switch (request.path("method").asText()) {
          case "initialize" ->
              Map.of(
                  "protocolVersion",
                  "2025-11-25",
                  "capabilities",
                  Map.of("tools", Map.of()),
                  "serverInfo",
                  Map.of("name", "flowtrail-local-fixture", "version", "1"));
          case "tools/list" ->
              Map.of(
                  "tools",
                  List.of(
                      Map.of(
                          "name",
                          "echo",
                          "description",
                          "Return local demo text",
                          "inputSchema",
                          Map.of(
                              "type",
                              "object",
                              "properties",
                              Map.of("text", Map.of("type", "string")),
                              "required",
                              List.of("text"),
                              "additionalProperties",
                              false))));
          case "tools/call" ->
              Map.of(
                  "content",
                  List.of(
                      Map.of(
                          "type",
                          "text",
                          "text",
                          "Local MCP fixture: "
                              + request.path("params").path("arguments").path("text").asText())));
          default -> Map.of();
        };
    return JSON.writeValueAsString(
        Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
  }
}
