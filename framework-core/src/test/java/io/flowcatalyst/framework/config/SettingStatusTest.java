package io.flowcatalyst.framework.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the configuration reports about itself for the admin config page: never a secret value. */
class SettingStatusTest {

  @TempDir Path dir;

  private Config load(Map<String, String> env, Setting<?>... settings) {
    List<Declaration> declarations = java.util.Arrays.stream(settings).map(s -> new Declaration("mod", s)).toList();
    Config.Loaded loaded = Config.load(dir, env, declarations);
    if (loaded instanceof Config.Failed f) {
      throw new AssertionError(f.problems().toString());
    }
    return ((Config.Ok) loaded).config();
  }

  private static SettingStatus status(Config c, String key) {
    return c.statuses().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
  }

  @Test
  void aSecretReportsThatItIsSetAndFromWhereButNeverItsValue() {
    Setting<Secret> s = Setting.secret("db.password");
    Config c = load(Map.of("DB_PASSWORD", "hunter2-very-secret"), s);
    SettingStatus st = status(c, "db.password");
    assertThat(st.secret()).isTrue();
    assertThat(st.state()).isEqualTo(SettingStatus.State.SET);
    assertThat(st.source()).isEqualTo("environment variable DB_PASSWORD");
    assertThat(st.value()).isNull();
    assertThat(c.statuses().toString()).doesNotContain("hunter2");
  }

  @Test
  void aSecretFromAFileNamesTheFileAndStillHasNoValue() throws IOException {
    Files.writeString(dir.resolve("application.yaml"), "api:\n  token: tok-123456\n");
    Config c = load(Map.of(), Setting.secret("api.token"));
    SettingStatus st = status(c, "api.token");
    assertThat(st.source()).contains("application.yaml");
    assertThat(st.value()).isNull();
    assertThat(c.statuses().toString()).doesNotContain("tok-123456");
  }

  @Test
  void anUnsetOptionalSecretIsUnset() {
    SettingStatus st = status(load(Map.of(), Setting.secret("api.token")), "api.token");
    assertThat(st.state()).isEqualTo(SettingStatus.State.UNSET);
    assertThat(st.value()).isNull();
  }

  @Test
  void aPlainSettingShowsItsValueAndSource() {
    Setting<Duration> s = Setting.duration("http.timeout").withDefault(Duration.ofSeconds(30));
    Config c = load(Map.of(), s);
    SettingStatus st = status(c, "http.timeout");
    assertThat(st.state()).isEqualTo(SettingStatus.State.DEFAULT);
    assertThat(st.source()).isEqualTo("default");
    assertThat(st.value()).isEqualTo("PT30S");
    assertThat(st.module()).isEqualTo("mod");

    SettingStatus fromEnv = status(load(Map.of("HTTP_TIMEOUT", "5s"), s), "http.timeout");
    assertThat(fromEnv.state()).isEqualTo(SettingStatus.State.SET);
    assertThat(fromEnv.value()).isEqualTo("PT5S");
  }

  @Test
  void aStringThatIsNamedLikeACredentialIsWithheldEvenIfNotDeclaredSecret() {
    Setting<String> s = Setting.string("mail.api-key");
    SettingStatus st = status(load(Map.of("MAIL_API_KEY", "key-abc-999"), s), "mail.api-key");
    assertThat(st.state()).isEqualTo(SettingStatus.State.SET);
    assertThat(st.value()).isNull();
    assertThat(st.withheld()).isTrue();
  }

  @Test
  void aUriLosesItsCredentialsAndQuery() {
    Setting<java.net.URI> s = Setting.uri("db.url");
    SettingStatus st = status(load(Map.of("DB_URL", "postgres://app:pw123@db.internal:5432/x?sslpassword=zzz"), s), "db.url");
    assertThat(st.value()).isEqualTo("postgres://db.internal:5432/x");
    assertThat(st.toString()).doesNotContain("pw123").doesNotContain("zzz");
  }

  @Test
  void anOpaqueJdbcUriLosesItsCredentialsToo() {
    Setting<java.net.URI> s = Setting.uri("db.url");
    SettingStatus st = status(load(Map.of("DB_URL", "jdbc:postgresql://app:pw123@db.internal:5432/x?password=zzz"), s), "db.url");
    assertThat(st.value()).isEqualTo("jdbc:postgresql://db.internal:5432/x");
  }

  @Test
  void aRequiredSettingThatLoadedIsSet() {
    SettingStatus st = status(load(Map.of("APP_NAME", "x"), Setting.string("app.name").asRequired()), "app.name");
    assertThat(st.required()).isTrue();
    assertThat(st.state()).isEqualTo(SettingStatus.State.SET);
  }
}
