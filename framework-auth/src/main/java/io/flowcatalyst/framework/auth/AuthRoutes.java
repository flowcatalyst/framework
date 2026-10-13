package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.http.HttpErrors;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import java.time.Instant;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@code /auth/login}, {@code /auth/callback}, {@code /auth/session}, {@code /auth/logout} (contract section 4). */
final class AuthRoutes {

  private static final Logger LOG = LoggerFactory.getLogger(AuthRoutes.class);
  private static final Pattern STATE = Pattern.compile("[A-Za-z0-9_-]{43}");

  private final RelyingParty rp;
  private final SessionManager manager;
  private final Sessions sessions;
  private final Cookies cookies;
  private final ReturnToPolicy returnTo;
  private final String defaultLanding;
  private final LoginFailureHandler failures;
  private final @Nullable String postLogoutRedirectUri;
  private final InstantSource clock;

  AuthRoutes(
      RelyingParty rp,
      SessionManager manager,
      Sessions sessions,
      Cookies cookies,
      ReturnToPolicy returnTo,
      String defaultLanding,
      LoginFailureHandler failures,
      @Nullable String postLogoutRedirectUri,
      InstantSource clock) {
    this.rp = rp;
    this.manager = manager;
    this.sessions = sessions;
    this.cookies = cookies;
    this.returnTo = returnTo;
    this.defaultLanding = defaultLanding;
    this.failures = failures;
    this.postLogoutRedirectUri = postLogoutRedirectUri;
    this.clock = clock;
  }

  // ---- GET /auth/login ---------------------------------------------------------------------

  Response login(Request request) {
    String landing = landing(request.query("returnTo"));
    SessionManager.LoginData login =
        new SessionManager.LoginData(
            SessionHandles.random(32), SessionHandles.random(32), SessionHandles.random(32), landing, clock.instant());
    String url;
    try {
      url = rp.authorizeUrl(login);
    } catch (PlatformUnavailableException e) {
      LOG.warn("sign-in could not start: {} (correlation id {})", e.getMessage(), request.correlationId());
      return noStore(HttpErrors.error(502, "PLATFORM_UNAVAILABLE", "the sign-in service cannot be reached; try again"));
    }
    String handle = manager.createLogin(login);
    return noStore(Response.redirect(url))
        .addHeader("Set-Cookie", cookies.set(cookies.loginName(login.state()), handle, SessionManager.LOGIN_LIFETIME.toSeconds()));
  }

  private String landing(@Nullable String requested) {
    String chosen = returnTo.returnTo(requested);
    return chosen != null && ReturnToPolicy.isSameOriginPath(chosen) ? chosen : defaultLanding;
  }

  // ---- GET /auth/callback ------------------------------------------------------------------

  Response callback(Request request) {
    String state = request.query("state");
    if (state == null || !STATE.matcher(state).matches()) {
      return fail(request, null, 400, "INVALID_CALLBACK", "the sign-in answer is incomplete", Map.of());
    }
    String bindingName = cookies.loginName(state);
    String handle = Cookies.read(request, bindingName);
    if (handle == null) {
      return fail(
          request, bindingName, 400, "LOGIN_NOT_STARTED_HERE",
          "This sign-in was not started in this browser. Start the sign-in again.", Map.of());
    }
    Optional<SessionManager.LoginData> taken = manager.takeLogin(handle);
    if (taken.isEmpty() || !SessionHandles.same(taken.get().state(), state)) {
      return fail(request, bindingName, 400, "INVALID_STATE", "this sign-in has expired or was already used; start again", Map.of());
    }
    SessionManager.LoginData login = taken.get();
    String error = request.query("error");
    if (error != null) {
      String code = error.matches("[a-z_]{1,64}") ? error : "invalid";
      return fail(request, bindingName, 400, "LOGIN_REFUSED", "the sign-in was not completed", Map.of("error", code));
    }
    String code = request.query("code");
    if (code == null || code.isEmpty() || code.length() > 512) {
      return fail(request, bindingName, 400, "INVALID_CALLBACK", "the sign-in answer is incomplete", Map.of());
    }
    RelyingParty.IdToken verified;
    try {
      switch (rp.exchange(code, login.codeVerifier())) {
        case RelyingParty.Exchange.Refused r -> {
          return fail(request, bindingName, 400, "LOGIN_FAILED", "the sign-in could not be completed", Map.of("error", r.error()));
        }
        case RelyingParty.Exchange.Tokens t -> verified = rp.verify(t.idToken(), login.nonce());
      }
    } catch (PlatformUnavailableException e) {
      LOG.warn("sign-in could not complete: {} (correlation id {})", e.getMessage(), request.correlationId());
      return fail(request, bindingName, 502, "PLATFORM_UNAVAILABLE", "the sign-in service cannot be reached; try again", Map.of());
    }
    RelyingParty.IdToken.Ok ok;
    switch (verified) {
      case RelyingParty.IdToken.Refused r -> {
        return fail(request, bindingName, 400, "INVALID_ID_TOKEN", "the sign-in answer could not be trusted", Map.of("reason", r.reason()));
      }
      case RelyingParty.IdToken.Ok o -> ok = o;
    }
    String previous = Cookies.read(request, cookies.sessionName());
    if (previous != null) {
      manager.deleteSession(previous);
    }
    String session = manager.createSession(ok.principal(), ok.authTime());
    long maxAge = manager.absoluteTimeout().toSeconds();
    LOG.info("signed in principal {} (correlation id {})", ok.principal().id(), request.correlationId());
    return noStore(Response.redirect(login.returnTo()))
        .addHeader("Set-Cookie", cookies.set(cookies.sessionName(), session, maxAge))
        .addHeader("Set-Cookie", cookies.clear(bindingName));
  }

  private Response fail(
      Request request, @Nullable String bindingName, int status, String code, String message, Map<String, String> details) {
    LOG.info("sign-in failed: {} {} (correlation id {})", code, details, request.correlationId());
    Response response = noStore(failures.handle(new LoginFailure(status, code, message, details), request));
    return bindingName == null ? response : response.addHeader("Set-Cookie", cookies.clear(bindingName));
  }

  // ---- GET /auth/session -------------------------------------------------------------------

  Response session(Request request) throws Exception {
    Optional<Authenticated> current = sessions.current(request);
    if (current.isEmpty()) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("authenticated", false);
      body.put("loginUrl", "/auth/login");
      return noStore(Response.json(200, body));
    }
    Authenticated a = current.get();
    Principal p = a.principal();
    Map<String, Object> principal = new LinkedHashMap<>();
    principal.put("id", p.id().value());
    principal.put("kind", p.kind().name());
    if (p.name() != null) {
      principal.put("name", p.name());
    }
    if (p.email() != null) {
      principal.put("email", p.email());
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("authenticated", true);
    body.put("principal", principal);
    if (p.tier() != null) {
      body.put("tier", p.tier());
    }
    body.put("client", a.client().value());
    body.put("clients", p.clients());
    body.put("applications", p.applications());
    body.put("roles", p.roles());
    body.put("permissions", new TreeSet<>(a.permissions()));
    Instant authTime = a.authTime();
    if (authTime != null) {
      body.put("authTime", authTime.toString());
    }
    body.put("expiresAt", a.expiresAt().toString());
    Instant idle = a.idleExpiresAt();
    if (idle != null) {
      body.put("idleExpiresAt", idle.toString());
    }
    Map<String, Object> csrf = new LinkedHashMap<>();
    csrf.put("mode", "fetch-metadata");
    csrf.put("jsonBodies", true);
    body.put("csrf", csrf);
    return noStore(Response.json(200, body));
  }

  // ---- POST /auth/logout -------------------------------------------------------------------

  Response logout(Request request) {
    String handle = Cookies.read(request, cookies.sessionName());
    if (handle != null) {
      sessions.csrf().check(request);
      manager.deleteSession(handle);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("loggedOut", true);
    body.put("endSessionUrl", rp.endSessionUrl(postLogoutRedirectUri));
    return noStore(Response.json(200, body)).addHeader("Set-Cookie", cookies.clear(cookies.sessionName()));
  }

  private static Response noStore(Response response) {
    return response.header("Cache-Control", "no-store");
  }
}
