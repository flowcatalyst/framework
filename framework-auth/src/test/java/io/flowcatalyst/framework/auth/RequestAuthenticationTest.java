package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.http.Method;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract section 7: the order, CSRF, the Context, and 401 versus 302. */
class RequestAuthenticationTest {

  @TempDir Path dir;
  AuthApp t;
  Browser b;

  @BeforeEach
  void start() {
    t = AuthApp.start(dir);
    t.provider.signInAs(Map.of("roles", List.of("orders:clerk")));
    b = t.browser();
    assertThat(b.signIn("/").status()).isEqualTo(302);
  }

  @AfterEach
  void stop() {
    t.close();
  }

  private TestResponse post(String path, String contentType, String body, String... headers) {
    TestClient.Call call = t.client.request(Method.POST, path);
    if (contentType != null) {
      call.header("Content-Type", contentType);
    }
    if (body != null) {
      call.body(body);
    }
    return b.send(call, null, headers);
  }

  // ---- CSRF (section 7.2) --------------------------------------------------------------------

  @Test
  void crossSiteAndSameSiteRequestsWithTheCookieAreRefused() {
    for (String site : new String[] {"cross-site", "same-site", "bogus"}) {
      TestResponse r = post("/api/things", "application/json", "{}", "Sec-Fetch-Site", site);
      assertThat(r.status()).as(site).isEqualTo(403);
      assertThat(r.tree().path("error").asString()).isEqualTo("CROSS_ORIGIN_REQUEST");
    }
  }

  @Test
  void withoutFetchMetadataTheOriginMustBeOurs() {
    assertThat(post("/api/things", "application/json", "{}", "Origin", "https://evil.example").status()).isEqualTo(403);
    assertThat(post("/api/things", "application/json", "{}", "Origin", "null").status()).isEqualTo(403);
    assertThat(post("/api/things", "application/json", "{}", "Origin", "https://app.example.evil").status()).isEqualTo(403);
    assertThat(post("/api/things", "application/json", "{}", "Origin", "http://app.example").status()).isEqualTo(403);
    assertThat(post("/api/things", "application/json", "{}", "Origin", "https://app.example").status()).isEqualTo(201);
    assertThat(post("/api/things", "application/json", "{}", "Origin", "https://APP.example:443").status()).isEqualTo(201);
  }

  @Test
  void aRequestWithNeitherHeaderIsRefused() {
    TestResponse r = post("/api/things", "application/json", "{}");
    assertThat(r.status()).isEqualTo(403);
    assertThat(r.tree().path("error").asString()).isEqualTo("CROSS_ORIGIN_REQUEST");
  }

  @Test
  void fetchMetadataWinsOverAMatchingOrigin() {
    assertThat(post("/api/things", "application/json", "{}", "Sec-Fetch-Site", "cross-site", "Origin", "https://app.example").status())
        .isEqualTo(403);
  }

  @Test
  void bodiesMustBeJson() {
    for (String type : new String[] {"application/x-www-form-urlencoded", "text/plain", "multipart/form-data; boundary=x"}) {
      TestResponse r = post("/api/things", type, "a=b", "Sec-Fetch-Site", "same-origin");
      assertThat(r.status()).as(type).isEqualTo(415);
      assertThat(r.tree().path("error").asString()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }
    assertThat(post("/api/things", null, "a=b", "Sec-Fetch-Site", "same-origin").status()).isEqualTo(415);
    assertThat(post("/api/things", "application/merge-patch+json", "{}", "Sec-Fetch-Site", "same-origin").status()).isEqualTo(201);
    assertThat(post("/api/things", "application/json; charset=utf-8", "{}", "Sec-Fetch-Site", "none").status()).isEqualTo(201);
  }

  @Test
  void aRequestWithoutABodyNeedsNoContentType() {
    assertThat(post("/api/echo", null, null, "Sec-Fetch-Site", "same-origin").status()).isEqualTo(200);
  }

  @Test
  void safeMethodsAreNotChecked() {
    assertThat(b.get("/api/me", "Sec-Fetch-Site", "cross-site").status()).isEqualTo(200);
  }

  @Test
  void bearerRequestsAreExemptAndTheCookieIsIgnored() {
    String token = t.provider.accessToken(Map.of("sub", "prn_SVC", "scope", "orders:thing:create"));
    TestResponse r = post("/api/things", "text/plain", "x", "Authorization", "Bearer " + token, "Sec-Fetch-Site", "cross-site");
    assertThat(r.status()).isEqualTo(201);
    TestResponse me = b.get("/api/me", "Authorization", "Bearer " + token);
    assertThat(me.tree().path("principal").asString()).isEqualTo("prn_SVC");
    assertThat(me.tree().path("principalKind").asString()).isEqualTo("SERVICE");
    assertThat(me.tree().path("source").asString()).isEqualTo("BEARER");
  }

  // ---- bearer (section 7.1) ------------------------------------------------------------------

  @Test
  void aBadBearerTokenNeverFallsBackToTheCookie() {
    TestResponse r = b.get("/api/me", "Authorization", "Bearer not-a-token");
    assertThat(r.status()).isEqualTo(401);
    assertThat(b.get("/api/me", "Authorization", "Bearer " + t.provider.accessToken(Map.of("token_use", "identity"))).status())
        .isEqualTo(401);
    assertThat(b.get("/api/me", "Authorization", "Basic YTpi").status()).isEqualTo(401);
    assertThat(b.get("/api/me").status()).isEqualTo(200);
  }

  @Test
  void bearerSchemeIsCaseInsensitiveAndPermissionsComeFromTheScope() {
    String token = t.provider.accessToken(Map.of("scope", "orders:thing:view x:y"));
    TestResponse me = t.browser().get("/api/me", "Authorization", "bearer " + token);
    assertThat(me.status()).isEqualTo(200);
    assertThat(me.tree().path("permissions").toString()).isEqualTo("[\"orders:thing:view\",\"x:y\"]");
  }

  @Test
  void whenKeysCannotBeReadABearerRequestIs503() throws Exception {
    t.close();
    t = AuthApp.start(dir);
    String token = t.provider.accessToken(Map.of());
    t.provider.down(true);
    TestResponse r = t.browser().get("/api/me", "Authorization", "Bearer " + token);
    assertThat(r.status()).isEqualTo(503);
    assertThat(r.tree().path("error").asString()).isEqualTo("UNAVAILABLE");
  }

  @Test
  void bearerTokensCanBeTurnedOff() throws Exception {
    t.close();
    t = AuthApp.start(dir, x -> x.bearerTokens(false), Map.of());
    String token = t.provider.accessToken(Map.of());
    assertThat(t.browser().get("/api/me", "Authorization", "Bearer " + token).status()).isEqualTo(401);
  }

  // ---- the Context (section 7.3) -------------------------------------------------------------

  @Test
  void permissionsAreMappedFromRolesAndRequiredOnTheRoute() throws Exception {
    TestResponse me = b.get("/api/me");
    assertThat(me.tree().path("permissions").toString()).isEqualTo("[\"orders:thing:create\",\"orders:thing:view\"]");
    assertThat(post("/api/things", "application/json", "{}", "Sec-Fetch-Site", "same-origin").status()).isEqualTo(201);
    t.close();
    t = AuthApp.start(dir);
    Browser plain = t.browser();
    plain.signIn("/");
    TestResponse r = plain.send(t.client.request(Method.POST, "/api/things"), "{}", "Sec-Fetch-Site", "same-origin");
    assertThat(r.status()).isEqualTo(403);
    assertThat(r.tree().path("error").asString()).isEqualTo("FORBIDDEN");
  }

  @Test
  void theApplicationCodeIsPutInTheContext() throws Exception {
    t.close();
    t = AuthApp.start(dir, x -> x.application(new io.flowcatalyst.framework.context.ApplicationCode("orders")), Map.of());
    Browser c = t.browser();
    c.signIn("/");
    assertThat(c.get("/api/me").tree().path("application").asString()).isEqualTo("orders");
  }

  @Test
  void aSelectedClientMustBeOneThePrincipalMayActFor() throws Exception {
    t.close();
    t = AuthApp.start(dir, x -> x.clients((p, r) -> new ClientId(r.header("X-Client") == null ? "_platform" : r.header("X-Client"))), Map.of());
    t.provider.signInAs(Map.of("tier", "PARTNER", "clients", List.of("clt_1:acme", "clt_2:globex")));
    Browser c = t.browser();
    c.signIn("/");
    assertThat(c.get("/api/me", "X-Client", "acme").tree().path("client").asString()).isEqualTo("acme");
    assertThat(c.get("/api/me", "X-Client", "clt_2").tree().path("client").asString()).isEqualTo("clt_2");
    TestResponse r = c.get("/api/me", "X-Client", "initech");
    assertThat(r.status()).isEqualTo(403);
    assertThat(r.tree().path("error").asString()).isEqualTo("CLIENT_NOT_PERMITTED");
    assertThat(c.get("/api/me", "X-Client", "clt").status()).isEqualTo(403);
  }

  @Test
  void aClientServiceTokenGetsItsOnlyClientAndAnAnchorTokenThePlatform() {
    Browser anon = t.browser();
    String client = t.provider.accessToken(Map.of("tier", "CLIENT", "clients", List.of("clt_1:acme")));
    assertThat(anon.get("/api/me", "Authorization", "Bearer " + client).tree().path("client").asString()).isEqualTo("acme");
    String partner = t.provider.accessToken(Map.of("tier", "PARTNER", "clients", List.of("clt_1:acme", "clt_2:globex")));
    assertThat(anon.get("/api/me", "Authorization", "Bearer " + partner).tree().path("client").asString()).isEqualTo("_platform");
    String bare = t.provider.accessToken(Map.of("tier", "CLIENT", "clients", List.of("clt_1")));
    assertThat(anon.get("/api/me", "Authorization", "Bearer " + bare).tree().path("client").asString()).isEqualTo("_platform");
    assertThat(anon.get("/api/me", "Authorization", "Bearer " + t.provider.accessToken(Map.of())).tree().path("client").asString())
        .isEqualTo("_platform");
  }

  @Test
  void anApplicationResolverCanAddItsOwnCredentialsAndDelegateTheRest() throws Exception {
    t.close();
    t = AuthApp.start(dir, x -> x.provideContextResolver(false), Map.of());
    io.flowcatalyst.framework.http.ContextResolver platform = t.app.get(AuthContextResolver.class);
    io.flowcatalyst.framework.http.ContextResolver mine =
        request ->
            "operator-secret".equals(request.header("X-Operator"))
                ? io.flowcatalyst.framework.context.Context.of(ClientSelector.PLATFORM)
                    .withPrincipal(new io.flowcatalyst.framework.context.PrincipalId("operator"))
                    .withPermissions(java.util.Set.of("orders:*:*"))
                : platform.resolve(request);
    TestClient client = TestClient.of(t.app.get(io.flowcatalyst.framework.http.Routes.class), mine);
    assertThat(client.get("/api/me").header("X-Operator", "operator-secret").send().tree().path("principal").asString()).isEqualTo("operator");
    String token = t.provider.accessToken(Map.of("sub", "prn_SVC"));
    assertThat(client.get("/api/me").header("Authorization", "Bearer " + token).send().tree().path("principal").asString()).isEqualTo("prn_SVC");
    assertThat(client.get("/api/me").send().status()).isEqualTo(401);
    assertThat(t.app.find(io.flowcatalyst.framework.http.ContextResolver.class)).isEmpty();
  }

  @Test
  void theDefaultClientIsTheHomeClientsIdentifier() throws Exception {
    t.close();
    t = AuthApp.start(dir);
    t.provider.signInAs(Map.of("tier", "CLIENT", "client_id", "clt_9", "clients", List.of("clt_9:initech")));
    Browser c = t.browser();
    c.signIn("/");
    assertThat(c.get("/api/me").tree().path("client").asString()).isEqualTo("initech");
  }

  // ---- 401 versus 302 (section 7.4) ----------------------------------------------------------

  @Test
  void anHtmlNavigationWithoutASessionIsRedirectedToLogin() {
    Browser anon = t.browser();
    TestResponse r = anon.get("/page?a=1&b=%2F", "Sec-Fetch-Mode", "navigate", "Accept", "text/html");
    assertThat(r.status()).isEqualTo(302);
    assertThat(r.header("Location")).isEqualTo("/auth/login?returnTo=%2Fpage%3Fa%3D1%26b%3D%252F");
    TestResponse old = anon.get("/page", "Accept", "text/html,application/xhtml+xml");
    assertThat(old.status()).isEqualTo(302);
    TestResponse head = anon.send(t.client.request(Method.HEAD, "/page"), null, "Sec-Fetch-Mode", "navigate");
    assertThat(head.status()).isEqualTo(302);
  }

  @Test
  void apiCallsGet401AndAreNeverRedirected() {
    Browser anon = t.browser();
    TestResponse fetch = anon.get("/api/me", "Sec-Fetch-Mode", "cors", "Accept", "text/html");
    assertThat(fetch.status()).isEqualTo(401);
    assertThat(fetch.tree().path("error").asString()).isEqualTo("UNAUTHENTICATED");
    assertThat(anon.get("/api/me").status()).isEqualTo(401);
    assertThat(anon.get("/api/me", "Accept", "application/json").status()).isEqualTo(401);
    assertThat(anon.send(t.client.request(Method.POST, "/api/echo"), "{}", "Sec-Fetch-Mode", "navigate", "Accept", "text/html").status())
        .isEqualTo(401);
    assertThat(anon.get("/page", "Sec-Fetch-Mode", "navigate", "Authorization", "Bearer x").status()).isEqualTo(401);
  }

  @Test
  void aHandlersOwn401IsRedirectedForANavigationToo() {
    assertThat(t.browser().get("/public", "Sec-Fetch-Mode", "navigate").status()).isEqualTo(302);
    assertThat(t.browser().get("/public", "Sec-Fetch-Mode", "cors").status()).isEqualTo(401);
  }
}
