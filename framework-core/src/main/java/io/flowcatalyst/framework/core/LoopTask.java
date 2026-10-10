package io.flowcatalyst.framework.core;

/** The work of a {@link Background} loop, run once per tick. */
@FunctionalInterface
public interface LoopTask {

  /**
   * Runs once.
   *
   * @param tick this run: its number, the fencing token when a lock gate admitted it, and whether
   *     the application is stopping
   * @throws Exception a failed run; it is logged, counted, and the loop continues
   */
  void run(LoopTick tick) throws Exception;
}
