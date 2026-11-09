package io.flowcatalyst.framework.push;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/** A {@link Log} in memory for the hub's own tests: records every read and can hold them. */
final class MemLog implements Log {

  final List<Entry> entries = new ArrayList<>();
  final List<Integer> limits = new ArrayList<>();
  volatile @Nullable CountDownLatch hold;
  final BlockingQueue<Boolean> reading = new LinkedBlockingQueue<>();
  volatile boolean interrupted;

  /** Adds n pushes with ids 1..n; push i goes to topic test:order:(i mod topics). */
  MemLog fill(int n, int topics, boolean outboxIds) {
    for (int i = 1; i <= n; i++) {
      entries.add(new Entry(i, "test:order:" + (i % topics), "order.updated", "acme", "{\"n\":" + i + "}", TestNode.BEGIN, outboxIds ? i : 0));
    }
    return this;
  }

  synchronized List<Integer> reads() {
    return List.copyOf(limits);
  }

  @Override
  public void append(Connection tx, List<OutboxRow> rows, Instant at) {}

  @Override
  public List<Entry> readAfter(long after, int limit) {
    synchronized (this) {
      limits.add(limit);
    }
    reading.add(true);
    CountDownLatch latch = hold;
    if (latch != null) {
      try {
        latch.await();
      } catch (InterruptedException e) {
        interrupted = true;
        Thread.currentThread().interrupt();
        throw new LogException("interrupted", e);
      }
    }
    synchronized (this) {
      List<Entry> out = new ArrayList<>();
      for (Entry e : entries) {
        if (e.id() > after && out.size() < limit) {
          out.add(e);
        }
      }
      return out;
    }
  }

  @Override
  public synchronized List<Entry> newest(int limit) {
    return List.copyOf(entries.subList(Math.max(0, entries.size() - limit), entries.size()));
  }

  @Override
  public synchronized long latest() {
    return entries.isEmpty() ? 0 : entries.getLast().id();
  }

  @Override
  public long trimmed() {
    return 0;
  }

  @Override
  public long prune(Instant before) {
    return 0;
  }

  @Override
  public void follow(Follower follower, BooleanSupplier stopping) {
    follower.grown();
    while (!stopping.getAsBoolean()) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  @Override
  public void end(Holder holder) {}

  @Override
  public void ping() {}
}
