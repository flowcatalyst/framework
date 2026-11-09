package io.flowcatalyst.framework.push;

import io.flowcatalyst.framework.auth.Authenticated;
import io.flowcatalyst.framework.auth.AuthContextResolver;
import io.flowcatalyst.framework.auth.FieldPrincipal;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.core.Provider;
import java.util.Optional;

/** Authenticators for {@link PushModule.Builder#authenticate}. */
public final class PushAuth {

  private PushAuth() {}

  /**
   * Authenticates with the auth module (sessions-and-login contract): a bearer token, a field token
   * with its DPoP proof, or the session cookie with its CSRF rule. A request with none of them is
   * unauthenticated, not refused; one with a bad credential is a 401.
   *
   * @return the provider; the auth module must be listed
   */
  public static Provider<PushAuthenticator> authModule() {
    return lookup -> authModule(lookup.get(AuthContextResolver.class));
  }

  /**
   * The same over a resolver.
   *
   * @param resolver the auth module's resolver
   * @return the authenticator
   */
  public static PushAuthenticator authModule(AuthContextResolver resolver) {
    return request -> {
      if (!resolver.carriesCredentials(request)) {
        return Optional.empty();
      }
      Context context = resolver.resolve(request);
      Optional<Authenticated> authenticated = AuthContextResolver.authenticated(request);
      if (authenticated.isEmpty()) {
        return Optional.of(Credentials.of(context, null));
      }
      Authenticated a = authenticated.get();
      FieldPrincipal field = a.fieldPrincipal();
      if (field == null) {
        return Optional.of(Credentials.of(context, a.expiresAt()));
      }
      String device = field.deviceId() == null ? "" : field.deviceId();
      return Optional.of(new Credentials(context, a.expiresAt(), field.keyThumbprint(), device, field.sessionId()));
    };
  }
}
