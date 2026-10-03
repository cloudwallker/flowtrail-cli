package dev.flowtrail.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.flowtrail.memory.MemoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryStoreTest {
  @TempDir Path root;

  @Test
  void factsAndSessionsSurviveReopenAndRemainProjectScoped() throws Exception {
    Path a = Files.createDirectory(root.resolve("a"));
    Path b = Files.createDirectory(root.resolve("b"));
    var store = new MemoryStore(a);
    var fact = store.save("订单接口使用幂等键 orderId", "src/OrderService.java:8");
    store.saveSession("session-1", "[{\"role\":\"user\",\"content\":\"订单\"}]", "检查幂等");
    var reopened = new MemoryStore(a);
    assertEquals(fact.id(), reopened.recall("订单", 5).getFirst().id());
    assertEquals("检查幂等", reopened.loadSession("session-1").orElseThrow().summary());
    assertTrue(new MemoryStore(b).list().isEmpty());
    assertTrue(new MemoryStore(b).loadSession("session-1").isEmpty());
    assertTrue(reopened.delete(fact.id()));
    assertTrue(store.list().isEmpty());
  }

  @Test
  void sqlTextIsDataAndInvalidSessionJsonIsRejected() throws Exception {
    var store = new MemoryStore(root);
    store.save("quote ' OR 1=1; DROP TABLE memories; --", "manual");
    assertEquals(1, store.list().size());
    assertThrows(IllegalArgumentException.class, () -> store.saveSession("x", "not-json", ""));
    assertThrows(IllegalArgumentException.class, () -> store.save(" ", "manual"));
  }
}
