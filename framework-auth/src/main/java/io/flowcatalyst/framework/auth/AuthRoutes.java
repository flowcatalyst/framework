package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.http.HttpErrors;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.OperationRequest;
import io.flowcatalyst.framework.http.Outcome;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
      // a browser navigation gets the application's failure answer (contract 4.1.1); an API call keeps the envelope
      if (AuthMiddleware.isNavigation(request)) {
        return fail(request, null, 502, "PLATFORM_UNAVAILABLE", "the sign-in service cannot be reached; try again", Map.of());
      }
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
    Optional<SessionManager.LoginData> taken;
    try {
      taken = manager.takeLogin(handle);
    } catch (SessionStoreException e) {
      LOG.error("sign-in could not complete: the session store failed (correlation id {})", request.correlationId(), e);
      // the binding is spent either way (security review I8)
      return fail(request, bindingName, 500, "INTERNAL", "the sign-in could not be completed; try again", Map.of());
    }
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
    String session;
    try {
      session = manager.createSession(ok.principal(), ok.authTime());
    } catch (SessionStoreException e) {
      LOG.error("sign-in could not complete: the session store failed (correlation id {})", request.correlationId(), e);
      return fail(request, bindingName, 500, "INTERNAL", "the sign-in could not be completed; try again", Map.of());
    }
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

  // ---- GET /auth/session (operation getAuthSession) -----------------------------------------

  Outcome<AuthSession> session(OperationRequest<NoBody> request) throws Exception {
    Optional<Authenticated> current = sessions.current(request.raw());
    if (current.isEmpty()) {
      return noStore(Outcome.ok(new AuthSession.Anonymous(false, "/auth/login")));
    }
    Authenticated a = current.get();
    Principal p = a.principal();
    AuthSession.Authenticated body =
        new AuthSession.Authenticated(
            true,
            new AuthPrincipal(p.id().value(), p.kind(), p.name(), p.email()),
            AuthTier.of(p.tier()),
            a.client().value(),
            p.clients(),
            p.applications(),
            p.roles(),
            List.copyOf(new TreeSet<>(a.permissions())),
            // a browser session always has both (sections 6.1 and 6.4); only bearer tokens lack them
            Objects.requireNonNull(a.authTime(), "a session has an authTime"),
            a.expiresAt(),
            Objects.requireNonNull(a.idleExpiresAt(), "a session has an idle expiry"),
            AuthCsrf.FIXED);
    return noStore(Outcome.ok(body));
  }

  // ---- POST /auth/logout (operation logout) --------------------------------------------------

  Outcome<AuthLogout> logout(OperationRequest<NoBody> request) {
    String handle = Cookies.read(request.raw(), cookies.sessionName());
    if (handle != null) {
      sessions.csrf().check(request.raw());
      manager.deleteSession(handle);
    }
    // endSessionUrl is omitted when unknown (section 4.4, amended): typed operations omit nulls
    Outcome.Ok<AuthLogout> out = noStore(Outcome.ok(new AuthLogout(true, rp.endSessionUrl(postLogoutRedirectUri))));
    // the clearing cookie goes only to a request that carried a session cookie and passed the CSRF
    // check (security review F2): a cross-site POST cannot make the browser drop a session
    return handle == null ? out : out.header("Set-Cookie", cookies.clear(cookies.sessionName()));
  }

  private static <R> Outcome.Ok<R> noStore(Outcome.Ok<R> outcome) {
    return outcome.header("Cache-Control", "no-store");
  }

  private static Response noStore(Response response) {
    return response.header("Cache-Control", "no-store");
  }
}
