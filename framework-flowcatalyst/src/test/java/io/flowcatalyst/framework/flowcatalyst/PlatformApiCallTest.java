package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.httpclient.Auth;
import io.flowcatalyst.framework.httpclient.ServiceClient;
import io.flowcatalyst.framework.httpclient.ServiceOptions;
import io.flowcatalyst.framework.httpclient.module.HttpClientModule;
import io.flowcatalyst.framework.httpclient.module.ServiceClients;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Calling a platform API with the HTTP client and the application's platform token. */
class PlatformApiCallTest {

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

  @Test
  void aRegisteredClientSendsThePlatformToken() {
    try (App app =
        App.builder()
            .configDirectory(dir)
            .environment(
                Map.of(
                    "FLOWCATALYST_BASE_URL", platform.baseUrl(),
                    "FLOWCATALYST_APPLICATION", "orders",
                    "FLOWCATALYST_CLIENT_ID", FakePlatform.CLIENT_ID,
                    "FLOWCATALYST_CLIENT_SECRET", FakePlatform.CLIENT_SECRET))
            .module(new FlowCatalystModule())
            .module(new HttpClientModule())
            .start()) {
      PlatformClient platformClient = app.get(PlatformClient.class);
      ServiceClient api =
          app.get(ServiceClients.class)
              .register(
                  ServiceOptions.builder("platform", app.config().get(FlowCatalystModule.BASE_URL))
                      .auth(Auth.bearer(platformClient::accessToken, platformClient::clearToken))
                      .build());

      int status = api.get("/api/event-types").send().status();

      assertThat(status).isEqualTo(404); // the fake serves no such API; the request is what counts
      FakePlatform.Recorded call =
          platform.requests().stream().filter(r -> r.path().equals("/api/event-types")).findFirst().orElseThrow();
      assertThat(call.headers().get("authorization")).isEqualTo("Bearer " + platformClient.accessToken());
    }
  }

  private App start() {
    return App.builder()
        .configDirectory(dir)
        .environment(
            Map.of(
                "FLOWCATALYST_BASE_URL", platform.baseUrl(),
                "FLOWCATALYST_APPLICATION", "orders",
                "FLOWCATALYST_CLIENT_ID", FakePlatform.CLIENT_ID,
                "FLOWCATALYST_CLIENT_SECRET", FakePlatform.CLIENT_SECRET))
        .module(new FlowCatalystModule())
        .start();
  }

  @Test
  void theBaseUrlIsTheConfiguredOne() {
    try (App app = start()) {
      assertThat(app.get(PlatformClient.class).baseUrl()).isEqualTo(java.net.URI.create(platform.baseUrl()));
    }
  }

  @Test
  void theSdkClientUsesThePlatformTokenAndAfterA401FetchesANewOneAndRetriesOnce() {
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    platform.route(
        "GET",
        "/api/roles",
        r ->
            calls.getAndIncrement() == 0
                ? new FakePlatform.Reply(401, "{\"error\":\"token expired\"}")
                : new FakePlatform.Reply(200, PlatformAdminTest.roles()));
    try (App app = start()) {
      PlatformClient platformClient = app.get(PlatformClient.class);
      String before = platformClient.accessToken(); // a cached token, as after any earlier call
      platform.clearRequests();

      var roles = platformClient.sdk().roles().list();

      assertThat(roles.getRoles()).isNotEmpty();
      List<FakePlatform.Recorded> sent =
          platform.requests().stream().filter(r -> r.path().equals("/api/roles")).toList();
      assertThat(sent).hasSize(2);
      // the first call carried the cached token; the second a newly fetched one
      assertThat(sent.get(0).headers().get("authorization")).isEqualTo("Bearer " + before);
      // (the fake numbers tokens from its request count, which clearRequests reset, so the text can repeat)
      assertThat(platform.tokenRequests()).isEqualTo(1);
      assertThat(sent.get(1).headers().get("authorization")).isEqualTo("Bearer " + platformClient.accessToken());
    }
  }

  @Test
  void theSdkClientRetriesATransientFailureInsideTheCall() {
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    platform.route(
        "GET",
        "/api/roles",
        r ->
            calls.getAndIncrement() == 0
                ? new FakePlatform.Reply(503, "{\"error\":\"busy\"}")
                : new FakePlatform.Reply(200, PlatformAdminTest.roles()));
    try (App app = start()) {
      assertThat(app.get(PlatformClient.class).sdk().roles().list().getRoles()).isNotEmpty();
      assertThat(calls).hasValue(2);
    }
  }

  @Test
  void aDisabledModuleHasNoBaseUrlOrSdkClient() {
    try (App app =
        App.builder()
            .configDirectory(dir)
            .environment(Map.of("FLOWCATALYST_ENABLED", "false"))
            .module(new FlowCatalystModule())
            .start()) {
      PlatformClient platformClient = app.get(PlatformClient.class);
      org.assertj.core.api.Assertions.assertThatIllegalStateException().isThrownBy(platformClient::baseUrl);
      org.assertj.core.api.Assertions.assertThatIllegalStateException().isThrownBy(platformClient::sdk);
    }
  }
}
