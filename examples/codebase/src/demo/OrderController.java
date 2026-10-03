package demo;

/** Framework-free sample entry point; this is not a running HTTP server. */
public final class OrderController {
  private final OrderService service = new OrderService();

  public String postRefund(String requestKey, String orderId) {
    if (orderId == null || orderId.isBlank()) throw new IllegalArgumentException("orderId required");
    return service.refundOrder(requestKey, orderId);
  }
}
