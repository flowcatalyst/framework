package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.usecase.AuditData;
import java.util.Map;

/** The commands of the order use cases. */
public final class Commands {

  private Commands() {}

  /**
   * Place a new order.
   *
   * @param customer who is ordering
   */
  public record PlaceOrder(String customer) implements AuditData {
    @Override
    public Map<String, Object> auditData() {
      return Map.of("customer", customer);
    }
  }

  /**
   * Add a line to an open order.
   *
   * @param orderId the order
   * @param sku what to add
   * @param quantity how many, positive
   * @param unitPriceCents the price of one, in cents
   */
  public record AddLine(String orderId, String sku, int quantity, long unitPriceCents)
      implements AuditData {
    @Override
    public Map<String, Object> auditData() {
      return Map.of("orderId", orderId, "sku", sku, "quantity", quantity);
    }
  }

  /**
   * Cancel an open order.
   *
   * @param orderId the order
   * @param reason why
   */
  public record CancelOrder(String orderId, String reason) implements AuditData {
    @Override
    public Map<String, Object> auditData() {
      return Map.of("orderId", orderId, "reason", reason);
    }
  }
}
