package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.auth.Auth;
import io.flowcatalyst.framework.auth.AuthModule;
import io.flowcatalyst.framework.auth.Authenticated;
import io.flowcatalyst.framework.auth.CookieKeys;
import io.flowcatalyst.framework.auth.FieldOptions;
import io.flowcatalyst.framework.auth.FieldPermissions;
import io.flowcatalyst.framework.auth.MemorySessionStore;
import io.flowcatalyst.framework.auth.MutableClock;
import io.flowcatalyst.framework.auth.TestFieldDevice;
import io.flowcatalyst.framework.auth.TestOidcProvider;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.RouteOptions;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract section 15.6: the online check before a high-value action. The route sits behind the
 * real field-worker authentication; the platform is the fake.
 */
class RequireLiveFieldTest {

  static final String ORIGIN = "https://app.example";
  static final String INTROSPECT = "/api/field-tokens/introspect";
  static final String ACTIVE =
      "{\"active\":true,\"sub\":\"fwu_0TESTWORKER\",\"clientId\":\"clt_0TESTCLIENT\",\"workerCode\":\"W001\","
          + "\"deviceId\":\"fwd_0TESTDEVICE\",\"appCode\":\"orders\",\"amr\":[\"pin\"],\"authTime\":\"2026-11-03T12:00:00Z\","
          + "\"exp\":\"2026-11-03T12:10:00Z\"}";
  static final String INACTIVE = "{\"active\":false}";

  @TempDir Path dir;

  MutableClock clock;
  FakePlatform platform;
  TestOidcProvider provider;
  TestFieldDevice device;
  App app;
  TestClient client;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2026-11-03T12:00:00Z"));
    platform = FakePlatform.start();
    provider = TestOidcProvider.start(clock);
    platform.respondTo("POST", INTROSPECT, 200, ACTIVE);
    device = TestFieldDevice.create(clock);
  }

  @AfterEach
  void tearDown() {
    if (app != null) {
      app.close();
    }
    provider.close();
    platform.close();
  }

  /** Starts the application with a gate route that needs a live token for {@code maxAge}. */
  void start(Duration maxAge) {
    provider.client("app-client", "", ORIGIN + "/auth/callback");
    Map<String, String> env = new HashMap<>(Receiving.env(platform));
    env.put("APP_ENV", "test");
    env.put("AUTH_ISSUER", provider.issuer().toString());
    env.put("AUTH_CLIENT_ID", "app-client");
    env.put("AUTH_PUBLIC_BASE_URL", ORIGIN);
    env.put("AUTH_COOKIE_KEYS", CookieKeys.generate("k1"));
    Module gate =
        new Module() {
          @Override
          public String id() {
            return "gate";
          }

          @Override
          public void boot(App a) {
            Routes routes = a.get(Routes.class).forModule(id());
            routes.get("/api/gate", request -> Response.ok(Map.of("opened", true)), RouteOptions.requiresContext().with(RequireLiveField.of(a, maxAge)));
            routes.get(
                "/api/gate-field-only",
                request -> Response.ok(Map.of("opened", true)),
                RouteOptions.requiresContext().with(Auth.requireSource(Authenticated.Source.FIELD)).with(RequireLiveField.of(a, maxAge)));
          }
        };
    AppBuilder b = App.builder().configDirectory(dir).environment(env).instantSource(clock);
    b.module(Receiving.routes())
        .module(
            AuthModule.builder()
                .sessions(lookup -> MemorySessionStore.create(clock))
                .application(new ApplicationCode("orders"))
                .fieldWorkers(FieldOptions.builder().permissions(FieldPermissions.all("orders:gate:open")).build())
                .build())
        .module(gate)
        .module(new FlowCatalystModule());
    app = b.start();
    client = TestClient.of(app.get(Routes.class), app.get(ContextResolver.class));
  }

  private TestResponse gate(String path, Map<String, Object> claims) {
    String token = provider.fieldToken("orders", device.jkt(), claims);
    // the way a device does it: no nonce first, then the one the answer offers
    TestResponse first = client.get(path).header("Authorization", "DPoP " + token).header("DPoP", device.proof("GET", ORIGIN + path, null, token)).send();
    String nonce = TestFieldDevice.nonceFrom(first.header("DPoP-Nonce"));
    return client.get(path).header("Authorization", "DPoP " + token).header("DPoP", device.proof("GET", ORIGIN + path, nonce, token)).send();
  }

  private List<FakePlatform.Recorded> introspections() {
    return platform.requests().stream().filter(r -> r.path().equals(INTROSPECT)).toList();
  }

  @Test
  void anActiveTokenGoesThroughAndThePlatformIsAskedWithTheTokenAsTheServiceAccount() {
    start(Duration.ZERO);
    String token = provider.fieldToken("orders", device.jkt(), Map.of());
    TestResponse first = client.get("/api/gate").header("Authorization", "DPoP " + token).header("DPoP", device.proof("GET", ORIGIN + "/api/gate", null, token)).send();
    String nonce = TestFieldDevice.nonceFrom(first.header("DPoP-Nonce"));
    TestResponse r = client.get("/api/gate").header("Authorization", "DPoP " + token).header("DPoP", device.proof("GET", ORIGIN + "/api/gate", nonce, token)).send();
    assertThat(r.status()).isEqualTo(200);
    assertThat(introspections()).hasSize(1);
    FakePlatform.Recorded call = introspections().getFirst();
    assertThat(call.method()).isEqualTo("POST");
    assertThat(call.json().path("token").asString()).isEqualTo(token);
    assertThat(call.headers().get("authorization")).startsWith("Bearer ").doesNotContain(token);
    assertThat(platform.issued(call.headers().get("authorization").substring(7))).isTrue();
  }

  @Test
  void anInactiveTokenIs401WithTheDpopChallenge() {
    start(Duration.ZERO);
    platform.respondTo("POST", INTROSPECT, 200, INACTIVE);
    TestResponse r = gate("/api/gate", Map.of());
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.tree().path("error").asString()).isEqualTo("UNAUTHENTICATED");
    assertThat(r.header("WWW-Authenticate")).isEqualTo("DPoP error=\"invalid_token\"");
    assertThat(r.header("DPoP-Nonce")).isNotBlank();
  }

  @Test
  void whenThePlatformCannotBeAskedTheActionIsRefusedWith503() {
    start(Duration.ZERO);
    platform.respondTo("POST", INTROSPECT, 500, "{\"error\":\"boom\"}");
    TestResponse serverError = gate("/api/gate", Map.of());
    assertThat(serverError.status()).isEqualTo(503);
    assertThat(serverError.tree().path("error").asString()).isEqualTo("UNAVAILABLE");

    // the service account lacks platform:iam:field-token:introspect
    platform.respondTo("POST", INTROSPECT, 403, "{\"error\":\"FORBIDDEN\"}");
    assertThat(gate("/api/gate", Map.of()).status()).isEqualTo(503);

    // a nonsense answer is not an answer
    platform.respondTo("POST", INTROSPECT, 200, "{\"nothing\":true}");
    assertThat(gate("/api/gate", Map.of()).status()).isEqualTo(503);
  }

  @Test
  void anActiveAnswerIsRememberedPerSessionForMaxAgeAndAnInactiveOneIsNot() {
    start(Duration.ofSeconds(30));
    assertThat(gate("/api/gate", Map.of()).status()).isEqualTo(200);
    assertThat(gate("/api/gate", Map.of()).status()).isEqualTo(200);
    assertThat(introspections()).hasSize(1);

    // another login family is another question
    assertThat(gate("/api/gate", Map.of("sid", "fws_OTHER")).status()).isEqualTo(200);
    assertThat(introspections()).hasSize(2);

    // after maxAge the platform is asked again, and a revocation shows
    clock.advance(Duration.ofSeconds(31));
    platform.respondTo("POST", INTROSPECT, 200, INACTIVE);
    assertThat(gate("/api/gate", Map.of()).status()).isEqualTo(401);
    assertThat(introspections()).hasSize(3);
    assertThat(gate("/api/gate", Map.of()).status()).isEqualTo(401);
    assertThat(introspections()).hasSize(4);
  }

  @Test
  void withMaxAgeZeroEveryRequestAsksThePlatform() {
    start(Duration.ZERO);
    assertThat(gate("/api/gate", Map.of()).status()).isEqualTo(200);
    assertThat(gate("/api/gate", Map.of()).status()).isEqualTo(200);
    assertThat(introspections()).hasSize(2);
  }

  @Test
  void theCacheNeverOutlivesTheTokenItself() {
    start(Duration.ofHours(1));
    assertThat(gate("/api/gate", Map.of()).status()).isEqualTo(200);
    // the token lived ten minutes; the family's next token is asked about again once that one is gone
    clock.advance(Duration.ofMinutes(11));
    assertThat(gate("/api/gate", Map.of()).status()).isEqualTo(200);
    assertThat(introspections()).hasSize(2);
  }

  @Test
  void anAnswerAboutAnotherWorkerIsNotAnAnswerForThisRequest() {
    start(Duration.ZERO);
    platform.respondTo("POST", INTROSPECT, 200, ACTIVE.replace("fwu_0TESTWORKER", "fwu_SOMEONEELSE"));
    assertThat(gate("/api/gate", Map.of()).status()).isEqualTo(401);
  }

  @Test
  void aRequestThatIsNotAFieldWorkersIsRefusedAndNeverAsksThePlatform() {
    start(Duration.ZERO);
    String api = provider.accessToken(Map.of("scope", ""));
    TestResponse r = client.get("/api/gate").header("Authorization", "Bearer " + api).send();
    assertThat(r.status()).isEqualTo(403);
    assertThat(r.tree().path("error").asString()).isEqualTo("FORBIDDEN");
    assertThat(introspections()).isEmpty();
    // requireSource refuses it before the platform check
    assertThat(client.get("/api/gate-field-only").header("Authorization", "Bearer " + api).send().status()).isEqualTo(403);
    assertThat(introspections()).isEmpty();
  }

  @Test
  void aNegativeMaxAgeIsRefused() {
    start(Duration.ZERO);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> RequireLiveField.of(app, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
  }
}
