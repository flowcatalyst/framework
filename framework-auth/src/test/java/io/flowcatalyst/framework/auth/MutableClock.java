package io.flowcatalyst.framework.auth;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/** A clock the tests move. */
public final class MutableClock implements InstantSource {

  private volatile Instant now;

  /**
   * Creates the clock.
   *
   * @param start the first time
   */
  public MutableClock(Instant start) {
    this.now = start;
  }

  @Override
  public Instant instant() {
    return now;
  }

  /**
   * Sets the clock.
   *
   * @param value the new time
   */
  public void set(Instant value) {
    now = value;
  }

  /**
   * Moves the clock forward.
   *
   * @param by how far
   */
  public void advance(Duration by) {
    now = now.plus(by);
  }
}
