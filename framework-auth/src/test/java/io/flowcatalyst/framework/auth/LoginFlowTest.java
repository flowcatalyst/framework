package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.http.TestResponse;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The login and callback endpoints against a real test provider (contract sections 4.1, 4.2). */
class LoginFlowTest {

  @TempDir Path dir;
  AuthApp t;

  @BeforeEach
  void start() {
    t = AuthApp.start(dir);
  }

  @AfterEach
  void stop() {
    t.close();
  }

  static Map<String, String> query(String url) {
    Map<String, String> out = new LinkedHashMap<>();
    String raw = URI.create(url).getRawQuery();
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  static String withState(String callbackUrl, String state) {
    Map<String, String> q = query(callbackUrl);
    return "/auth/callback?code=" + q.get("code") + "&state=" + state;
  }

  // ---- refusals ------------------------------------------------------------------------------

  @Test
  void aCallbackInAnotherBrowserIsRefusedAndTheRightBrowserCanStillFinish() {
    Browser alice = t.browser();
    String callback = alice.startSignIn("/");
    Browser mallory = t.browser();
    TestResponse r = mallory.get(mallory.local(callback));
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.tree().path("error").asString()).isEqualTo("LOGIN_NOT_STARTED_HERE");
    assertThat(r.tree().path("message").asString()).isEqualTo("This sign-in was not started in this browser. Start the sign-in again.");
    assertThat(mallory.jar).isEmpty();
    // the login record was not touched, so the browser that started it can finish
    TestResponse ok = alice.get(alice.local(callback));
    assertThat(ok.status()).isEqualTo(302);
    assertThat(alice.jar).containsKey("__Host-fw_session");
  }

  @Test
  void aChangedStateIsRefusedBecauseThisBrowserHasNoBindingForIt() {
    Browser b = t.browser();
    String callback = b.startSignIn("/");
    String other = SessionHandles.random(32);
    TestResponse r = b.get(withState(callback, other));
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.tree().path("error").asString()).isEqualTo("LOGIN_NOT_STARTED_HERE");
    assertThat(b.jar).doesNotContainKey("__Host-fw_session");
  }

  @Test
  void aBindingCookieThatHoldsAnotherLoginRecordIsRefusedAsInvalidState() {
    Browser b = t.browser();
    String first = b.startSignIn("/");
    String firstState = query(first).get("state");
    String firstCookie = new Cookies(true).loginName(firstState);
    String firstHandle = b.jar.get(firstCookie);
    String second = b.startSignIn("/");
    String secondState = query(second).get("state");
    // put the first login's record under the second state's cookie name
    b.jar.put(new Cookies(true).loginName(secondState), firstHandle);
    TestResponse r = b.get(b.local(second));
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.tree().path("error").asString()).isEqualTo("INVALID_STATE");
    assertThat(b.jar).doesNotContainKey("__Host-fw_session");
  }

  @Test
  void aReplayedCallbackIsRefused() {
    Browser b = t.browser();
    String callback = b.startSignIn("/");
    Map<String, String> before = new LinkedHashMap<>(b.jar);
    assertThat(b.get(b.local(callback)).status()).isEqualTo(302);
    // an attacker who copied the URL and the binding cookie replays both
    Browser replay = t.browser();
    replay.jar.putAll(before);
    TestResponse r = replay.get(replay.local(callback));
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.tree().path("error").asString()).isEqualTo("INVALID_STATE");
    assertThat(replay.jar).doesNotContainKey("__Host-fw_session");
  }

  @Test
  void aMissingOrMalformedStateOrCodeIsAnInvalidCallback() {
    Browser b = t.browser();
    assertThat(b.get("/auth/callback?code=x").tree().path("error").asString()).isEqualTo("INVALID_CALLBACK");
    assertThat(b.get("/auth/callback?code=x&state=short").tree().path("error").asString()).isEqualTo("INVALID_CALLBACK");
    String callback = b.startSignIn("/");
    String state = query(callback).get("state");
    TestResponse r = b.get("/auth/callback?state=" + state);
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.tree().path("error").asString()).isEqualTo("INVALID_CALLBACK");
    // the binding cookie is cleared and the record was spent
    assertThat(b.jar).doesNotContainKey(new Cookies(true).loginName(state));
  }

  @Test
  void anErrorFromThePlatformIsLoginRefusedAndItsDescriptionIsNotEchoed() {
    t.provider.authorizeError("access_denied");
    Browser b = t.browser();
    TestResponse r = b.get(b.local(b.startSignIn("/")));
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.tree().path("error").asString()).isEqualTo("LOGIN_REFUSED");
    assertThat(r.tree().path("details").path("error").asString()).isEqualTo("access_denied");
    assertThat(r.body()).doesNotContain("script");
    assertThat(b.jar).isEmpty();
  }

  @Test
  void aRefusedCodeIsLoginFailedAndAPlatformFailureIs502() {
    Browser b = t.browser();
    t.provider.tokenStatus(400);
    TestResponse refused = b.get(b.local(b.startSignIn("/")));
    assertThat(refused.status()).isEqualTo(400);
    assertThat(refused.tree().path("error").asString()).isEqualTo("LOGIN_FAILED");
    assertThat(refused.tree().path("details").path("error").asString()).isEqualTo("invalid_grant");
    t.provider.tokenStatus(500);
    TestResponse down = b.get(b.local(b.startSignIn("/")));
    assertThat(down.status()).isEqualTo(502);
    assertThat(down.tree().path("error").asString()).isEqualTo("PLATFORM_UNAVAILABLE");
    assertThat(b.jar).doesNotContainKey("__Host-fw_session");
  }

  @Test
  void loginAnswers502WhenDiscoveryCannotBeReadAndTriesAgainNextTime() {
    t.provider.down(true);
    TestResponse r = t.browser().get("/auth/login");
    assertThat(r.status()).isEqualTo(502);
    assertThat(r.tree().path("error").asString()).isEqualTo("PLATFORM_UNAVAILABLE");
    t.provider.down(false);
    assertThat(t.browser().get("/auth/login").status()).isEqualTo(302);
  }

  @Test
  void aDiscoveryDocumentForAnotherIssuerOrWithOffOriginEndpointsIsRefused() {
    t.provider.discoveryChanges(Map.of("issuer", "https://evil.example"));
    assertThat(t.browser().get("/auth/login").status()).isEqualTo(502);
    t.provider.discoveryChanges(Map.of("token_endpoint", "https://evil.example/oauth/token"));
    assertThat(t.browser().get("/auth/login").status()).isEqualTo(502);
    t.provider.discoveryChanges(Map.of("jwks_uri", "https://evil.example/jwks"));
    assertThat(t.browser().get("/auth/login").status()).isEqualTo(502);
    t.provider.discoveryChanges(Map.of("code_challenge_methods_supported", java.util.List.of("plain")));
    assertThat(t.browser().get("/auth/login").status()).isEqualTo(502);
  }

  @Test
  void anUnsafeReturnToIsReplacedByTheDefaultLanding() {
    for (String bad : new String[] {"//evil.example/x", "/\\evil.example", "https://evil.example/", "javascript:alert(1)", "/a\nb", "x", "/" + "a".repeat(2048)}) {
      Browser b = t.browser();
      TestResponse r = b.signIn(bad);
      assertThat(r.status()).as(bad).isEqualTo(302);
      assertThat(r.header("Location")).as(bad).isEqualTo("/");
    }
    Browser b = t.browser();
    assertThat(b.signIn("/ingestions?page=2").header("Location")).isEqualTo("/ingestions?page=2");
  }

  @Test
  void theLoginRoutesAreRateLimitedPerAddress() {
    Browser b = t.browser();
    for (int i = 0; i < 20; i++) {
      assertThat(b.get("/auth/login").status()).isEqualTo(302);
    }
    assertThat(b.get("/auth/login").status()).isEqualTo(429);
    assertThat(b.get("/auth/callback?state=x").status()).isEqualTo(429);
    assertThat(b.get("/auth/session").status()).isEqualTo(200);
  }

  // ---- the happy path ----------------------------------------------------------------------

  @Test
  void signingInEndToEndAsAPublicClient() {
    Browser b = t.browser();
    TestResponse login = b.get("/auth/login?returnTo=%2Forders");
    assertThat(login.status()).isEqualTo(302);
    assertThat(login.header("Cache-Control")).isEqualTo("no-store");
    String authorize = login.header("Location");
    assertThat(authorize).startsWith(t.provider.issuer() + "/oauth/authorize?");
    Map<String, String> q = query(authorize);
    assertThat(q.keySet())
        .containsExactly("response_type", "client_id", "redirect_uri", "scope", "state", "nonce", "code_challenge", "code_challenge_method");
    assertThat(q).containsEntry("response_type", "code").containsEntry("client_id", AuthApp.CLIENT_ID)
        .containsEntry("redirect_uri", AuthApp.ORIGIN + "/auth/callback").containsEntry("scope", "openid profile email")
        .containsEntry("code_challenge_method", "S256");
    assertThat(q.get("state")).matches("[A-Za-z0-9_-]{43}");
    assertThat(q.get("nonce")).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(q.get("state"));
    String bindingName = "__Host-fw_login_" + new Cookies(true).loginName(q.get("state")).substring("__Host-fw_login_".length());
    String setLogin = login.headerValues("Set-Cookie").getFirst();
    assertThat(setLogin).startsWith(bindingName + "=").contains("; Path=/").contains("; Max-Age=600").contains("; HttpOnly")
        .contains("; SameSite=Lax").contains("; Secure").doesNotContain("Domain");

    TestResponse callback = b.get(b.local(b.atProvider(authorize)));
    assertThat(callback.status()).isEqualTo(302);
    assertThat(callback.header("Location")).isEqualTo("/orders");
    assertThat(callback.header("Cache-Control")).isEqualTo("no-store");
    String setSession = callback.headerValues("Set-Cookie").stream().filter(c -> c.startsWith("__Host-fw_session=")).findFirst().orElseThrow();
    assertThat(setSession).matches("__Host-fw_session=[A-Za-z0-9_-]{43}; Path=/; Max-Age=28800; HttpOnly; SameSite=Lax; Secure");
    assertThat(callback.headerValues("Set-Cookie")).anyMatch(c -> c.startsWith(bindingName + "=;") && c.contains("Max-Age=0"));

    // the code was exchanged as a public client: no secret, the verifier that matches the challenge
    Map<String, String> form = t.provider.lastTokenForm();
    assertThat(form).containsEntry("grant_type", "authorization_code").containsEntry("client_id", AuthApp.CLIENT_ID)
        .containsEntry("redirect_uri", AuthApp.ORIGIN + "/auth/callback").doesNotContainKey("client_secret");
    assertThat(TestOidcProvider.challenge(form.get("code_verifier"))).isEqualTo(q.get("code_challenge"));
    assertThat(t.provider.lastTokenAuthorization()).isEmpty();

    TestResponse me = b.get("/api/me");
    assertThat(me.status()).isEqualTo(200);
    assertThat(me.tree().path("principal").asString()).isEqualTo("prn_USER1");
    assertThat(me.tree().path("client").asString()).isEqualTo("_platform");
    assertThat(me.tree().path("source").asString()).isEqualTo("SESSION");
    assertThat(me.tree().path("permissions").size()).isZero();
  }

  @Test
  void aConfidentialClientSendsItsSecretWithBasicAuthentication() throws Exception {
    t.close();
    t = AuthApp.start(dir, b -> {}, Map.of("AUTH_CLIENT_SECRET", "s3cret/with:chars"));
    Browser b = t.browser();
    assertThat(b.signIn("/").status()).isEqualTo(302);
    String expected = "Basic " + java.util.Base64.getEncoder().encodeToString("app-client:s3cret%2Fwith%3Achars".getBytes(StandardCharsets.UTF_8));
    assertThat(t.provider.lastTokenAuthorization()).isEqualTo(expected);
    assertThat(t.provider.lastTokenForm()).doesNotContainKey("client_secret");
    assertThat(b.jar).containsKey("__Host-fw_session");
  }

  @Test
  void twoLoginsInFlightInOneBrowserBothComplete() {
    Browser b = t.browser();
    String first = b.startSignIn("/a");
    String second = b.startSignIn("/b");
    assertThat(b.get(b.local(second)).header("Location")).isEqualTo("/b");
    assertThat(b.get(b.local(first)).header("Location")).isEqualTo("/a");
  }

  @Test
  void signingInAgainRotatesTheSession() {
    Browser b = t.browser();
    b.signIn("/");
    String old = b.jar.get("__Host-fw_session");
    b.signIn("/");
    String fresh = b.jar.get("__Host-fw_session");
    assertThat(fresh).isNotEqualTo(old);
    assertThat(t.store.read(SessionStore.SESSION, old)).isEmpty();
    Browser thief = t.browser();
    thief.jar.put("__Host-fw_session", old);
    assertThat(thief.get("/api/me").status()).isEqualTo(401);
  }

  @Test
  void theLoginThemeIsSentAsTheClientParameter() throws Exception {
    t.close();
    t = AuthApp.start(dir, b -> b.loginTheme("acme"), Map.of());
    TestResponse login = t.browser().get("/auth/login");
    assertThat(query(login.header("Location"))).containsEntry("client", "acme");
  }

  @Test
  void aFailureHandlerInCodeReplacesTheJsonAnswer() throws Exception {
    t.close();
    t = AuthApp.start(dir, b -> b.loginFailure(LoginFailureHandler.redirectTo("/signed-out")), Map.of());
    t.provider.authorizeError("access_denied");
    Browser b = t.browser();
    TestResponse r = b.get(b.local(b.startSignIn("/")));
    assertThat(r.status()).isEqualTo(302);
    assertThat(r.header("Location")).isEqualTo("/signed-out?error=LOGIN_REFUSED");
    assertThat(b.jar).isEmpty();
  }
}
