package io.flowcatalyst.framework.locks;

/** The outcome of trying to acquire a lock. */
public sealed interface AcquireResult {

  /**
   * The lock was acquired.
   *
   * @param lease the lease, to refresh and release
   */
  record Acquired(Lease lease) implements AcquireResult {}

  /** Someone else holds the lock (or, for a wait, still did when the wait ended). */
  record Busy() implements AcquireResult {}
}
