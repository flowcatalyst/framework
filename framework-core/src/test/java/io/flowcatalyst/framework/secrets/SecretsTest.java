package io.flowcatalyst.framework.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.StartupException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The secrets contract, with the abuse cases of its threat model (section 2). */
class SecretsTest {

  static final String VALUE = "s3cr3t-Value-42";

  @TempDir Path dir;
  @TempDir Path secrets;
  @TempDir Path outside;

  final ClientId acme = new ClientId("acme");
  final ClientId globex = new ClientId("globex");

  private App start(SecretsModule module, Map<String, String> env) {
    AppBuilder b = App.builder().configDirectory(dir).environment(env).module(module);
    return b.start();
  }

  private App files(UnaryOperator<SecretStores.FileStore> adjust) {
    return start(SecretsModule.builder().store("files", adjust.apply(SecretStores.file(secrets))).build(), Map.of());
  }

  private App files() {
    return files(f -> f);
  }

  private void write(String client, String name, String content) throws IOException {
    Path d = Files.createDirectories(secrets.resolve(client));
    Files.writeString(d.resolve(name), content);
  }

  private static String reveal(SecretResult r) {
    assertThat(r).isInstanceOf(SecretResult.Found.class);
    return ((SecretResult.Found) r).secret().reveal();
  }

  // ---- references ------------------------------------------------------------------------

  @Test
  void referencesNeverCarryAPathOrAClient() {
    assertThat(SecretRef.parse("sftp-password")).isEqualTo(new SecretRef(null, "sftp-password"));
    assertThat(SecretRef.parse("files:sftp-password")).isEqualTo(new SecretRef("files", "sftp-password"));
    for (String bad :
        new String[] {
          "", "../etc/passwd", "a/b", ".hidden", "..", "Sftp", "sftp_password", "sftp--password",
          "-x", "x-", "files:", ":x", "files:a:b", "files:../x", "acme/sftp", "x".repeat(101),
          "files:sftp password", "files:sftp\npassword"
        }) {
      assertThatIllegalArgumentException().as(bad).isThrownBy(() -> SecretRef.parse(bad));
    }
    Throwable long_ = catchThrowable(() -> SecretRef.parse("x".repeat(500) + "/"));
    assertThat(long_.getMessage()).contains("(501 characters)").doesNotContain("xxxxxxxxxx");
  }

  // ---- file driver -----------------------------------------------------------------------

  @Test
  void aFileIsReadForTheCallersClientOnlyWithOneTrailingNewlineRemoved() throws IOException {
    write("acme", "sftp-password", VALUE + "\n");
    write("globex", "sftp-password", "globex-value\r\n");
    try (App app = files()) {
      SecretResolver r = app.get(SecretResolver.class);
      assertThat(reveal(r.resolve(acme, SecretRef.parse("files:sftp-password")))).isEqualTo(VALUE);
      assertThat(reveal(r.resolve(globex, SecretRef.of("sftp-password")))).isEqualTo("globex-value");
      assertThat(r.resolve(new ClientId("initech"), SecretRef.of("sftp-password")))
          .isInstanceOf(SecretResult.NotFound.class);
      assertThat(r.require(acme, SecretRef.of("sftp-password")).reveal()).isEqualTo(VALUE);
    }
  }

  @Test
  void clientIdsThatAreNotPlainDirectoryNamesAreRefused() throws IOException {
    write("acme", "x", VALUE);
    try (App app = files()) {
      SecretResolver r = app.get(SecretResolver.class);
      for (String client : new String[] {"..", ".", ".acme", "..acme"}) {
        assertThat(r.resolve(new ClientId(client), SecretRef.of("x")))
            .as(client)
            .isInstanceOf(SecretResult.Refused.class);
      }
    }
  }

  @Test
  void aLinkThatLeadsOutOfTheDirectoryIsNotFollowedAndNothingIsShown() throws IOException {
    Path target = Files.writeString(outside.resolve("passwd"), VALUE);
    Files.createDirectories(secrets.resolve("acme"));
    Files.createSymbolicLink(secrets.resolve("acme").resolve("stolen"), target);
    Files.createSymbolicLink(secrets.resolve("evil"), outside);
    Files.writeString(outside.resolve("token"), VALUE);
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream old = System.err;
    try (App app = files()) {
      System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
      SecretResolver r = app.get(SecretResolver.class);
      assertThat(r.resolve(acme, SecretRef.of("stolen"))).isInstanceOf(SecretResult.NotFound.class);
      assertThat(r.resolve(new ClientId("evil"), SecretRef.of("token")))
          .isInstanceOf(SecretResult.NotFound.class);
    } finally {
      System.setErr(old);
    }
    assertThat(err.toString(StandardCharsets.UTF_8)).doesNotContain(VALUE).doesNotContain(outside.toString());
  }

  @Test
  void aLinkInsideTheDirectoryIsFollowedTheKubernetesLayout() throws IOException {
    // /secrets/..2026_10_10/acme/sftp-password, /secrets/..data -> ..2026_10_10, /secrets/acme -> ..data/acme
    Path dated = Files.createDirectories(secrets.resolve("..2026_10_10").resolve("acme"));
    Files.writeString(dated.resolve("sftp-password"), VALUE);
    Files.createSymbolicLink(secrets.resolve("..data"), secrets.resolve("..2026_10_10"));
    Files.createSymbolicLink(secrets.resolve("acme"), Path.of("..data", "acme"));
    try (App app = files()) {
      assertThat(reveal(app.get(SecretResolver.class).resolve(acme, SecretRef.of("sftp-password"))))
          .isEqualTo(VALUE);
    }
  }

  @Test
  void aLinkToAnotherClientsSecretIsNotFollowed() throws IOException {
    write("globex", "api-key", VALUE);
    Files.createDirectories(secrets.resolve("acme"));
    Files.createSymbolicLink(
        secrets.resolve("acme").resolve("api-key"), secrets.resolve("globex").resolve("api-key"));
    try (App app = files()) {
      assertThat(app.get(SecretResolver.class).resolve(acme, SecretRef.of("api-key")))
          .isInstanceOf(SecretResult.NotFound.class);
    }
  }

  @Test
  void aClientDifferingOnlyByCaseNeverReadsAnotherClientsFile() throws IOException {
    write("acme", "api-key", VALUE);
    try (App app = files()) {
      // On a case-insensitive file system ACME/api-key opens acme/api-key; the real-path check refuses it.
      assertThat(app.get(SecretResolver.class).resolve(new ClientId("ACME"), SecretRef.of("api-key")))
          .isInstanceOf(SecretResult.NotFound.class);
    }
  }

  @Test
  void tooLargeOrInvalidFilesFailWithoutShowingTheirContent() throws IOException {
    write("acme", "big", VALUE + "x".repeat(100));
    Files.createDirectories(secrets.resolve("acme"));
    Files.write(secrets.resolve("acme").resolve("binary"), new byte[] {'s', '3', (byte) 0xC3, (byte) 0x28});
    try (App app = files(f -> f.withMaxBytes(50))) {
      SecretResolver r = app.get(SecretResolver.class);
      Throwable big = catchThrowable(() -> r.resolve(acme, SecretRef.of("big")));
      assertThat(big).isInstanceOf(SecretStoreException.class).hasMessageContaining("maxBytes");
      assertThat(big.getMessage()).doesNotContain(VALUE);
      Throwable bin = catchThrowable(() -> r.resolve(acme, SecretRef.of("binary")));
      assertThat(bin).isInstanceOf(SecretStoreException.class).hasMessageContaining("UTF-8");
      assertThat(bin.getCause()).isNull();
    }
  }

  @Test
  void emptyFilesAndDirectoriesAreNotFound() throws IOException {
    write("acme", "empty", "\n");
    Files.createDirectories(secrets.resolve("acme").resolve("a-dir"));
    try (App app = files()) {
      SecretResolver r = app.get(SecretResolver.class);
      assertThat(r.resolve(acme, SecretRef.of("empty"))).isInstanceOf(SecretResult.NotFound.class);
      assertThat(r.resolve(acme, SecretRef.of("a-dir"))).isInstanceOf(SecretResult.NotFound.class);
    }
  }

  @Test
  void theDirectoryMustBeAbsoluteWhenBuiltAndExistAtStartUp() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SecretStores.file(Path.of("relative/path")))
        .withMessageContaining("directory");
    Throwable t =
        catchThrowable(
            () -> start(SecretsModule.builder().store("files", SecretStores.file(outside.resolve("missing"))).build(), Map.of()));
    assertThat(t).isInstanceOf(StartupException.class).hasMessageContaining("directory").hasMessageContaining("secret store 'files'");
  }

  // ---- env driver ------------------------------------------------------------------------

  @Test
  void envVariablesAreNamedInjectivelyAndOnlyUnderThePrefix() throws IOException {
    Map<String, String> env = new HashMap<>();
    env.put("CLIENT_SECRET_ACME_CORP__SFTP_PASSWORD", VALUE);
    env.put("CLIENT_SECRET_ACME__CORP_SFTP_PASSWORD", "other");
    env.put("DATABASE_INSTANCES_MAIN_PASSWORD", "db-password");
    try (App app = start(SecretsModule.builder().store("env", SecretStores.env("CLIENT_SECRET_")).build(), env)) {
      SecretResolver r = app.get(SecretResolver.class);
      assertThat(reveal(r.resolve(new ClientId("acme-corp"), SecretRef.of("sftp-password"))))
          .isEqualTo(VALUE);
      // client "acme", name "corp-sftp-password" maps to CLIENT_SECRET_ACME__CORP_SFTP_PASSWORD,
      // a different variable: the double underscore separates client and name unambiguously.
      assertThat(reveal(r.resolve(acme, SecretRef.of("corp-sftp-password")))).isEqualTo("other");
      assertThat(r.resolve(new ClientId("database"), SecretRef.of("instances-main-password")))
          .isInstanceOf(SecretResult.NotFound.class);
      for (String client : new String[] {"acme_corp", "Acme", "acme.corp", "_platform", "acme--corp"}) {
        assertThat(r.resolve(new ClientId(client), SecretRef.of("sftp-password")))
            .as(client)
            .isInstanceOf(SecretResult.Refused.class);
      }
      Throwable t = catchThrowable(() -> r.require(new ClientId("acme_corp"), SecretRef.of("sftp-password")));
      assertThat(t).isInstanceOf(SecretNotFoundException.class).hasMessageContaining("refused");
    }
  }

  @Test
  void theEncodingNeverMapsTwoReferencesToOneVariable() {
    EnvSecretStore store = new EnvSecretStore("e", "CLIENT_SECRET_", io.flowcatalyst.framework.config.EnvironmentVariables.of(Map.of()));
    String[] parts = {"a", "b", "a-b", "b-a", "ab", "a-b-c", "c", "b-c", "a1", "1a"};
    Map<String, String> seen = new HashMap<>();
    for (String client : parts) {
      for (String name : parts) {
        String variable = store.variable(new ClientId(client), name).orElseThrow();
        String previous = seen.put(variable, client + "/" + name);
        assertThat(previous).as(variable).isNull();
        assertThat(variable).startsWith("CLIENT_SECRET_");
      }
    }
  }

  @Test
  void envPrefixesThatReachFrameworkVariablesAreRefusedWhenBuilt() {
    for (String prefix : new String[] {"DATABASE_", "APP_X_", "FLOWCATALYST_", "X_", "lower_", "NO_UNDERSCORE"}) {
      assertThatIllegalArgumentException().as(prefix).isThrownBy(() -> SecretStores.env(prefix)).withMessageContaining("prefix");
    }
  }

  // ---- caching ---------------------------------------------------------------------------

  @Test
  void foundValuesAreCachedForTheTtlPerClientAndNotFoundIsNeverCached() {
    AtomicLong now = new AtomicLong();
    Map<String, String> values = new HashMap<>(Map.of("acme/k", "one", "globex/k", "g"));
    int[] reads = {0};
    SecretStore backing =
        (client, name) -> {
          reads[0]++;
          String v = values.get(client.value() + "/" + name);
          return v == null ? new SecretResult.NotFound() : new SecretResult.Found(Secret.of(v));
        };
    CachingSecretStore cache = new CachingSecretStore(backing, Duration.ofSeconds(60), 2, now::get);
    assertThat(reveal(cache.find(acme, "k"))).isEqualTo("one");
    values.put("acme/k", "two"); // rotated
    assertThat(reveal(cache.find(acme, "k"))).isEqualTo("one");
    assertThat(reveal(cache.find(globex, "k"))).isEqualTo("g");
    now.addAndGet(Duration.ofSeconds(60).toNanos());
    assertThat(reveal(cache.find(acme, "k"))).isEqualTo("two");
    cache.evict(acme, "k");
    values.put("acme/k", "three");
    assertThat(reveal(cache.find(acme, "k"))).isEqualTo("three");
    int before = reads[0];
    cache.find(acme, "missing");
    cache.find(acme, "missing");
    assertThat(reads[0] - before).isEqualTo(2);
    cache.find(new ClientId("c3"), "x");
    values.put("c3/x", "v");
    assertThat(reveal(cache.find(new ClientId("c3"), "x"))).isEqualTo("v");
    assertThat(cache.size()).isLessThanOrEqualTo(2);
    cache.evictAll();
    assertThat(cache.size()).isZero();
  }

  @Test
  void theResolverEvictsThroughToTheStore() throws IOException {
    write("acme", "k", "one");
    try (App app = files(f -> f.withCache(CachePolicy.of(Duration.ofHours(1), 10_000)))) {
      SecretResolver r = app.get(SecretResolver.class);
      assertThat(reveal(r.resolve(acme, SecretRef.of("k")))).isEqualTo("one");
      write("acme", "k", "two");
      assertThat(reveal(r.resolve(acme, SecretRef.of("k")))).isEqualTo("one");
      r.evict(acme, SecretRef.of("k"));
      assertThat(reveal(r.resolve(acme, SecretRef.of("k")))).isEqualTo("two");
      write("acme", "k", "three");
      r.evictAll();
      assertThat(reveal(r.resolve(acme, SecretRef.of("k")))).isEqualTo("three");
    }
  }

  // ---- the resolver and the platform driver ------------------------------------------------

  @Test
  void thePlatformPlaceholderFailsAtStartAndNamesThePlatformSecretsDriver() throws IOException {
    Throwable t =
        catchThrowable(
            () -> start(SecretsModule.builder().store("p", SecretStores.platform()).build(), Map.of()));
    assertThat(t).isInstanceOf(StartupException.class).hasMessageContaining("PlatformSecrets.store()");
  }

  @Test
  void withoutInstancesOrWithAnUnknownStoreResolvingFails() throws IOException {
    try (App app = start(SecretsModule.builder().build(), Map.of())) {
      SecretResolver r = app.get(SecretResolver.class);
      assertThatIllegalStateException().isThrownBy(() -> r.resolve(acme, SecretRef.of("x")));
    }
    write("acme", "x", VALUE);
    try (App app = files()) {
      SecretResolver r = app.get(SecretResolver.class);
      assertThatIllegalArgumentException().isThrownBy(() -> r.resolve(acme, SecretRef.parse("nope:x")));
    }
  }

  @Test
  void requireNamesTheSecretButNeverShowsAValue() throws IOException {
    write("acme", "present", VALUE);
    try (App app = files()) {
      SecretResolver r = app.get(SecretResolver.class);
      assertThatThrownBy(() -> r.require(globex, SecretRef.of("present")))
          .isInstanceOf(SecretNotFoundException.class)
          .hasMessageContaining("present")
          .hasMessageContaining("globex")
          .hasMessageNotContaining(VALUE);
      assertThat(r.require(acme, SecretRef.of("present")).toString()).isEqualTo("Secret[****]");
    }
  }

  @Test
  void secretMatchesComparesWithoutRevealing() {
    Secret s = Secret.of(VALUE);
    assertThat(s.matches(VALUE)).isTrue();
    assertThat(s.matches(VALUE + "x")).isFalse();
    assertThat(s.matches("")).isFalse();
    assertThat(Secret.of("").matches("")).isTrue();
  }

  // ---- behaviour in code (config-and-code contract, section 3.7) ----------------------------

  @Test
  void cachePolicyDefaultsAndRanges() {
    assertThat(CachePolicy.defaults()).isEqualTo(new CachePolicy(Duration.ofMinutes(5), 10_000));
    assertThat(CachePolicy.none().ttl()).isZero();
    assertThat(CachePolicy.of(Duration.ofHours(24), 1_000_000).maxEntries()).isEqualTo(1_000_000);
    assertThatIllegalArgumentException().isThrownBy(() -> CachePolicy.of(Duration.ofMillis(-1), 1)).withMessageContaining("ttl");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CachePolicy.of(Duration.ofHours(24).plusNanos(1), 1))
        .withMessageContaining("ttl");
    assertThatIllegalArgumentException().isThrownBy(() -> CachePolicy.of(Duration.ZERO, 0)).withMessageContaining("maxEntries");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CachePolicy.of(Duration.ZERO, 1_000_001))
        .withMessageContaining("maxEntries");
    assertThat(SecretStores.file(secrets).maxBytes()).isEqualTo(65_536);
    assertThat(SecretStores.file(secrets).cache()).isEqualTo(CachePolicy.defaults());
    assertThat(SecretStores.env("CLIENT_SECRET_").cache()).isEqualTo(CachePolicy.defaults());
    assertThatIllegalArgumentException().isThrownBy(() -> SecretStores.file(secrets).withMaxBytes(0)).withMessageContaining("maxBytes");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SecretStores.file(secrets).withMaxBytes(1_048_577))
        .withMessageContaining("maxBytes");
  }

  @Test
  void withoutACacheARotatedFileIsReadAtOnce() throws IOException {
    write("acme", "k", "one");
    try (App app = files(f -> f.withCache(CachePolicy.none()))) {
      SecretResolver r = app.get(SecretResolver.class);
      assertThat(reveal(r.resolve(acme, SecretRef.of("k")))).isEqualTo("one");
      write("acme", "k", "two");
      assertThat(reveal(r.resolve(acme, SecretRef.of("k")))).isEqualTo("two");
    }
  }

  @Test
  void withTheDefaultCacheARotatedFileIsReadAfterTheTtl() throws IOException {
    write("acme", "k", "one");
    try (App app = files()) {
      SecretResolver r = app.get(SecretResolver.class);
      assertThat(reveal(r.resolve(acme, SecretRef.of("k")))).isEqualTo("one");
      write("acme", "k", "two");
      assertThat(reveal(r.resolve(acme, SecretRef.of("k")))).isEqualTo("one");
    }
  }

  @Test
  void theDefaultStoreRulesAreCheckedWhenBuilt() throws IOException {
    write("acme", "x", VALUE);
    SecretsModule.Builder two =
        SecretsModule.builder().store("files", SecretStores.file(secrets)).store("env", SecretStores.env("CLIENT_SECRET_"));
    assertThatIllegalArgumentException().isThrownBy(two::build).withMessageContaining("defaultStore");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SecretsModule.builder().store("files", SecretStores.file(secrets)).defaultStore("nope").build())
        .withMessageContaining("'nope'");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SecretsModule.builder().store("files", SecretStores.file(secrets)).store("files", SecretStores.file(secrets)));
    try (App app = start(two.defaultStore("files").build(), Map.of())) {
      assertThat(reveal(app.get(SecretResolver.class).resolve(acme, SecretRef.of("x")))).isEqualTo(VALUE);
    }
  }

  static final io.flowcatalyst.framework.config.Setting<String> DIRECTORY =
      io.flowcatalyst.framework.config.Setting.string("demo.secrets-directory").asRequired();

  @Test
  void aDeploymentSpecificDirectoryIsTheApplicationsOwnSetting() throws IOException {
    write("acme", "x", VALUE);
    io.flowcatalyst.framework.core.Module demo =
        new io.flowcatalyst.framework.core.Module() {
          @Override
          public String id() {
            return "demo";
          }

          @Override
          public void register(AppBuilder app) {
            app.declare(DIRECTORY);
          }
        };
    SecretStoreFactory fromSetting =
        (name, lookup) ->
            SecretStores.file(Path.of(lookup.get(io.flowcatalyst.framework.config.Config.class).get(DIRECTORY)))
                .create(name, lookup);
    try (App app =
        App.builder()
            .configDirectory(dir)
            .environment(Map.of("DEMO_SECRETS_DIRECTORY", secrets.toString()))
            .module(SecretsModule.builder().store("files", fromSetting).build())
            .module(demo)
            .start()) {
      assertThat(reveal(app.get(SecretResolver.class).resolve(acme, SecretRef.of("x")))).isEqualTo(VALUE);
    }
  }

  @Test
  void aLeftoverSecretsSectionFailsStartUpNamingTheCode() throws IOException {
    Files.writeString(
        dir.resolve("application.yaml"),
        "secrets:\n  default: env\n  instances:\n    env: { driver: env, prefix: CLIENT_SECRET_, cache-ttl: 1m }\n");
    Throwable t = catchThrowable(() -> start(SecretsModule.builder().build(), Map.of("SECRETS_INSTANCES_ENV_CACHE_MAX", "5")));
    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(((StartupException) t).problems())
        .extracting(io.flowcatalyst.framework.core.Problem::key)
        .containsExactlyInAnyOrder(
            "secrets.default",
            "secrets.instances.env.driver",
            "secrets.instances.env.prefix",
            "secrets.instances.env.cache-ttl",
            "secrets.instances.env.cache-max");
    assertThat(t.getMessage()).contains("SecretStores.env(prefix)");
  }
}
