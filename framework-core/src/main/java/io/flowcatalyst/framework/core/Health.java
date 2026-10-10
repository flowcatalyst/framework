package io.flowcatalyst.framework.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * Named health checks added at boot and run together with a timeout each.
 *
 * <p>Each check has a {@link Criticality}: a critical check that is not up makes the application
 * not ready; an informational one is only reported. The criticality a module asks for can be
 * changed by name in configuration: {@code app.health.critical} and {@code app.health.informational}
 * list check names, and win over what the module asked for.
 */
public final class Health {

  private record Registered(HealthCheck check, Criticality criticality) {}

  private final Duration timeout;
  private final MonotonicClock clock;
  private final Set<String> forcedCritical;
  private final Set<String> forcedInformational;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, Registered> checks = new LinkedHashMap<>();

  Health(Duration timeout, MonotonicClock clock) {
    this(timeout, clock, Set.of(), Set.of());
  }

  Health(
      Duration timeout,
      MonotonicClock clock,
      Set<String> forcedCritical,
      Set<String> forcedInformational) {
    this.timeout = timeout;
    this.clock = clock;
    this.forcedCritical = Set.copyOf(forcedCritical);
    this.forcedInformational = Set.copyOf(forcedInformational);
  }

  /**
   * Adds a critical check: when it is not up, the application is not ready.
   *
   * @param name a unique name, for example {@code database:main}
   * @param check the check
   * @throws IllegalArgumentException when the name is already used
   */
  public void add(String name, HealthCheck check) {
    add(name, check, Criticality.CRITICAL);
  }

  /**
   * Adds a check with the given criticality, unless configuration names the check in
   * {@code app.health.critical} or {@code app.health.informational}, which wins.
   *
   * @param name a unique name, for example {@code database:main}
   * @param check the check
   * @param criticality whether the check decides readiness
   * @throws IllegalArgumentException when the name is already used
   */
  public void add(String name, HealthCheck check, Criticality criticality) {
    Criticality effective = criticality;
    if (forcedCritical.contains(name)) {
      effective = Criticality.CRITICAL;
    } else if (forcedInformational.contains(name)) {
      effective = Criticality.INFORMATIONAL;
    }
    lock.lock();
    try {
      if (checks.putIfAbsent(name, new Registered(check, effective)) != null) {
        throw new IllegalArgumentException("health check '" + name + "' already exists");
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * The criticality a check was added with, after configuration was applied.
   *
   * @param name the check's name
   * @return its criticality, or null when no check has that name
   */
  public @Nullable Criticality criticality(String name) {
    lock.lock();
    try {
      Registered r = checks.get(name);
      return r == null ? null : r.criticality();
    } finally {
      lock.unlock();
    }
  }

  /**
   * The names given in {@code app.health.critical} or {@code app.health.informational} that no
   * added check has, for a start-up warning (a typo there must not pass silently).
   *
   * @return the unmatched names, sorted
   */
  List<String> unmatchedOverrides() {
    Set<String> names = new LinkedHashSet<>();
    names.addAll(forcedCritical);
    names.addAll(forcedInformational);
    lock.lock();
    try {
      names.removeAll(checks.keySet());
    } finally {
      lock.unlock();
    }
    List<String> out = new ArrayList<>(names);
    out.sort(null);
    return out;
  }

  /**
   * Runs every check in parallel on virtual threads, each limited by {@code app.health.timeout}.
   *
   * @return a result per check, with the names of the informational ones
   */
  public HealthReport check() {
    Map<String, Registered> snapshot;
    lock.lock();
    try {
      snapshot = new LinkedHashMap<>(checks);
    } finally {
      lock.unlock();
    }
    Map<String, FutureTask<HealthResult>> running = new LinkedHashMap<>();
    Set<String> informational = new LinkedHashSet<>();
    long deadline = clock.nanos() + timeout.toNanos();
    for (Map.Entry<String, Registered> e : snapshot.entrySet()) {
      HealthCheck check = e.getValue().check();
      if (e.getValue().criticality() == Criticality.INFORMATIONAL) {
        informational.add(e.getKey());
      }
      FutureTask<HealthResult> task = new FutureTask<>(() -> run(check));
      Thread.ofVirtual().name("health-" + e.getKey()).start(task);
      running.put(e.getKey(), task);
    }
    Map<String, HealthResult> results = new LinkedHashMap<>();
    for (Map.Entry<String, FutureTask<HealthResult>> e : running.entrySet()) {
      long remaining = Math.max(0L, deadline - clock.nanos());
      results.put(e.getKey(), await(e.getValue(), remaining));
    }
    return new HealthReport(results, informational);
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
