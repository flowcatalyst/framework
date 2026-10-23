package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.secrets.SecretRef;
import io.flowcatalyst.framework.secrets.SecretResolver;
import io.flowcatalyst.framework.secrets.SecretResult;
import io.flowcatalyst.framework.secrets.SecretsModule;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code platform} secrets driver and the platform-config calls against a <b>real</b> platform
 * (platform-config contract section 6, last bullet). Skipped unless {@code FC_REAL_PLATFORM_URL} is set, so the
 * normal build never needs a platform. Run it against an isolated local {@code fcdev} (never a shared one):
 *
 * <pre>
 * FC_REAL_PLATFORM_URL=http://127.0.0.1:18080 FC_REAL_APP=yard FC_REAL_CLIENT_ID=... FC_REAL_CLIENT_SECRET=...
 * FC_REAL_CLIENT_CODE=acme ./mvnw -o -pl framework-core,framework-flowcatalyst -Dtest=PlatformConfigRealPlatformTest test
 * </pre>
 *
 * The application must exist on the platform with a service account attached, and the client {@code
 * FC_REAL_CLIENT_CODE} must exist. The test sets, rotates and deletes the property {@code secrets/real-test} for
 * that client only.
 */
class PlatformConfigRealPlatformTest {

  static final String SECTION = "secrets";
  static final String NAME = "real-test";

  @TempDir Path dir;

  @Test
  void aPerClientSecretIsSetResolvedRotatedAndEvicted() {
    String url = System.getenv("FC_REAL_PLATFORM_URL");
    assumeTrue(url != null && !url.isBlank(), "FC_REAL_PLATFORM_URL is not set");
    String application = System.getenv("FC_REAL_APP");
    String code = System.getenv("FC_REAL_CLIENT_CODE");

    Map<String, String> env = new HashMap<>();
    env.put("FLOWCATALYST_BASE_URL", url);
    env.put("FLOWCATALYST_APPLICATION", application);
    env.put("FLOWCATALYST_CLIENT_ID", System.getenv("FC_REAL_CLIENT_ID"));
    env.put("FLOWCATALYST_CLIENT_SECRET", System.getenv("FC_REAL_CLIENT_SECRET"));
    env.put("FLOWCATALYST_RECEIVERS_SIGNING_SECRET", "real-platform-test-signing-secret");

    try (App app =
        App.builder()
            .configDirectory(dir)
            .environment(env)
            .module(Receiving.routes())
            .module(new FlowCatalystModule())
            .module(SecretsModule.builder().store("platform", PlatformSecrets.store()).build())
            .start()) {
      PlatformClient platform = app.get(PlatformClient.class);
      SecretResolver resolver = app.get(SecretResolver.class);
      SecretRef ref = SecretRef.of(NAME);
      ClientId byCode = new ClientId(code);

      try {
        // Set by the client's CODE: the platform resolves it to the id.
        PlatformConfigData.Property stored =
            platform.setConfigProperty(SECTION, NAME, code, new PlatformConfigData.SetRequest("first-value", "SECRET", "real platform test"));
        assertThat(stored.value()).as("a secret is masked in the answer").isEqualTo("***");
        String clientIdOnPlatform = stored.clientId();
        assertThat(clientIdOnPlatform).as("stored under the id, not the code").startsWith("clt_");

        // Resolved by code, and by the id the platform stored: one scope.
        assertThat(resolver.require(byCode, ref).reveal()).isEqualTo("first-value");
        assertThat(resolver.require(new ClientId(clientIdOnPlatform), ref).reveal()).isEqualTo("first-value");

        // The list never carries the plaintext.
        List<PlatformConfigData.Property> listed = platform.configProperties();
        assertThat(listed).anySatisfy(p -> {
          assertThat(p.property()).isEqualTo(NAME);
          assertThat(p.value()).isEqualTo("***");
        });

        // Rotate: the cache still serves the old value until evicted.
        platform.setConfigProperty(SECTION, NAME, code, new PlatformConfigData.SetRequest("second-value", "SECRET", null));
        assertThat(resolver.require(byCode, ref).reveal()).as("cached").isEqualTo("first-value");
        resolver.evict(byCode, ref);
        assertThat(resolver.require(byCode, ref).reveal()).as("after evict").isEqualTo("second-value");

        // An unknown client is not found; a client with no value is not found (no global fallback).
        assertThat(resolver.resolve(new ClientId("no-such-client-xyz"), ref)).isInstanceOf(SecretResult.NotFound.class);
      } finally {
        platform.deleteConfigProperty(SECTION, NAME, code);
      }
      resolver.evictAll();
      assertThat(resolver.resolve(byCode, ref)).as("deleted").isInstanceOf(SecretResult.NotFound.class);
    }
  }
}
