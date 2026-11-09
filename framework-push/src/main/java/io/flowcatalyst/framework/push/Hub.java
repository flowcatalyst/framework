package io.flowcatalyst.framework.push;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This instance's view of the log (push contract, section 8): it follows the log, keeps the most
 * recent pushes in memory and fans each push out to the streams of its topic.
 */
final class Hub {

  /** Why a stream ended (the reason label of fw_push_streams_ended_total). */
  static final String END_CLIENT = "client";

  static final String END_SLOW = "slow";
  static final String END_EXPIRED = "expired";
  static final String END_REVOKED = "revoked";
  static final String END_DRAIN = "drain";

  /** How many entries one read of the log asks for. */
  static final int PAGE_SIZE = 1000;

  private static final Logger LOG = LoggerFactory.getLogger(Hub.class);

  /** The hub's settings. */
  record Config(int buffer, int recent, int maxStreams, int maxPerPrincipal, long pollMillis) {}

  /** What opening a stream gives: the stream and where it starts, or why it cannot open. */
  sealed interface Opened {}

  record Ok(PushStream stream, Opening opening) implements Opened {}

  record Refused(boolean draining) implements Opened {}

  /**
   * Where a stream starts: the highest id dispatched when it was registered (pushes after it come
   * live), and, when the memory holds everything after the client's Last-Event-ID, the pushes to
   * replay from it.
   */
  record Opening(long latest, boolean memory, List<Frame> replay) {}

  /** A log replay stopped because the stream ended meanwhile (the drain, a revocation, a slow stream). */
  static final class StreamEnded extends RuntimeException {
    private static final long serialVersionUID = 1L;

    StreamEnded() {
      super(null, null, false, false);
    }
  }

  private final Log log;
  private final PushMetrics metrics;
  private final Config config;

  private final ReentrantLock lock = new ReentrantLock();
  private long last; // the highest id dispatched
  private long from; // the ring holds every push after from
  private final ArrayDeque<Frame> ring = new ArrayDeque<>();
  private final OutboxSeen seen;
  private final Map<String, Set<PushStream>> byTopic = new HashMap<>();
  private final Map<String, Set<PushStream>> byPrincipal = new HashMap<>();
  private final Set<PushStream> all = new HashSet<>();
  private boolean draining;

  private final Semaphore wake = new Semaphore(0);
  private final java.util.concurrent.CountDownLatch following = new java.util.concurrent.CountDownLatch(1);
  private volatile boolean stopped;
  private @Nullable Thread reader;
  private @Nullable Thread follower;

  private final ReentrantLock pageLock = new ReentrantLock();
  private final Map<Long, CompletableFuture<Page>> pages = new HashMap<>();

  Hub(Log log, PushMetrics metrics, Config config) {
    this.log = log;
    this.metrics = metrics;
    this.config = config;
    this.seen = new OutboxSeen(Math.max(config.recent(), 10_000));
  }

  /**
   * Reads where the log stands before the hub serves (push contract section 8): the last id, and the
   * newest push.recent pushes into memory, in one read, so that the clients of an instance that was
   * just started resume from memory too. The memory then holds every push after {@code from}: the
   * push before the oldest one read, or, when the log holds no more than push.recent pushes, the
   * highest id the log has deleted (read after the pushes, so a push pruned meanwhile is not counted
   * as held).
   */
  void warm() {
    long latest = log.latest();
    List<Entry> entries = List.of();
    long trimmed = latest;
    if (config.recent() > 0) {
      entries = log.newest(config.recent() + 1);
      if (entries.size() <= config.recent()) {
        trimmed = log.trimmed();
      }
    }
    lock.lock();
    try {
      last = latest;
      from = latest;
      if (!entries.isEmpty()) {
        List<Entry> held = entries;
        if (entries.size() > config.recent()) {
          from = entries.getFirst().id();
          held = entries.subList(1, entries.size());
        } else {
          from = trimmed;
        }
        for (Entry e : held) {
          if (e.id() <= from) {
            continue;
          }
          last = Math.max(last, e.id());
          if (e.outboxId() != 0 && !seen.add(e.outboxId())) {
            continue;
          }
          ring.addLast(Frame.of(e));
        }
      }
      metrics.logId.set(last);
    } finally {
      lock.unlock();
    }
  }

  // ---- streams ------------------------------------------------------------------------------

  /** Whether a subscribe of principal is within the limits now (push contract section 8). */
  boolean admit(String principal) {
    lock.lock();
    try {
      return admitLocked(principal);
    } finally {
      lock.unlock();
    }
  }

  private boolean admitLocked(String principal) {
    if (all.size() >= config.maxStreams()) {
      return false;
    }
    if (!principal.isEmpty()) {
      Set<PushStream> mine = byPrincipal.get(principal);
      return mine == null || mine.size() < config.maxPerPrincipal();
    }
    return true;
  }

  /**
   * Registers a stream. With {@code hasLast}, the replay from memory is taken in the same critical
   * section, so nothing is evicted between deciding and reading.
   */
  Opened open(Subscription sub, boolean platform, long lastEventId, boolean hasLast) {
    lock.lock();
    try {
      if (draining) {
        return new Refused(true);
      }
      if (!admitLocked(sub.principal())) {
        return new Refused(false);
      }
      PushStream s = new PushStream(sub, platform);
      for (String t : sub.topics()) {
        byTopic.computeIfAbsent(t, k -> new HashSet<>()).add(s);
      }
      if (!sub.principal().isEmpty()) {
        byPrincipal.computeIfAbsent(sub.principal(), k -> new HashSet<>()).add(s);
      }
      all.add(s);
      metrics.open.set(all.size());
      boolean memory = false;
      List<Frame> replay = List.of();
      if (hasLast && lastEventId >= from && lastEventId <= last) {
        memory = true;
        replay = new ArrayList<>();
        for (Frame f : ring) {
          if (f.id() > lastEventId && s.wants(f.entry())) {
            replay.add(f);
          }
        }
      }
      return new Ok(s, new Opening(last, memory, replay));
    } finally {
      lock.unlock();
    }
  }

  /** Ends a stream for a reason; a stream already ended keeps its first reason. */
  void close(PushStream s, String reason) {
    lock.lock();
    try {
      endLocked(s, reason);
    } finally {
      lock.unlock();
    }
  }

  private void endLocked(PushStream s, String reason) {
    if (s.isEnded()) {
      return;
    }
    for (String t : s.topics) {
      remove(byTopic, t, s);
    }
    if (!s.sub.principal().isEmpty()) {
      remove(byPrincipal, s.sub.principal(), s);
    }
    all.remove(s);
    metrics.open.set(all.size());
    metrics.ended.labels(reason).inc();
    s.end(reason);
  }

  private static void remove(Map<String, Set<PushStream>> index, String key, PushStream s) {
    Set<PushStream> set = index.get(key);
    if (set != null) {
      set.remove(s);
      if (set.isEmpty()) {
        index.remove(key);
      }
    }
  }

  /** Ends the streams of a holder (revocation, section 7). */
  void endHolder(Holder holder) {
    lock.lock();
    try {
      for (PushStream s : new ArrayList<>(all)) {
        Subscription sub = s.sub;
        if ((!holder.principal().isEmpty() && holder.principal().equals(sub.principal()))
            || (!holder.device().isEmpty() && holder.device().equals(sub.device()))
            || (!holder.session().isEmpty() && holder.session().equals(sub.session()))) {
          endLocked(s, END_REVOKED);
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * The server's drain began: ends every open stream at once with the reason drain, in one pass
   * under the lock, and opens no stream after it (push contract section 8). Each stream's thread
   * sees its stream ended and returns, so the server's drain does not wait for a write or a
   * heartbeat.
   */
  void drain() {
    lock.lock();
    try {
      draining = true;
      int n = all.size();
      for (PushStream s : all) {
        s.end(END_DRAIN);
      }
      all.clear();
      byTopic.clear();
      byPrincipal.clear();
      metrics.open.set(0);
      if (n > 0) {
        metrics.ended.labels(END_DRAIN).inc(n);
      }
    } finally {
      lock.unlock();
    }
  }

  boolean isDraining() {
    lock.lock();
    try {
      return draining;
    } finally {
      lock.unlock();
    }
  }

  int openCount() {
    lock.lock();
    try {
      return all.size();
    } finally {
      lock.unlock();
    }
  }

  /** The highest id dispatched. */
  long lastId() {
    lock.lock();
    try {
      return last;
    } finally {
      lock.unlock();
    }
  }

  // ---- dispatching --------------------------------------------------------------------------

  /**
   * Hands a page of the log to the streams of each push's topic, in id order. A stream whose buffer
   * is full is ended (no event: a slow client cannot get one); it resumes from its Last-Event-ID
   * like any other drop.
   */
  void dispatch(List<Frame> page) {
    lock.lock();
    try {
      for (Frame f : page) {
        if (f.id() <= last) {
          continue;
        }
        last = f.id();
        if (f.entry().outboxId() != 0 && !seen.add(f.entry().outboxId())) {
          continue; // logged twice (A2): delivered once
        }
        remember(f);
        Set<PushStream> listeners = byTopic.get(f.entry().topic());
        if (listeners == null) {
          continue;
        }
        List<PushStream> slow = null;
        for (PushStream s : listeners) {
          if (s.accepts(f.entry()) && !s.offer(f, config.buffer())) {
            if (slow == null) {
              slow = new ArrayList<>();
            }
            slow.add(s);
          }
        }
        if (slow != null) {
          for (PushStream s : slow) {
            endLocked(s, END_SLOW);
          }
        }
      }
      metrics.logId.set(last);
    } finally {
      lock.unlock();
    }
  }

  /** Keeps f among the push.recent most recent pushes. */
  private void remember(Frame f) {
    if (config.recent() == 0) {
      from = f.id();
    } else if (ring.size() < config.recent()) {
      ring.addLast(f);
    } else {
      Frame evicted = ring.pollFirst();
      from = evicted == null ? from : evicted.id();
      ring.addLast(f);
    }
  }

  static List<Frame> framesOf(List<Entry> page) {
    List<Frame> out = new ArrayList<>(page.size());
    for (Entry e : page) {
      out.add(Frame.of(e));
    }
    return out;
  }

  /**
   * Reads the log after the last id dispatched until it has everything, until the hub stops, or
   * until the drain begins (no stream is left to hear it, and the database is not given work while
   * the instance closes its connections).
   */
  void catchUp() {
    while (!stopped && !isDraining()) {
      List<Entry> page = log.readAfter(lastId(), PAGE_SIZE);
      dispatch(framesOf(page));
      if (page.size() < PAGE_SIZE) {
        return;
      }
    }
  }

  /** Wakes the hub to read the log. */
  void poke() {
    if (wake.availablePermits() == 0) {
      wake.release();
    }
  }

  /** Starts the hub's two threads: the reader of the log and the driver's follower. */
  void start() {
    reader = Thread.ofVirtual().name("push-hub").start(this::run);
    follower = Thread.ofVirtual().name("push-follow").start(this::follow);
  }

  /** Waits until the driver follows the log, at most the time given; false when it did not. */
  boolean awaitFollowing(long millis) throws InterruptedException {
    return following.await(millis, TimeUnit.MILLISECONDS);
  }

  /**
   * Stops the hub. A log read under way is let finish (it takes milliseconds), not interrupted: an
   * interrupt makes the driver abandon its connection.
   */
  void stop(long waitMillis) {
    stopped = true;
    poke();
    for (Thread t : new Thread[] {reader, follower}) {
      if (t != null) {
        try {
          t.join(waitMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void run() {
    while (!stopped) {
      try {
        catchUp();
      } catch (RuntimeException e) {
        if (!stopped) {
          LOG.warn("push: reading the log failed; will retry", e);
        }
      }
      try {
        wake.tryAcquire(config.pollMillis(), TimeUnit.MILLISECONDS);
        wake.drainPermits();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** Keeps the driver's follower running, with a growing pause after a failure. */
  private void follow() {
    long pause = 1000;
    Follower f =
        new Follower() {
          @Override
          public void grown() {
            following.countDown();
            poke();
          }

          @Override
          public void end(Holder holder) {
            endHolder(holder);
          }
        };
    while (!stopped) {
      long started = System.nanoTime();
      try {
        log.follow(f, () -> stopped);
      } catch (RuntimeException e) {
        if (stopped) {
          return;
        }
        if (System.nanoTime() - started > TimeUnit.MINUTES.toNanos(1)) {
          pause = 1000;
        }
        LOG.warn("push: following the log failed; polling until it is back (retry in {} ms)", pause, e);
      }
      long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(pause);
      while (!stopped && System.nanoTime() < until) {
        try {
          Thread.sleep(50);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      pause = Math.min(30_000, pause * 2);
    }
  }

  // ---- replay from the log ------------------------------------------------------------------

  /** One read of the log for replays, shared by every replay that reads after the same id. */
  private record Page(List<Entry> entries, long trimmed) {}

  /**
   * Reads PAGE_SIZE entries after {@code after}, then the log's {@code trimmed}, sharing the read
   * with any replay already reading after the same id: streams resuming together from the same
   * Last-Event-ID (the followers of a broadcast topic) read each page once.
   */
  private Page readPage(long after) throws InterruptedException {
    CompletableFuture<Page> mine = new CompletableFuture<>();
    CompletableFuture<Page> existing;
    pageLock.lock();
    try {
      existing = pages.putIfAbsent(after, mine);
    } finally {
      pageLock.unlock();
    }
    if (existing != null) {
      try {
        return existing.get();
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        throw cause instanceof RuntimeException re ? re : new LogException("push: read the log", cause == null ? e : cause);
      }
    }
    try {
      List<Entry> entries = log.readAfter(after, PAGE_SIZE);
      Page page = new Page(entries, log.trimmed());
      mine.complete(page);
      return page;
    } catch (RuntimeException e) {
      mine.completeExceptionally(e);
      throw e;
    } finally {
      pageLock.lock();
      try {
        pages.remove(after);
      } finally {
        pageLock.unlock();
      }
    }
  }

  /**
   * Writes the stream's pushes after {@code after}, up to {@code upto}, from the log, one page at a
   * time: it holds one page (at most PAGE_SIZE entries) and a bounded set of recent outbox ids,
   * whatever the length of the replay. Returns true when the log no longer keeps everything after
   * the cursor: the caller sends a reset. The check follows each read, so a row pruned before the
   * read is never silently skipped.
   *
   * @throws StreamEnded when the stream ended meanwhile
   */
  boolean replayLog(PushStream s, long after, long upto, Consumer<Entry> emit) throws InterruptedException {
    long cursor = after;
    // an outbox row logged twice (A2) is logged again by the next relay run, within one relay batch
    // (at most 5000) of the first: remembering the last 10 000 outbox ids is enough
    OutboxSeen local = new OutboxSeen(10_000);
    while (cursor < upto) {
      if (s.isEnded()) {
        throw new StreamEnded();
      }
      Page page = readPage(cursor);
      if (cursor < page.trimmed()) {
        return true;
      }
      for (Entry e : page.entries()) {
        if (e.id() > upto) {
          return false;
        }
        cursor = e.id();
        if (e.outboxId() != 0 && !local.add(e.outboxId())) {
          continue;
        }
        if (s.wants(e)) {
          emit.accept(e);
        }
      }
      if (page.entries().size() < PAGE_SIZE) {
        break;
      }
    }
    return false;
  }

  /** The most recent outbox ids dispatched (bounded), for drivers that can log a row twice. */
  static final class OutboxSeen {
    private final int capacity;
    private final Set<Long> set = new HashSet<>();
    private final long[] order;
    private int count;
    private int next;

    OutboxSeen(int capacity) {
      this.capacity = capacity;
      this.order = new long[capacity];
    }

    /** False when the id was seen before. */
    boolean add(long id) {
      if (set.contains(id)) {
        return false;
      }
      if (count < capacity) {
        order[count++] = id;
      } else {
        set.remove(order[next]);
        order[next] = id;
        next = (next + 1) % capacity;
      }
      set.add(id);
      return true;
    }
  }
}
