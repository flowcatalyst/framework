package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.openapi.OpenApi;
import io.flowcatalyst.framework.http.openapi.Schema;
import io.flowcatalyst.framework.http.openapi.SecurityScheme;

/**
 * The module's two typed operations and what the OpenAPI document needs about them (sessions
 * contract section 4.5, OpenAPI contract sections 7 and 8). Both are public: they answer without a
 * session. {@code /auth/login} and {@code /auth/callback} are browser navigations, not operations.
 */
final class AuthApi {

  /** The production name of the session cookie, documented whatever the environment. */
  static final String SESSION_COOKIE = "__Host-fw_session";

  static final Operation<NoBody, AuthSession> SESSION =
      Operation.get("/auth/session", AuthSession.class)
          .id("getAuthSession")
          .summary("Read the browser session")
          .tag("auth")
          .build();

  static final Operation<NoBody, AuthLogout> LOGOUT =
      Operation.post("/auth/logout", AuthLogout.class)
          .id("logout")
          .summary("Sign out of the application")
          .tag("auth")
          .error(403, "CROSS_ORIGIN_REQUEST", "the request did not come from this application's pages")
          .error(415, "UNSUPPORTED_MEDIA_TYPE", "a request body must be JSON")
          .build();

  private AuthApi() {}

  /** Declares the operations, their fixed components and the security schemes this module resolves. */
  static void declare(AppBuilder app, boolean bearer) {
    OpenApi.declare(app, SESSION, LOGOUT);
    OpenApi.schema(app, AuthSession.class, Schema.oneOf(AuthSession.Authenticated.class, AuthSession.Anonymous.class));
    OpenApi.property(app, AuthSession.Authenticated.class, "authenticated", Schema.bool().constant(true));
    OpenApi.property(app, AuthSession.Anonymous.class, "authenticated", Schema.bool().constant(false));
    OpenApi.property(app, AuthCsrf.class, "mode", Schema.string().constant("fetch-metadata"));
    OpenApi.property(app, AuthCsrf.class, "jsonBodies", Schema.bool().constant(true));
    OpenApi.property(app, AuthLogout.class, "loggedOut", Schema.bool().constant(true));
    OpenApi.securityScheme(app, "session", SecurityScheme.cookie(SESSION_COOKIE));
    if (bearer) {
      OpenApi.securityScheme(app, "bearer", SecurityScheme.bearer("JWT"));
    }
  }
}
