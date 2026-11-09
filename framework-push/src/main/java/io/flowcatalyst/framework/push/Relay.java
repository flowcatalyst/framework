package io.flowcatalyst.framework.push;

import io.flowcatalyst.framework.core.LoopTick;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Moves pushes from {@code fw_push_outbox} into the log (push contract, section 3). One instance at
 * a time runs it, under the lock {@code svc:push-relay}; while it holds the lock it listens on
 * {@code fw_push_outbox} and drains the outbox until the application stops or the lock is lost.
 */
final class Relay {

  /** The relay's loop and lock name (push contract section 3). */
  static final String LOOP = "push-relay";

  /**
   * Bounds one relay run, which is not interrupted when the application stops or the lock is lost:
   * it is let finish, because a run that a second relay overlaps is safe ({@code FOR UPDATE}, A1's
   * primary key, A2's outbox ids) and an interrupted query costs the pool its connection.
   */
  static final long RUN_TIMEOUT_MILLIS = 10_000;

  /** A step of a test that dies or waits inside a run. */
  @FunctionalInterface
  interface Hook {
    void run() throws Exception;
  }

  /**
   * Takes the oldest rows of the outbox as a queue: always from the head, in id order, never after a
   * remembered id, so a row whose transaction commits after a later row's is taken by a later run.
   * FOR UPDATE makes a second relay wait for the first instead of taking the same rows.
   */
  private static final String TAKE_SQL =
      "SELECT id, topic, type, coalesce(client, ''), data, created_at"
          + " FROM fw_push_outbox ORDER BY id LIMIT ? FOR UPDATE";

  private final DataSource dataSource;
  private final Log log;
  private final InstantSource clock;
  private final PushMetrics metrics;
  private final int batch;
  private final long intervalMillis;

  /**
   * When set, runs after the rows were logged and deleted and before the commit; a throw rolls the
   * run back (tests: a relay that dies mid-run).
   */
  volatile @Nullable Hook beforeCommit;

  Relay(DataSource dataSource, Log log, InstantSource clock, PushMetrics metrics, int batch, long intervalMillis) {
    this.dataSource = dataSource;
    this.log = log;
    this.clock = clock;
    this.metrics = metrics;
    this.batch = batch;
    this.intervalMillis = intervalMillis;
  }

  /**
   * One run: take up to {@code batch} rows in id order, append them to the log in that order,
   * delete them, commit.
   *
   * @return how many rows it moved
   */
  int once() throws SQLException {
    try (Connection tx = dataSource.getConnection()) {
      tx.setAutoCommit(false);
      tx.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      boolean committed = false;
      try {
        List<OutboxRow> taken = new ArrayList<>();
        try (PreparedStatement statement = tx.prepareStatement(TAKE_SQL)) {
          statement.setInt(1, batch);
          try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
              taken.add(
                  new OutboxRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()));
            }
          }
        }
        if (taken.isEmpty()) {
          return 0;
        }
        Instant now = clock.instant();
        log.append(tx, taken, now);
        Object[] ids = new Object[taken.size()];
        Instant oldest = taken.getFirst().createdAt();
        for (int i = 0; i < ids.length; i++) {
          OutboxRow row = taken.get(i);
          ids[i] = row.id();
          if (row.createdAt().isBefore(oldest)) {
            oldest = row.createdAt();
          }
        }
        try (PreparedStatement statement = tx.prepareStatement("DELETE FROM fw_push_outbox WHERE id = ANY(?)")) {
          statement.setArray(1, tx.createArrayOf("bigint", ids));
          statement.executeUpdate();
        }
        Hook hook = beforeCommit;
        if (hook != null) {
          try {
            hook.run();
          } catch (SQLException | RuntimeException e) {
            throw e;
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        }
        tx.commit();
        committed = true;
        metrics.published.inc(taken.size());
        metrics.relayLag.record(Duration.between(oldest, now));
        return taken.size();
      } finally {
        if (!committed) {
          try {
            tx.rollback();
          } catch (SQLException e) {
            // the connection is gone; the transaction with it
          }
        }
      }
    }
  }

  /** One run on a thread of its own that nothing interrupts, bounded by {@link #RUN_TIMEOUT_MILLIS}. */
  int onceUninterrupted() throws SQLException {
    AtomicReference<@Nullable Object> outcome = new AtomicReference<>();
    Thread worker =
        Thread.ofVirtual()
            .name("push-relay-run")
            .start(
                () -> {
                  try {
                    outcome.set(once());
                  } catch (SQLException | RuntimeException e) {
                    outcome.set(e);
                  }
                });
    boolean interrupted = false;
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RUN_TIMEOUT_MILLIS);
    while (worker.isAlive()) {
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        break;
      }
      try {
        worker.join(Duration.ofNanos(left));
      } catch (InterruptedException e) {
        interrupted = true; // finish the run, then let the caller see the interrupt
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    Object result = outcome.get();
    if (result instanceof Integer n) {
      return n;
    }
    if (result instanceof SQLException e) {
      throw e;
    }
    if (result instanceof RuntimeException e) {
      throw e;
    }
    throw new SQLException("push relay: the run took more than " + RUN_TIMEOUT_MILLIS + " ms");
  }

  /** Runs until the outbox is empty (a run that moves fewer than a batch) or the loop stops. */
  private void drain(LoopTick tick) throws SQLException {
    while (!tick.stopping() && !Thread.currentThread().isInterrupted()) {
      int n = onceUninterrupted();
      if (n < batch) {
        return;
      }
    }
  }

  /**
   * The loop's task, admitted by the lock gate: keeps relaying while it holds the lock, woken by
   * {@code LISTEN fw_push_outbox} and at least every {@code push.relay.interval}, until the loop
   * stops or the lock is lost. A failure ends the run; the loop runs it again after the interval.
   */
  void run(LoopTick tick) throws Exception {
    try (PgListener listener = new PgListener(dataSource, List.of(PostgresLog.CHANNEL_OUTBOX))) {
      while (!tick.stopping() && !Thread.currentThread().isInterrupted()) {
        drain(tick);
        if (tick.stopping() || Thread.currentThread().isInterrupted()) {
          return;
        }
        // woken by a notification, or after the interval in case one was missed; in short waits,
        // so that a stop is noticed within a quarter of a second
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(intervalMillis);
        boolean woken = false;
        while (!woken && !tick.stopping() && !Thread.currentThread().isInterrupted()) {
          long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
          if (left <= 0) {
            break;
          }
          woken = listener.await((int) Math.min(250, left)).length > 0;
        }
      }
    } catch (SQLException e) {
      if (tick.stopping() || Thread.currentThread().isInterrupted()) {
        return;
      }
      throw e;
    }
  }
}
