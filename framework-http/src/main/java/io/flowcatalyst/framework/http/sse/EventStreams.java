package io.flowcatalyst.framework.http.sse;

import io.flowcatalyst.framework.http.ClientDisconnectedException;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-sent event streams: one instance per application (the HTTP module provides it), which the
 * application {@link #publish publishes} its events to and whose {@link #serve} answers a stream
 * route. A stream:
 *
 * <ul>
 *   <li>first sends {@code retry: 3000}, then, when the client sends {@code Last-Event-ID} (or the
 *       {@code lastEventId} query parameter, for clients that cannot set the header), the events
 *       after it from the application's {@link Replay} (at most {@link #REPLAY_LIMIT}), then the
 *       live events its filter admits, each once and in id order;
 *   <li>sends a {@code : ping} comment whenever it has been idle for the heartbeat, so proxies keep
 *       it open and a client that went away is noticed;
 *   <li>ends when the client goes, when it falls more than {@link #BUFFER} events behind (the
 *       client reconnects with its last id), when the replay fails, and on {@link #closeAll}, which
 *       the HTTP module runs when shutdown begins, before the drain (so open streams do not hold
 *       the drain for its grace).
 * </ul>
 *
 * <pre>{@code
 * EventStreams streams = app.get(EventStreams.class);
 * routes.get("/api/stores/{store}/events", r -> {
 *   String channel = "store:" + r.context().client() + ":" + r.pathParam("store");
 *   return streams.serve(r, e -> e.type().startsWith("pick-"), (after, limit) -> log.after(channel, after, limit));
 * }, RouteOptions.requiresContext());
 * // where the application learns of a committed change (its log tail):
 * streams.publish(new SseEvent(row.id(), row.type(), row.payloadJson()));
 * }</pre>
 *
 * <p>Publishing is in-process: each instance publishes what its own tail of the shared log reads,
 * so any instance can serve any stream and a client resumes from its last id on any of them.
 * Safe for concurrent use.
 */
public final class EventStreams {

  private static final Logger LOG = LoggerFactory.getLogger(EventStreams.class);

  /** The heartbeat the HTTP module uses unless {@code http.sse.heartbeat} says otherwise. */
  public static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(25);

  /** The most events a reconnecting stream asks its {@link Replay} for. */
  public static final int REPLAY_LIMIT = 1000;

  /** The most published events a stream holds unsent; one more ends it (the client resumes). */
  public static final int BUFFER = 256;

  /** The reconnection delay sent to the client first, in milliseconds. */
  public static final long RETRY_MILLIS = 3000;

  private static final byte[] RETRY = ("retry: " + RETRY_MILLIS + "\n\n").getBytes(StandardCharsets.UTF_8);
  private static final byte[] PING = ": ping\n\n".getBytes(StandardCharsets.UTF_8);

  /** The events a stream missed while it was away. */
  @FunctionalInterface
  public interface Replay {
    /**
     * The events after an id, in id order.
     *
     * @param lastEventId the last id the client saw
     * @param limit the most events to return
     * @return the events with an id above {@code lastEventId}, ascending
     * @throws Exception when they cannot be read; the stream ends and the client reconnects
     */
    List<SseEvent> after(long lastEventId, int limit) throws Exception;
  }

  private final long heartbeatNanos;
  private final Set<Subscription> open = ConcurrentHashMap.newKeySet();
  private volatile boolean closed;

  /**
   * Creates the streams.
   *
   * @param heartbeat how long a stream may be idle before it sends a comment; positive
   */
  public EventStreams(Duration heartbeat) {
    if (heartbeat.isNegative() || heartbeat.isZero()) {
      throw new IllegalArgumentException("the heartbeat must be positive");
    }
    this.heartbeatNanos = heartbeat.toNanos();
  }

  /**
   * Hands an event to every open stream whose filter admits it. Never blocks: a stream that is
   * {@link #BUFFER} events behind is ended instead.
   *
   * @param event the event
   */
  public void publish(SseEvent event) {
    for (Subscription s : open) {
      if (s.filter.test(event) && !s.offer(event)) {
        open.remove(s);
      }
    }
  }

  /**
   * Answers a stream request: the events after the client's {@code Last-Event-ID} from {@code
   * replay}, then the live ones {@code filter} admits.
   *
   * @param request the request
   * @param filter which published events this stream sends
   * @param replay reads the events a reconnecting client missed
   * @return the streamed response ({@code text/event-stream})
   */
  public Response serve(Request request, Predicate<SseEvent> filter, Replay replay) {
    return respond(lastEventId(request), filter, replay);
  }

  /**
   * Answers a stream request with live events only; a {@code Last-Event-ID} is ignored.
   *
   * @param request the request
   * @param filter which published events this stream sends
   * @return the streamed response ({@code text/event-stream})
   */
  public Response serve(Request request, Predicate<SseEvent> filter) {
    return respond(null, filter, null);
  }

  /**
   * Ends every open stream (each sends what it holds first) and every stream opened later. The
   * HTTP module runs it when shutdown begins, before the drain.
   */
  public void closeAll() {
    closed = true;
    for (Subscription s : open) {
      s.end();
      open.remove(s);
    }
  }

  /**
   * How many streams are open.
   *
   * @return the number of subscribed streams
   */
  public int open() {
    return open.size();
  }

  private Response respond(@Nullable Long lastEventId, Predicate<SseEvent> filter, @Nullable Replay replay) {
    return Response.stream(200, "text/event-stream", out -> run(out, lastEventId, filter, replay))
        .header("Cache-Control", "no-cache")
        .header("X-Accel-Buffering", "no");
  }

  private void run(OutputStream out, @Nullable Long lastEventId, Predicate<SseEvent> filter, @Nullable Replay replay) {
    // subscribe first, then replay: what is published during the replay waits in the subscription
    // and is skipped when the replay already sent it
    Subscription sub = new Subscription(filter);
    open.add(sub);
    if (closed) {
      open.remove(sub); // closeAll may have run before the add; it must not miss this stream
      send(out, RETRY);
      return;
    }
    try {
      send(out, RETRY);
      long sent = 0;
      if (lastEventId != null && replay != null) {
        sent = lastEventId;
        List<SseEvent> missed;
        try {
          missed = replay.after(lastEventId, REPLAY_LIMIT);
        } catch (Exception e) {
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
          LOG.warn("an event stream's replay after id {} failed; ending the stream", lastEventId, e);
          return;
        }
        for (SseEvent event : missed) {
          if (event.id() > sent) {
            send(out, encode(event));
            sent = event.id();
          }
        }
      }
      while (true) {
        SseEvent next;
        try {
          next = sub.poll(heartbeatNanos);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt(); // the drain's grace ran out
          return;
        }
        if (next != null) {
          if (next.id() > sent) {
            send(out, encode(next));
            sent = next.id();
          }
        } else if (sub.over()) {
          return;
        } else {
          send(out, PING);
        }
      }
    } finally {
      open.remove(sub);
    }
  }

  private static void send(OutputStream out, byte[] bytes) {
    try {
      out.write(bytes);
      out.flush();
    } catch (IOException e) {
      throw new ClientDisconnectedException(e);
    }
  }

  static byte[] encode(SseEvent event) {
    StringBuilder sb = new StringBuilder(event.data().length() + 48);
    sb.append("id: ").append(event.id()).append('\n');
    sb.append("event: ").append(event.type()).append('\n');
    for (String line : event.data().split("\r\n|\r|\n", -1)) {
      sb.append("data: ").append(line).append('\n');
    }
    sb.append('\n');
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static @Nullable Long lastEventId(Request request) {
    String raw = request.header("Last-Event-ID");
    if (raw == null || raw.isBlank()) {
      raw = request.query("lastEventId");
    }
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      long id = Long.parseLong(raw.trim());
      return id < 0 ? null : id;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** One stream's queue of published events. */
  private static final class Subscription {
    final Predicate<SseEvent> filter;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final ArrayDeque<SseEvent> pending = new ArrayDeque<>();
    private boolean ended;

    Subscription(Predicate<SseEvent> filter) {
      this.filter = filter;
    }

    /** Queues an event; false (and the stream ends) when it is a buffer behind or already ended. */
    boolean offer(SseEvent event) {
      lock.lock();
      try {
        if (ended) {
          return false;
        }
        if (pending.size() >= BUFFER) {
          ended = true;
          changed.signalAll();
          return false;
        }
        pending.add(event);
        changed.signalAll();
        return true;
      } finally {
        lock.unlock();
      }
    }

    void end() {
      lock.lock();
      try {
        ended = true;
        changed.signalAll();
      } finally {
        lock.unlock();
      }
    }

    /** The next event, or null when the wait ran out or the stream ended with nothing held. */
    @Nullable SseEvent poll(long nanos) throws InterruptedException {
      lock.lock();
      try {
        long left = nanos;
        while (pending.isEmpty() && !ended && left > 0) {
          left = changed.awaitNanos(left);
        }
        return pending.poll();
      } finally {
        lock.unlock();
      }
    }

    /** Whether the stream ended and everything it held was taken. */
    boolean over() {
      lock.lock();
      try {
        return ended && pending.isEmpty();
      } finally {
        lock.unlock();
      }
    }
  }
}
