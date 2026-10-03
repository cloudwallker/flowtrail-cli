package dev.flowtrail;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FlowTrailTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path directory;

  @Test
  void helpListsTheFourCommands() {
    Result result = invoke("--help");
    assertEquals(0, result.code());
    for (String command : new String[] {"init", "validate", "run", "doctor"}) {
      assertTrue(result.out().contains(command));
    }
  }

  @Test
  void generatedExampleRunsAndCannotOverwriteUserData() throws Exception {
    Path file = directory.resolve("含 空格.json");
    assertEquals(0, invoke("init", file.toString(), "--json").code());
    assertEquals(0, invoke("validate", file.toString()).code());
    assertEquals(0, invoke("run", file.toString()).code());
    Files.writeString(file, "my original data");
    assertEquals(1, invoke("init", file.toString()).code());
    assertEquals("my original data", Files.readString(file));
  }

  @Test
  void textStepsPassUnicodeAndResolveOnlyOnce() throws Exception {
    Path file =
        flow(
            """
                {"name":"hello","steps":[
                  {"id":"first","type":"text","text":"你好 $5 \\nworld"},
                  {"id":"second","type":"text","text":"result: ${first.output}"}]}
                """);
    Result result = invoke("run", file.toString(), "--json");
    assertEquals(0, result.code(), result.err());
    JsonNode json = JSON.readTree(result.out());
    assertEquals("result: 你好 $5 \nworld", json.path("steps").get(1).path("output").asText());
    assertTrue(result.err().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"name\":\"x\",\"steps\":[]}",
        "{\"name\":\"x\",\"steps\":[{\"id\":\"a\",\"type\":\"shell\",\"text\":\"x\"}]}",
        "{\"name\":\"x\",\"steps\":[{\"id\":\"a\",\"type\":\"text\",\"text\":\"x\",\"typo\":1}]}",
        "{\"name\":\"x\",\"steps\":[{\"id\":\"a\",\"type\":\"text\",\"text\":123}]}",
        "{\"name\":\"x\",\"steps\":[{\"id\":\"a\",\"type\":\"text\",\"text\":\"${b.output}\"},{\"id\":\"b\",\"type\":\"text\",\"text\":\"x\"}]}",
        "{\"name\":\"x\",\"steps\":[{\"id\":\"a\",\"type\":\"text\",\"text\":\"x\"},{\"id\":\"a\",\"type\":\"text\",\"text\":\"y\"}]}",
        "{\"name\":\"x\",\"steps\":[{\"id\":\"a\",\"type\":\"text\",\"text\":\"${missing}\"}]}",
        "{\"name\":\"x\",\"steps\":[{\"id\":\"a\",\"type\":\"http\",\"url\":\"file:///etc/passwd\"}]}",
        "{\"name\":\"x\",\"steps\":[{\"id\":\"a\",\"type\":\"http\",\"url\":\"http://user:secret@localhost\"}]}",
        "{\"name\":\"x\",\"steps\":[{\"id\":\"a\",\"type\":\"http\",\"url\":\"http://localhost\",\"timeoutSeconds\":0}]}",
        "{\"name\":\"x\",\"steps\":[{\"id\":\"a\",\"type\":\"http\",\"url\":\"http://localhost\",\"timeoutSeconds\":1.5}]}",
        "{\"name\":\"x\",\"steps\":[{\"id\":\"a\",\"type\":\"http\",\"url\":\"http://localhost\",\"method\":\"DELETE\"}]}",
        "{\"name\":\"x\",\"steps\":[{\"id\":\"a\",\"type\":\"http\",\"url\":\"http://localhost\",\"headers\":{\"Host\":\"example.org\"}}]}",
        "{\"name\":\"x\",\"steps\":[{\"id\":\"a\",\"type\":\"text\",\"text\":\"${unfinished\"}]}",
        "{\"name\":\"x\",\"name\":\"y\",\"steps\":[]}",
        "{} {}",
        "not json secret-token"
      })
  void invalidDefinitionsProduceStructuredErrorsWithoutEchoingInput(String content)
      throws Exception {
    Result result = invoke("validate", flow(content).toString(), "--json");
    assertEquals(2, result.code(), result.err());
    assertTrue(result.out().isEmpty());
    assertFalse(JSON.readTree(result.err()).path("ok").asBoolean());
    assertFalse(result.err().contains("secret-token"));
  }

  @Test
  void missingFileAndArgumentErrorsAreMachineReadable() throws Exception {
    Result missing = invoke("run", directory.resolve("missing.json").toString(), "--json");
    assertEquals(1, missing.code());
    assertFalse(JSON.readTree(missing.err()).path("ok").asBoolean());
    Result arguments = invoke("run", "--json");
    assertEquals(2, arguments.code());
    assertFalse(JSON.readTree(arguments.err()).path("ok").asBoolean());
  }

  @Test
  void doctorReportsJavaAndDirectoryChecks() throws Exception {
    Result result = invoke("doctor", "--json");
    assertEquals(0, result.code(), result.err());
    assertTrue(JSON.readTree(result.out()).path("checks").path("java21").asBoolean());
    assertTrue(
        JSON.readTree(result.out()).path("checks").path("workingDirectoryWritable").asBoolean());
  }

  @Test
  void httpPostPassesHeadersAndBodyAndFeedsTheNextStep() throws Exception {
    AtomicReference<String> request = new AtomicReference<>();
    AtomicReference<String> header = new AtomicReference<>();
    HttpServer server = server();
    server.createContext(
        "/echo",
        exchange -> {
          request.set(
              exchange.getRequestMethod()
                  + ":"
                  + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          header.set(exchange.getRequestHeaders().getFirst("X-Learning"));
          byte[] body = "你好 ${unexpanded.output}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    try {
      Path file =
          flow(
              """
                    {"name":"http","steps":[
                    {"id":"input","type":"text","text":"hello"},
                    {"id":"fetch","type":"http","url":"http://127.0.0.1:%d/echo","method":"POST","headers":{"X-Learning":"demo"},"body":"${input.output}"},
                    {"id":"show","type":"text","text":"${fetch.output}"}]}
                    """
                  .formatted(server.getAddress().getPort()));
      Result result = invoke("run", file.toString(), "--json");
      assertEquals(0, result.code(), result.err());
      assertEquals("POST:hello", request.get());
      assertEquals("demo", header.get());
      assertEquals(
          "你好 ${unexpanded.output}",
          JSON.readTree(result.out()).path("steps").get(2).path("output").asText());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void httpFailureStopsLaterStepsAndDoesNotPrintResponseSecrets() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    HttpServer server = server();
    server.createContext(
        "/fail",
        exchange -> {
          calls.incrementAndGet();
          byte[] body = "private-token".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(503, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/fail";
      Path file =
          flow(
              """
                    {"name":"fail","steps":[{"id":"first","type":"http","url":"%s"},{"id":"second","type":"http","url":"%s"}]}
                    """
                  .formatted(url, url));
      Result result = invoke("run", file.toString(), "--json");
      assertEquals(1, result.code());
      assertEquals(1, calls.get());
      assertTrue(result.out().isEmpty());
      assertTrue(JSON.readTree(result.err()).path("error").asText().contains("503"));
      assertFalse(result.err().contains("private-token"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void validatesWholeFileBeforeMakingAnyRequest() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    HttpServer server = server();
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    try {
      Path file =
          flow(
              """
                    {"name":"invalid","steps":[{"id":"fetch","type":"http","url":"http://127.0.0.1:%d/"},{"id":"bad","type":"text","text":"${missing.output}"}]}
                    """
                  .formatted(server.getAddress().getPort()));
      assertEquals(2, invoke("run", file.toString()).code());
      assertEquals(0, calls.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void slowResponseTimesOut() throws Exception {
    HttpServer server = server();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      server.setExecutor(executor);
      server.createContext(
          "/slow",
          exchange -> {
            try {
              Thread.sleep(1800);
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
            }
            exchange.close();
          });
      server.start();
      try {
        Result result =
            invoke(
                "run",
                flow("""
                        {"name":"timeout","steps":[{"id":"slow","type":"http","url":"http://127.0.0.1:%d/slow","timeoutSeconds":1}]}
                        """
                        .formatted(server.getAddress().getPort()))
                    .toString(),
                "--json");
        assertEquals(1, result.code());
        assertTrue(JSON.readTree(result.err()).path("error").asText().contains("timed out"));
      } finally {
        server.stop(0);
      }
    }
  }

  private HttpServer server() throws Exception {
    return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
  }

  @Test
  void timeoutCoversTheResponseBody() throws Exception {
    HttpServer server = server();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      server.setExecutor(executor);
      server.createContext(
          "/body",
          exchange -> {
            exchange.sendResponseHeaders(200, 2);
            try (var output = exchange.getResponseBody()) {
              output.write('A');
              output.flush();
              try {
                Thread.sleep(2500);
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              }
              output.write('B');
            }
          });
      server.start();
      try {
        Path file =
            flow(
                """
                        {"name":"body-timeout","steps":[{"id":"slow","type":"http","url":"http://127.0.0.1:%d/body","timeoutSeconds":1}]}
                        """
                    .formatted(server.getAddress().getPort()));
        long started = System.nanoTime();
        Result result = invoke("run", file.toString(), "--json");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertEquals(1, result.code(), result.out());
        assertTrue(result.err().contains("timed out"));
        assertTrue(
            elapsedMillis < 2200, "Timeout must not wait for the response body: " + elapsedMillis);
      } finally {
        server.stop(0);
      }
    }
  }

  @Test
  void urlCanReferenceAnEntirePriorOutput() throws Exception {
    HttpServer server = server();
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    try {
      Path file =
          flow(
              """
                    {"name":"url-ref","steps":[{"id":"base","type":"text","text":"http://127.0.0.1:%d/"},
                    {"id":"fetch","type":"http","url":"${base.output}"}]}
                    """
                  .formatted(server.getAddress().getPort()));
      Result result = invoke("run", file.toString(), "--json");
      assertEquals(0, result.code(), result.err());
      assertEquals("", JSON.readTree(result.out()).path("steps").get(1).path("output").asText());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void interruptedExecutionPreservesTheInterruptAndStopsBeforeOutput() throws Exception {
    Path file =
        flow(
            "{\"name\":\"interrupt\",\"steps\":[{\"id\":\"a\",\"type\":\"text\",\"text\":\"never\"}]}");
    Thread.currentThread().interrupt();
    try {
      Result result = invoke("run", file.toString(), "--json");
      assertEquals(130, result.code());
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(result.out().isEmpty());
    } finally {
      Thread.interrupted();
    }
  }

  private Path flow(String content) throws Exception {
    Path path = directory.resolve("flow.json");
    Files.writeString(path, content);
    return path;
  }

  private Result invoke(String... args) {
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    int code = FlowTrail.execute(args, new PrintWriter(out, true), new PrintWriter(err, true));
    return new Result(code, out.toString(), err.toString());
  }

  private record Result(int code, String out, String err) {}
}
