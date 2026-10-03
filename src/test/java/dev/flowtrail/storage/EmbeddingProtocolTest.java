package dev.flowtrail.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.flowtrail.rag.Embeddings;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EmbeddingProtocolTest {
  @Test
  void timeoutAlsoBoundsAResponseBodyThatStopsAfterHeaders() throws Exception {
    var release = new java.util.concurrent.CountDownLatch(1);
    var headersSent = new java.util.concurrent.CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          exchange.sendResponseHeaders(200, 1024);
          exchange.getResponseBody().write('{');
          exchange.getResponseBody().flush();
          headersSent.countDown();
          try {
            release.await(3, java.util.concurrent.TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var provider =
          Embeddings.openAi(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/"),
              "model",
              "placeholder",
              2,
              java.time.Duration.ofMillis(500));
      try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        var result = executor.submit(() -> provider.embed("query"));
        assertTrue(
            headersSent.await(2, java.util.concurrent.TimeUnit.SECONDS),
            "The server must have started the response body");
        assertTimeoutPreemptively(
            java.time.Duration.ofMillis(1200),
            () -> assertThrows(java.util.concurrent.ExecutionException.class, result::get));
      }
    } finally {
      release.countDown();
      server.stop(0);
    }
  }

  @Test
  void openAiAndOllamaParseRealHttpAndRejectDimensions() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          String request =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          byte[] response =
              (request.contains("broken")
                      ? "{\"data\":[{\"embedding\":[1]}]}"
                      : "{\"data\":[{\"embedding\":[0.6,0.8]}]}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.createContext(
        "/api/embed",
        exchange -> {
          byte[] response = "{\"embeddings\":[[0.8,0.6]]}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      var openAi =
          Embeddings.openAi(base.resolve("/v1/"), "embedding-model", "test-placeholder", 2);
      assertArrayEquals(new double[] {0.6, 0.8}, openAi.embed("refund"));
      assertThrows(IllegalStateException.class, () -> openAi.embed("broken"));
      assertArrayEquals(
          new double[] {0.8, 0.6}, Embeddings.ollama(base, "embedding-model", 2).embed("refund"));
    } finally {
      server.stop(0);
    }
  }
}
