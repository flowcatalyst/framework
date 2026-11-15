package io.flowcatalyst.framework.partitions;

import io.flowcatalyst.framework.metrics.Counter;
import java.time.InstantSource;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** What the passes share: the clock, the failure counter, the archive segment size and the strays already logged. */
final class Core {

  final InstantSource clock;
  final Counter failures;
  final int segmentBytes;
  private final Set<String> strayLogged = ConcurrentHashMap.newKeySet();

  Core(InstantSource clock, Counter failures, int segmentBytes) {
    this.clock = clock;
    this.failures = failures;
    this.segmentBytes = segmentBytes;
  }

  void failed(Target tg, String step) {
    failures.labels(tg.qualified, step).inc();
  }

  /** Whether this is the first time the stray is seen by this process. */
  boolean firstSighting(String key) {
    return strayLogged.add(key);
  }
}
