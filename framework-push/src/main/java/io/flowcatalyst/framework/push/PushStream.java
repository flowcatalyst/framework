package io.flowcatalyst.framework.push;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * One open stream on this instance. The hub appends live pushes with {@link #offer} and never
 * blocks; the stream's own (virtual) thread takes them with {@link #next} and waits with
 * {@link #await}. The queue holds at most {@code push.stream.buffer} pushes and exists only while
 * the stream is behind (an idle stream holds none).
 */
final class PushStream {

  private static final int KEEP = 16;

  final Subscription sub;
  final Set<String> topics;
  final boolean platform;

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition changed = lock.newCondition();
  private @Nullable ArrayDeque<Frame> queue;
  private boolean grew;
  private boolean ended;
  private @Nullable String reason;

  PushStream(Subscription sub, boolean platform) {
    this.sub = sub;
    this.platform = platform;
    this.topics = new HashSet<>(sub.topics());
  }

  /** The client rule of push contract section 5, made for every push on every stream. */
  boolean accepts(Entry e) {
    return e.client().isEmpty() || platform || e.client().equals(sub.client());
  }

  /** Whether the stream receives e: a granted topic and the client rule. */
  boolean wants(Entry e) {
    return topics.contains(e.topic()) && accepts(e);
  }

  /** Queues f unless limit pushes are already waiting; false means the stream is too slow. */
  boolean offer(Frame f, int limit) {
    lock.lock();
    try {
      ArrayDeque<Frame> q = queue;
      if (q == null) {
        q = new ArrayDeque<>(4);
        queue = q;
      }
      if (q.size() >= limit) {
        return false;
      }
      q.addLast(f);
      grew |= q.size() > KEEP;
      changed.signalAll();
      return true;
    } finally {
      lock.unlock();
    }
  }

  /** The oldest waiting push, null when none waits. */
  @Nullable Frame next() {
    lock.lock();
    try {
      ArrayDeque<Frame> q = queue;
      if (q == null) {
        return null;
      }
      Frame f = q.pollFirst();
      if (q.isEmpty() && grew) {
        queue = null; // a stream that caught up with a backlog keeps no memory of it
        grew = false;
      }
      return f;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Waits until a push is queued, the stream is ended, or the time is up.
   *
   * @return the time left (zero or less when it ran out)
   */
  long await(long nanos) throws InterruptedException {
    lock.lock();
    try {
      long left = nanos;
      while (!ended && (queue == null || queue.isEmpty()) && left > 0) {
        left = changed.awaitNanos(left);
      }
      return left;
    } finally {
      lock.unlock();
    }
  }

  boolean hasPushes() {
    lock.lock();
    try {
      return queue != null && !queue.isEmpty();
    } finally {
      lock.unlock();
    }
  }

  /** Ends the stream; the first reason is kept. */
  void end(String why) {
    lock.lock();
    try {
      if (!ended) {
        ended = true;
        reason = why;
      }
      changed.signalAll();
    } finally {
      lock.unlock();
    }
  }

  boolean isEnded() {
    lock.lock();
    try {
      return ended;
    } finally {
      lock.unlock();
    }
  }

  /** Why the stream ended, null while it has not. */
  @Nullable String reason() {
    lock.lock();
    try {
      return reason;
    } finally {
      lock.unlock();
    }
  }
}
