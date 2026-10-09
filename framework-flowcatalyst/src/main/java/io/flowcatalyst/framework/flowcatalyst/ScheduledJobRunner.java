package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.locks.LockKey;
import io.flowcatalyst.framework.locks.LockStore;
import io.flowcatalyst.framework.locks.LockStoreUnavailableException;
import io.flowcatalyst.framework.locks.Locks;
import io.flowcatalyst.framework.locks.WithLockResult;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs scheduled-job firings in the background (flowcatalyst contract, section 4): the overlap
 * lock, the timeout, and the completion report.
 *
 * <p>Each firing gets a virtual thread. Unless the firing is {@code concurrent}, that thread takes
 * {@code scheduled-job:{jobCode}} from the default lock instance with {@link LockStore#withLock}
 * (no wait), which refreshes the lease every third of its length while the handler runs. The
 * handler itself runs on a second virtual thread, so the first can bound it:
 *
 * <ul>
 *   <li>lock held elsewhere: the handler does not run; a tracked job is completed {@code FAILURE
 *       {skipped: true, reason: "lock-held"}};
 *   <li>lock lost while running: the handler's thread is interrupted, and a tracked job is
 *       completed {@code FAILURE} saying so; the lease never lapses unnoticed;
 *   <li>timeout: the handler's thread is interrupted and a tracked job is completed {@code FAILURE}
 *       at once, but the lock is held (and refreshed) until the handler really stops, so a run
 *       that ignores the interrupt never overlaps the next one;
 *   <li>lock store unavailable: the handler does not run (no exclusion means no run).
 * </ul>
 *
 * <p>The completion call is retried {@value #COMPLETION_RETRIES} times, the delay doubling from
 * {@code completion-retry-delay}, then logged at ERROR.
 */
final class ScheduledJobRunner {

  private static final Logger LOG = LoggerFactory.getLogger(ScheduledJobRunner.class);

  /** Retries of a failed completion call, after the first attempt. */
  static final int COMPLETION_RETRIES = 3;

  /** The prefix of the overlap lock's name. */
  static final String LOCK_PREFIX = "scheduled-job:";

  private final PlatformClient platform;
  private final ApplicationCode application;
  private final @Nullable Locks locks;
  private final Duration defaultTimeout;
  private final Duration lockLease;
  private final Duration completionRetryDelay;
  private final Set<Thread> running = ConcurrentHashMap.newKeySet();
  private volatile boolean stopping;

  ScheduledJobRunner(
      PlatformClient platform,
      ApplicationCode application,
      @Nullable Locks locks,
      Duration defaultTimeout,
      Duration lockLease,
      Duration completionRetryDelay) {
    this.platform = platform;
    this.application = application;
    this.locks = locks;
    this.defaultTimeout = defaultTimeout;
    this.lockLease = lockLease;
    this.completionRetryDelay = completionRetryDelay;
  }

  /**
   * Starts a firing in the background.
   *
   * @return false when the application is shutting down and nothing was started
   */
  boolean start(ScheduledJobFiring firing, ScheduledJobHandler handler) {
    if (stopping) {
      return false;
    }
    Thread thread =
        Thread.ofVirtual()
            .name("fc-job-" + firing.jobCode())
            .unstarted(
                () -> {
                  try {
                    run(firing, handler);
                  } catch (RuntimeException | Error e) {
                    LOG.error(
                        "flowcatalyst: scheduled job {} instance {} ended unexpectedly",
                        firing.jobCode(),
                        firing.instanceId(),
                        e);
                  } finally {
                    running.remove(Thread.currentThread());
                  }
                });
    running.add(thread);
    thread.start();
    return true;
  }

  /**
   * Interrupts every running firing and waits for them, up to a deadline. Firings interrupted this
   * way are reported as failed.
   *
   * @param drain how long to wait
   */
  void stop(Duration drain) {
    stopping = true;
    for (Thread t : running) {
      t.interrupt();
    }
    long deadline = System.nanoTime() + drain.toNanos();
    for (Thread t : running) {
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        break;
      }
      try {
        t.join(Duration.ofNanos(left));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /**
   * How many firings are running, for tests.
   *
   * @return the count
   */
  int running() {
    return running.size();
  }

  // ---- one firing --------------------------------------------------------------------------

  private void run(ScheduledJobFiring firing, ScheduledJobHandler handler) {
    Context ctx =
        new Context(
            Receivers.PLATFORM_CLIENT,
            null,
            application,
            firing.correlationId(),
            null,
            firing.instanceId());
    Duration timeout = firing.timeout().orElse(defaultTimeout);
    Report report = new Report(firing);
    if (firing.concurrent()) {
      report.complete(invoke(handler, ctx, firing, timeout, report));
      return;
    }
    Locks registry = locks;
    if (registry == null) {
      // Start-up refuses job handlers without a lock instance; never run unprotected.
      report.complete(Outcome.failure("no lock instance is configured for the overlap lock"));
      return;
    }
    LockKey key = LockKey.service(LOCK_PREFIX + firing.jobCode());
    WithLockResult<Outcome> result;
    try {
      LockStore store = registry.defaultInstance();
      result =
          store.withLock(
              key,
              lockLease,
              Duration.ZERO,
              lease -> {
                firing.fencingToken(lease.fencingToken());
                return invoke(handler, ctx, firing, timeout, report);
              });
    } catch (LockStoreUnavailableException | IllegalStateException e) {
      LOG.error(
          "flowcatalyst: scheduled job {} instance {} did not run: the overlap lock could not be"
              + " taken ({})",
          firing.jobCode(),
          firing.instanceId(),
          e.getClass().getSimpleName());
      report.complete(Outcome.failure("the overlap lock could not be taken; the job did not run"));
      return;
    } catch (InterruptedException e) {
      report.complete(Outcome.failure("interrupted before it ran"));
      return;
    }
    switch (result) {
      case WithLockResult.NotAcquired<Outcome> notAcquired -> {
        LOG.info(
            "flowcatalyst: scheduled job {} instance {} skipped: another run holds its lock",
            firing.jobCode(),
            firing.instanceId());
        report.complete(Outcome.skipped());
      }
      case WithLockResult.Completed<Outcome> completed -> report.complete(completed.value());
      case WithLockResult.Lost<Outcome> lost -> {
        LOG.error(
            "flowcatalyst: scheduled job {} instance {} lost its overlap lock while running; the"
                + " handler was interrupted and the run is reported as failed",
            firing.jobCode(),
            firing.instanceId());
        report.complete(Outcome.failure("the overlap lock was lost while the job ran"));
      }
    }
  }

  /** Runs the handler on its own virtual thread, bounded by the timeout. Never throws. */
  private Outcome invoke(
      ScheduledJobHandler handler,
      Context ctx,
      ScheduledJobFiring firing,
      Duration timeout,
      Report report) {
    FutureTask<JobResult> task = new FutureTask<>(() -> handler.run(ctx, firing));
    Thread worker = Thread.ofVirtual().name("fc-job-handler-" + firing.jobCode()).unstarted(task);
    worker.start();
    try {
      JobResult result = task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
      return switch (result) {
        case JobResult.Success s -> Outcome.success(s.value());
        case JobResult.Failure f -> Outcome.failure(f.message());
        case null -> {
          LOG.error(
              "flowcatalyst: scheduled job {} instance {} handler returned null (correlation {})",
              firing.jobCode(),
              firing.instanceId(),
              firing.correlationId());
          yield Outcome.failure("the handler returned no result");
        }
      };
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      LOG.error(
          "flowcatalyst: scheduled job {} instance {} failed (correlation {})",
          firing.jobCode(),
          firing.instanceId(),
          firing.correlationId(),
          cause);
      return Outcome.failure(describe(cause));
    } catch (TimeoutException e) {
      worker.interrupt();
      LOG.error(
          "flowcatalyst: scheduled job {} instance {} did not finish within {} (correlation {});"
              + " interrupted it",
          firing.jobCode(),
          firing.instanceId(),
          timeout,
          firing.correlationId());
      // Report now; keep the lock (the caller's withLock refreshes it) until the handler stops.
      report.complete(Outcome.failure("timed out after " + human(timeout)));
      try {
        worker.join();
      } catch (InterruptedException interrupted) {
        // The lock was lost or the application is stopping: stop waiting.
        worker.interrupt();
      }
      return Outcome.reported();
    } catch (InterruptedException e) {
      // The lock was lost (withLock turns this into Lost) or the application is stopping.
      worker.interrupt();
      return Outcome.failure("interrupted: the application is stopping");
    }
  }

  private static String human(Duration d) {
    return d.toMillisPart() == 0 && d.toNanosPart() % 1_000_000 == 0 && d.getSeconds() > 0
        ? d.getSeconds() + "s"
        : d.toMillis() + "ms";
  }

  private static String describe(@Nullable Throwable t) {
    if (t == null) {
      return "the handler failed";
    }
    String message = t.getMessage();
    return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
  }

  // ---- outcome and report ------------------------------------------------------------------

  /**
   * How a firing ended.
   *
   * @param status the completion status
   * @param result the result to send, or null
   * @param alreadyReported true when the report was sent before the outcome was known (timeout)
   */
  record Outcome(PlatformClient.Completion status, @Nullable JsonNode result, boolean alreadyReported) {

    static Outcome success(@Nullable Object value) {
      return new Outcome(PlatformClient.Completion.SUCCESS, value == null ? null : sanitise(value), false);
    }

    static Outcome failure(String message) {
      ObjectNode error = Envelopes.JSON.createObjectNode();
      error.put("error", message);
      return new Outcome(PlatformClient.Completion.FAILURE, sanitise(error), false);
    }

    static Outcome skipped() {
      ObjectNode result = Envelopes.JSON.createObjectNode();
      result.put("skipped", true);
      result.put("reason", "lock-held");
      return new Outcome(PlatformClient.Completion.FAILURE, result, false);
    }

    static Outcome reported() {
      return new Outcome(PlatformClient.Completion.FAILURE, null, true);
    }
  }

  /**
   * A result as JSON, cut when its JSON is longer than {@link JobResult#MAX_RESULT_CHARS}:
   * {@code {truncated: true, preview: ...}}; {@code {unserialisable: true}} when it cannot be
   * written.
   */
  static JsonNode sanitise(Object value) {
    try {
      JsonNode node = value instanceof JsonNode j ? j : Envelopes.JSON.valueToTree(value);
      String json = Envelopes.JSON.writeValueAsString(node);
      if (json.length() <= JobResult.MAX_RESULT_CHARS) {
        return node;
      }
      ObjectNode cut = Envelopes.JSON.createObjectNode();
      cut.put("truncated", true);
      cut.put("preview", json.substring(0, JobResult.MAX_RESULT_CHARS));
      return cut;
    } catch (RuntimeException | StackOverflowError e) {
      // Jackson errors, a getter that throws, or a self-referencing value.
      ObjectNode bad = Envelopes.JSON.createObjectNode();
      bad.put("unserialisable", true);
      return bad;
    }
  }

  /** Sends at most one completion per firing, and only when the firing tracks completion. */
  private final class Report {
    private final ScheduledJobFiring firing;
    private final AtomicBoolean sent = new AtomicBoolean();

    Report(ScheduledJobFiring firing) {
      this.firing = firing;
    }

    void complete(Outcome outcome) {
      if (outcome.alreadyReported() || !firing.tracksCompletion() || !sent.compareAndSet(false, true)) {
        return;
      }
      int attempts = 1 + COMPLETION_RETRIES;
      long delayMs = Math.max(1L, completionRetryDelay.toMillis());
      RuntimeException last = null;
      for (int attempt = 1; attempt <= attempts; attempt++) {
        try {
          platform.completeJobInstance(firing.instanceId(), outcome.status(), outcome.result());
          return;
        } catch (RuntimeException e) {
          last = e;
          if (attempt == attempts) {
            break;
          }
          LOG.warn(
              "flowcatalyst: completion of scheduled job {} instance {} failed (attempt {} of {});"
                  + " retrying in {} ms",
              firing.jobCode(),
              firing.instanceId(),
              attempt,
              attempts,
              delayMs);
          try {
            Thread.sleep(delayMs);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            break;
          }
          delayMs *= 2;
        }
      }
      LOG.error(
          "flowcatalyst: could not report {} for scheduled job {} instance {}: {}",
          outcome.status(),
          firing.jobCode(),
          firing.instanceId(),
          last == null ? "interrupted" : last.getMessage());
    }
  }
}
