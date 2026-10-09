package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.locks.LockKey;
import io.flowcatalyst.framework.locks.LockStore;
import io.flowcatalyst.framework.locks.Locks;
import io.flowcatalyst.framework.locks.WithLockResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes {@code fw_dispatch_once} rows older than the retention, in the background (flowcatalyst
 * contract, section 4a). One virtual thread owned by the module: it purges at start-up and then
 * every {@code once-purge-interval}, and stops at shutdown.
 *
 * <p>Bounded: rows go in batches of {@value #BATCH}, at most {@value #MAX_BATCHES} batches per run
 * (the rest waits for the next run), so one run never holds a long transaction or runs unbounded.
 * Safe with several replicas: when a default lock instance exists a run takes the service lock
 * {@value #LOCK_NAME} without waiting and skips if another replica has it; without one, the
 * {@code DELETE} is idempotent and concurrent runs only delete the same old rows once.
 */
final class OncePurge {

  private static final Logger LOG = LoggerFactory.getLogger(OncePurge.class);

  static final String TABLE = "fw_dispatch_once";
  static final String LOCK_NAME = "fw-dispatch-once-purge";
  static final int BATCH = 5_000;
  static final int MAX_BATCHES = 200;

  private static final String DELETE =
      "DELETE FROM fw_dispatch_once WHERE ctid IN"
          + " (SELECT ctid FROM fw_dispatch_once WHERE processed_at < ? LIMIT ?)";

  private final Database db;
  private final @Nullable Locks locks;
  private final Duration retention;
  private final Duration interval;
  private final InstantSource clock;
  private volatile @Nullable Thread thread;

  OncePurge(
      Database db, @Nullable Locks locks, Duration retention, Duration interval, InstantSource clock) {
    this.db = db;
    this.locks = locks;
    this.retention = retention;
    this.interval = interval;
    this.clock = clock;
  }

  /** Starts the background task. */
  void start() {
    Thread t = Thread.ofVirtual().name("fc-once-purge").unstarted(this::loop);
    thread = t;
    t.start();
  }

  /**
   * Stops the task and waits for it, up to a deadline.
   *
   * @param drain how long to wait
   */
  void stop(Duration drain) {
    Thread t = thread;
    if (t == null) {
      return;
    }
    t.interrupt();
    try {
      t.join(drain);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void loop() {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        long deleted = runOnce();
        if (deleted > 0) {
          LOG.info("flowcatalyst: purged {} fw_dispatch_once rows older than {}", deleted, retention);
        }
      } catch (RuntimeException | SQLException e) {
        // Never stops the task: the next run tries again.
        LOG.warn("flowcatalyst: purging {} failed ({}); will retry", TABLE, e.toString());
      }
      try {
        Thread.sleep(interval);
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  /**
   * One purge run: deletes rows whose {@code processed_at} is before {@code now - retention}
   * (the application's clock). Takes the cross-replica lock when a lock instance is configured.
   *
   * @return rows deleted; 0 when another replica held the lock
   */
  long runOnce() throws SQLException {
    Instant cutoff = clock.instant().minus(retention);
    LockStore store = lockStore();
    if (store == null) {
      return deleteOlderThan(cutoff);
    }
    try {
      WithLockResult<Long> result =
          store.withLock(
              LockKey.service(LOCK_NAME),
              Duration.ofMinutes(5),
              Duration.ZERO,
              lease -> deleteOlderThan(cutoff));
      return switch (result) {
        case WithLockResult.Completed<Long> done -> done.value();
        case WithLockResult.NotAcquired<Long> other -> 0L;
        case WithLockResult.Lost<Long> lost -> 0L;
      };
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return 0;
    }
  }

  private @Nullable LockStore lockStore() {
    Locks registry = locks;
    if (registry == null) {
      return null;
    }
    try {
      return registry.defaultInstance();
    } catch (IllegalStateException e) {
      return null;
    }
  }

  long deleteOlderThan(Instant cutoff) throws SQLException {
    long total = 0;
    OffsetDateTime bound = OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC);
    try (Connection c = db.dataSource().getConnection();
        PreparedStatement s = c.prepareStatement(DELETE)) {
      for (int i = 0; i < MAX_BATCHES && !Thread.currentThread().isInterrupted(); i++) {
        s.setObject(1, bound);
        s.setInt(2, BATCH);
        int n = s.executeUpdate();
        total += n;
        if (n < BATCH) {
          break;
        }
      }
    }
    return total;
  }
}
