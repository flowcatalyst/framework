package io.flowcatalyst.framework.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

  @TempDir Path dir;

  private void write(String name, String content) throws IOException {
    Files.writeString(dir.resolve(name), content);
  }

  private Config load(Map<String, String> env, Setting<?>... settings) {
    Config.Loaded loaded = Config.load(dir, env, declare(settings));
    if (loaded instanceof Config.Failed f) {
      throw new AssertionError("unexpected problems: " + f.problems());
    }
    return ((Config.Ok) loaded).config();
  }

  private List<ConfigProblem> problems(Map<String, String> env, Setting<?>... settings) {
    Config.Loaded loaded = Config.load(dir, env, declare(settings));
    assertThat(loaded).isInstanceOf(Config.Failed.class);
    return ((Config.Failed) loaded).problems();
  }

  private static List<Declaration> declare(Setting<?>... settings) {
    return java.util.Arrays.stream(settings).map(s -> new Declaration("test", s)).toList();
  }

  // ---- sources and precedence -------------------------------------------------------------

  @Test
  void defaultIsLowestSource() {
    Setting<String> s = Setting.string("app.name").withDefault("from-default");
    assertThat(load(Map.of(), s).get(s)).isEqualTo("from-default");
  }

  @Test
  void applicationFileBeatsDefault() throws IOException {
    write("application.yaml", "app:\n  name: from-file\n");
    Setting<String> s = Setting.string("app.name").withDefault("from-default");
    assertThat(load(Map.of(), s).get(s)).isEqualTo("from-file");
  }

  @Test
  void environmentFileBeatsApplicationFile() throws IOException {
    write("application.yaml", "app:\n  name: base\n  other: base-other\n");
    write("application-staging.yaml", "app:\n  name: staging\n");
    Setting<String> name = Setting.string("app.name");
    Setting<String> other = Setting.string("app.other");
    Config c = load(Map.of("APP_ENV", "staging"), name, other);
    assertThat(c.get(name)).isEqualTo("staging");
    assertThat(c.get(other)).isEqualTo("base-other");
    assertThat(c.environment()).isEqualTo("staging");
  }

  @Test
  void environmentFileOfAnotherEnvironmentIsIgnored() throws IOException {
    write("application-staging.yaml", "app:\n  name: staging\n");
    Setting<String> name = Setting.string("app.name").withDefault("d");
    assertThat(load(Map.of("APP_ENV", "prod"), name).get(name)).isEqualTo("d");
  }

  @Test
  void environmentVariableBeatsAllFiles() throws IOException {
    write("application.yaml", "app:\n  name: base\n");
    write("application-local.yaml", "app:\n  name: local-file\n");
    write("secrets.local.yaml", "app:\n  name: secrets-file\n");
    Setting<String> name = Setting.string("app.name");
    assertThat(load(Map.of("APP_NAME", "from-env"), name).get(name)).isEqualTo("from-env");
  }

  @Test
  void secretsLocalFileSitsBetweenFilesAndEnvironment() throws IOException {
    write("application.yaml", "db:\n  password: from-app\n");
    write("application-local.yaml", "db:\n  password: from-local\n");
    write("secrets.local.yaml", "db:\n  password: from-secrets\n");
    Setting<Secret> pw = Setting.secret("db.password");
    assertThat(load(Map.of(), pw).get(pw).reveal()).isEqualTo("from-secrets");
    assertThat(load(Map.of("DB_PASSWORD", "from-env"), pw).get(pw).reveal())
        .isEqualTo("from-env");
  }

  @Test
  void secretsLocalFileIsReadInTestToo() throws IOException {
    write("secrets.local.yaml", "db:\n  password: s\n");
    Setting<Secret> pw = Setting.secret("db.password");
    assertThat(load(Map.of("APP_ENV", "test"), pw).get(pw).reveal()).isEqualTo("s");
  }

  @Test
  void secretsLocalFileIsIgnoredOutsideLocalAndTest() throws IOException {
    write("secrets.local.yaml", "db:\n  password: s\n");
    Setting<Secret> pw = Setting.secret("db.password").asRequired();
    List<ConfigProblem> found = problems(Map.of("APP_ENV", "prod"), pw);
    assertThat(found).hasSize(1);
    assertThat(found.get(0).key()).isEqualTo("db.password");
    assertThat(found.get(0).message()).contains("missing required setting");
  }

  @Test
  void appEnvDefaultsToLocal() {
    assertThat(load(Map.of()).environment()).isEqualTo("local");
    assertThat(load(Map.of("APP_ENV", " ")).environment()).isEqualTo("local");
  }

  @Test
  void missingFilesAreFine() {
    Setting<Integer> s = Setting.integer("a.b").withDefault(3);
    assertThat(load(Map.of(), s).get(s)).isEqualTo(3);
  }

  // ---- environment variable names ---------------------------------------------------------

  @Test
  void envNameUppercasesAndReplacesDotsAndHyphens() {
    assertThat(Setting.string("database.instances.main.url").envName())
        .isEqualTo("DATABASE_INSTANCES_MAIN_URL");
    assertThat(Setting.string("pool.min-idle").envName()).isEqualTo("POOL_MIN_IDLE");
    assertThat(Setting.string("a_b.c-d").envName()).isEqualTo("A_B_C_D");
  }

  @Test
  void declaredSettingReadsDerivedEnvName() {
    Setting<Integer> s = Setting.integer("pool.min-idle");
    assertThat(load(Map.of("POOL_MIN_IDLE", "4"), s).get(s)).isEqualTo(4);
  }

  @Test
  void environmentIsNeverScannedForUndeclaredNames() {
    Setting<String> s = Setting.string("a.b").withDefault("d");
    Config c = load(Map.of("SOMETHING_ELSE", "x", "A_C", "y"), s);
    assertThat(c.get(s)).isEqualTo("d");
  }

  @Test
  void instanceKeysReadDerivedEnvNameAndCreateNoInstances() throws IOException {
    write("application.yaml", "locks:\n  instances:\n    main:\n      driver: database\n");
    Config c =
        load(
            Map.of(
                "LOCKS_INSTANCES_MAIN_DRIVER", "redis",
                "LOCKS_INSTANCES_OTHER_DRIVER", "memory"));
    InstanceConfig instances = c.section("locks.instances");
    assertThat(instances.keys()).containsExactly("main");
    assertThat(instances.section("main").string("driver")).isEqualTo("redis");
  }

  // ---- value parsing ----------------------------------------------------------------------

  @Test
  void durationsAcceptAllUnitsAndIso() throws IOException {
    write(
        "application.yaml",
        "d:\n  ms: 500ms\n  s: 30s\n  m: 5m\n  h: 2h\n  d: 1d\n  iso: PT30S\n");
    Config c =
        load(
            Map.of(),
            Setting.duration("d.ms"),
            Setting.duration("d.s"),
            Setting.duration("d.m"),
            Setting.duration("d.h"),
            Setting.duration("d.d"),
            Setting.duration("d.iso"));
    assertThat(c.get(Setting.duration("d.ms"))).isEqualTo(Duration.ofMillis(500));
    assertThat(c.get(Setting.duration("d.s"))).isEqualTo(Duration.ofSeconds(30));
    assertThat(c.get(Setting.duration("d.m"))).isEqualTo(Duration.ofMinutes(5));
    assertThat(c.get(Setting.duration("d.h"))).isEqualTo(Duration.ofHours(2));
    assertThat(c.get(Setting.duration("d.d"))).isEqualTo(Duration.ofDays(1));
    assertThat(c.get(Setting.duration("d.iso"))).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void badDurationsAreRejected() {
    for (String bad : List.of("30", "abc", "-5s", "5 s", "PT-5S", "1.5s")) {
      Setting<Duration> s = Setting.duration("a.d");
      List<ConfigProblem> found = problems(Map.of("A_D", bad), s);
      assertThat(found).as(bad).hasSize(1);
      assertThat(found.get(0).message()).contains("expected a duration");
    }
  }

  @Test
  void booleansAcceptTrueAndFalseOnly() throws IOException {
    Setting<Boolean> s = Setting.bool("a.flag");
    assertThat(load(Map.of("A_FLAG", "true"), s).get(s)).isTrue();
    assertThat(load(Map.of("A_FLAG", "false"), s).get(s)).isFalse();
    for (String bad : List.of("yes", "1", "TRUE", "on", "")) {
      assertThat(problems(Map.of("A_FLAG", bad), s)).as(bad).hasSize(1);
    }
    write("application.yaml", "a:\n  flag: true\n");
    assertThat(load(Map.of(), s).get(s)).isTrue();
  }

  @Test
  void integersLongsUrisListsAndEnums() throws IOException {
    write(
        "application.yaml",
        """
        n:
          int: 42
          long: 9000000000
          uri: https://example.test/x
          list: [a, b, c]
          mode: read-only
        """);
    enum Mode {
      READ_ONLY,
      READ_WRITE
    }
    Setting<Integer> i = Setting.integer("n.int");
    Setting<Long> l = Setting.longValue("n.long");
    Setting<URI> u = Setting.uri("n.uri");
    Setting<List<String>> list = Setting.stringList("n.list");
    Setting<Mode> mode = Setting.enumOf("n.mode", List.of(Mode.values()));
    Config c = load(Map.of(), i, l, u, list, mode);
    assertThat(c.get(i)).isEqualTo(42);
    assertThat(c.get(l)).isEqualTo(9_000_000_000L);
    assertThat(c.get(u)).hasToString("https://example.test/x");
    assertThat(c.get(list)).containsExactly("a", "b", "c");
    assertThat(c.get(mode)).isEqualTo(Mode.READ_ONLY);
    assertThat(load(Map.of("N_LIST", "x, y"), list).get(list)).containsExactly("x", "y");
    assertThat(load(Map.of("N_LIST", ""), list).get(list)).isEmpty();
    assertThat(problems(Map.of("N_MODE", "other"), mode).get(0).message())
        .contains("one of READ_ONLY, READ_WRITE");
  }

  @Test
  void unparseableValueNamesKeySourceAndExpectedType() throws IOException {
    write("application.yaml", "a:\n  port: eighty\n");
    Setting<Integer> s = Setting.integer("a.port");
    List<ConfigProblem> found = problems(Map.of(), s);
    ConfigProblem p = found.get(0);
    assertThat(p.key()).isEqualTo("a.port");
    assertThat(p.source()).endsWith("application.yaml");
    assertThat(p.message()).contains("an integer").contains("eighty");
    found = problems(Map.of("A_PORT", "x"), s);
    assertThat(found.get(0).source()).isEqualTo("environment variable A_PORT");
  }

  // ---- requirements and problems ----------------------------------------------------------

  @Test
  void requiredSettingWithNoValueFails() {
    Setting<String> s = Setting.string("a.b").asRequired();
    List<ConfigProblem> found = problems(Map.of(), s);
    assertThat(found).hasSize(1);
    assertThat(found.get(0).module()).isEqualTo("test");
    assertThat(found.get(0).message()).contains("A_B");
  }

  @Test
  void requiredSettingWithDefaultIsSatisfied() {
    Setting<String> s = Setting.string("a.b").asRequired().withDefault("x");
    assertThat(load(Map.of(), s).get(s)).isEqualTo("x");
  }

  @Test
  void optionalSettingWithoutValueIsEmpty() {
    Setting<String> s = Setting.string("a.b");
    Config c = load(Map.of(), s);
    assertThat(c.find(s)).isEmpty();
    assertThatThrownBy(() -> c.get(s)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void undeclaredSettingCannotBeRead() {
    Config c = load(Map.of());
    assertThatIllegalArgumentException().isThrownBy(() -> c.get(Setting.string("x.y")));
  }

  @Test
  void allProblemsAreReportedTogether() throws IOException {
    write("application.yaml", "a:\n  port: nope\n  t: 5\n");
    Setting<Integer> port = Setting.integer("a.port");
    Setting<Duration> timeout = Setting.duration("a.t");
    Setting<String> missing = Setting.string("a.missing").asRequired();
    Setting<Secret> missingSecret = Setting.secret("a.token").asRequired();
    Setting<String> dup = Setting.string("a.missing");
    List<ConfigProblem> found = problems(Map.of(), port, timeout, missing, missingSecret, dup);
    assertThat(found)
        .extracting(ConfigProblem::key)
        .containsExactlyInAnyOrder("a.port", "a.t", "a.missing", "a.token", "a.missing");
    assertThat(found).anyMatch(p -> p.message().contains("declared twice"));
  }

  @Test
  void invalidYamlIsAProblemAndOthersStillReported() throws IOException {
    write("application.yaml", "a: [unclosed\n");
    write("application-local.yaml", "- just\n- a list\n");
    Setting<String> missing = Setting.string("a.b").asRequired();
    List<ConfigProblem> found = problems(Map.of(), missing);
    assertThat(found).hasSize(3);
    assertThat(found.get(0).message()).contains("invalid YAML");
    assertThat(found.get(1).message()).contains("top level must be a mapping");
  }

  @Test
  void duplicateYamlKeysAreAProblem() throws IOException {
    write("application.yaml", "a: 1\na: 2\n");
    assertThat(problems(Map.of())).hasSize(1);
  }

  // ---- secrets ----------------------------------------------------------------------------

  @Test
  void secretToStringIsRedacted() {
    Secret s = Secret.of("hunter2");
    assertThat(s.toString()).isEqualTo("Secret[****]").doesNotContain("hunter2");
    assertThat(s.reveal()).isEqualTo("hunter2");
    assertThat(s).isEqualTo(Secret.of("hunter2")).isNotEqualTo(Secret.of("other"));
    assertThat(s.hashCode()).isEqualTo(Secret.of("other").hashCode());
    assertThat(s).isNotInstanceOf(java.io.Serializable.class);
  }

  @Test
  void configToStringRedactsSecrets() throws IOException {
    write("application.yaml", "db:\n  password: hunter2\n  user: app\n");
    Config c = load(Map.of("DB_TOKEN", "t0ken"), Setting.secret("db.password"),
        Setting.string("db.user"), Setting.secret("db.token"));
    assertThat(c.toString()).contains("db.user=app").doesNotContain("hunter2").doesNotContain("t0ken");
  }

  @Test
  void secretNeverAppearsInErrorMessages() throws IOException {
    write("application.yaml", "db:\n  password: [hunter2, hunter3]\n");
    List<ConfigProblem> found = problems(Map.of(), Setting.secret("db.password"));
    assertThat(found).hasSize(1);
    assertThat(found.toString()).doesNotContain("hunter");
  }

  @Test
  void secretInstanceKeyNeverAppearsInErrorMessages() throws IOException {
    write("application.yaml", "x:\n  instances:\n    a:\n      password: [hunter2]\n");
    InstanceConfig ic = load(Map.of()).section("x.instances.a");
    assertThatThrownBy(() -> ic.secret("password"))
        .isInstanceOf(ConfigException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("hunter2"));
  }

  @Test
  void secretCannotHaveADefault() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Setting<>("a.b", SettingType.SECRET, Secret.of("x"), false, ""));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Setting.secret("a.b").withDefault(Secret.of("x")));
  }

  @Test
  void keysMustBeDottedLowercase() {
    for (String bad : List.of("", "A.b", "a..b", "a.", ".a", "1a", "a b", "a.B")) {
      assertThatIllegalArgumentException().as(bad).isThrownBy(() -> Setting.string(bad));
    }
    Setting.string("a-b_c.d9");
  }

  // ---- instance sections ------------------------------------------------------------------

  @Test
  void instanceConfigReadsTypedValuesNestedSectionsAndDefaults() throws IOException {
    write(
        "application.yaml",
        """
        db:
          instances:
            main:
              url: jdbc:x
              migrate: false
              timeout: 5s
              pool: { max: 10, min-idle: 2 }
        """);
    InstanceConfig ic = load(Map.of("DB_INSTANCES_MAIN_POOL_MAX", "20")).section("db.instances.main");
    assertThat(ic.string("url")).isEqualTo("jdbc:x");
    assertThat(ic.bool("migrate", true)).isFalse();
    assertThat(ic.duration("timeout", Duration.ZERO)).isEqualTo(Duration.ofSeconds(5));
    assertThat(ic.section("pool").integer("max", 1)).isEqualTo(20);
    assertThat(ic.section("pool").integer("min-idle", 1)).isEqualTo(2);
    assertThat(ic.section("pool").integer("absent", 7)).isEqualTo(7);
    assertThat(ic.unreadKeys()).isEmpty();
  }

  @Test
  void instanceConfigReportsUnreadKeysAtAnyDepth() throws IOException {
    write(
        "application.yaml",
        "db:\n  instances:\n    main:\n      url: u\n      urll: typo\n      pool: { maxx: 1 }\n");
    InstanceConfig ic = load(Map.of()).section("db.instances.main");
    ic.string("url");
    assertThat(ic.unreadKeys()).containsExactly("urll", "pool.maxx");
  }

  @Test
  void missingRequiredInstanceKeyNamesKeyAndEnvVar() {
    InstanceConfig ic = load(Map.of()).section("db.instances.main");
    assertThatThrownBy(() -> ic.string("url"))
        .isInstanceOfSatisfying(
            ConfigException.class,
            e -> {
              assertThat(e.problem().key()).isEqualTo("db.instances.main.url");
              assertThat(e.getMessage()).contains("DB_INSTANCES_MAIN_URL");
            });
  }

  @Test
  void instanceConfigToStringShowsNoValues() throws IOException {
    write("application.yaml", "db:\n  instances:\n    main:\n      password: hunter2\n");
    InstanceConfig ic = load(Map.of()).section("db.instances.main");
    assertThat(ic.toString()).contains("password").doesNotContain("hunter2");
  }

  // ---- environment-variable name collisions -----------------------------------------------

  @Test
  void declaredKeysThatShareAnEnvironmentVariableFailAndNameBothModules() {
    Setting<String> dash = Setting.string("a.b-c");
    Setting<String> underscore = Setting.string("a.b_c");
    Setting<String> dotted = Setting.string("a.b.c");
    List<Declaration> declarations =
        List.of(
            new Declaration("first", dash),
            new Declaration("second", underscore),
            new Declaration("third", dotted));
    Config.Loaded loaded = Config.load(dir, Map.of(), declarations);
    assertThat(loaded).isInstanceOf(Config.Failed.class);
    List<ConfigProblem> found = ((Config.Failed) loaded).problems();
    assertThat(found).hasSize(2);
    assertThat(found.get(0).module()).isEqualTo("second");
    assertThat(found.get(0).key()).isEqualTo("a.b_c");
    assertThat(found.get(0).message())
        .contains("A_B_C")
        .contains("'a.b-c'")
        .contains("declared by module 'first'");
    assertThat(found.get(1).module()).isEqualTo("third");
    assertThat(found.get(1).key()).isEqualTo("a.b.c");
  }

  @Test
  void anInstanceKeyThatSharesAnEnvironmentVariableWithADeclaredKeyFails() throws IOException {
    write("application.yaml", "db:\n  instances:\n    main:\n      url: x\n");
    Setting<String> clash = Setting.string("db.instances-main.url");
    List<ConfigProblem> found = problems(Map.of(), clash);
    assertThat(found).hasSize(1);
    assertThat(found.get(0).key()).isEqualTo("db.instances.main.url");
    assertThat(found.get(0).message())
        .contains("DB_INSTANCES_MAIN_URL")
        .contains("'db.instances-main.url'");
  }

  @Test
  void twoInstancesWhoseNamesDifferOnlyInDashAndUnderscoreFail() throws IOException {
    write(
        "application.yaml",
        "db:\n  instances:\n    main-db:\n      url: x\n    main_db:\n      url: y\n");
    List<ConfigProblem> found = problems(Map.of());
    assertThat(found).hasSize(1);
    assertThat(found.get(0).message()).contains("DB_INSTANCES_MAIN_DB_URL");
  }

  @Test
  void distinctKeysAndTheSameKeyInTwoFilesAreNotCollisions() throws IOException {
    write("application.yaml", "db:\n  instances:\n    main:\n      url: x\n");
    write("application-local.yaml", "db:\n  instances:\n    main:\n      url: y\n");
    Setting<String> a = Setting.string("a.b-c");
    Setting<String> b = Setting.string("a.bc");
    load(Map.of(), a, b);
  }
}
