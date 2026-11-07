package io.flowcatalyst.framework.http.idempotency;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.pipeline.Middleware;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.InstantSource;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Inbound {@code Idempotency-Key}: a write sent with the header runs once per client, caller and
 * key, and a repeat gets the first answer again (a device's offline queue replays what it could not
 * confirm; without this a claim could run twice).
 *
 * <pre>{@code
 * Middleware<Request, Response> once = Idempotency.middleware(app.get(IdempotencyStore.class), app.get(InstantSource.class));
 * routes.post("/api/picks/{id}/claim", picks::claim, RouteOptions.requiresContext().with(once));
 * }</pre>
 *
 * <p>Route middleware on a route that requires a context (it runs after the context is resolved):
 *
 * <ul>
 *   <li>Without the header the request runs as it is; so does one whose context has no principal.
 *   <li>A key over {@link #MAX_KEY_LENGTH} characters is {@code 400 IDEMPOTENCY_KEY_INVALID}.
 *   <li>The first request with a key takes it and runs. A {@code 2xx} answer with a body held in
 *       memory is kept (status, {@code Content-Type}, body); any other answer, a thrown exception or
 *       a streamed body frees the key, so a retry does the work.
 *   <li>A repeat with the same method, path and body gets the kept answer, with {@code
 *       Idempotency-Replayed: true}; other headers of the first answer are not kept.
 *   <li>A repeat while the first still runs is {@code 409 IDEMPOTENCY_IN_PROGRESS} (retry shortly);
 *       another request under the same key is {@code 422 IDEMPOTENCY_KEY_REUSED}.
 * </ul>
 *
 * <p>The body is read (within the route's body limit) to hash it, so the handler reads the cached
 * bytes. Keys are kept by the store; the database store forgets them after {@code
 * idempotency.retention}. A request that took a key and whose process died leaves the key in
 * progress until it is forgotten.
 */
public final class Idempotency {

  /** The request header. */
  public static final String HEADER = "Idempotency-Key";

  /** The response header that marks a repeat's answer. */
  public static final String REPLAYED_HEADER = "Idempotency-Replayed";

  /** The longest key accepted. */
  public static final int MAX_KEY_LENGTH = 200;

  private Idempotency() {}

  /**
   * The middleware.
   *
   * @param store where keys are kept
   * @param clock the time keys are taken and completed
   * @return route middleware
   */
  public static Middleware<Request, Response> middleware(IdempotencyStore store, InstantSource clock) {
    return (request, next) -> {
      String raw = request.header(HEADER);
      String key = raw == null ? "" : raw.strip();
      if (key.isEmpty()) {
        return next.handle(request);
      }
      if (key.length() > MAX_KEY_LENGTH) {
        throw new HttpException(
            400, "IDEMPOTENCY_KEY_INVALID", "An Idempotency-Key is at most " + MAX_KEY_LENGTH + " characters.");
      }
      Context context = request.context();
      Optional<PrincipalId> principal = context.findPrincipal();
      if (principal.isEmpty()) {
        return next.handle(request);
      }
      IdempotencyScope scope =
          new IdempotencyScope(context.client().value(), principal.get().value(), key);
      String hash = hash(request);
      if (!store.reserve(scope, hash, clock.instant())) {
        return repeat(store, scope, hash);
      }
      Response response;
      try {
        response = next.handle(request);
      } catch (Exception | Error e) {
        store.release(scope);
        throw e;
      }
      byte[] body =
          switch (response.body()) {
            case Response.Body.Empty empty -> new byte[0];
            case Response.Body.Bytes bytes -> bytes.content();
            case Response.Body.Stream stream -> null;
          };
      if (body == null || response.status() < 200 || response.status() > 299) {
        store.release(scope);
        return response;
      }
      store.complete(scope, new StoredAnswer(response.status(), response.contentType(), body), clock.instant());
      return response;
    };
  }

  private static Response repeat(IdempotencyStore store, IdempotencyScope scope, String hash) {
    Optional<IdempotencyRecord> found = store.find(scope);
    if (found.isEmpty()) {
      // the first failed and freed the key between our reservation and this read
      throw inProgress();
    }
    IdempotencyRecord record = found.get();
    if (!record.requestHash().equals(hash)) {
      throw new HttpException(422, "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was used for another request.");
    }
    StoredAnswer answer = record.answer();
    if (answer == null) {
      throw inProgress();
    }
    String contentType = answer.contentType();
    Response replay =
        contentType == null
            ? Response.empty(answer.status())
            : Response.bytes(answer.status(), contentType, answer.body());
    return replay.header(REPLAYED_HEADER, "true");
  }

  private static HttpException inProgress() {
    return new HttpException(
        409, "IDEMPOTENCY_IN_PROGRESS", "The first request with this key is still running; retry shortly.");
  }

  /** SHA-256 of the method, the path and the body, in hex. */
  static String hash(Request request) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      sha.update((request.method().name() + " " + request.path() + "\n").getBytes(StandardCharsets.UTF_8));
      sha.update(request.bodyBytes());
      return HexFormat.of().formatHex(sha.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
