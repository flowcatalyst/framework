package io.flowcatalyst.framework.core;

import io.flowcatalyst.framework.metrics.Counter;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Named loops on virtual threads, owned by the application's lifecycle (background-and-metrics
 * contract, section 1). Provided by the core module.
 *
 * <pre>{@code
 * app.get(Background.class)
 *     .loop("invoice-sweeper", LoopOptions.every(Duration.ofSeconds(30)), tick -> sweep(tick));
 * }</pre>
 *
 * <p>Loops registered during start-up begin once every module's {@code started} hook ran; later
 * ones begin at once. {@link App#close()} stops every loop before any module's {@code shutdown}
 * hook. Each loop has a health check {@code background:<name>} and the
 * {@code fw_background_*} metrics.
 */
public final class Background {

  private static final Logger LOG = LoggerFactory.getLogger(Background.class);
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");
  private static final int NAME_MAX = 100;

  private final Health health;
  private final MonotonicClock clock;
  private final Counter runs;
  private final Timer durations;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, Loop> loops = new LinkedHashMap<>();
  private boolean started;
  private volatile boolean closing;

  Background(Health health, Metrics metrics, MonotonicClock clock) {
    this.health = health;
    this.clock = clock;
    this.runs =
        metrics.counter(
            "fw_background_runs_total",
            "Runs of background loops by outcome: success, failure, skipped, lost.",
            "loop",
            "outcome");
    this.durations =
        metrics.timer(
            "fw_background_run_duration_seconds",
            "Duration of background loop runs (runs a gate skipped are not timed).",
            "loop");
  }

  /**
   * Registers a loop.
   *
   * @param name {@code [a-z][a-z0-9-]*}, at most 100 characters, unique
   * @param options the schedule, gate and health rules
   * @param task the work
   * @throws IllegalArgumentException for a bad or duplicate name
   * @throws IllegalStateException once the application began closing
   */
  public void loop(String name, LoopOptions options, LoopTask task) {
    if (name.length() > NAME_MAX || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "loop name must match [a-z][a-z0-9-]*, at most " + NAME_MAX + " characters: " + name);
    }
    lock.lock();
    try {
      if (closing) {
        throw new IllegalStateException("the application is closing; loop " + name + " not added");
      }
      if (loops.containsKey(name)) {
        throw new IllegalArgumentException("loop " + name + " already exists");
      }
      Loop loop = new Loop(name, options, task);
      health.add("background:" + name, loop::check, options.criticality());
      loops.put(name, loop);
      if (started) {
        loop.start();
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * The names of the registered loops.
   *
   * @return names in registration order
   */
  public List<String> names() {
    lock.lock();
    try {
      return List.copyOf(loops.keySet());
    } finally {
      lock.unlock();
    }
  }

  /** Starts every registered loop; called once start-up finished. */
  void startAll() {
    lock.lock();
    try {
      if (started || closing) {
        return;
      }
      started = true;
      for (Loop loop : loops.values()) {
        loop.start();
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Stops every loop: all are signalled, waiting ones end at once, running ones get until the
   * drain, then are interrupted and left behind.
   */
  void stop(Duration drain) {
    List<Loop> all;
    lock.lock();
    try {
      closing = true;
      all = new ArrayList<>(loops.values());
    } finally {
      lock.unlock();
    }
    for (Loop loop : all) {
      loop.requestStop();
    }
    long deadline = clock.nanos() + drain.toNanos();
    for (Loop loop : all) {
      loop.awaitEnd(Math.max(0L, deadline - clock.nanos()), drain);
    }
  }

  /** One loop and its state, read by its health check. */
  private final class Loop {
    private final String name;
    private final LoopOptions options;
    private final LoopTask task;
    private final ReentrantLock waitLock = new ReentrantLock();
    private final Condition wake = waitLock.newCondition();
    private final Counter.Series success;
    private final Counter.Series failure;
    private final Counter.Series skipped;
    private final Counter.Series lost;
    private final Timer.Series duration;
    private @Nullable Thread thread;
    private volatile boolean stopping;
    private volatile boolean running;
    private volatile long runStartNanos;
    private volatile long runNumber;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile @Nullable String lastFailure;
    private volatile @Nullable String endedBy;

    Loop(String name, LoopOptions options, LoopTask task) {
      this.name = name;
      this.options = options;
      this.task = task;
      this.success = runs.labels(name, "success");
      this.failure = runs.labels(name, "failure");
      this.skipped = runs.labels(name, "skipped");
      this.lost = runs.labels(name, "lost");
      this.duration = durations.labels(name);
    }

    void start() {
      thread = Thread.ofVirtual().name("loop-" + name).start(this::body);
    }

    private void body() {
      if (!pause(options.initialDelay())) {
        return;
      }
      while (!stopping) {
        if (!runOnce()) {
          return;
        }
        Thread.interrupted(); // a run may leave the flag set; it must not end the wait below
        if (!pause(options.interval())) {
          return;
        }
      }
    }

    /** Waits, ending early on stop. False when the loop should end. */
    private boolean pause(Duration wait) {
      long remaining = wait.toNanos();
      waitLock.lock();
      try {
        while (!stopping && remaining > 0) {
          remaining = wake.awaitNanos(remaining);
        }
        return !stopping;
      } catch (InterruptedException e) {
        return false;
      } finally {
        waitLock.unlock();
      }
    }

    /** One run. False when the loop must end (an Error). */
    private boolean runOnce() {
      long number = runNumber + 1;
      runNumber = number;
      long start = clock.nanos();
      runStartNanos = start;
      running = true;
      boolean timed = true;
      try {
        LoopGate gate = options.gate();
        if (gate == null) {
          task.run(new Tick(name, number, null));
          succeeded();
        } else {
          switch (gate.enter(name, token -> task.run(new Tick(name, number, token)))) {
            case LoopGate.Ran ran -> succeeded();
            case LoopGate.Skipped s -> {
              timed = false;
              skipped.inc();
              LOG.debug("loop {} run {} skipped: {}", name, number, s.reason());
            }
            case LoopGate.Lost l -> {
              failed("lost its gate while running");
              lost.inc();
              LOG.error("loop {} run {} lost its lock while running; it ran without exclusion", name, number);
            }
          }
        }
        return true;
      } catch (Exception e) {
        failed(e.getClass().getSimpleName());
        failure.inc();
        if (stopping && e instanceof InterruptedException) {
          LOG.debug("loop {} run {} interrupted while stopping", name, number);
        } else {
          LOG.error("loop {} run {} failed", name, number, e);
        }
        return true;
      } catch (Error e) {
        endedBy = e.getClass().getSimpleName();
        failure.inc();
        LOG.error("loop {} run {} threw {}; the loop has ended", name, number, endedBy, e);
        return false;
      } finally {
        running = false;
        if (timed) {
          duration.recordNanos(clock.nanos() - start);
        }
      }
    }

    private void succeeded() {
      consecutiveFailures.set(0);
      success.inc();
    }

    private void failed(String what) {
      consecutiveFailures.incrementAndGet();
      lastFailure = what;
    }

    void requestStop() {
      waitLock.lock();
      try {
        stopping = true;
        wake.signalAll();
      } finally {
        waitLock.unlock();
      }
    }

    void awaitEnd(long remainingNanos, Duration drain) {
      Thread t = thread;
      if (t == null) {
        return;
      }
      try {
        if (!t.join(Duration.ofNanos(remainingNanos))) {
          LOG.warn(
              "loop {} did not stop within the drain period of {}; interrupting it and moving on",
              name,
              drain);
          t.interrupt();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        t.interrupt();
      }
    }

    /** The health check: throws when the loop is not healthy; never says more than a class name. */
    void check() {
      String ended = endedBy;
      if (ended != null) {
        throw new IllegalStateException("the loop ended after " + ended);
      }
      int failures = consecutiveFailures.get();
      if (failures >= options.downAfterFailures()) {
        throw new IllegalStateException(
            failures + " runs in a row failed; last: " + lastFailure);
      }
      Duration stuck = options.stuckAfter();
      if (stuck != null && running) {
        long took = clock.nanos() - runStartNanos;
        if (took > stuck.toNanos()) {
          throw new IllegalStateException(
              "run " + runNumber + " has been running for " + took / 1_000_000_000L + "s");
        }
      }
    }
  }

  /** The tick handed to a run. */
  private final class Tick implements LoopTick {
    private final String loop;
    private final long run;
    private final @Nullable Long token;

    Tick(String loop, long run, @Nullable Long token) {
      this.loop = loop;
      this.run = run;
      this.token = token;
    }

    @Override
    public String loop() {
      return loop;
    }

    @Override
    public long run() {
      return run;
    }

    @Override
    public OptionalLong fencingToken() {
      Long t = token;
      return t == null ? OptionalLong.empty() : OptionalLong.of(t);
    }

    @Override
    public boolean stopping() {
      return closing;
    }
  }
}
