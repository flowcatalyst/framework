package io.flowcatalyst.framework.admin;

import static io.flowcatalyst.framework.admin.AdminFixture.APP;
import static io.flowcatalyst.framework.admin.AdminFixture.CONSOLE;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.TestResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The config section (M4e slice 3): settings, where they came from, secrets never shown. */
class AdminConfigTest {

  static final String VIEW = "acme:admin:config:view";
  static final String SECRET_TEXT = "s3cr3t-value-DO-NOT-SHOW";
  static final String CREDENTIAL_TEXT = "key-material-DO-NOT-SHOW";

  /** Declares a few settings of different kinds. */
  static Module settings() {
    return new Module() {
      @Override
      public String id() {
        return "demo";
      }

      @Override
      public void register(AppBuilder app) {
        app.declare(Setting.secret("demo.password").describedAs("The database password."));
        app.declare(Setting.secret("demo.unused-token"));
        app.declare(Setting.string("demo.mail-api-key"));
        app.declare(Setting.duration("demo.timeout").withDefault(Duration.ofSeconds(30)));
        app.declare(Setting.integer("demo.workers"));
        app.declare(Setting.uri("demo.url"));
      }
    };
  }

  static App start() {
    return AdminFixture.builder(
            Map.of(
                "APP_ENV", "test",
                "DEMO_PASSWORD", SECRET_TEXT,
                "DEMO_MAIL_API_KEY", CREDENTIAL_TEXT,
                "DEMO_WORKERS", "4",
                "DEMO_URL", "https://user:pw999@api.example.test:8443/v1?token=abc"),
            AdminConfigModule.of(APP),
            settings())
        .start();
  }

  static TestResponse get(App app, String path, String perms) {
    return AdminFixture.client(app).get(path).header("X-Perms", perms).send();
  }

  static JsonNode setting(JsonNode list, String key) {
    for (JsonNode s : list.get("settings")) {
      if (s.get("key").asString().equals(key)) {
        return s;
      }
    }
    throw new AssertionError("no setting " + key + " in " + list);
  }

  @Test
  void aSecretIsReportedAsSetWithItsSourceAndNeverItsValue() {
    try (App app = start()) {
      TestResponse r = get(app, "/api/admin/config", VIEW);

      assertThat(r.status()).isEqualTo(200);
      JsonNode pw = setting(r.tree(), "demo.password");
      assertThat(pw.get("secret").asBoolean()).isTrue();
      assertThat(pw.get("state").asString()).isEqualTo("SET");
      assertThat(pw.get("source").asString()).isEqualTo("environment variable DEMO_PASSWORD");
      assertThat(pw.has("value")).isFalse();
      assertThat(pw.get("withheld").asBoolean()).isTrue();
      assertThat(setting(r.tree(), "demo.unused-token").get("state").asString()).isEqualTo("UNSET");
      assertThat(r.body()).doesNotContain(SECRET_TEXT);
    }
  }

  @Test
  void aCredentialNamedStringAndAUrlsCredentialsAreNeverShownEither() {
    try (App app = start()) {
      TestResponse r = get(app, "/api/admin/config", VIEW);

      assertThat(setting(r.tree(), "demo.mail-api-key").has("value")).isFalse();
      assertThat(setting(r.tree(), "demo.url").get("value").asString()).isEqualTo("https://api.example.test:8443/v1");
      assertThat(r.body()).doesNotContain(CREDENTIAL_TEXT).doesNotContain("pw999").doesNotContain("token=abc");
    }
  }

  @Test
  void plainSettingsShowValueAndWhereItCameFrom() {
    try (App app = start()) {
      JsonNode list = get(app, "/api/admin/config", VIEW).tree();

      JsonNode timeout = setting(list, "demo.timeout");
      assertThat(timeout.get("state").asString()).isEqualTo("DEFAULT");
      assertThat(timeout.get("source").asString()).isEqualTo("default");
      assertThat(timeout.get("value").asString()).isEqualTo("PT30S");
      assertThat(setting(list, "demo.workers").get("value").asString()).isEqualTo("4");
      assertThat(list.get("environment").asString()).isEqualTo("test");
      assertThat(list.get("health").get("ready").asBoolean()).isTrue();
    }
  }

  @Test
  void theListFiltersByStateSecretAndText() {
    try (App app = start()) {
      JsonNode unset = get(app, "/api/admin/config?state=UNSET", VIEW).tree();
      assertThat(unset.get("settings")).extracting(n -> n.get("key").asString()).containsExactly("demo.unused-token");
      assertThat(unset.get("settings")).allSatisfy(n -> assertThat(n.get("state").asString()).isEqualTo("UNSET"));

      JsonNode secrets = get(app, "/api/admin/config?secret=true", VIEW).tree();
      assertThat(secrets.get("settings")).extracting(n -> n.get("key").asString()).containsExactly("demo.password", "demo.unused-token");

      JsonNode found = get(app, "/api/admin/config?q=WORKERS", VIEW).tree();
      assertThat(found.get("settings")).extracting(n -> n.get("key").asString()).containsExactly("demo.workers");
      assertThat(found.get("total").asInt()).isEqualTo(1);
    }
  }

  @Test
  void oneSettingByKeyAnd404ForAnUnknownOne() {
    try (App app = start()) {
      TestResponse one = get(app, "/api/admin/config/demo.password", VIEW);
      assertThat(one.status()).isEqualTo(200);
      assertThat(one.tree().get("description").asString()).isEqualTo("The database password.");
      assertThat(one.body()).doesNotContain(SECRET_TEXT);

      TestResponse missing = get(app, "/api/admin/config/no.such.key", VIEW);
      assertThat(missing.status()).isEqualTo(404);
      assertThat(missing.tree().get("error").asString()).isEqualTo("SETTING_NOT_FOUND");
    }
  }

  @Test
  void bothOperationsNeedTheConfigPermissionAndNothingElseOpensThem() {
    try (App app = start()) {
      for (String path : new String[] {"/api/admin/config", "/api/admin/config/demo.password"}) {
        TestResponse none = get(app, path, "");
        assertThat(none.status()).as(path).isEqualTo(403);
        assertThat(none.tree().get("error").asString()).isEqualTo("FORBIDDEN");
        assertThat(none.tree().get("message").asString()).isEqualTo("this needs the permission " + VIEW);
        assertThat(none.body()).as(path).doesNotContain(SECRET_TEXT);
        // the console permission opens the area, not this section
        assertThat(get(app, path, CONSOLE).status()).as(path).isEqualTo(403);
        assertThat(get(app, path, "acme:admin:roles:view").status()).as(path).isEqualTo(403);
        assertThat(get(app, path, "acme:admin:*:*").status()).as(path).isNotEqualTo(403);
      }
      assertThat(AdminFixture.client(app).get("/api/admin/config").send().status()).isEqualTo(401);
    }
  }

  @Test
  void theNavigationListsTheConfigSectionOnlyForItsHolders() {
    try (App app = start()) {
      JsonNode both = get(app, "/api/admin/navigation", CONSOLE + "," + VIEW).tree();
      assertThat(both.get("sections")).extracting(n -> n.get("id").asString()).containsExactly("about", "config");
      JsonNode consoleOnly = get(app, "/api/admin/navigation", CONSOLE).tree();
      assertThat(consoleOnly.get("sections")).extracting(n -> n.get("id").asString()).containsExactly("about");
    }
  }

  @Test
  void valuesCanBeSwitchedOffForTheWholeSection() {
    try (App app =
        AdminFixture.builder(Map.of("APP_ENV", "test", "DEMO_WORKERS", "4"), AdminConfigModule.of(APP).withoutValues(), settings())
            .start()) {
      JsonNode workers = setting(get(app, "/api/admin/config", VIEW).tree(), "demo.workers");
      assertThat(workers.get("state").asString()).isEqualTo("SET");
      assertThat(workers.has("value")).isFalse();
      assertThat(workers.get("withheld").asBoolean()).isTrue();
    }
  }
}
