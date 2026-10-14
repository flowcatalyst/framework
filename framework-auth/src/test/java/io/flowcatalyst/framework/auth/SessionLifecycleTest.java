package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.http.Method;
import io.flowcatalyst.framework.http.TestResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract sections 4.3, 4.4, 5.3, 6.4, 6.5 and 9. */
class SessionLifecycleTest {

  @TempDir Path dir;
  AuthApp t;

  @AfterEach
  void stop() {
    if (t != null) {
      t.close();
    }
  }

  private Browser signedIn() {
    Browser b = t.browser();
    assertThat(b.signIn("/").status()).isEqualTo(302);
    return b;
  }

  private TestResponse logout(Browser b, String... headers) {
    return b.send(t.client.request(Method.POST, "/auth/logout"), null, headers);
  }

  // ---- /auth/session -------------------------------------------------------------------------

  @Test
  void withoutASessionTheSessionEndpointSaysSoAndClearsAStaleCookie() {
    t = AuthApp.start(dir);
    Browser b = t.browser();
    TestResponse r = b.get("/auth/session");
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body()).isEqualTo("{\"authenticated\":false,\"loginUrl\":\"/auth/login\"}");
    assertThat(r.header("Cache-Control")).isEqualTo("no-store");
    b.jar.put("__Host-fw_session", SessionHandles.newHandle());
    TestResponse stale = b.get("/auth/session");
    assertThat(stale.tree().path("authenticated").asBoolean()).isFalse();
    assertThat(stale.headerValues("Set-Cookie")).anyMatch(c -> c.startsWith("__Host-fw_session=;") && c.contains("Max-Age=0"));
    assertThat(b.jar).isEmpty();
  }

  @Test
  void theSessionEndpointDescribesTheSignedInPrincipal() {
    t = AuthApp.start(dir);
    t.provider.signInAs(Map.of("roles", List.of("orders:clerk")));
    Browser b = signedIn();
    t.clock.advance(Duration.ofMinutes(5));
    TestResponse r = b.get("/auth/session");
    assertThat(r.body())
        .isEqualTo(
            "{\"authenticated\":true,\"principal\":{\"id\":\"prn_USER1\",\"kind\":\"USER\",\"name\":\"Ada Lovelace\",\"email\":\"ada@example.test\"},"
                + "\"tier\":\"ANCHOR\",\"client\":\"_platform\",\"clients\":[\"*\"],\"applications\":[\"*\"],\"roles\":[\"orders:clerk\"],"
                + "\"permissions\":[\"orders:thing:create\",\"orders:thing:view\"],\"authTime\":\"2026-10-13T08:00:00Z\","
                + "\"expiresAt\":\"2026-10-13T16:00:00Z\",\"idleExpiresAt\":\"2026-10-13T08:35:00Z\","
                + "\"csrf\":{\"mode\":\"fetch-metadata\",\"jsonBodies\":true}}");
  }

  @Test
  void aBearerTokenDoesNotMakeASession() {
    t = AuthApp.start(dir);
    TestResponse r = t.browser().get("/auth/session", "Authorization", "Bearer " + t.provider.accessToken(Map.of()));
    assertThat(r.tree().path("authenticated").asBoolean()).isFalse();
  }

  // ---- logout ----------------------------------------------------------------------------------

  @Test
  void aCrossSiteLogoutIsRefusedAndTheSessionLives() {
    t = AuthApp.start(dir);
    Browser b = signedIn();
    assertThat(logout(b, "Sec-Fetch-Site", "cross-site").status()).isEqualTo(403);
    assertThat(logout(b).status()).isEqualTo(403);
    assertThat(b.get("/api/me").status()).isEqualTo(200);
  }

  @Test
  void logoutRevokesTheSessionAndNamesThePlatformsEndSessionUrl() {
    t = AuthApp.start(dir, x -> x.postLogoutRedirect("/bye"), Map.of());
    Browser b = signedIn();
    String handle = b.jar.get("__Host-fw_session");
    Browser copy = t.browser();
    copy.jar.put("__Host-fw_session", handle);
    TestResponse r = logout(b, "Sec-Fetch-Site", "same-origin");
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().path("loggedOut").asBoolean()).isTrue();
    assertThat(r.tree().path("endSessionUrl").asString())
        .isEqualTo(t.provider.issuer() + "/auth/oidc/session/end?client_id=app-client&post_logout_redirect_uri=https%3A%2F%2Fapp.example%2Fbye");
    assertThat(r.headerValues("Set-Cookie")).anyMatch(c -> c.startsWith("__Host-fw_session=;") && c.contains("Max-Age=0"));
    assertThat(b.jar).isEmpty();
    assertThat(t.store.read(SessionStore.SESSION, handle)).isEmpty();
    assertThat(copy.get("/api/me").status()).isEqualTo(401);
  }

  @Test
  void logoutWithoutASessionIsStill200AndAJsonBodyIsAllowed() {
    t = AuthApp.start(dir);
    TestResponse r = t.browser().send(t.client.request(Method.POST, "/auth/logout"), null);
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().path("endSessionUrl").asString()).startsWith(t.provider.issuer() + "/auth/oidc/session/end?client_id=app-client");
    Browser b = signedIn();
    assertThat(b.send(t.client.request(Method.POST, "/auth/logout"), "{}", "Sec-Fetch-Site", "same-origin").status()).isEqualTo(200);
    Browser c = signedIn();
    TestResponse form = c.send(t.client.request(Method.POST, "/auth/logout").header("Content-Type", "text/plain").body("x"), null, "Sec-Fetch-Site", "same-origin");
    assertThat(form.status()).isEqualTo(415);
  }

  // ---- timeouts ----------------------------------------------------------------------------

  @Test
  void anIdleSessionExpiresAndIsDeleted() {
    t = AuthApp.start(dir);
    Browser b = signedIn();
    String handle = b.jar.get("__Host-fw_session");
    t.clock.advance(Duration.ofMinutes(29).plusSeconds(59));
    assertThat(b.get("/api/me").status()).isEqualTo(200);
    t.clock.advance(Duration.ofMinutes(30));
    TestResponse r = b.get("/api/me");
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.headerValues("Set-Cookie")).anyMatch(c -> c.startsWith("__Host-fw_session=;"));
    assertThat(t.store.size()).isZero();
    assertThat(t.store.read(SessionStore.SESSION, handle)).isEmpty();
  }

  @Test
  void activityKeepsASessionUntilTheAbsoluteTimeout() {
    t = AuthApp.start(dir);
    Browser b = signedIn();
    for (int i = 0; i < 23; i++) {
      t.clock.advance(Duration.ofMinutes(20));
      assertThat(b.get("/api/me").status()).as("after %d minutes", (i + 1) * 20).isEqualTo(200);
    }
    // 7 h 40 min so far; the absolute timeout is 8 h
    t.clock.advance(Duration.ofMinutes(20));
    assertThat(b.get("/api/me").status()).isEqualTo(401);
  }

  @Test
  void theSessionCodeEnforcesTheTimeoutsEvenWhenTheStoreWouldKeepTheRecord() {
    // records written before the timeouts were shortened in code still carry their old store expiry
    t = AuthApp.start(dir);
    Instant now = t.clock.instant();
    Principal p = AuthTesting.user("prn_OLD");
    String idleOld =
        t.store.create(SessionStore.SESSION,
            SessionManager.json(new SessionManager.SessionData(p, now, now, now.minus(Duration.ofMinutes(31)), now.plus(Duration.ofHours(1)))),
            now.plus(Duration.ofDays(1)));
    String absoluteOld =
        t.store.create(SessionStore.SESSION,
            SessionManager.json(new SessionManager.SessionData(p, now, now.minus(Duration.ofHours(9)), now, now.minusSeconds(1))),
            now.plus(Duration.ofDays(1)));
    String fine =
        t.store.create(SessionStore.SESSION,
            SessionManager.json(new SessionManager.SessionData(p, now, now, now, now.plus(Duration.ofHours(1)))),
            now.plus(Duration.ofDays(1)));
    assertThat(t.client.get("/api/me").header("Cookie", "__Host-fw_session=" + idleOld).send().status()).isEqualTo(401);
    assertThat(t.client.get("/api/me").header("Cookie", "__Host-fw_session=" + absoluteOld).send().status()).isEqualTo(401);
    assertThat(t.client.get("/api/me").header("Cookie", "__Host-fw_session=" + fine).send().status()).isEqualTo(200);
    assertThat(t.store.read(SessionStore.SESSION, idleOld)).isEmpty();
    assertThat(t.store.read(SessionStore.SESSION, absoluteOld)).isEmpty();
  }

  @Test
  void lastSeenIsWrittenBackAtMostOnceAMinute() {
    AtomicInteger updates = new AtomicInteger();
    MutableClock[] clock = new MutableClock[1];
    t = AuthApp.start(dir, x -> x.sessions(lookup -> counting(lookup.get(java.time.InstantSource.class), updates)), Map.of());
    Browser b = signedIn();
    for (int i = 0; i < 10; i++) {
      t.clock.advance(Duration.ofSeconds(5));
      assertThat(b.get("/api/me").status()).isEqualTo(200);
    }
    assertThat(updates.get()).isZero();
    t.clock.advance(Duration.ofSeconds(15));
    b.get("/api/me");
    b.get("/api/me");
    assertThat(updates.get()).isEqualTo(1);
  }

  private static SessionStore counting(java.time.InstantSource clock, AtomicInteger updates) {
    MemorySessionStore inner = MemorySessionStore.create(clock);
    return new SessionStore() {
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
        return inner.take(kind, handle);
      }

      @Override
      public String update(String kind, String handle, String json, Instant expiresAt) {
        updates.incrementAndGet();
        return inner.update(kind, handle, json, expiresAt);
      }

      @Override
      public void delete(String kind, String handle) {
        inner.delete(kind, handle);
      }
    };
  }

  // ---- cookies -------------------------------------------------------------------------------

  @Test
  void aSessionCookieSentTwiceIsIgnored() {
    t = AuthApp.start(dir);
    Browser b = signedIn();
    String handle = b.jar.get("__Host-fw_session");
    // whichever position the valid value takes, a name sent twice is no cookie at all
    for (String header :
        new String[] {
          "__Host-fw_session=" + handle + "; __Host-fw_session=" + SessionHandles.newHandle(),
          "__Host-fw_session=" + SessionHandles.newHandle() + "; __Host-fw_session=" + handle
        }) {
      assertThat(t.client.get("/api/me").header("Cookie", header).send().status()).isEqualTo(401);
    }
    TestResponse split =
        t.client.get("/api/me").header("Cookie", "__Host-fw_session=x").header("Cookie", "__Host-fw_session=" + handle).send();
    assertThat(split.status()).isEqualTo(401);
    // a quoted value (RFC 6265 allows it; the JDK's cookie manager sends it) is the same cookie
    assertThat(t.client.get("/api/me").header("Cookie", "$Version=\"1\"; __Host-fw_session=\"" + handle + "\"; $Path=\"/\"").send().status())
        .isEqualTo(200);
  }

  @Test
  void overPlainHttpInTestTheCookiesLoseSecureAndThePrefix() {
    t = AuthApp.start(dir, x -> {}, Map.of("AUTH_PUBLIC_BASE_URL", "http://localhost:8091"));
    Browser b = new Browser(t.client, "http://localhost:8091");
    TestResponse callback = b.signIn("/");
    assertThat(callback.headerValues("Set-Cookie"))
        .anyMatch(c -> c.matches("fw_session=[A-Za-z0-9_-]{43}; Path=/; Max-Age=28800; HttpOnly; SameSite=Lax"));
    assertThat(b.jar).containsKey("fw_session").doesNotContainKey("__Host-fw_session");
    assertThat(b.get("/api/me").status()).isEqualTo(200);
    assertThat(b.post("/api/echo", "{}", "Origin", "http://localhost:8091").status()).isEqualTo(200);
  }

  @Test
  void plainHttpOutsideLocalAndTestFailsStartUp() {
    Throwable e = catchThrowable(() -> AuthApp.start(dir, x -> {}, Map.of("AUTH_PUBLIC_BASE_URL", "http://app.example", "APP_ENV", "production")));
    assertThat(e).isInstanceOf(StartupException.class).hasMessageContaining("plain http");
    // the test provider is http as well, so production refuses it too
    Throwable issuer = catchThrowable(() -> AuthApp.start(dir, x -> {}, Map.of("APP_ENV", "production")));
    assertThat(issuer).isInstanceOf(StartupException.class).hasMessageContaining("auth.issuer is plain http");
  }

  @Test
  void aPublicBaseUrlWithAPathIsRefused() {
    Throwable e = catchThrowable(() -> AuthApp.start(dir, x -> {}, Map.of("AUTH_PUBLIC_BASE_URL", "https://app.example/ingest")));
    assertThat(e).isInstanceOf(StartupException.class).hasMessageContaining("no path");
  }

  // ---- the cookie store end to end ---------------------------------------------------------

  @Test
  void cookieSessionsWorkEndToEndAndTheirLimitIsPinned() {
    String keys = CookieKeys.generate("k1");
    t = AuthApp.start(dir, AuthModule.Builder::cookieSessions, Map.of("AUTH_COOKIE_KEYS", keys));
    Browser b = signedIn();
    String sealed = b.jar.get("__Host-fw_session");
    assertThat(sealed).startsWith("v1.k1.");
    assertThat(t.store.size()).isZero();
    assertThat(b.get("/api/me").status()).isEqualTo(200);
    t.clock.advance(Duration.ofMinutes(2));
    TestResponse touched = b.get("/api/me");
    assertThat(touched.headerValues("Set-Cookie")).anyMatch(c -> c.startsWith("__Host-fw_session=v1.k1."));
    assertThat(b.jar.get("__Host-fw_session")).isNotEqualTo(sealed);
    // a copy taken before logout keeps working until it expires: the documented limit
    Browser copy = t.browser();
    copy.jar.put("__Host-fw_session", b.jar.get("__Host-fw_session"));
    assertThat(logout(b, "Sec-Fetch-Site", "same-origin").status()).isEqualTo(200);
    assertThat(copy.get("/api/me").status()).isEqualTo(200);
    t.clock.advance(Duration.ofMinutes(31));
    assertThat(copy.get("/api/me").status()).isEqualTo(401);
  }

  @Test
  void cookieSessionsWithoutKeysFailStartUp() {
    Throwable e = catchThrowable(() -> AuthApp.start(dir, AuthModule.Builder::cookieSessions, Map.of()));
    assertThat(e).isInstanceOf(StartupException.class).hasMessageContaining("auth.cookie-keys");
  }

  // ---- AuthTesting ---------------------------------------------------------------------------

  @Test
  void authTestingSignsAPrincipalInThroughTheConfiguredStore() {
    t = AuthApp.start(dir);
    AuthTesting auth = AuthTesting.of(t.app);
    String cookie = auth.cookie(AuthTesting.user("prn_T", "orders:clerk"));
    assertThat(cookie).startsWith(auth.cookieName() + "=");
    TestResponse me = t.client.get("/api/me").header("Cookie", cookie).send();
    assertThat(me.tree().path("principal").asString()).isEqualTo("prn_T");
    assertThat(me.tree().path("principalKind").asString()).isEqualTo("USER");
    assertThat(me.tree().path("permissions").toString()).isEqualTo("[\"orders:thing:create\",\"orders:thing:view\"]");
    assertThat(t.client.post("/api/things").header("Cookie", cookie).header("Sec-Fetch-Site", "same-origin").jsonBody("{}").send().status())
        .isEqualTo(201);
    String clientCookie = auth.cookie(AuthTesting.clientUser("prn_C", "clt_1", "acme"));
    assertThat(t.client.get("/api/me").header("Cookie", clientCookie).send().tree().path("client").asString()).isEqualTo("acme");
  }

  @Test
  void authTestingIsRefusedOutsideTestMode() {
    t = AuthApp.start(dir, x -> {}, Map.of("APP_ENV", "local"));
    Throwable e = catchThrowable(() -> AuthTesting.of(t.app));
    assertThat(e).isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_ENV");
  }
}
