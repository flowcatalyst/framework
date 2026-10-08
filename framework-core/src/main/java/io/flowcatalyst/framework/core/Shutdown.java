package io.flowcatalyst.framework.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs shutdown steps one by one, each on its own virtual thread with a drain limit. */
final class Shutdown {

  private static final Logger LOG = LoggerFactory.getLogger(Shutdown.class);

  private Shutdown() {}

  /** One step that may throw. */
  @FunctionalInterface
  interface Step {
    void run() throws Exception;
  }

  /**
   * Runs a step; a failure is logged, and a step that outlasts {@code drain} is logged as an
   * overrun, interrupted and left behind so the next step can run.
   */
  static void runBounded(String description, Duration drain, Step step) {
    Thread t =
        Thread.ofVirtual()
            .name("shutdown")
            .unstarted(
                () -> {
                  try {
                    step.run();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.warn("{} was interrupted", description);
                  } catch (Exception e) {
                    LOG.warn("{} failed", description, e);
                  }
                });
    t.start();
    try {
      if (!t.join(drain)) {
        LOG.warn("{} overran the drain period of {}; moving on", description, drain);
        t.interrupt();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      t.interrupt();
    }
  }

  /** Closes every {@link AutoCloseable} in the list, last to first. */
  static void closeInReverse(List<Object> services, Duration drain) {
    List<Object> reversed = new ArrayList<>(services);
    Collections.reverse(reversed);
    for (Object service : reversed) {
      if (service instanceof AutoCloseable closeable) {
        runBounded(
            "closing " + service.getClass().getSimpleName(),
            drain,
            closeable::close);
      }
    }
  }
}
