package dev.flowtrail;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderProtocolTest {
  @TempDir Path root;

  @Test
  void oversizedUnterminatedStreamLineStopsBeforeWaitingForNewline() throws Exception {
    var release = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext(
        "/chat/completions",
        ex -> {
          try {
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write("x".repeat(1_000_100).getBytes(StandardCharsets.UTF_8));
            ex.getResponseBody().flush();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
          } catch (Exception ignored) {
          } finally {
            ex.close();
          }
        });
    server.start();
    var provider =
        new dev.flowtrail.model.HttpModelProvider(
            "openai",
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "test",
            null,
            java.time.Duration.ofSeconds(5));
    var call =
        executor.submit(() -> provider.complete(java.util.List.of(), java.util.List.of(), s -> {}));
    try {
      assertThrows(
          java.util.concurrent.ExecutionException.class,
          () -> call.get(2, java.util.concurrent.TimeUnit.SECONDS));
    } finally {
      release.countDown();
      call.cancel(true);
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void ollamaUsesObjectArgumentsAndToolNameInResultMessages() throws Exception {
    Files.writeString(root.resolve("sample.txt"), "ollama evidence");
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<String> second = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/chat",
        ex -> {
          String request = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String body;
          if (calls.incrementAndGet() == 1)
            body =
                "{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"function\":{\"name\":\"read_file\",\"arguments\":{\"path\":\"sample.txt\"}}}]},\"done\":false}\n"
                    + "{\"done\":true}\n";
          else {
            second.set(request);
            body = "{\"message\":{\"content\":\"ollama completed\"},\"done\":true}\n";
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, bytes.length);
          ex.getResponseBody().write(bytes);
          ex.close();
        });
    server.start();
    try {
      var result =
          AgentCommandsTest.invoke(
              "agent",
              "read sample",
              "--project",
              root.toString(),
              "--provider",
              "ollama",
              "--base-url",
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "--model",
              "test",
              "--json");
      assertEquals(0, result.code(), result.err());
      var messages = AgentCommandsTest.JSON.readTree(second.get()).path("messages");
      assertTrue(
          messages.get(2).path("tool_calls").get(0).path("function").path("arguments").isObject());
      assertEquals("read_file", messages.get(3).path("tool_name").asText());
      assertTrue(messages.get(3).path("content").asText().contains("ollama evidence"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void assemblesOpenAiArgumentFragmentsBeforeToolExecutionAndFeedsResultBack() throws Exception {
    Files.writeString(root.resolve("sample.txt"), "source evidence");
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> second = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          String request =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String body;
          if (requests.incrementAndGet() == 1)
            body =
                """
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call1","type":"function","function":{"name":"read_file","arguments":"{\\\"path\\\":"}}]}}]}

data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\\"sample.txt\\\"}"}}]}}]}

data: [DONE]

""";
          else {
            second.set(request);
            body =
                "data: {\"choices\":[{\"delta\":{\"content\":\"Verified sample.txt:1\"}}]}\n\n"
                    + "data: [DONE]\n\n";
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var result =
          AgentCommandsTest.invoke(
              "agent",
              "read sample",
              "--project",
              root.toString(),
              "--provider",
              "openai",
              "--base-url",
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "--model",
              "test",
              "--json");
      assertEquals(0, result.code(), result.err());
      assertEquals(2, requests.get());
      assertTrue(second.get().contains("source evidence"));
      assertTrue(second.get().contains("call1"));
      assertEquals(
          "Verified sample.txt:1",
          AgentCommandsTest.JSON.readTree(result.out()).path("answer").asText());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void providerHttpFailureDoesNotSilentlyBecomeMock() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat/completions",
        ex -> {
          byte[] b = "secret-response-body".getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(401, b.length);
          ex.getResponseBody().write(b);
          ex.close();
        });
    server.start();
    try {
      var result =
          AgentCommandsTest.invoke(
              "agent",
              "read",
              "--project",
              root.toString(),
              "--provider",
              "openai",
              "--base-url",
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "--model",
              "test",
              "--json");
      assertEquals(1, result.code());
      assertFalse((result.out() + result.err()).contains("secret-response-body"));
      assertTrue(result.err().contains("401"));
    } finally {
      server.stop(0);
    }
  }
}
