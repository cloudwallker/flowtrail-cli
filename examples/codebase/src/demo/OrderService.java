package demo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed demonstration: the key provides local in-process idempotency only. */
public final class OrderService {
  private final Map<String, String> refunds = new ConcurrentHashMap<>();

  /** Refund an order once for a stable request key. 退款接口幂等键。 */
  public String refundOrder(String requestKey, String orderId) {
    if (requestKey == null || requestKey.isBlank()) throw new IllegalArgumentException("requestKey required");
    return refunds.computeIfAbsent(requestKey, ignored -> "refunded:" + orderId);
  }

  /** Retrieve the prior outcome without issuing another refund. 查询退款结果。 */
  public String findRefund(String requestKey) {
    return refunds.get(requestKey);
  }
}
