package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.context.PrincipalKind;
import io.flowcatalyst.framework.core.App;
import java.util.List;
import java.util.Set;

/**
 * Authenticated requests in an application's tests (contract section 9): creates a real session in
 * the application's configured store and returns the {@code Cookie} header, so the test exercises
 * the real routes, resolver, CSRF rules and permission mapping without an identity provider.
 * Refused unless {@code APP_ENV} is {@code test}.
 *
 * <pre>{@code
 * AuthTesting auth = AuthTesting.of(app);
 * String cookie = auth.cookie(AuthTesting.user("prn_1", "ingestor:operator"));
 * client.get("/api/v1/ingestions").header("Cookie", cookie).send();
 * client.post("/api/v1/ingestions").header("Cookie", cookie).header("Sec-Fetch-Site", "same-origin").jsonBody(body).send();
 * }</pre>
 */
public final class AuthTesting {

  private final Sessions sessions;

  private AuthTesting(Sessions sessions) {
    this.sessions = sessions;
  }

  /**
   * The helper for a started application with {@link AuthModule}.
   *
   * @param app the application
   * @return the helper
   * @throws IllegalStateException when {@code APP_ENV} is not {@code test}
   */
  public static AuthTesting of(App app) {
    String env = app.config().environment();
    if (!env.equals("test")) {
      throw new IllegalStateException("AuthTesting is allowed only when APP_ENV is 'test' (it is '" + env + "')");
    }
    return new AuthTesting(app.get(Sessions.class));
  }

  /**
   * Signs a principal in: a new session in the configured store.
   *
   * @param principal who
   * @return the {@code Cookie} header value, {@code <name>=<handle>}
   */
  public String cookie(Principal principal) {
    SessionManager manager = sessions.manager();
    return sessions.cookies().sessionName() + "=" + manager.createSession(principal, manager.now());
  }

  /**
   * The session cookie's name: {@code __Host-fw_session}, or {@code fw_session} over http.
   *
   * @return the name
   */
  public String cookieName() {
    return sessions.cookies().sessionName();
  }

  /**
   * An anchor user who may act for every client and holds roles.
   *
   * @param id the principal id
   * @param roles role names, {@code <application>:<role>}
   * @return the principal
   */
  public static Principal user(String id, String... roles) {
    return new Principal(
        new PrincipalId(id), PrincipalKind.USER, "Test User", id + "@example.test", "ANCHOR", null,
        List.of("*"), List.of("*"), List.of(roles), Set.of());
  }

  /**
   * A user of one client.
   *
   * @param id the principal id
   * @param clientId the client's platform id, for example {@code clt_1}
   * @param identifier the client's identifier, which becomes the {@code Context}'s client
   * @param roles role names
   * @return the principal
   */
  public static Principal clientUser(String id, String clientId, String identifier, String... roles) {
    return new Principal(
        new PrincipalId(id), PrincipalKind.USER, "Test User", id + "@example.test", "CLIENT", clientId,
        List.of(clientId + ":" + identifier), List.of("*"), List.of(roles), Set.of());
  }
}
