package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Criticality;
import io.flowcatalyst.framework.core.HealthPolicy;
import io.flowcatalyst.framework.core.HealthReport;
import io.flowcatalyst.framework.core.HealthResult;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlowCatalystModuleTest {

  @TempDir Path dir;

  private FakePlatform platform;

  @BeforeEach
  void startPlatform() {
    platform = FakePlatform.start();
  }

  @AfterEach
  void stopPlatform() {
    platform.close();
  }

  private Map<String, String> env(String baseUrl) {
    Map<String, String> env = new HashMap<>();
    env.put("FLOWCATALYST_BASE_URL", baseUrl);
    env.put("FLOWCATALYST_APPLICATION", "orders");
    env.put("FLOWCATALYST_CLIENT_ID", FakePlatform.CLIENT_ID);
    env.put("FLOWCATALYST_CLIENT_SECRET", FakePlatform.CLIENT_SECRET);
    return env;
  }

  private App start(Map<String, String> env) {
    return start(env, HealthPolicy.defaults());
  }

  private App start(Map<String, String> env, HealthPolicy health) {
    return start(env, health, FlowCatalystOptions.defaults());
  }

  private App start(Map<String, String> env, HealthPolicy health, FlowCatalystOptions options) {
    return App.builder()
        .configDirectory(dir)
        .environment(env)
        .health(health)
        .module(new FlowCatalystModule(options))
        .start();
  }

  private StartupException failure(Map<String, String> env) {
    Throwable t = catchThrowable(() -> start(env).close());
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  /** The first-token attempt runs in the background; wait for it so counts are stable. */
  private void awaitFirstTokenAttempt() throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (platform.tokenRequests() < 1 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(platform.tokenRequests()).isGreaterThanOrEqualTo(1);
    Thread.sleep(50);
  }

  @Test
  void providesTheClientAndTheHealthCheckIsUp() {
    try (App app = start(env(platform.baseUrl()))) {
      PlatformClient client = app.get(PlatformClient.class);
      assertThat(client.isEnabled()).isTrue();
      assertThat(client.application().value()).isEqualTo("orders");
      assertThat(app.health().check().results()).containsEntry("flowcatalyst", new HealthResult.Up());
    }
  }

  @Test
  void theTokenRequestIsClientCredentialsWithTheSecretInTheBody() throws InterruptedException {
    try (App app = start(env(platform.baseUrl()))) {
      awaitFirstTokenAttempt();
      FakePlatform.Recorded token = platform.requests().getFirst();
      assertThat(token.method()).isEqualTo("POST");
      assertThat(token.path()).isEqualTo("/oauth/token");
      assertThat(token.body())
          .contains("grant_type=client_credentials")
          .contains("client_id=" + FakePlatform.CLIENT_ID)
          .contains("client_secret=" + FakePlatform.CLIENT_SECRET);
      assertThat(app.get(PlatformClient.class).isEnabled()).isTrue();
    }
  }

  @Test
  void theTokenIsCachedUntilCleared() throws InterruptedException {
    try (App app = start(env(platform.baseUrl()))) {
      awaitFirstTokenAttempt();
      PlatformClient client = app.get(PlatformClient.class);
      String first = client.accessToken();
      assertThat(client.accessToken()).isEqualTo(first);
      assertThat(platform.tokenRequests()).isEqualTo(1);
      client.clearToken();
      assertThat(client.accessToken()).isNotEqualTo(first);
      assertThat(platform.tokenRequests()).isEqualTo(2);
    }
  }

  @Test
  void refusedCredentialsDoNotStopTheApplicationAndHealthIsDown() {
    Map<String, String> env = env(platform.baseUrl());
    env.put("FLOWCATALYST_CLIENT_SECRET", "wrong");
    try (App app = start(env)) {
      HealthResult result = app.health().check().results().get("flowcatalyst");
      assertThat(result).isInstanceOf(HealthResult.Down.class);
      assertThat(((HealthResult.Down) result).reason()).contains("credentials").doesNotContain("wrong");
    }
  }

  @Test
  void anUnreachablePlatformDoesNotStopTheApplicationAndHealthIsDown() throws IOException {
    int closedPort;
    try (ServerSocket s = new ServerSocket(0)) {
      closedPort = s.getLocalPort();
    }
    try (App app = start(env("http://127.0.0.1:" + closedPort))) {
      HealthReport report = app.health().check();
      assertThat(report.results().get("flowcatalyst")).isInstanceOf(HealthResult.Down.class);
      // Informational by default: the platform being down does not make the application not ready.
      assertThat(report.criticality("flowcatalyst")).isEqualTo(Criticality.INFORMATIONAL);
      assertThat(report.isReady()).isTrue();
    }
  }

  @Test
  void thePlatformCheckCanBeMadeCriticalByTheHealthPolicy() throws IOException {
    int closedPort;
    try (ServerSocket s = new ServerSocket(0)) {
      closedPort = s.getLocalPort();
    }
    Map<String, String> env = env("http://127.0.0.1:" + closedPort);
    try (App app = start(env, HealthPolicy.defaults().withCritical("flowcatalyst"))) {
      HealthReport report = app.health().check();
      assertThat(report.criticality("flowcatalyst")).isEqualTo(Criticality.CRITICAL);
      assertThat(report.isReady()).isFalse();
    }
  }

  @Test
  void healthRecoversWhenThePlatformDoes() throws InterruptedException {
    platform.failTokens(503);
    try (App app = start(env(platform.baseUrl()))) {
      awaitFirstTokenAttempt();
      HealthResult down = app.health().check().results().get("flowcatalyst");
      assertThat(down).isInstanceOf(HealthResult.Down.class);
      assertThat(((HealthResult.Down) down).reason()).contains("503");
      platform.failTokens(0);
      assertThat(app.health().check().results().get("flowcatalyst")).isEqualTo(new HealthResult.Up());
    }
  }

  @Test
  void theServicePrincipalIsTheSubOfTheClientCredentialsToken() {
    try (App app = start(env(platform.baseUrl()))) {
      PlatformClient client = app.get(PlatformClient.class);
      assertThat(client.servicePrincipal()).isEqualTo(new PrincipalId(FakePlatform.SERVICE_PRINCIPAL));
    }
  }

  @Test
  void aUserTokenIsNoServicePrincipal() {
    platform.tokenPrincipal("prn_0HUMANUSER001", "USER");
    try (App app = start(env(platform.baseUrl()))) {
      Throwable t = catchThrowable(app.get(PlatformClient.class)::servicePrincipal);
      assertThat(t).isInstanceOf(PlatformException.class).hasMessageContaining("type claim is not SERVICE");
    }
  }

  @Test
  void withoutReceiverHandlersTheHealthCheckDoesNotAskForTheServicePrincipal() {
    // a token-only application (an ingestor) keeps its health whatever kind its token is
    platform.tokenPrincipal("prn_0HUMANUSER001", "USER");
    try (App app = start(env(platform.baseUrl()))) {
      assertThat(app.health().check().results().get("flowcatalyst")).isEqualTo(new HealthResult.Up());
    }
  }

  @Test
  void disabledNeedsNoSettingsAndAddsNoHealthCheck() {
    try (App app = start(Map.of(), HealthPolicy.defaults(), FlowCatalystOptions.disabled())) {
      PlatformClient client = app.get(PlatformClient.class);
      assertThat(client.isEnabled()).isFalse();
      assertThat(app.health().check().results()).doesNotContainKey("flowcatalyst");
      assertThatIllegalStateException().isThrownBy(client::application).withMessageContaining("disabled");
      assertThatIllegalStateException().isThrownBy(client::accessToken).withMessageContaining("disabled");
      assertThatIllegalStateException().isThrownBy(client::servicePrincipal).withMessageContaining("disabled");
    }
  }

  @Test
  void enabledWithoutSettingsFailsNamingEveryMissingKey() {
    StartupException e = failure(Map.of());
    assertThat(e.problems())
        .extracting(Problem::key)
        .containsExactlyInAnyOrder(
            "flowcatalyst.base-url",
            "flowcatalyst.application",
            "flowcatalyst.client-id",
            "flowcatalyst.client-secret");
    assertThat(e.problems()).extracting(Problem::module).containsOnly("flowcatalyst");
  }

  @Test
  void malformedSettingsFailWithoutLeakingTheSecret() {
    Map<String, String> env = env("ftp://example.com");
    env.put("FLOWCATALYST_APPLICATION", "Orders!");
    env.put("FLOWCATALYST_CLIENT_SECRET", "s3cret-value");
    StartupException e = failure(env);
    assertThat(e.problems())
        .extracting(Problem::key)
        .containsExactlyInAnyOrder("flowcatalyst.base-url", "flowcatalyst.application");
    assertThat(e.getMessage()).doesNotContain("s3cret-value");
  }
}
