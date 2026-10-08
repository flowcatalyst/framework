package io.flowcatalyst.framework.locks;

/** The outcome of releasing a lease. */
public sealed interface ReleaseResult {

  /** The lock was released and may be acquired at once. */
  record Released() implements ReleaseResult {}

  /** The lease had expired on the store's clock, or someone else holds the lock. Nothing changed. */
  record NotHeld() implements ReleaseResult {}
}
