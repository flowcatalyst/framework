package io.flowcatalyst.framework.core;

import java.util.OptionalLong;

/** One run of a {@link Background} loop. */
public interface LoopTick {

  /**
   * The loop's name.
   *
   * @return the name
   */
  String loop();

  /**
   * The run number, starting at 1.
   *
   * @return the number
   */
  long run();

  /**
   * The fencing token of the lock the gate took for this run (locks contract): pass it to what the
   * run writes, so a write from a run that lost its lock can be refused.
   *
   * @return the token, or empty when the loop has no gate or its gate has no token
   */
  OptionalLong fencingToken();

  /**
   * Whether the application began stopping. A long run should check it and return.
   *
   * @return true once shutdown began
   */
  boolean stopping();
}
