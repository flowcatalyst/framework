package io.flowcatalyst.framework.examples.ordersdemo;

/** Where an order is in its life. */
public enum OrderStatus {
  /** Lines may be added. */
  OPEN,
  /** Cancelled; nothing more may change. */
  CANCELLED
}
