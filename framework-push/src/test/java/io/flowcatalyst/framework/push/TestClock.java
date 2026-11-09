package io.flowcatalyst.framework.push;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicReference;

/** A clock the test sets. */
final class TestClock implements InstantSource {

  final AtomicReference<Instant> now;

  TestClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  @Override
  public Instant instant() {
    return now.get();
  }

  void advance(Duration by) {
    now.updateAndGet(i -> i.plus(by));
  }
}
