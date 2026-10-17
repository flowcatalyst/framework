package io.flowcatalyst.framework.examples.ticketstrial;

import java.util.Optional;

/**
 * Where a ticket is in its life. The machine is one table, {@link #after(TicketAction)}:
 *
 * <pre>
 * OPEN --START--> IN_PROGRESS --RESOLVE--> RESOLVED --CLOSE--> CLOSED
 *  ^                                          |
 *  +-----------------REOPEN-------------------+
 * </pre>
 */
public enum TicketStatus {
  /** Waiting for someone to pick it up. */
  OPEN,
  /** Someone is working on it. */
  IN_PROGRESS,
  /** Done, waiting for the requester to accept it. */
  RESOLVED,
  /** Finished; nothing changes any more. */
  CLOSED;

  /**
   * The status an action leads to from this one.
   *
   * @param action what is done
   * @return the new status, or empty when the action is not allowed from here
   */
  public Optional<TicketStatus> after(TicketAction action) {
    return switch (action) {
      case START -> this == OPEN ? Optional.of(IN_PROGRESS) : Optional.empty();
      case RESOLVE -> this == IN_PROGRESS ? Optional.of(RESOLVED) : Optional.empty();
      case REOPEN -> this == RESOLVED ? Optional.of(OPEN) : Optional.empty();
      case CLOSE -> this == RESOLVED ? Optional.of(CLOSED) : Optional.empty();
    };
  }

  /**
   * Whether the SLA clock is running: a ticket that someone still has to resolve.
   *
   * @return true for open and in-progress tickets
   */
  public boolean isActive() {
    return this == OPEN || this == IN_PROGRESS;
  }
}
