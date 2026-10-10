package io.flowcatalyst.framework.core;

import org.jspecify.annotations.Nullable;

/**
 * Decides, before each run of a {@link Background} loop, whether this instance runs it. The lock
 * gate in {@code framework-locks} ({@code LockGate}) runs it only while holding a lock, so one
 * replica of several does the work.
 */
@FunctionalInterface
public interface LoopGate {

  /**
   * Runs the work if this instance may, and says what happened.
   *
   * @param loop the loop's name
   * @param work the run; call it at most once
   * @return what happened
   * @throws Exception what the work threw, or the gate's own failure (a failed run)
   */
  Result enter(String loop, GatedRun work) throws Exception;

  /** The run a gate admits. */
  @FunctionalInterface
  interface GatedRun {
    /**
     * Runs the task.
     *
     * @param fencingToken the token of what the gate holds, or null when it has none
     * @throws Exception what the task threw
     */
    void run(@Nullable Long fencingToken) throws Exception;
  }

  /** What a gate did. */
  sealed interface Result {}

  /** The work ran, and the gate held what it guards throughout. */
  record Ran() implements Result {}

  /**
   * The work did not run this time, for example because another instance holds the lock.
   *
   * @param reason why, for the debug log
   */
  record Skipped(String reason) implements Result {}

  /** The work ran but the gate lost what it guards while it ran: treat it as unprotected. */
  record Lost() implements Result {}
}
