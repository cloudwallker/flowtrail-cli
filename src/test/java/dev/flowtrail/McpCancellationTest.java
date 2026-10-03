package dev.flowtrail;

import static org.junit.jupiter.api.Assertions.*;

import dev.flowtrail.mcp.McpClient;
import dev.flowtrail.mcp.McpTransport;
import dev.flowtrail.policy.ExecutionPolicy;
import dev.flowtrail.tool.ToolRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpCancellationTest {
  @TempDir Path root;

  @Test
  void blockedCancellationNotificationCannotPreventTransportCleanup() throws Exception {
    CountDownLatch release = new CountDownLatch(1),
        closed = new CountDownLatch(1),
        notification = new CountDownLatch(1);
    McpTransport transport =
        new McpTransport() {
          public com.fasterxml.jackson.databind.JsonNode exchange(
              com.fasterxml.jackson.databind.JsonNode message) throws Exception {
            if (message.path("method").asText().equals("notifications/cancelled"))
              notification.countDown();
            release.await();
            return dev.flowtrail.model.Json.MAPPER.nullNode();
          }

          public void version(String version) {}

          public void close() {
            closed.countDown();
            release.countDown();
          }
        };
    var constructor =
        McpClient.class.getDeclaredConstructor(String.class, McpTransport.class, Duration.class);
    constructor.setAccessible(true);
    var client = constructor.newInstance("blocked", transport, Duration.ofMillis(50));
    var registry =
        new ToolRegistry(
            new ExecutionPolicy(root, false, true, Set.of()), Duration.ofSeconds(1), s -> {});
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var task =
        executor.submit(
            () -> {
              try {
                client.register(registry);
              } catch (Exception ignored) {
              }
            });
    try {
      assertTrue(notification.await(1, TimeUnit.SECONDS));
      assertTrue(
          closed.await(500, TimeUnit.MILLISECONDS),
          "Cleanup must have its own deadline even when cancellation blocks");
    } finally {
      release.countDown();
      task.cancel(true);
      client.close();
      executor.shutdownNow();
    }
  }
}
