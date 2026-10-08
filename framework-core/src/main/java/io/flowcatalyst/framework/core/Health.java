package io.flowcatalyst.framework.core;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/** Named health checks added at boot and run together with a timeout each. */
public final class Health {

  private final Duration timeout;
  private final MonotonicClock clock;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, HealthCheck> checks = new LinkedHashMap<>();

  Health(Duration timeout, MonotonicClock clock) {
    this.timeout = timeout;
    this.clock = clock;
  }

  /**
   * Adds a check.
   *
   * @param name a unique name, for example {@code database:main}
   * @param check the check
   * @throws IllegalArgumentException when the name is already used
   */
  public void add(String name, HealthCheck check) {
    lock.lock();
    try {
      if (checks.putIfAbsent(name, check) != null) {
        throw new IllegalArgumentException("health check '" + name + "' already exists");
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Runs every check in parallel on virtual threads, each limited by {@code app.health.timeout}.
   *
   * @return a result per check
   */
  public HealthReport check() {
    Map<String, HealthCheck> snapshot;
    lock.lock();
    try {
      snapshot = new LinkedHashMap<>(checks);
    } finally {
      lock.unlock();
    }
    Map<String, FutureTask<HealthResult>> running = new LinkedHashMap<>();
    long deadline = clock.nanos() + timeout.toNanos();
    for (Map.Entry<String, HealthCheck> e : snapshot.entrySet()) {
      HealthCheck check = e.getValue();
      FutureTask<HealthResult> task = new FutureTask<>(() -> run(check));
      Thread.ofVirtual().name("health-" + e.getKey()).start(task);
      running.put(e.getKey(), task);
    }
    Map<String, HealthResult> results = new LinkedHashMap<>();
    for (Map.Entry<String, FutureTask<HealthResult>> e : running.entrySet()) {
      long remaining = Math.max(0L, deadline - clock.nanos());
      results.put(e.getKey(), await(e.getValue(), remaining));
    }
    return new HealthReport(results);
  }

  private static HealthResult await(FutureTask<HealthResult> task, long remainingNanos) {
    try {
      return task.get(remainingNanos, TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      task.cancel(true);
      return new HealthResult.TimedOut();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      task.cancel(true);
      return new HealthResult.Down("interrupted");
    } catch (ExecutionException e) {
      return new HealthResult.Down(describe(e.getCause()));
    }
  }

  private static HealthResult run(HealthCheck check) {
    try {
      check.check();
      return new HealthResult.Up();
    } catch (Exception e) {
      return new HealthResult.Down(describe(e));
    }
  }

  private static String describe(@Nullable Throwable t) {
    if (t == null) {
      return "unknown failure";
    }
    String m = t.getMessage();
    String name = t.getClass().getSimpleName();
    return m == null ? name : name + ": " + m;
  }
}
