package demo;

import java.util.concurrent.atomic.AtomicInteger;

public final class InventoryService {
  private final AtomicInteger available = new AtomicInteger(10);

  /** Reserve stock with compare-and-set; prevent negative inventory. 库存预占。 */
  public boolean reserveStock(int quantity) {
    if (quantity < 1) throw new IllegalArgumentException("positive quantity required");
    while (true) {
      int previous = available.get();
      if (previous < quantity) return false;
      if (available.compareAndSet(previous, previous - quantity)) return true;
    }
  }

  public int availableStock() {
    return available.get();
  }
}
