package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.httpclient.Auth;
import io.flowcatalyst.framework.httpclient.ServiceClient;
import io.flowcatalyst.framework.httpclient.ServiceOptions;
import io.flowcatalyst.framework.httpclient.module.HttpClientModule;
import io.flowcatalyst.framework.httpclient.module.ServiceClients;
import java.nio.file.Path;
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
}
