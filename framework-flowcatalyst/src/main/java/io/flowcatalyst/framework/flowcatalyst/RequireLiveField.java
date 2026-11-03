package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.auth.Auth;
import io.flowcatalyst.framework.auth.Authenticated;
import io.flowcatalyst.framework.auth.FieldPrincipal;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.http.HttpErrors;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.pipeline.Middleware;
import io.flowcatalyst.framework.pipeline.Next;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Route middleware for an action that cannot wait for a field token to expire (opening a gate,
 * releasing a load; sessions-and-login contract section 15.6). Access tokens live ten minutes, so a
 * revoked device or a suspended worker keeps a valid token until then. This asks the platform
 * ({@code POST /api/field-tokens/introspect}, as the application's service account, which needs the
 * permission {@code platform:iam:field-token:introspect}) whether the token is still active.
 *
 * <pre>{@code
 * routes.post("/api/v1/gates/open", gates::open,
 *     RouteOptions.requiresContext()
 *         .with(Auth.requireSource(Authenticated.Source.FIELD))
 *         .with(RequireLiveField.of(app, Duration.ofSeconds(30))));
 * }</pre>
 *
 * <ul>
 *   <li>inactive: {@code 401 UNAUTHENTICATED} with {@code WWW-Authenticate: DPoP error="invalid_token"};
 *   <li>the platform cannot be asked (unreachable, refuses the service account, or answers
 *       something unusable): {@code 503 UNAVAILABLE}. It fails closed: a high-value action is never
 *       let through unchecked;
 *   <li>a request that is not a field worker's is answered {@code 403 FORBIDDEN}: there is no field
 *       token to check, and it is never let through unchecked.
 * </ul>
 *
 * <p>An active answer is remembered per login family ({@code sid}) for {@code maxAge}, never past the
 * token's own expiry; an inactive answer is never remembered, so a token that was merely expired does
 * not block the family's newer one. With {@code maxAge} zero every request asks the platform.
 */
public final class RequireLiveField {

  private static final Logger LOG = LoggerFactory.getLogger(RequireLiveField.class);
  private static final int SWEEP_AT = 4096;

  private RequireLiveField() {}

  /**
   * The middleware for the application's platform client and clock.
   *
   * @param app the started application, which provides the {@link PlatformClient} and the clock
   * @param maxAge how long an active answer is trusted; zero for no cache
   * @return the middleware
   */
  public static Middleware<Request, Response> of(App app, Duration maxAge) {
    return create(app.get(PlatformClient.class), maxAge, app.get(InstantSource.class));
  }

  /**
   * The middleware for a given client.
   *
   * @param client the platform client, whose service account may introspect field tokens
   * @param maxAge how long an active answer is trusted; zero for no cache
   * @param clock the clock for the cache
   * @return the middleware
   */
  public static Middleware<Request, Response> create(PlatformClient client, Duration maxAge, InstantSource clock) {
    if (maxAge.isNegative()) {
      throw new IllegalArgumentException("maxAge must not be negative");
    }
    return new Live(client, maxAge, clock);
  }

  private static final class Live implements Middleware<Request, Response> {

    private final PlatformClient client;
    private final Duration maxAge;
    private final InstantSource clock;
    /** sid to the instant until which an active answer holds. */
    private final Map<String, Instant> active = new ConcurrentHashMap<>();

    Live(PlatformClient client, Duration maxAge, InstantSource clock) {
      this.client = client;
      this.maxAge = maxAge;
      this.clock = clock;
    }

    @Override
    public Response handle(Request request, Next<Request, Response> next) throws Exception {
      Optional<Authenticated> who = Auth.current(request);
      if (who.isEmpty() || who.get().field().isEmpty()) {
        // never let a request that is not a field worker's through unchecked
        return HttpErrors.forbidden("FORBIDDEN", "this route is for field workers");
      }
      FieldPrincipal worker = who.get().field().get();
      Instant now = clock.instant();
      Instant until = active.get(worker.sessionId());
      if (until != null && now.isBefore(until)) {
        return next.handle(request);
      }
      String token = token(request);
      if (token == null) {
        return unavailable("the field token is not on the request");
      }
      FieldIntrospection answer;
      try {
        answer = client.introspectFieldToken(token);
      } catch (PlatformException e) {
        if (e.kind() == PlatformException.Kind.FORBIDDEN) {
          LOG.warn("the platform refused the application's service account the introspection of field tokens: it needs the permission platform:iam:field-token:introspect");
        } else {
          LOG.warn("a field token could not be checked with the platform ({}): {}", e.kind(), e.getMessage());
        }
        return unavailable("the platform cannot be asked right now; try again");
      } catch (RuntimeException e) {
        LOG.warn("a field token could not be checked with the platform: {}", e.getClass().getSimpleName());
        return unavailable("the platform cannot be asked right now; try again");
      }
      if (!answer.active() || !matches(worker, answer)) {
        active.remove(worker.sessionId());
        return HttpErrors.unauthenticated("authentication is required").header("WWW-Authenticate", "DPoP error=\"invalid_token\"");
      }
      if (!maxAge.isZero()) {
        remember(worker, now);
      }
      return next.handle(request);
    }

    /** An answer about another worker than the one verified is not an answer for this request. */
    private static boolean matches(FieldPrincipal worker, FieldIntrospection answer) {
      return answer.sub() == null || answer.sub().equals(worker.workerId());
    }

    private void remember(FieldPrincipal worker, Instant now) {
      if (active.size() >= SWEEP_AT) {
        active.values().removeIf(t -> !t.isAfter(now));
      }
      Instant until = now.plus(maxAge);
      Instant tokenEnds = worker.expiresAt();
      active.put(worker.sessionId(), until.isBefore(tokenEnds) ? until : tokenEnds);
    }

    private static @Nullable String token(Request request) {
      String authorization = request.header("Authorization");
      if (authorization == null || !authorization.regionMatches(true, 0, "DPoP ", 0, 5)) {
        return null;
      }
      String token = authorization.substring(5).trim();
      return token.isEmpty() ? null : token;
    }

    private static Response unavailable(String message) {
      return HttpErrors.error(503, "UNAVAILABLE", message);
    }
  }
}
