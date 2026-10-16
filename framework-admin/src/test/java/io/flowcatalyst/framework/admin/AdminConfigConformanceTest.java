package io.flowcatalyst.framework.admin;

import static io.flowcatalyst.framework.admin.AdminFixture.APP;

import io.flowcatalyst.framework.conformance.Conformance;
import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.TestResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes (or, without {@code -Dconformance.update=true}, compares) {@code
 * conformance/admin/config.json}: for a fixed set of declared settings and environment, what the
 * config section answers (admin contract section 10). The Go side compares. The point of the vector
 * is the masking: a secret's text, a credential-named key's text and a URL's user info never appear.
 */
class AdminConfigConformanceTest {

  static final String DESCRIPTION =
      "GET /api/admin/config and /api/admin/config/{key} (admin contract section 10). Setup: application 'acme' with the"
          + " admin module and the config module; module 'demo' declares 'declare' in order; the process environment is"
          + " 'environment' and no config files exist. A caller holding acme:admin:config:view gets 200 with the settings"
          + " that match the filters (state, secret, q = case-insensitive substring of key or module) in declaration order,"
          + " plus total (matching), set/defaulted/unset (of all) and the health of the application; otherwise 403 FORBIDDEN."
          + " Per setting: state SET (environment or file gave a value), DEFAULT, UNSET; source 'environment variable NAME',"
          + " 'default' or 'not set'; value only when safe: never for a secret, never for a key whose last words name a"
          + " credential (secret, password, passwd, passphrase, token, credential, api-key, private-key, access-key, signing-key,"
          + " salt), URLs without user info and query; withheld=true marks a value that exists and is not shown. Durations are"
          + " ISO-8601 (PT30S). Absent optional fields (value, description) are omitted, not null. The effective values are"
          + " never part of an error. An implementation declares settings of its own as well (module 'core' and others):"
          + " the expectations list only the settings of module 'demo', and the counts (total, set, defaulted, unset) are"
          + " not compared; 'type' is human text and is not compared; 'ready' is the readiness of the case's application. Settings are compared as parsed JSON.";

  record Decl(String key, String type, String defaultValue, boolean required, String description) {}

  static final List<Decl> DECLARE =
      List.of(
          new Decl("demo.password", "secret", null, false, "The database password."),
          new Decl("demo.unused-token", "secret", null, false, null),
          new Decl("demo.mail-api-key", "string", null, false, null),
          new Decl("demo.timeout", "duration", "30s", false, "Request timeout."),
          new Decl("demo.workers", "integer", null, false, null),
          new Decl("demo.url", "uri", null, false, null),
          new Decl("demo.name", "string", null, true, null),
          new Decl("demo.tags", "list", null, false, null),
          new Decl("demo.enabled", "bool", "true", false, null));

  static final Map<String, String> ENVIRONMENT =
      Map.of(
          "APP_ENV", "test",
          "DEMO_PASSWORD", "s3cr3t-value-DO-NOT-SHOW",
          "DEMO_MAIL_API_KEY", "key-material-DO-NOT-SHOW",
          "DEMO_WORKERS", "4",
          "DEMO_URL", "https://user:pw999@api.example.test:8443/v1?token=abc",
          "DEMO_NAME", "orders",
          "DEMO_TAGS", "a,b");

  static Setting<?> setting(Decl d) {
    Setting<?> s =
        switch (d.type()) {
          case "secret" -> Setting.secret(d.key());
          case "string" -> Setting.string(d.key());
          case "integer" -> Setting.integer(d.key());
          case "uri" -> Setting.uri(d.key());
          case "list" -> Setting.stringList(d.key());
          case "bool" -> Setting.bool(d.key()).withDefault(Boolean.parseBoolean(d.defaultValue() == null ? "false" : d.defaultValue()));
          case "duration" -> Setting.duration(d.key()).withDefault(Duration.ofSeconds(30));
          default -> throw new IllegalArgumentException(d.type());
        };
    if (d.required()) {
      s = required(s);
    }
    return d.description() == null ? s : described(s, d.description());
  }

  private static <T> Setting<T> required(Setting<T> s) {
    return s.asRequired();
  }

  private static <T> Setting<T> described(Setting<T> s, String text) {
    return s.describedAs(text);
  }

  record Case(String name, String path, List<String> held) {}

  static final String VIEW = "acme:admin:config:view";

  static final List<Case> CASES =
      List.of(
          new Case("everything", "/api/admin/config", List.of(VIEW)),
          new Case("by wildcard", "/api/admin/config", List.of("acme:admin:*:*")),
          new Case("not set only", "/api/admin/config?state=UNSET", List.of(VIEW)),
          new Case("defaults only", "/api/admin/config?state=DEFAULT", List.of(VIEW)),
          new Case("secrets only", "/api/admin/config?secret=true", List.of(VIEW)),
          new Case("text is a case-insensitive substring of the key", "/api/admin/config?q=WORKERS", List.of(VIEW)),
          new Case("text matches the module too", "/api/admin/config?q=dem", List.of(VIEW)),
          new Case("nothing matches", "/api/admin/config?q=zzz", List.of(VIEW)),
          new Case("one secret", "/api/admin/config/demo.password", List.of(VIEW)),
          new Case("one credential-named string", "/api/admin/config/demo.mail-api-key", List.of(VIEW)),
          new Case("one url", "/api/admin/config/demo.url", List.of(VIEW)),
          new Case("one unknown key", "/api/admin/config/no.such.key", List.of(VIEW)),
          new Case("the console permission alone", "/api/admin/config", List.of("acme:admin:console:view")),
          new Case("no permissions", "/api/admin/config", List.of()),
          new Case("no permissions, one key", "/api/admin/config/demo.password", List.of()));

  @Test
  void theConfigVectorIsCurrent() throws Exception {
    JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    Map<String, Object> vector = new LinkedHashMap<>();
    vector.put("description", DESCRIPTION);
    vector.put("application", "acme");
    vector.put("environment", new java.util.TreeMap<>(ENVIRONMENT));
    List<Map<String, Object>> declare = new ArrayList<>();
    for (Decl d : DECLARE) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("key", d.key());
      m.put("type", d.type());
      if (d.defaultValue() != null) {
        m.put("default", d.defaultValue());
      }
      if (d.required()) {
        m.put("required", true);
      }
      if (d.description() != null) {
        m.put("description", d.description());
      }
      declare.add(m);
    }
    vector.put("declare", declare);

    Module settings =
        new Module() {
          @Override
          public String id() {
            return "demo";
          }

          @Override
          public void register(AppBuilder app) {
            for (Decl d : DECLARE) {
              app.declare(setting(d));
            }
          }
        };
    List<Map<String, Object>> cases = new ArrayList<>();
    try (App app = AdminFixture.builder(ENVIRONMENT, AdminConfigModule.of(APP), settings).start()) {
      for (Case c : CASES) {
        TestResponse r = AdminFixture.client(app).get(c.path()).header("X-Perms", String.join(",", c.held())).send();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", c.name());
        entry.put("path", c.path());
        entry.put("permissions", c.held());
        Map<String, Object> expect = new LinkedHashMap<>();
        expect.put("status", r.status());
        JsonNode tree = r.tree();
        if (r.status() == 200) {
          Object body = mapper.readValue(r.body(), Object.class);
          if (body instanceof Map<?, ?> m && m.containsKey("health")) {
            // the health of the fixture application is the same for every case; keep the readiness only
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("environment", m.get("environment"));
            List<Object> demo = new ArrayList<>();
            for (Object o : (List<?>) m.get("settings")) {
              if ("demo".equals(((Map<?, ?>) o).get("module"))) {
                demo.add(o);
              }
            }
            copy.put("settings", demo);
            copy.put("ready", ((Map<?, ?>) m.get("health")).get("ready"));
            body = copy;
          }
          expect.put("body", body);
        } else {
          expect.put("error", tree.get("error").asString());
        }
        entry.put("expect", expect);
        cases.add(entry);
      }
    }
    vector.put("cases", cases);
    Conformance.check("admin/config.json", mapper.writeValueAsString(vector) + "\n");
  }
}
