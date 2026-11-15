package io.flowcatalyst.framework.partitions.admin;

import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicReference;

/** A wall clock a test moves. */
final class TestClock implements InstantSource {

  private final AtomicReference<Instant> now;

  TestClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  void set(Instant value) {
    now.set(value);
  }

  @Override
  public Instant instant() {
    return now.get();
  }
}
