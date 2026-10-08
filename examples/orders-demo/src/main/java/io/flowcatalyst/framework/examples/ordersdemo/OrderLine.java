package io.flowcatalyst.framework.examples.ordersdemo;

/**
 * One line of an order.
 *
 * @param lineId the line's id, {@code lin_} plus a TSID
 * @param sku what was ordered
 * @param quantity how many, positive
 * @param unitPriceCents the price of one, in cents
 */
public record OrderLine(String lineId, String sku, int quantity, long unitPriceCents) {

  /**
   * The line's total.
   *
   * @return quantity times unit price, in cents
   */
  public long totalCents() {
    return quantity * unitPriceCents;
  }
}
