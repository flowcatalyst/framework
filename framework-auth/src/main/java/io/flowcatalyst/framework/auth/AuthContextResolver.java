package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Request;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real {@link ContextResolver} (contract section 7.1): a platform bearer token wins and never
 * falls back to the cookie; else the session cookie; else {@code 401 UNAUTHENTICATED}, which
 * {@link AuthModule}'s middleware turns into a {@code 302} for an HTML navigation.
 */
public final class AuthContextResolver implements ContextResolver {

  private static final Logger LOG = LoggerFactory.getLogger(AuthContextResolver.class);

  private final Sessions sessions;
  private final @Nullable PlatformTokens bearer;
  private final Authorities authorities;

  AuthContextResolver(Sessions sessions, @Nullable PlatformTokens bearer, Authorities authorities) {
    this.sessions = sessions;
    this.bearer = bearer;
    this.authorities = authorities;
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

  private static HttpException unauthenticated() {
    return new HttpException(401, "UNAUTHENTICATED", "authentication is required");
  }
}
