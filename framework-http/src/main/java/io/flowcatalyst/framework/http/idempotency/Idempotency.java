package io.flowcatalyst.framework.http.idempotency;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.pipeline.Middleware;
import io.flowcatalyst.framework.pipeline.Next;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inbound {@code Idempotency-Key}: a write sent with the header runs once per client, caller and
 * key, and a repeat gets the first answer again (a device's offline queue replays what it could not
 * confirm; without this a claim could run twice). The same behaviour as the Go framework's {@code
 * idempotency} package (HTTP contract section 13.6).
 *
 * <pre>{@code
 * Middleware<Request, Response> once = Idempotency.middleware(app.get(IdempotencyStore.class), app.get(InstantSource.class));
 * routes.post("/api/picks/{id}/claim", picks::claim, RouteOptions.requiresContext().with(once));
 * }</pre>
 *
 * <p>Route middleware on a route that requires a context (it runs after the context is resolved):
 *
 * <ul>
 *   <li>Without the header the request runs as it is.
 *   <li>A key that is not 1 to {@link #MAX_KEY_LENGTH} printable ASCII characters (spaces around it
 *       are not part of it), or a header sent twice, is {@code 400 IDEMPOTENCY_KEY_INVALID}; a key on
 *       a request whose context has no principal is {@code 400 IDEMPOTENCY_KEY_UNSUPPORTED} (it cannot
 *       be scoped to a caller, so the request is not run unprotected).
 *   <li>The first request with a key takes it and runs. A {@code 2xx} answer with a body held in
 *       memory is kept (status, {@code Content-Type}, body); any other answer, a thrown exception or
 *       a streamed body frees the key, so a retry does the work.
 *   <li>A repeat with the same method, path, query and body gets the kept answer, with {@code
 *       Idempotency-Replayed: true}; other headers of the first answer are not kept. A repeat that
 *       arrives while the first runs waits for it ({@link Options#maxWait()}, 10 s) and then gets its
 *       answer, or {@code 409 IDEMPOTENCY_IN_PROGRESS} when the wait runs out.
 *   <li>Another request under a key already used is {@code 422 IDEMPOTENCY_KEY_REUSED}.
 *   <li>A key older than the retention (24 h) is taken over by the next request with it, and so is an
 *       unfinished one older than the in-flight timeout (5 min: the process that ran it may have
 *       stopped). Only the request that holds the key completes or frees it.
 * </ul>
 *
 * <p>The body is read (within the route's body limit) to hash it, so the handler reads the cached
 * bytes. The database store also forgets keys after {@code idempotency.retention}; give the
 * middleware the same retention ({@code IdempotencyDatabaseModule} provides {@link Options} built
 * from its settings).
 */
public final class Idempotency {

  /** The request header. */
  public static final String HEADER = "Idempotency-Key";

  /** The response header that marks a repeat's answer. */
  public static final String REPLAYED_HEADER = "Idempotency-Replayed";

  /** The longest key accepted, in characters. */
  public static final int MAX_KEY_LENGTH = 200;

  /** How long a key and its answer are kept by default. */
  public static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

  /** How long a repeat waits by default for the first request with its key to finish. */
  public static final Duration DEFAULT_WAIT = Duration.ofSeconds(10);

  /** How long by default an unfinished reservation holds its key before another request takes it over. */
  public static final Duration DEFAULT_IN_FLIGHT_TIMEOUT = Duration.ofMinutes(5);

  private static final Logger LOG = LoggerFactory.getLogger(Idempotency.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final long FIRST_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
  private static final long MAX_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

  /**
   * The middleware's timings.
   *
   * @param retention how long a key and its answer are kept; after it a repeat runs again
   * @param maxWait how long a repeat that arrives while the first request runs waits for its answer
   * @param inFlightTimeout how long an unfinished reservation holds its key
   */
  public record Options(Duration retention, Duration maxWait, Duration inFlightTimeout) {

    /** Checks the timings: retention and in-flight timeout positive, the wait not negative. */
    public Options {
      if (retention.isNegative() || retention.isZero()) {
        throw new IllegalArgumentException("the retention must be positive");
      }
      if (maxWait.isNegative()) {
        throw new IllegalArgumentException("the wait must not be negative");
      }
      if (inFlightTimeout.isNegative() || inFlightTimeout.isZero()) {
        throw new IllegalArgumentException("the in-flight timeout must be positive");
      }
    }

    /**
     * The defaults: {@link #DEFAULT_RETENTION}, {@link #DEFAULT_WAIT}, {@link #DEFAULT_IN_FLIGHT_TIMEOUT}.
     *
     * @return the defaults
     */
    public static Options defaults() {
      return new Options(DEFAULT_RETENTION, DEFAULT_WAIT, DEFAULT_IN_FLIGHT_TIMEOUT);
    }

    /**
     * These options with another retention.
     *
     * @param value the retention
     * @return the options
     */
    public Options withRetention(Duration value) {
      return new Options(value, maxWait, inFlightTimeout);
    }

    /**
     * These options with another wait.
     *
     * @param value the wait
     * @return the options
     */
    public Options withMaxWait(Duration value) {
      return new Options(retention, value, inFlightTimeout);
    }

    /**
     * These options with another in-flight timeout.
     *
     * @param value the in-flight timeout
     * @return the options
     */
    public Options withInFlightTimeout(Duration value) {
      return new Options(retention, maxWait, value);
    }
  }

  private Idempotency() {}

  /**
   * The middleware with the default {@link Options}.
   *
   * @param store where keys are kept
   * @param clock the time keys are taken and completed
   * @return route middleware
   */
  public static Middleware<Request, Response> middleware(IdempotencyStore store, InstantSource clock) {
    return middleware(store, clock, Options.defaults());
  }

  /**
   * The middleware.
   *
   * @param store where keys are kept
   * @param clock the time keys are taken and completed
   * @param options the timings
   * @return route middleware
   */
  public static Middleware<Request, Response> middleware(IdempotencyStore store, InstantSource clock, Options options) {
    return (request, next) -> {
      List<String> values = request.headers(HEADER);
      if (values.isEmpty()) {
        return next.handle(request);
      }
      String key = values.size() == 1 ? trimSpaces(values.getFirst()) : "";
      if (!validKey(key)) {
        throw new HttpException(
            400,
            "IDEMPOTENCY_KEY_INVALID",
            "An Idempotency-Key is 1 to " + MAX_KEY_LENGTH + " printable ASCII characters, sent once.");
      }
      Context context = request.context();
      Optional<PrincipalId> principal = context.findPrincipal();
      if (principal.isEmpty()) {
        throw new HttpException(400, "IDEMPOTENCY_KEY_UNSUPPORTED", "An Idempotency-Key needs an authenticated caller.");
      }
      IdempotencyScope scope = new IdempotencyScope(context.client().value(), principal.get().value(), key);
      String hash = hash(request);
      long deadline = System.nanoTime() + options.maxWait().toNanos();
      long poll = FIRST_POLL_NANOS;
      while (true) {
        String token = newToken();
        Instant now = clock.instant();
        if (store.reserve(scope, hash, token, now, now.minus(options.retention()), now.minus(options.inFlightTimeout()))) {
          return first(store, clock, scope, token, request, next);
        }
        Optional<IdempotencyRecord> found = store.find(scope);
        if (found.isEmpty()) {
          continue; // freed (or expired) since: take it
        }
        IdempotencyRecord record = found.get();
        if (!record.requestHash().equals(hash)) {
          throw new HttpException(422, "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was used for another request.");
        }
        StoredAnswer answer = record.answer();
        if (answer != null) {
          return replay(answer);
        }
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          throw new HttpException(
              409, "IDEMPOTENCY_IN_PROGRESS", "The first request with this Idempotency-Key is still running; retry shortly.");
        }
        TimeUnit.NANOSECONDS.sleep(Math.min(poll, left));
        poll = Math.min(2 * poll, MAX_POLL_NANOS);
      }
    };
  }

  /** Runs the request that holds the key and keeps its answer, or frees the key. */
  private static Response first(
      IdempotencyStore store,
      InstantSource clock,
      IdempotencyScope scope,
      String token,
      Request request,
      Next<Request, Response> next)
      throws Exception {
    Response response;
    try {
      response = next.handle(request);
    } catch (Exception | Error e) {
      release(store, scope, token);
      throw e;
    }
    byte[] body =
        switch (response.body()) {
          case Response.Body.Empty empty -> new byte[0];
          case Response.Body.Bytes bytes -> bytes.content();
          case Response.Body.Stream stream -> null;
        };
    if (body == null || response.status() < 200 || response.status() > 299) {
      release(store, scope, token);
      return response;
    }
    try {
      store.complete(scope, token, new StoredAnswer(response.status(), response.contentType(), body), clock.instant());
    } catch (RuntimeException e) {
      // the work is done and its answer goes to the caller; a repeat runs it again
      LOG.warn("an Idempotency-Key's answer could not be kept; the key is freed", e);
      release(store, scope, token);
    }
    return response;
  }

  private static void release(IdempotencyStore store, IdempotencyScope scope, String token) {
    try {
      store.release(scope, token);
    } catch (RuntimeException e) {
      LOG.warn("an Idempotency-Key could not be freed; it is freed when its reservation times out", e);
    }
  }

  private static Response replay(StoredAnswer answer) {
    String contentType = answer.contentType();
    Response replay =
        contentType == null || contentType.isEmpty()
            ? Response.empty(answer.status())
            : Response.bytes(answer.status(), contentType, answer.body());
    return replay.header(REPLAYED_HEADER, "true");
  }

  /** The value without the spaces and tabs around it (HTTP's optional white space). */
  private static String trimSpaces(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) {
      start++;
    }
    while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) {
      end--;
    }
    return value.substring(start, end);
  }

  /**
   * Whether a key is 1 to {@link #MAX_KEY_LENGTH} printable ASCII characters ({@code !} to {@code ~}).
   *
   * @param key the key
   * @return whether it is allowed
   */
  static boolean validKey(@Nullable String key) {
    if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
      return false;
    }
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (c < 0x21 || c > 0x7e) {
        return false;
      }
    }
    return true;
  }

  private static String newToken() {
    byte[] raw = new byte[16];
    RANDOM.nextBytes(raw);
    return HexFormat.of().formatHex(raw);
  }

  /** SHA-256 of {@code METHOD SP path "?" query LF body}, in hex (the path and query as sent). */
  static String hash(Request request) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      String query = request.rawQuery();
      sha.update(
          (request.method().name() + " " + request.path() + "?" + (query == null ? "" : query) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      sha.update(request.bodyBytes());
      return HexFormat.of().formatHex(sha.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
