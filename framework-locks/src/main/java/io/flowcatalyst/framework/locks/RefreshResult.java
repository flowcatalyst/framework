package io.flowcatalyst.framework.locks;

/** The outcome of refreshing a lease. */
public sealed interface RefreshResult {

  /**
   * The lease was extended.
   *
   * @param lease a new value with the new deadline and the same tokens; use it from now on
   */
  record Refreshed(Lease lease) implements RefreshResult {}

  /** The lease had expired on the store's clock, or someone else holds the lock. Nothing changed. */
  record Lost() implements RefreshResult {}
}
