package io.flowcatalyst.framework.examples.ticketstrial;

/** How urgent a ticket is; the SLA gives each priority its own time to resolve. */
public enum Priority {
  /** Whenever there is time. */
  LOW,
  /** The usual. */
  NORMAL,
  /** Soon. */
  HIGH,
  /** Now. */
  URGENT
}
