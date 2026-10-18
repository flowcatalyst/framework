package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.http.Method;
import io.flowcatalyst.framework.http.TestResponse;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The 2026-10-18 security review's Java items (contract section 14): F1, F2, I1, I2, I3, I5, I8. */
class SecurityHardeningTest {

  @TempDir Path dir;
  AuthApp t;
  TestOidcProvider provider;
  final java.util.List<TestOidcProvider> providers = new java.util.ArrayList<>();

  @AfterEach
  void stop() {
    if (t != null) {
      t.close();
    }
    providers.forEach(TestOidcProvider::close);
  }

  private Platform platform(boolean development) {
    provider = TestOidcProvider.start(new MutableClock(Instant.parse("2026-10-18T08:00:00Z")));
    providers.add(provider);
    return new Platform(provider.issuer(), Duration.ofSeconds(5), new MutableClock(Instant.parse("2026-10-18T08:00:00Z")), development);
  }

  private static Map<String, Object> change(String key, Object value) {
    Map<String, Object> m = new HashMap<>();
    m.put(key, value);
    return m;
  }

  // ---- F2 --------------------------------------------------------------------------------------

  @Test
  void logoutWithoutASessionCookieSendsNoCookie() {
    t = AuthApp.start(dir);
    TestResponse r = t.browser().send(t.client.request(Method.POST, "/auth/logout"), null, "Sec-Fetch-Site", "same-origin");
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.headerValues("Set-Cookie")).isEmpty();
  }

  @Test
  void aRefusedLogoutSendsNoCookieAndKeepsTheSession() {
    t = AuthApp.start(dir);
    Browser b = t.browser();
    assertThat(b.signIn("/").status()).isEqualTo(302);
    TestResponse r = b.send(t.client.request(Method.POST, "/auth/logout"), null, "Sec-Fetch-Site", "cross-site");
    assertThat(r.status()).isEqualTo(403);
    assertThat(r.headerValues("Set-Cookie")).isEmpty();
    assertThat(b.get("/api/me").status()).isEqualTo(200);
  }

  // ---- I5 --------------------------------------------------------------------------------------

  @Test
  void anEmptySecFetchSiteIsRefusedEvenWithTheRightOrigin() {
    t = AuthApp.start(dir);
    Browser b = t.browser();
    b.signIn("/");
    TestResponse r = b.send(t.client.request(Method.POST, "/auth/logout"), null, "Sec-Fetch-Site", "", "Origin", AuthApp.ORIGIN);
    assertThat(r.status()).isEqualTo(403);
    assertThat(b.get("/api/me").status()).isEqualTo(200);
  }

  // ---- I8 --------------------------------------------------------------------------------------

  /** A store whose login records cannot be taken. */
  private static final class BrokenTake implements SessionStore {
    private final SessionStore inner;

    BrokenTake(SessionStore inner) {
      this.inner = inner;
    }

    @Override
    public String create(String kind, String json, Instant expiresAt) {
      return inner.create(kind, json, expiresAt);
    }

    @Override
    public Optional<String> read(String kind, String handle) {
      return inner.read(kind, handle);
    }

    @Override
    public Optional<String> take(String kind, String handle) {
      throw new SessionStoreException("the store is down");
    }

    @Override
    public String update(String kind, String handle, String json, Instant expiresAt) {
      return inner.update(kind, handle, json, expiresAt);
    }

    @Override
    public void delete(String kind, String handle) {
      inner.delete(kind, handle);
    }
  }

  @Test
  void theCallbackClearsTheBindingCookieWhenTheStoreFails() {
    MutableClock clock = new MutableClock(Instant.parse("2026-10-18T08:00:00Z"));
    SessionStore store = new BrokenTake(MemorySessionStore.create(clock));
    t = AuthApp.start(dir, b -> b.sessions(lookup -> store), Map.of());
    Browser b = t.browser();
    String callback = b.startSignIn("/");
    assertThat(b.jar.keySet()).anyMatch(k -> k.startsWith("__Host-fw_login_"));
    TestResponse r = b.get(b.local(callback));
    assertThat(r.status()).isEqualTo(500);
    assertThat(r.headerValues("Set-Cookie")).anyMatch(c -> c.startsWith("__Host-fw_login_") && c.contains("Max-Age=0"));
    assertThat(b.jar).isEmpty();
  }

  // ---- I1 --------------------------------------------------------------------------------------

  @Test
  void anHttpAuthorizationEndpointIsRefusedOutsideDevelopment() {
    Platform p = platform(false);
    assertThatThrownBy(p::discovery).isInstanceOf(PlatformUnavailableException.class).hasMessageContaining("authorization_endpoint");
  }

  @Test
  void anHttpsAuthorizationEndpointIsAcceptedOutsideDevelopment() throws Exception {
    Platform p = platform(false);
    provider.discoveryChanges(change("authorization_endpoint", "https://login.example/authorize"));
    assertThat(p.discovery().authorizationEndpoint()).isEqualTo(URI.create("https://login.example/authorize"));
  }

  @Test
  void anHttpAuthorizationEndpointIsAcceptedInDevelopmentButOtherSchemesAreNot() throws Exception {
    Platform p = platform(true);
    assertThat(p.discovery().authorizationEndpoint().getScheme()).isEqualTo("http");
    for (String bad : new String[] {"javascript:alert(1)", "data:text/html,x", "ftp://login.example/a"}) {
      Platform fresh = platform(true);
      provider.discoveryChanges(change("authorization_endpoint", bad));
      assertThatThrownBy(fresh::discovery).as(bad).isInstanceOf(PlatformUnavailableException.class);
    }
  }

  @Test
  void anEndSessionEndpointOffTheIssuersOriginIsIgnored() throws Exception {
    Platform p = platform(true);
    provider.discoveryChanges(change("end_session_endpoint", "https://evil.example/logout"));
    assertThat(p.discovery().endSessionEndpoint()).isNull();
  }

  @Test
  void anEndSessionEndpointWithUserInfoOrAnotherSchemeIsIgnored() throws Exception {
    Platform p = platform(true);
    String base = provider.issuer().toString();
    provider.discoveryChanges(change("end_session_endpoint", base.replace("http://", "http://user:pw@") + "/logout"));
    assertThat(p.discovery().endSessionEndpoint()).isNull();
    Platform strict = platform(false);
    provider.discoveryChanges(change("authorization_endpoint", "https://login.example/authorize"));
    // same origin as the issuer but plain http, and this is not development
    assertThat(strict.discovery().endSessionEndpoint()).isNull();
  }

  @Test
  void aSameOriginEndSessionEndpointIsKept() throws Exception {
    Platform p = platform(true);
    assertThat(p.discovery().endSessionEndpoint()).isEqualTo(URI.create(provider.issuer() + "/auth/oidc/session/end"));
  }

  @Test
  void aRefusedEndSessionEndpointOmitsEndSessionUrlWithAWarning() {
    t = AuthApp.start(dir);
    t.provider.discoveryChanges(change("end_session_endpoint", "https://evil.example/logout"));
    try (LogCapture log = LogCapture.start()) {
      TestResponse r = t.browser().send(t.client.request(Method.POST, "/auth/logout"), null, "Sec-Fetch-Site", "same-origin");
      assertThat(r.body()).isEqualTo("{\"loggedOut\":true}");
      assertThat(log.text()).contains("end_session_endpoint").contains("ignored");
    }
  }

  // ---- I2 --------------------------------------------------------------------------------------

  @Test
  void theStandaloneVerifierRefusesAnHttpIssuerUnlessOptedIn() {
    MutableClock clock = new MutableClock(Instant.parse("2026-10-18T08:00:00Z"));
    assertThatThrownBy(() -> PlatformTokens.create(URI.create("http://platform.example.com"), Duration.ofSeconds(5), clock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("createAllowingPlainHttp");
    assertThat(PlatformTokens.create(URI.create("https://platform.example.com"), Duration.ofSeconds(5), clock)).isNotNull();
    assertThat(PlatformTokens.createAllowingPlainHttp(URI.create("http://127.0.0.1:1"), Duration.ofSeconds(5), clock)).isNotNull();
    assertThatThrownBy(() -> PlatformTokens.createAllowingPlainHttp(URI.create("ftp://platform.example.com"), Duration.ofSeconds(5), clock))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- I3 --------------------------------------------------------------------------------------

  @Test
  void theDefaultLimiterWarnsOnceWhenARequestCarriesXForwardedFor() {
    t = AuthApp.start(dir);
    try (LogCapture log = LogCapture.start()) {
      t.browser().get("/auth/login", "X-Forwarded-For", "203.0.113.9");
      t.browser().get("/auth/login", "X-Forwarded-For", "203.0.113.10");
      String text = log.text();
      int first = text.indexOf("X-Forwarded-For");
      assertThat(first).isGreaterThanOrEqualTo(0);
      assertThat(text.indexOf("X-Forwarded-For", first + 1)).isEqualTo(-1);
    }
  }

  @Test
  void noWarningWithoutTheHeader() {
    t = AuthApp.start(dir);
    try (LogCapture log = LogCapture.start()) {
      t.browser().get("/auth/login");
      assertThat(log.text()).doesNotContain("X-Forwarded-For");
    }
  }

  // ---- F1 --------------------------------------------------------------------------------------

  @Test
  void anInterruptedRequestNeitherCancelsTheSharedKeyReadNorStartsTheBackOff() throws Exception {
    Platform p = platform(true);
    provider.keysDelay(Duration.ofMillis(600));
    AtomicBoolean interrupted = new AtomicBoolean();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread caller =
        new Thread(
            () -> {
              try {
                p.key("key-1");
              } catch (Throwable e) {
                failure.set(e);
              }
              interrupted.set(Thread.currentThread().isInterrupted());
            });
    caller.start();
    long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (provider.keyFetches() == 0 && System.nanoTime() < until) {
      Thread.sleep(5);
    }
    assertThat(provider.keyFetches()).isEqualTo(1);
    caller.interrupt();
    caller.join();
    assertThat(failure.get()).isInstanceOf(PlatformUnavailableException.class);
    assertThat(interrupted.get()).as("the caller's interrupt flag is kept").isTrue();

    // the read went on without the caller: the key is there at once, with no second read and no back-off
    provider.keysDelay(Duration.ZERO);
    assertThat(p.key("key-1")).isPresent();
    assertThat(provider.keyFetches()).isEqualTo(1);
  }
}
