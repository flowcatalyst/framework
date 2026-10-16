package io.flowcatalyst.framework.examples.ordersjte;

import java.time.Instant;
import java.util.Locale;

/**
 * An order, as the demo pages show it.
 *
 * @param id the order id, {@code ord-0001}
 * @param customer the customer's name
 * @param status PENDING, PAID, SHIPPED or CANCELLED
 * @param totalCents the total in cents
 * @param createdAt when it was placed
 */
public record Order(String id, String customer, String status, long totalCents, Instant createdAt) {

  /** The statuses, in the order the filter lists them. */
  public static final java.util.List<String> STATUSES = java.util.List.of("PENDING", "PAID", "SHIPPED", "CANCELLED");

  /**
   * The total as money.
   *
   * @return for example {@code 1,234.50}
   */
  public String total() {
    return String.format(Locale.ROOT, "%,.2f", totalCents / 100.0);
  }

  /**
   * The badge variant of the status.
   *
   * @return a badge variant
   */
  public String badgeVariant() {
    return switch (status) {
      case "PAID" -> "success";
      case "SHIPPED" -> "info";
      case "CANCELLED" -> "destructive";
      default -> "secondary";
    };
  }

  /**
   * The status as a label.
   *
   * @return for example {@code Pending}
   */
  public String statusLabel() {
    return status.charAt(0) + status.substring(1).toLowerCase(Locale.ROOT);
  }

  /**
   * Whether the order can still be cancelled.
   *
   * @return true for PENDING and PAID
   */
  public boolean cancellable() {
    return status.equals("PENDING") || status.equals("PAID");
  }
}
