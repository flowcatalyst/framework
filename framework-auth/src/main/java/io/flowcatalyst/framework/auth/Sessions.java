package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.http.Request;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The browser session of a request (contract section 7.1, step 3). Provided by {@link AuthModule}.
 * Routes that require a context get it through the resolver; other handlers call {@link
 * #current}. On an unsafe method the CSRF rules run first.
 */
public final class Sessions {

  private static final Logger LOG = LoggerFactory.getLogger(Sessions.class);

  private final SessionManager manager;
  private final Cookies cookies;
  private final CsrfGuard csrf;
  private final Authorities authorities;
  private final InstantSource clock;

  Sessions(SessionManager manager, Cookies cookies, CsrfGuard csrf, Authorities authorities, InstantSource clock) {
    this.manager = manager;
    this.cookies = cookies;
    this.csrf = csrf;
    this.authorities = authorities;
    this.clock = clock;
  }

  /**
   * The session of this request: read once per request, timeouts checked, {@code lastSeenAt}
   * written back when due.
   *
   * @param request the request
   * @return who the session is for, or empty when there is no valid session
   * @throws io.flowcatalyst.framework.http.HttpException {@code 403 CROSS_ORIGIN_REQUEST} or
   *     {@code 415} on an unsafe request that breaks the CSRF rules, {@code 403
   *     CLIENT_NOT_PERMITTED} when the selected client is not the principal's
   * @throws Exception from the application's client selector
   */
  public Optional<Authenticated> current(Request request) throws Exception {
    RequestState state = RequestState.of(request);
    Optional<Authenticated> known = state.session();
    if (known != null) {
      return known;
    }
    String handle = Cookies.read(request, cookies.sessionName());
    if (handle == null) {
      state.session(Optional.empty());
      return Optional.empty();
    }
    csrf.check(request);
    Optional<SessionManager.Live> live = manager.session(handle);
    if (live.isEmpty()) {
      LOG.debug("a session cookie named no live session; clearing it");
      state.setCookie(cookies.clear(cookies.sessionName()));
      state.session(Optional.empty());
      return Optional.empty();
    }
    SessionManager.Live l = live.get();
    if (l.handleChanged()) {
      state.setCookie(cookies.set(cookies.sessionName(), l.handle(), maxAge(l)));
    }
    Authenticated a =
        authorities.authenticate(
            l.data().principal(),
            request,
            Authenticated.Source.SESSION,
            l.data().absoluteExpiresAt(),
            l.idleExpiresAt(),
            l.data().authTime());
    Optional<Authenticated> result = Optional.of(a);
    state.session(result);
    return result;
  }

  private long maxAge(SessionManager.Live l) {
    return Duration.between(clock.instant(), l.data().absoluteExpiresAt()).toSeconds();
  }

  SessionManager manager() {
    return manager;
  }

  Cookies cookies() {
    return cookies;
  }

  CsrfGuard csrf() {
    return csrf;
  }
}
