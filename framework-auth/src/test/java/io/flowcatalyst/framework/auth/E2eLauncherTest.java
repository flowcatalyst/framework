package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class E2eLauncherTest {

  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> call(E2eLauncher launcher, String method, String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + launcher.controlPort() + path)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void theStandardEndpointsAndTheApplicationsOwnAnswer() throws Exception {
    AtomicInteger sessions = new AtomicInteger();
    try (E2eLauncher launcher = E2eLauncher.start(new String[] {"4001", "0"}, "demo-web")) {
      launcher.endSessions(sessions::incrementAndGet).control("/control/echo", q -> q.getOrDefault("word", "") + "!").control("/control/boom", q -> {
        throw new IllegalStateException("no good");
      });
      launcher.listen();

      assertThat(call(launcher, "GET", "/control/ready").body()).isEqualTo("ready");
      assertThat(call(launcher, "POST", "/control/sign-in-as?roles=a:b,c:d&name=Grace").statusCode()).isEqualTo(200);
      assertThat(call(launcher, "POST", "/control/sign-in-as?roles=&client=acme&sub=prn_g").statusCode()).isEqualTo(200);
      assertThat(call(launcher, "POST", "/control/provider-down?value=true").statusCode()).isEqualTo(200);
      assertThat(call(launcher, "POST", "/control/provider-down?value=false").statusCode()).isEqualTo(200);
      assertThat(call(launcher, "POST", "/control/authorize-error?value=access_denied").statusCode()).isEqualTo(200);
      assertThat(call(launcher, "POST", "/control/end-sessions").body()).isEqualTo("ok");
      assertThat(sessions).hasValue(1);
      assertThat(call(launcher, "POST", "/control/echo?word=h%C3%A9llo").body()).isEqualTo("héllo!");
      HttpResponse<String> boom = call(launcher, "POST", "/control/boom");
      assertThat(boom.statusCode()).isEqualTo(500);
      assertThat(boom.body()).isEqualTo("no good");
    }
  }

  @Test
  void theEnvironmentNamesThePortsTheProviderAndTheOrigin() {
    try (E2eLauncher launcher = E2eLauncher.start(new String[] {"4001", "4002", "http://127.0.0.1:5175"}, "demo-web")) {
      assertThat(launcher.environment())
          .containsEntry("APP_ENV", "test")
          .containsEntry("HTTP_HOST", "127.0.0.1")
          .containsEntry("HTTP_PORT", "4001")
          .containsEntry("AUTH_CLIENT_ID", "demo-web")
          .containsEntry("AUTH_CLIENT_SECRET", "")
          .containsEntry("AUTH_PUBLIC_BASE_URL", "http://127.0.0.1:5175")
          .containsEntry("AUTH_ISSUER", launcher.provider().issuer().toString());
      assertThat(launcher.appPort()).isEqualTo(4001);
      assertThat(launcher.controlPort()).isEqualTo(4002);
    }
  }

  @Test
  void aSecretInTheDevelopersLocalFileDoesNotReachTheKitsPublicClient(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
    java.nio.file.Files.writeString(dir.resolve("secrets.local.yaml"), "auth:\n  client-secret: a-real-secret\n");
    try (E2eLauncher launcher = E2eLauncher.start(new String[] {"4001", "4002"}, "demo-web")) {
      java.util.Map<String, String> env = launcher.environment();
      env.put("AUTH_PUBLIC_BASE_URL", "https://app.example");
      try (AuthApp t = AuthApp.start(dir, b -> {}, java.util.Map.of("AUTH_CLIENT_SECRET", env.get("AUTH_CLIENT_SECRET")))) {
        Browser b = t.browser();
        assertThat(b.signIn("/").status()).isEqualTo(302);
        assertThat(t.provider.lastTokenAuthorization()).isEmpty();
      }
    }
  }

  @Test
  void aPersonIsPlatformWideOrOneClientsUser() {
    assertThat(E2eLauncher.person("x:y", "Ada")).containsEntry("name", "Ada").containsEntry("email", "ada@example.test").doesNotContainKeys("sub", "clients");
    assertThat(E2eLauncher.person("", "Ada")).containsEntry("roles", java.util.List.of());
    assertThat(E2eLauncher.person("x:y", "Ada", "acme", "prn_ada"))
        .containsEntry("sub", "prn_ada")
        .containsEntry("tier", "CLIENT")
        .containsEntry("clients", java.util.List.of("clt_acme:acme"));
  }

  @Test
  void aControlPathMustLiveUnderControl() {
    try (E2eLauncher launcher = E2eLauncher.start(new String[] {"4001", "4002"}, "demo-web")) {
      assertThatThrownBy(() -> launcher.control("/other", q -> "x")).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> E2eLauncher.start(new String[] {"4001"}, "demo-web")).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void ownedResourcesCloseLastFirstOnce() {
    StringBuilder order = new StringBuilder();
    E2eLauncher launcher = E2eLauncher.start(new String[] {"4001", "4002"}, "demo-web");
    launcher.own(() -> order.append("first,"));
    launcher.own(() -> order.append("second,"));
    launcher.close();
    launcher.close();
    assertThat(order).hasToString("second,first,");
  }
}
