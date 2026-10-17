package io.flowcatalyst.framework.examples.ticketstrial;

/** What an agent can do to move a ticket along its status machine. */
public enum TicketAction {
  /** Start working: an assigned open ticket becomes in progress. */
  START,
  /** The work is done: an in-progress ticket becomes resolved, with a resolution. */
  RESOLVE,
  /** The fix did not hold: a resolved ticket is open again. */
  REOPEN,
  /** Nothing more to do: a resolved ticket is closed for good. */
  CLOSE
}
