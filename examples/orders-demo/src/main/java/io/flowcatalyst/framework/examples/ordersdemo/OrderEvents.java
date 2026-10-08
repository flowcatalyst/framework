package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.usecase.DomainEvent;
import io.flowcatalyst.framework.usecase.EventMetadata;
import io.flowcatalyst.framework.usecase.EventType;

/** The domain events of the order use cases. */
public final class OrderEvents {

  /** {@code orders:sales:order:placed}. */
  public static final EventType PLACED = new EventType("orders", "sales", "order", "placed");

  /** {@code orders:sales:order:line-added}. */
  public static final EventType LINE_ADDED = new EventType("orders", "sales", "order", "line-added");

  /** {@code orders:sales:order:cancelled}. */
  public static final EventType CANCELLED = new EventType("orders", "sales", "order", "cancelled");

  private OrderEvents() {}

  /**
   * An order was placed.
   *
   * @param metadata the event metadata
   * @param orderId the order
   * @param customer who ordered
   */
  public record OrderPlaced(EventMetadata metadata, String orderId, String customer)
      implements DomainEvent {}

  /**
   * A line was added to an order.
   *
   * @param metadata the event metadata
   * @param orderId the order
   * @param lineId the new line
   * @param sku what was added
   * @param quantity how many
   * @param unitPriceCents the price of one, in cents
   * @param lineCount how many lines the order has now
   */
  public record OrderLineAdded(
      EventMetadata metadata,
      String orderId,
      String lineId,
      String sku,
      int quantity,
      long unitPriceCents,
      int lineCount)
      implements DomainEvent {}

  /**
   * An order was cancelled.
   *
   * @param metadata the event metadata
   * @param orderId the order
   * @param reason why
   * @param lineCount how many lines it had
   */
  public record OrderCancelled(
      EventMetadata metadata, String orderId, String reason, int lineCount)
      implements DomainEvent {}
}
