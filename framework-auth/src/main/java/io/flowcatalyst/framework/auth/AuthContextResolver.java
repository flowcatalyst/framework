package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Request;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real {@link ContextResolver} (contract section 7.1): a platform bearer token wins and never
 * falls back to the cookie; with field workers enabled, so does the {@code DPoP} scheme (section
 * 15); else the session cookie; else {@code 401 UNAUTHENTICATED}, which
 * {@link AuthModule}'s middleware turns into a {@code 302} for an HTML navigation.
 */
public final class AuthContextResolver implements ContextResolver {

  private static final Logger LOG = LoggerFactory.getLogger(AuthContextResolver.class);

  private final Sessions sessions;
  private final @Nullable PlatformTokens bearer;
  private final Authorities authorities;
  private final @Nullable FieldAuth field;

  AuthContextResolver(Sessions sessions, @Nullable PlatformTokens bearer, Authorities authorities) {
    this(sessions, bearer, authorities, null);
  }

  AuthContextResolver(Sessions sessions, @Nullable PlatformTokens bearer, Authorities authorities, @Nullable FieldAuth field) {
    this.sessions = sessions;
    this.bearer = bearer;
    this.authorities = authorities;
    this.field = field;
  }

  /** What the DPoP branch needs: the verifier, the nonces to issue and the field permission mapping. */
  record FieldAuth(
      FieldVerifier verifier, FieldNonces nonces, FieldPermissionResolver permissions, FieldTokens tokens, String publicBase, ApplicationCode application) {}

  /**
   * Whether the request presents credentials at all: an {@code Authorization} header (bearer or
   * DPoP) or the session cookie. A route that also serves callers without credentials (a push
   * subscribe with tickets only) resolves the context only when this is true, so a bad credential
   * is still a 401.
   *
   * @param request the request
   * @return true when it carries an {@code Authorization} header or the session cookie
   */
  public boolean carriesCredentials(Request request) {
    if (!request.headers("Authorization").isEmpty()) {
      return true;
    }
    return Cookies.read(request, sessions.cookies().sessionName()) != null;
  }

  /**
   * What {@link #resolve} authenticated the request as: the source, the credential's expiry and,
   * for a field worker, the device key, device and login family.
   *
   * @param request a request that {@link #resolve} has resolved
   * @return the authentication, empty when the request was not resolved (or failed)
   */
  public static Optional<Authenticated> authenticated(Request request) {
    return Optional.ofNullable(request.attribute(RequestState.AUTHENTICATED));
  }

  @Override
  public Context resolve(Request request) throws Exception {
    Authenticated a = authenticate(request);
    request.attribute(RequestState.AUTHENTICATED, a);
    return authorities.context(a, request);
  }

  private Authenticated authenticate(Request request) throws Exception {
    PlatformTokens tokens = bearer;
    String authorization = request.header("Authorization");
    FieldAuth fieldAuth = field;
    if (fieldAuth != null && authorization != null && authorization.regionMatches(true, 0, "DPoP ", 0, 5)) {
      return authenticateField(request, authorization.substring(5).trim(), fieldAuth);
    }
    if (tokens != null && authorization != null) {
      if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
        throw unauthenticated();
      }
      return switch (tokens.verify(authorization.substring(7).trim())) {
        case TokenResult.Valid v ->
            authorities.authenticate(v.token().principal(), request, Authenticated.Source.BEARER, v.token().expiresAt(), null, null);
        case TokenResult.Invalid i -> {
          LOG.debug("refused a bearer token: {}", i.reason());
          throw unauthenticated();
        }
        case TokenResult.Unavailable u -> {
          LOG.warn("a bearer token could not be checked: {}", u.reason());
          throw new HttpException(503, "UNAVAILABLE", "tokens cannot be checked right now; try again");
        }
      };
    }
    Optional<Authenticated> session = sessions.current(request);
    if (session.isPresent()) {
      return session.get();
    }
    throw unauthenticated();
  }

  /**
   * Contract section 15.1: the {@code DPoP} scheme is step 1b. There is no fall-back to the cookie.
   * Every answer carries a fresh {@code DPoP-Nonce}, so a client rarely needs the retry.
   */
  private Authenticated authenticateField(Request request, String token, FieldAuth fieldAuth) throws Exception {
    String nonce = fieldAuth.nonces().issue();
    RequestState.of(request).dpopNonce(nonce);
    FieldVerifier.Outcome outcome = fieldAuth.verifier().verify(token, request.headers("DPoP"), request.method().name(), request.path());
    return switch (outcome) {
      case FieldVerifier.Outcome.Verified v -> {
        if (!authorities.fieldMayActFor(v.worker(), request)) {
          // the same refusal a user of another client gets (contract section 7.3)
          throw new FieldAuthException(403, "CLIENT_NOT_PERMITTED", "the principal may not act for this client", null, nonce);
        }
        yield authorities.field(v.worker(), fieldPermissions(request, v.worker(), fieldAuth, nonce));
      }
      case FieldVerifier.Outcome.Refused r -> {
        LOG.debug("refused a field credential: {}", r.reason());
        throw new FieldAuthException(401, "UNAUTHENTICATED", "authentication is required", "DPoP error=\"invalid_token\"", nonce);
      }
      case FieldVerifier.Outcome.NonceRequired n ->
          throw new FieldAuthException(401, "USE_DPOP_NONCE", "retry with the DPoP-Nonce from this answer", "DPoP error=\"use_dpop_nonce\"", nonce);
      case FieldVerifier.Outcome.PinChangeRequired p ->
          throw new FieldAuthException(403, "PIN_CHANGE_REQUIRED", "the PIN must be changed on the platform before this application can be used", null, nonce);
      case FieldVerifier.Outcome.Unavailable u -> {
        LOG.warn("a field credential could not be checked: {}", u.reason());
        throw new FieldAuthException(503, "UNAVAILABLE", "tokens cannot be checked right now; try again", null, nonce);
      }
    };
  }

  /** The worker's permissions; a resolver that fails answers 503, never "no permissions". */
  private static Collection<String> fieldPermissions(Request request, FieldPrincipal worker, FieldAuth fieldAuth, String nonce) {
    try {
      Collection<String> granted = fieldAuth.permissions().permissions(request, worker);
      return granted == null ? Set.of() : granted;
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      LOG.warn("a field worker's permissions could not be found: {}", worker.workerId(), e);
      throw new FieldAuthException(503, "UNAVAILABLE", "tokens cannot be checked right now; try again", null, nonce);
    }
  }

  private static HttpException unauthenticated() {
    return new HttpException(401, "UNAUTHENTICATED", "authentication is required");
  }
}
