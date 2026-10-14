package io.flowcatalyst.framework.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.config.KeyRules;
import io.flowcatalyst.framework.config.RemovedKey;
import io.flowcatalyst.framework.config.Setting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The key rules (config-and-code contract, section 5): removed keys of things now defined in code
 * fail start-up in a file or the environment, and a claimed component refuses undeclared keys.
 */
class KeyRulesTest {

  @TempDir Path dir;

  private AppBuilder builder(Map<String, String> env) {
    return App.builder().configDirectory(dir).environment(env);
  }

  private AppBuilder builder() {
    return builder(Map.of());
  }

  private void file(String name, String yaml) throws IOException {
    Files.writeString(dir.resolve(name), yaml);
  }

  private static StartupException failure(AppBuilder b) {
    Throwable t = catchThrowable(b::start);
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  // ---- removed keys ------------------------------------------------------------------------

  @Test
  void noServiceSettingIsARemovedKey() {
    // config-and-code contract, section 8: nothing under app or http is removed, and of a
    // database or Redis instance section only driver
    for (RemovedKey removed : KeyRules.frameworkRemovedKeys()) {
      String p = removed.pattern();
      for (String component : new String[] {"app", "http"}) {
        assertThat(p).doesNotStartWith(component + ".").isNotEqualTo(component);
      }
      if (p.startsWith("database") || p.startsWith("redis")) {
        assertThat(p).endsWith(".driver");
      }
    }
  }

  @Test
  void aRemovedKeyInAFileFailsStartUpNamingWhereItWent() throws IOException {
    file("application.yaml", "locks:\n  default: main\nsecrets:\n  default: env\n");
    StartupException e = failure(builder());
    assertThat(e.problems()).extracting(Problem::key).containsExactlyInAnyOrder("locks.default", "secrets.default");
    assertThat(e.problems()).allSatisfy(p -> assertThat(p.message()).startsWith("moved to code: "));
    assertThat(e.getMessage())
        .contains("LocksModule.builder().defaultStore(name)")
        .contains("SecretsModule.builder().defaultStore(name)")
        .contains("application.yaml");
  }

  @Test
  void aRemovedKeyInTheEnvironmentFailsStartUp() {
    StartupException e =
        failure(builder(Map.of("HTTP_CLIENT_EVENT_LOOPS", "4", "HTTP_CLIENTS_DEFAULT", "billing")));
    assertThat(e.problems())
        .extracting(Problem::key)
        .containsExactlyInAnyOrder("http-client.event-loops", "http-clients.default");
    assertThat(e.getMessage())
        .contains("environment variable HTTP_CLIENT_EVENT_LOOPS")
        .contains("HttpClientModule.builder().eventLoops(int)");
  }

  @Test
  void aRemovedKeyOfAModuleThatIsNotListedStillFails() throws IOException {
    file("application.yaml", "rate-limits:\n  default: api\n  instances:\n    api: { limit: 5, colour: red }\n");
    StartupException e = failure(builder());
    assertThat(e.problems())
        .extracting(Problem::key)
        .containsExactlyInAnyOrder("rate-limits.default", "rate-limits.instances.api.limit", "rate-limits.instances.api.colour");
    assertThat(e.getMessage()).contains("MemoryRateLimiter.create");
  }

  @Test
  void aWildcardIsExpandedWithTheInstanceNamesInTheFilesForTheEnvironment() throws IOException {
    // an instance named only in a file (the key itself is not removed: the whole component is)
    file("application.yaml", "rate-limits:\n  instances:\n    api: {}\n");
    StartupException e =
        failure(builder(Map.of("RATE_LIMITS_INSTANCES_API_BURST", "4", "RATE_LIMITS_INSTANCES_OTHER_BURST", "4")));
    assertThat(e.problems())
        .filteredOn(p -> p.source() != null && p.source().startsWith("environment"))
        .singleElement()
        .satisfies(p -> {
          assertThat(p.key()).isEqualTo("rate-limits.instances.api.burst");
          assertThat(p.source()).isEqualTo("environment variable RATE_LIMITS_INSTANCES_API_BURST");
          assertThat(p.message()).contains("RateLimiter.create");
        });
  }

  @Test
  void aOneSegmentPatternIsNotLookedUpInTheEnvironment() {
    builder(Map.of("LOCKS", "x", "SECRETS", "y", "RATE_LIMITS", "z")).start().close();
  }

  @Test
  void aKeyThatAModuleDeclaresIsNeverARemovedKey() throws IOException {
    Setting<Integer> own = Setting.integer("http-client.event-loops");
    file("application.yaml", "http-client:\n  event-loops: 3\n");
    App app = builder().module(Mod.of("mine").register(b -> b.declare(own))).start();
    assertThat(app.config().get(own)).isEqualTo(3);
  }

  @Test
  void anEmptySectionAboveDeclaredKeysIsNotReported() throws IOException {
    Setting<String> own = Setting.string("mail.name");
    file("application.yaml", "app:\nmail:\n");
    builder()
        .module(Mod.of("mine").register(b -> {
          b.declare(own);
          b.claimComponent("mail");
        }))
        .start()
        .close();
  }

  // ---- claimed components ------------------------------------------------------------------

  @Test
  void aMisspeltKeyUnderAClaimedComponentFailsStartUpListingTheDeclaredKeys() throws IOException {
    file("application.yaml", "app:\n  shutdown:\n    drian: 5s\nother:\n  colour: green\n");
    StartupException e = failure(builder());
    assertThat(e.problems()).singleElement().satisfies(p -> {
      assertThat(p.key()).isEqualTo("app.shutdown.drian");
      assertThat(p.module()).isEqualTo("core");
      assertThat(p.message()).contains("unknown key").contains("'app'").contains("app.shutdown.drain");
    });
  }

  @Test
  void aModuleClaimsAComponentAndItsDeclaredKeysAreListed() throws IOException {
    Setting<String> host = Setting.string("mail.host");
    file("application.yaml", "mail:\n  host: smtp\n  hots: smtp\n");
    StartupException e =
        failure(builder().module(Mod.of("mail").register(b -> {
          b.declare(host);
          b.claimComponent("mail");
        })));
    assertThat(e.problems()).singleElement().satisfies(p -> {
      assertThat(p.key()).isEqualTo("mail.hots");
      assertThat(p.module()).isEqualTo("mail");
      assertThat(p.message()).contains("mail.host");
    });
  }

  @Test
  void twoModulesClaimingOneComponentFailStartUp() {
    StartupException e =
        failure(
            builder()
                .module(Mod.of("a").register(b -> b.claimComponent("mail")))
                .module(Mod.of("b").register(b -> b.claimComponent("mail"))));
    assertThat(e.problems()).singleElement().satisfies(p -> {
      assertThat(p.module()).isEqualTo("b");
      assertThat(p.message()).contains("already claimed by module 'a'");
    });
  }

  @Test
  void claimingTheCoreComponentFailsAndBadNamesAreRefused() {
    StartupException e = failure(builder().module(Mod.of("a").register(b -> b.claimComponent("app"))));
    assertThat(e.getMessage()).contains("already claimed by module 'core'");
    StartupException bad = failure(builder().module(Mod.of("a").register(b -> b.claimComponent("Mail.x"))));
    assertThat(bad.getMessage()).contains("component");
  }

  @Test
  void claimComponentIsOnlyForTheRegisterPhase() {
    assertThatIllegalStateException().isThrownBy(() -> builder().claimComponent("mail"));
  }
}
