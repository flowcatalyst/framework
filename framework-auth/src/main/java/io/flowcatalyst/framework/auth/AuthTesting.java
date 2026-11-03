package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.context.PrincipalKind;
import io.flowcatalyst.framework.core.App;
import java.time.Instant;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
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
  private final AuthModule.Services services;
  private final InstantSource clock;
  private final String issuer;

  private AuthTesting(Sessions sessions, AuthModule.Services services, InstantSource clock, String issuer) {
    this.sessions = sessions;
    this.services = services;
    this.clock = clock;
    this.issuer = issuer;
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
    return new AuthTesting(
        app.get(Sessions.class), app.get(AuthModule.Services.class), app.get(InstantSource.class), Platform.normalise(app.config().get(AuthModule.ISSUER)));
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

  /**
   * The two headers of a request a field worker makes (contract section 15.8).
   *
   * @param authorization the {@code Authorization} header value, {@code DPoP <token>}
   * @param dpop the {@code DPoP} header value, the proof
   */
  public record FieldRequest(String authorization, String dpop) {

    /**
     * Both headers by name, for a test client.
     *
     * @return {@code Authorization} and {@code DPoP}
     */
    public Map<String, String> headers() {
      return Map.of("Authorization", authorization, "DPoP", dpop);
    }
  }

  /**
   * The headers of a field worker's request to a route under test: a field access token for the
   * application, bound to the device and signed by {@code signer}, and a valid proof with a valid
   * application nonce. The application must have been built with {@code fieldWorkers} and trust the
   * signer's key ({@code TestOidcProvider::signField}). Refused unless {@code APP_ENV} is {@code
   * test}, which {@link #of} checked.
   *
   * @param worker who, for example from {@link #fieldWorker}
   * @param device the device holding the key the token is bound to
   * @param signer signs the access token's claims with the key the application trusts
   * @param method the request method
   * @param path the request path, without query
   * @return the headers
   */
  public FieldRequest fieldRequest(
      FieldPrincipal worker, TestFieldDevice device, Function<Map<String, Object>, String> signer, String method, String path) {
    AuthContextResolver.FieldAuth field = services.requireField();
    Instant now = clock.instant();
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", issuer);
    claims.put("aud", field.application().value());
    claims.put("sub", worker.workerId());
    claims.put("token_use", "field");
    claims.put("client_id", worker.clientId());
    claims.put("client_identifier", worker.clientIdentifier());
    claims.put("device_id", worker.deviceId());
    claims.put("device_mode", worker.deviceMode().name());
    claims.put("sid", worker.sessionId());
    if (worker.workerCode() != null) {
      claims.put("worker_code", worker.workerCode());
    }
    if (worker.name() != null) {
      claims.put("name", worker.name());
    }
    claims.put("amr", worker.amr());
    claims.put("auth_time", (worker.authTime() == null ? now : worker.authTime()).getEpochSecond());
    claims.put("cnf", Map.of("jkt", device.jkt()));
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("iat", now.getEpochSecond());
    claims.put("nbf", now.getEpochSecond());
    claims.put("exp", now.plusSeconds(600).getEpochSecond());
    String token = signer.apply(claims);
    String proof = device.proof(method, field.publicBase() + path, field.nonces().issue(), token);
    return new FieldRequest("DPoP " + token, proof);
  }

  /**
   * A field worker on a personal device.
   *
   * @param workerId the principal id, {@code fwu_...}
   * @param clientIdentifier the client's identifier, which becomes the {@code Context}'s client
   * @param mode whether the device is the worker's own or shared
   * @return the worker
   */
  public static FieldPrincipal fieldWorker(String workerId, String clientIdentifier, FieldPrincipal.DeviceMode mode) {
    return new FieldPrincipal(
        workerId, "W001", "Test Worker", "clt_" + clientIdentifier, clientIdentifier, "app", "fwd_TEST", mode, List.of("pin"), null, "fws_TEST",
        Instant.EPOCH);
  }
}
