package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.secrets.CachePolicy;
import io.flowcatalyst.framework.secrets.SecretNotFoundException;
import io.flowcatalyst.framework.secrets.SecretRef;
import io.flowcatalyst.framework.secrets.SecretResolver;
import io.flowcatalyst.framework.secrets.SecretResult;
import io.flowcatalyst.framework.secrets.SecretStoreException;
import io.flowcatalyst.framework.secrets.SecretsModule;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code platform} secrets driver (platform-config contract section 3) against the fake platform. */
class PlatformSecretsTest {

  static final String VALUE = "s3cr3t-VALUE-do-not-log";
  static final ClientId ACME = new ClientId("clt_0ACME");
  static final String PATH = "/api/config/orders/secrets/ftp-password/secret";

  @TempDir Path dir;

  FakePlatform platform;
  App app;

  @BeforeEach
  void start() {
    platform = FakePlatform.start();
    platform.respondTo("GET", PATH, 200, revealed(VALUE));
    app = starting(PlatformSecrets.store(), Map.of());
  }

  @AfterEach
  void stop() {
    app.close();
    platform.close();
  }

  App starting(PlatformSecrets.Store store, Map<String, String> more) {
    Map<String, String> env = new java.util.HashMap<>(Receiving.env(platform));
    env.putAll(more);
    return App.builder()
        .configDirectory(dir)
        .environment(env)
        .module(Receiving.routes())
        .module(new FlowCatalystModule())
        .module(SecretsModule.builder().store("platform", store).build())
        .start();
  }

  static String revealed(String value) {
    return "{\"applicationCode\":\"orders\",\"section\":\"secrets\",\"property\":\"ftp-password\",\"clientId\":\"clt_0ACME\",\"value\":\""
        + value
        + "\",\"updatedAt\":\"2026-10-23T10:00:00Z\"}";
  }

  SecretResolver resolver() {
    return app.get(SecretResolver.class);
  }

  List<FakePlatform.Recorded> reveals() {
    return platform.requests().stream().filter(r -> r.path().startsWith("/api/config/")).toList();
  }

  @Test
  void aSecretIsRevealedAtTheClientsScopeOfTheConfiguredSection() {
    Secret secret = resolver().require(ACME, SecretRef.of("ftp-password"));

    assertThat(secret.reveal()).isEqualTo(VALUE);
    assertThat(reveals()).hasSize(1);
    FakePlatform.Recorded call = reveals().getFirst();
    assertThat(call.method()).isEqualTo("GET");
    assertThat(call.path()).isEqualTo(PATH);
    assertThat(call.query()).isEqualTo("clientId=clt_0ACME");
    assertThat(call.headers().get("authorization")).startsWith("Bearer ");
  }

  @Test
  void theSectionOptionChangesThePath() {
    app.close();
    platform.respondTo("GET", "/api/config/orders/ftp/ftp-password/secret", 200, revealed("other"));
    app = starting(PlatformSecrets.store().section("ftp"), Map.of());

    assertThat(resolver().require(ACME, SecretRef.of("ftp-password")).reveal()).isEqualTo("other");
    assertThat(reveals().getFirst().path()).isEqualTo("/api/config/orders/ftp/ftp-password/secret");
    assertThatThrownBy(() -> PlatformSecrets.store().section("Bad Section")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aMissingSecretIsNotFoundAndNotCachedSoALaterOneIsSeen() {
    platform.respondTo("GET", PATH, 404, "{\"error\":\"NOT_FOUND\"}");
    assertThat(resolver().resolve(ACME, SecretRef.of("ftp-password"))).isInstanceOf(SecretResult.NotFound.class);
    assertThatThrownBy(() -> resolver().require(ACME, SecretRef.of("ftp-password"))).isInstanceOf(SecretNotFoundException.class);

    platform.respondTo("GET", PATH, 200, revealed(VALUE));
    assertThat(resolver().require(ACME, SecretRef.of("ftp-password")).reveal()).isEqualTo(VALUE);
  }

  @Test
  void aPlainPropertyIsRefused() {
    platform.respondTo("GET", PATH, 400, "{\"error\":\"NOT_A_SECRET\",\"message\":\"the property is plain\"}");

    SecretResult result = resolver().resolve(ACME, SecretRef.of("ftp-password"));

    assertThat(result).isEqualTo(new SecretResult.Refused("the platform property is not a secret"));
  }

  @Test
  void aClientIdentifierIsPassedThroughForThePlatformToResolve() {
    Secret secret = resolver().require(new ClientId("bevco"), SecretRef.of("ftp-password"));

    assertThat(secret.reveal()).isEqualTo(VALUE);
    assertThat(reveals().getFirst().query()).isEqualTo("clientId=bevco");
  }

  @Test
  void aClientThatIsNeitherAPlatformIdNorAnIdentifierIsRefusedWithoutACall() {
    for (String client : List.of("clt_", "clt_a.b", "a_b", "x..y", "acme.", "-acme", "acme-")) {
      SecretResult result = resolver().resolve(new ClientId(client), SecretRef.of("ftp-password"));
      assertThat(result).as(client).isInstanceOf(SecretResult.Refused.class);
    }
    assertThat(reveals()).isEmpty();
  }

  @Test
  void aForbiddenAnswerNamesTheApplicationAndNoValue() {
    platform.respondTo("GET", PATH, 403, "{\"error\":\"FORBIDDEN\",\"message\":\"" + VALUE + "\"}");

    Throwable t = catchThrowable(() -> resolver().resolve(ACME, SecretRef.of("ftp-password")));

    assertThat(t).isInstanceOf(SecretStoreException.class).hasMessageContaining("orders").hasMessageContaining("not authorised to reveal");
    assertThat(t.getMessage()).doesNotContain(VALUE);
  }

  @Test
  void serverErrorsNetworkErrorsAndUndecodableBodiesAreStoreExceptionsWithoutTheValue() {
    platform.respondTo("GET", PATH, 500, "{\"error\":\"BOOM\",\"message\":\"" + VALUE + "\"}");
    Throwable server = catchThrowable(() -> resolver().resolve(ACME, SecretRef.of("ftp-password")));
    assertThat(server).isInstanceOf(SecretStoreException.class);
    assertThat(server.getMessage()).doesNotContain(VALUE);

    platform.respondTo("GET", PATH, 200, "not json " + VALUE);
    Throwable garbled = catchThrowable(() -> resolver().resolve(ACME, SecretRef.of("ftp-password")));
    assertThat(garbled).isInstanceOf(SecretStoreException.class);
    assertThat(garbled.getMessage()).doesNotContain(VALUE);
    assertThat(garbled.getCause()).isNull();

    platform.respondTo("GET", PATH, 200, "{\"section\":\"secrets\"}");
    assertThat(catchThrowable(() -> resolver().resolve(ACME, SecretRef.of("ftp-password")))).isInstanceOf(SecretStoreException.class);

    platform.close();
    Throwable down = catchThrowable(() -> resolver().resolve(ACME, SecretRef.of("ftp-password")));
    assertThat(down).isInstanceOf(SecretStoreException.class);
    assertThat(down.getMessage()).doesNotContain(VALUE);
  }

  @Test
  void theValueIsInNoLogLineAndNoToString() {
    try (LogCapture logs = LogCapture.start()) {
      Secret secret = resolver().require(ACME, SecretRef.of("ftp-password"));
      platform.respondTo("GET", PATH, 500, "{\"error\":\"BOOM\"}");
      resolver().evictAll();
      assertThat(catchThrowable(() -> resolver().resolve(ACME, SecretRef.of("ftp-password")))).isNotNull();

      assertThat(logs.text()).doesNotContain(VALUE);
      assertThat(secret.toString()).doesNotContain(VALUE);
    }
    assertThat(new PlatformConfigData.Revealed("secrets", "p", null, VALUE, "t").toString()).doesNotContain(VALUE);
    assertThat(new PlatformConfigData.SetRequest(VALUE, "SECRET", null).toString()).doesNotContain(VALUE);
  }

  @Test
  void aFoundValueIsCachedUntilItIsEvicted() {
    resolver().require(ACME, SecretRef.of("ftp-password"));
    resolver().require(ACME, SecretRef.of("ftp-password"));
    assertThat(reveals()).hasSize(1);

    platform.respondTo("GET", PATH, 200, revealed("rotated"));
    assertThat(resolver().require(ACME, SecretRef.of("ftp-password")).reveal()).isEqualTo(VALUE);

    resolver().evict(ACME, SecretRef.of("ftp-password"));
    assertThat(resolver().require(ACME, SecretRef.of("ftp-password")).reveal()).isEqualTo("rotated");
    assertThat(reveals()).hasSize(2);

    platform.respondTo("GET", PATH, 200, revealed("third"));
    resolver().evictAll();
    assertThat(resolver().require(ACME, SecretRef.of("ftp-password")).reveal()).isEqualTo("third");
  }

  @Test
  void noCacheAsksTheEveryTime() {
    app.close();
    app = starting(PlatformSecrets.store().withCache(CachePolicy.none()), Map.of());

    resolver().require(ACME, SecretRef.of("ftp-password"));
    resolver().require(ACME, SecretRef.of("ftp-password"));

    assertThat(reveals()).hasSize(2);
  }

  @Test
  void aPercentEncodedNameIsNeverNeededButASectionStaysOneSegment() {
    // names and sections are validated, so a path can only hold [a-z0-9-]; the encoder is still applied
    assertThat(PlatformHttp.enc("a b/c")).isEqualTo("a%20b%2Fc");
  }

  @Test
  void aDisabledPlatformFailsStartUp() {
    app.close();
    Throwable t =
        catchThrowable(
            () -> app = starting(PlatformSecrets.store(), Map.of("FLOWCATALYST_ENABLED", "false")));

    assertThat(t).isInstanceOf(StartupException.class).hasMessageContaining("flowcatalyst.enabled");
    // keep @AfterEach happy
    app = starting(PlatformSecrets.store(), Map.of());
  }

  @Test
  void theCorePlaceholderPointsAtThisDriver() {
    Throwable t =
        catchThrowable(
            () ->
                App.builder()
                    .configDirectory(dir)
                    .environment(Receiving.env(platform))
                    .module(SecretsModule.builder().store("p", io.flowcatalyst.framework.secrets.SecretStores.platform()).build())
                    .start());

    assertThat(t).isInstanceOf(StartupException.class).hasMessageContaining("PlatformSecrets.store()");
  }
}
