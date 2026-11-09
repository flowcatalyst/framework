package io.flowcatalyst.framework.push;

import io.flowcatalyst.framework.http.ClientDisconnectedException;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.Validator;
import io.flowcatalyst.framework.usecase.Push;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The three routes of push contract section 6: {@code POST {base}/push/subscriptions},
 * {@code GET {base}/push/stream} and {@code GET {base}/push/keys}. The stream token travels in the
 * query ({@code st}) so that a browser {@code EventSource} can use it; it is never logged here.
 */
final class PushHttp {

  /** The stream's heartbeat (http contract 13.5), unless {@code http.sse.heartbeat} says otherwise. */
  static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(25);

  private static final Logger LOG = LoggerFactory.getLogger(PushHttp.class);

  /** The body of a subscribe. */
  record SubscribeBody(@Nullable List<String> topics, @Nullable List<String> tickets) {}

  /** The answer to a subscribe. */
  record SubscribeAnswer(String streamToken, String expiresAt, List<String> granted, List<Denial> denied) {}

  private static final Validator<SubscribeBody> BODY = Validator.of(SubscribeBody.class);

  private final Pushes pushes;

  /** How long an idle stream waits before it writes a heartbeat: {@code http.sse.heartbeat}. */
  volatile Duration heartbeat = DEFAULT_HEARTBEAT;

  PushHttp(Pushes pushes) {
    this.pushes = pushes;
  }

  private static HttpException unavailable() {
    return new HttpException(503, "UNAVAILABLE", "the server is shutting down; reconnect to another instance");
  }

  private static HttpException pushLimit() {
    return new HttpException(429, "PUSH_LIMIT", "too many push streams are open; try again later");
  }

  /** POST {base}/push/subscriptions (push contract 6.1). */
  Response subscribe(Request request) throws Exception {
    if (pushes.hub().isDraining()) {
      throw unavailable();
    }
    SubscribeBody body = request.json(BODY);
    List<String> topics = checkTopics(body.topics() == null ? List.of() : body.topics());
    Optional<Credentials> credentials = pushes.authenticator() == null ? Optional.empty() : pushes.authenticator().authenticate(request);
    Instant now = pushes.clock().instant();
    Granter.Decision d = pushes.granter().decide(topics, body.tickets() == null ? List.of() : body.tickets(), credentials, now);
    if (!pushes.hub().admit(d.subscription().principal())) {
      throw pushLimit();
    }
    String token = pushes.tokens().sign(d.subscription(), d.expires().getEpochSecond());
    return Response.json(200, new SubscribeAnswer(token, d.expires().toString(), d.granted(), d.denied()));
  }

  /** Checks the requested topics: 1 to push.max-topics, each valid; duplicates dropped. */
  private List<String> checkTopics(List<String> topics) {
    if (topics.isEmpty()) {
      throw new HttpException(400, "VALIDATION", "topics must name at least one topic");
    }
    if (topics.size() > pushes.maxTopics()) {
      throw new HttpException(400, "VALIDATION", "at most " + pushes.maxTopics() + " topics per subscribe (push.max-topics)");
    }
    List<String> out = new ArrayList<>(topics.size());
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < topics.size(); i++) {
      String t = topics.get(i);
      try {
        Push.checkTopic(t);
      } catch (IllegalArgumentException | NullPointerException e) {
        throw new HttpException(400, "VALIDATION", "topics[" + i + "] is not a topic: {application}:{segment}(:{segment})*");
      }
      if (seen.add(t)) {
        out.add(t);
      }
    }
    return out;
  }

  /** GET {base}/push/keys: the ticket keys' JWKS. */
  Response keys(Request request) {
    return Response.bytes(200, "application/json", pushes.jwks()).header("Cache-Control", "public, max-age=300");
  }

  /** GET {base}/push/stream?st=... (push contract 6.2 and section 7). */
  Response listen(Request request) {
    Hub hub = pushes.hub();
    if (hub.isDraining()) {
      throw unavailable();
    }
    String st = request.query("st"); // absent is empty: a token that is not valid
    StreamTokens.Verified verified = pushes.tokens().verify(st == null ? "" : st, pushes.clock().instant());
    if (verified instanceof StreamTokens.Refused refused) {
      String message =
          refused.code().equals(StreamTokens.CODE_EXPIRED)
              ? "the stream token has expired; subscribe again"
              : "the stream token is not valid; subscribe again";
      throw new HttpException(401, refused.code(), message);
    }
    StreamTokens.Claims claims = ((StreamTokens.Valid) verified).claims();
    Cursor cursor = Cursor.read(request);
    if (!hub.admit(claims.subscription().principal())) {
      throw pushLimit();
    }
    return Response.stream(200, "text/event-stream", out -> serve(out, claims, cursor))
        .header("Cache-Control", "no-cache")
        .header("X-Accel-Buffering", "no");
  }

  /** What the client sent as Last-Event-ID. */
  private record Cursor(boolean present, long id, boolean bad) {

    static final Cursor NONE = new Cursor(false, 0, false);

    /**
     * Reads the client's cursor: the Last-Event-ID header, else the lastEventId query parameter (an
     * EventSource cannot set headers). Empty or blank is no cursor. A value that is not a
     * non-negative integer is a cursor that names no push (bad): the stream is answered with a
     * reset, never silently started live.
     */
    static Cursor read(Request request) {
      String raw = request.header("Last-Event-ID");
      raw = raw == null ? "" : raw.strip();
      if (raw.isEmpty()) {
        String q = request.query("lastEventId");
        raw = q == null ? "" : q.strip();
      }
      if (raw.isEmpty()) {
        return NONE;
      }
      try {
        long n = Long.parseLong(raw);
        return n < 0 ? new Cursor(false, 0, true) : new Cursor(true, n, false);
      } catch (NumberFormatException e) {
        return new Cursor(false, 0, true);
      }
    }
  }

  /** Writes and flushes; a write that fails means the client has gone. */
  private static final class Out {
    private final OutputStream out;

    Out(OutputStream out) {
      this.out = out;
    }

    void send(String text) {
      write(text.getBytes(StandardCharsets.UTF_8));
      flush();
    }

    void write(byte[] bytes) {
      try {
        out.write(bytes);
      } catch (IOException e) {
        throw new ClientDisconnectedException(e);
      }
    }

    void flush() {
      try {
        out.flush();
      } catch (IOException e) {
        throw new ClientDisconnectedException(e);
      }
    }
  }

  /** The body of an open stream, run on the request's virtual thread. */
  private void serve(OutputStream raw, StreamTokens.Claims claims, Cursor cursor) throws IOException {
    Hub hub = pushes.hub();
    PushMetrics metrics = pushes.metrics();
    Subscription sub = claims.subscription();
    boolean platform = sub.client().equals(io.flowcatalyst.framework.context.ClientId.PLATFORM.value());
    Hub.Opened opened = hub.open(sub, platform, cursor.id(), cursor.present());
    if (!(opened instanceof Hub.Ok ok)) {
      return; // the hub began to drain or filled up since the request was admitted; the client reconnects
    }
    PushStream stream = ok.stream();
    Hub.Opening op = ok.opening();
    String reason = Hub.END_CLIENT;
    Out out = new Out(raw);
    try {
      out.send(Wire.retryField());
      out.send(Wire.subscribedEvent(sub.topics(), op.latest()));
      long sent = op.latest(); // live pushes at or below it are already accounted for
      if (cursor.bad()) { // a cursor that names no push cannot be resumed from: the client reloads its state
        metrics.resets.labels(Wire.RESET_UNKNOWN_ID).inc();
        out.send(Wire.resetEvent(Wire.RESET_UNKNOWN_ID, op.latest()));
      }
      if (cursor.present()) {
        long last = cursor.id();
        if (last > op.latest()) {
          long top;
          try {
            top = pushes.log().latest();
          } catch (LogException e) {
            LOG.warn("push: reading the log's last id failed; ending the stream", e);
            return;
          }
          if (last > top) {
            metrics.resets.labels(Wire.RESET_UNKNOWN_ID).inc();
            out.send(Wire.resetEvent(Wire.RESET_UNKNOWN_ID, op.latest()));
          } else {
            sent = last; // this instance is behind; skip what the client has
          }
        } else if (last == op.latest()) {
          // nothing to replay
        } else if (op.memory()) {
          metrics.replaySource.labels("memory").inc();
          for (Frame f : op.replay()) {
            out.write(f.wire());
          }
          out.flush();
          metrics.delivered.inc(op.replay().size());
        } else {
          metrics.replaySource.labels("log").inc();
          boolean reset;
          try {
            reset =
                hub.replayLog(
                    stream,
                    last,
                    op.latest(),
                    e -> {
                      out.write(Wire.pushEvent(e));
                      out.flush();
                      metrics.delivered.inc();
                    });
          } catch (Hub.StreamEnded e) {
            reset = false; // the loop below sees why (auth-expired for a revocation)
          } catch (LogException e) {
            LOG.warn("push: the replay from the log failed; ending the stream", e);
            return;
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
          if (reset) {
            metrics.resets.labels(Wire.RESET_RETENTION).inc();
            out.send(Wire.resetEvent(Wire.RESET_RETENTION, op.latest()));
          }
        }
      }
      reason = live(stream, claims, out, sent, metrics);
    } catch (ClientDisconnectedException e) {
      reason = Hub.END_CLIENT;
      throw e;
    } finally {
      hub.close(stream, reason);
    }
  }

  /** The live part: queued pushes, the heartbeat, the expiry and the ends the hub asks for. */
  private String live(PushStream stream, StreamTokens.Claims claims, Out out, long alreadySent, PushMetrics metrics) {
    long sent = alreadySent;
    Instant now = pushes.clock().instant();
    long expiryNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, Duration.between(now, Instant.ofEpochSecond(claims.exp())).toMillis()));
    long heartbeatAt = System.nanoTime() + heartbeat.toNanos();
    while (true) {
      if (stream.isEnded()) {
        String why = stream.reason();
        if (Hub.END_REVOKED.equals(why)) {
          out.send(Wire.authExpiredEvent());
        }
        return why == null ? Hub.END_CLIENT : why;
      }
      long t = System.nanoTime();
      if (t - expiryNanos >= 0) {
        out.send(Wire.authExpiredEvent());
        return Hub.END_EXPIRED;
      }
      if (stream.hasPushes()) {
        int n = 0;
        for (Frame f = stream.next(); f != null; f = stream.next()) {
          if (f.id() <= sent) {
            continue;
          }
          out.write(f.wire());
          sent = f.id();
          n++;
        }
        if (n > 0) {
          out.flush();
          metrics.delivered.inc(n);
          heartbeatAt = System.nanoTime() + heartbeat.toNanos();
        }
        continue;
      }
      if (t - heartbeatAt >= 0) {
        out.send(Wire.HEARTBEAT);
        heartbeatAt = System.nanoTime() + heartbeat.toNanos();
        continue;
      }
      try {
        stream.await(Math.min(expiryNanos - t, heartbeatAt - t));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt(); // the server's drain grace ran out
        return Hub.END_DRAIN;
      }
    }
  }
}
