package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.http.jetty.JettyHttpModule;
import java.net.CookieManager;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole sign-in over real sockets: Jetty serves the application, the test provider is a real
 * server, and a JDK client with a cookie manager plays the browser, following each redirect.
 */
class AuthOverHttpTest {

  @TempDir Path dir;

  @Test
  void signInUseTheSessionAndSignOutOverHttp() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    String origin = "http://127.0.0.1:" + port;
    MutableClock clock = new MutableClock(Instant.now());
    try (TestOidcProvider provider = TestOidcProvider.start(clock)) {
      provider.client("app-client", "", origin + "/auth/callback");
      provider.signInAs(Map.of("roles", List.of("orders:clerk")));
      try (App app =
          App.builder()
              .configDirectory(dir)
              .environment(
                  Map.of(
                      "APP_ENV", "test",
                      "HTTP_HOST", "127.0.0.1",
                      "HTTP_PORT", Integer.toString(port),
                      "AUTH_ISSUER", provider.issuer().toString(),
                      "AUTH_CLIENT_ID", "app-client",
                      "AUTH_PUBLIC_BASE_URL", origin))
              .instantSource(clock)
              .module(new JettyHttpModule())
              .module(
                  AuthModule.builder()
                      .sessions(lookup -> MemorySessionStore.create(clock))
                      .permissions(PermissionMapper.byRole(Map.of("orders:clerk", java.util.Set.of("orders:thing:create"))))
                      .build())
              .module(AuthApp.testRoutes())
              .start()) {
        HttpClient browser =
            HttpClient.newBuilder().cookieHandler(new CookieManager()).followRedirects(HttpClient.Redirect.NEVER).build();

        HttpResponse<String> page = get(browser, origin + "/page", "Accept", "text/html");
        assertThat(page.statusCode()).isEqualTo(302);
        String login = page.headers().firstValue("Location").orElseThrow();
        assertThat(login).isEqualTo("/auth/login?returnTo=%2Fpage");

        HttpResponse<String> toProvider = get(browser, origin + login);
        assertThat(toProvider.statusCode()).isEqualTo(302);
        HttpResponse<String> back = get(browser, toProvider.headers().firstValue("Location").orElseThrow());
        assertThat(back.statusCode()).isEqualTo(302);
        HttpResponse<String> callback = get(browser, back.headers().firstValue("Location").orElseThrow());
        assertThat(callback.statusCode()).as(callback.body()).isEqualTo(302);
        assertThat(callback.headers().allValues("Set-Cookie")).hasSize(2);
        assertThat(callback.headers().firstValue("Location")).contains("/page");

        assertThat(get(browser, origin + "/page", "Accept", "text/html").statusCode()).isEqualTo(200);
        HttpResponse<String> me = get(browser, origin + "/api/me");
        assertThat(me.body()).contains("\"principal\":\"prn_USER1\"").contains("orders:thing:create");

        HttpResponse<String> crossSite =
            browser.send(
                HttpRequest.newBuilder(URI.create(origin + "/api/things")).header("Content-Type", "application/json")
                    .header("Origin", "https://evil.example").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(crossSite.statusCode()).isEqualTo(403);
        HttpResponse<String> sameOrigin =
            browser.send(
                HttpRequest.newBuilder(URI.create(origin + "/api/things")).header("Content-Type", "application/json")
                    .header("Origin", origin).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(sameOrigin.statusCode()).isEqualTo(201);

        HttpResponse<String> logout =
            browser.send(
                HttpRequest.newBuilder(URI.create(origin + "/auth/logout")).header("Origin", origin)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(logout.statusCode()).isEqualTo(200);
        assertThat(get(browser, origin + "/api/me").statusCode()).isEqualTo(401);
      }
    }
  }

  private static HttpResponse<String> get(HttpClient client, String url, String... headers) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
    for (int i = 0; i < headers.length; i += 2) {
      b.header(headers[i], headers[i + 1]);
    }
    return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }
}
