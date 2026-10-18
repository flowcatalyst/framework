package io.flowcatalyst.framework.settings;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.conformance.JsonVectors;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.usecase.Authorize;
import io.flowcatalyst.framework.usecase.DomainEvent;
import io.flowcatalyst.framework.usecase.EventMetadata;
import io.flowcatalyst.framework.usecase.EventType;
import io.flowcatalyst.framework.usecase.Events;
import io.flowcatalyst.framework.usecase.OutboxModule;
import io.flowcatalyst.framework.usecase.Plan;
import io.flowcatalyst.framework.usecase.UseCase;
import io.flowcatalyst.framework.usecase.UseCaseModule;
import io.flowcatalyst.framework.usecase.UseCaseResult;
import io.flowcatalyst.framework.usecase.UseCases;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes and checks {@code conformance/settings/client-settings.json} and
 * {@code conformance/migrations/client-settings.json} (client-settings contract sections 2 to 5):
 * the setting keys, and scripted sequences of reads and writes against {@code fw_client_settings}
 * with the stored rows after each. Java writes them; Go compares.
 */
class ClientSettingsConformanceTest {

  static final String FILE = "settings/client-settings.json";
  static final String HISTORY = "migrations/client-settings.json";
  static final int MINUTES_MAX = 43_200;
  static final EventType CHANGED = new EventType("settings-test", "tests", "settings", "changed");

  record Sla(int low, int normal, int high, int urgent) {}

  enum Mode {
    MANUAL,
    AUTO
  }

  record Changed(EventMetadata metadata, String client) implements DomainEvent {}

  record Save(String client) {}

  /** The vector's catalog of settings: name, key, type, default (as JSON) and rule. */
  static final Map<String, ClientSetting<?>> CATALOG = new HashMap<>();

  static {
    CATALOG.put(
        "sla",
        ClientSetting.of("tickets.sla", Sla.class, new Sla(2880, 1440, 480, 60))
            .rule(
                m -> {
                  for (int minutes : new int[] {m.low(), m.normal(), m.high(), m.urgent()}) {
                    if (minutes < 1 || minutes > MINUTES_MAX) {
                      return Optional.of("Each time is 1 to " + MINUTES_MAX + " minutes.");
                    }
                  }
                  if (!(m.urgent() <= m.high() && m.high() <= m.normal() && m.normal() <= m.low())) {
                    return Optional.of("A more urgent ticket cannot be given more time.");
                  }
                  return Optional.empty();
                }));
    CATALOG.put("prefix", ClientSetting.of("invoice.prefix", String.class, "INV-"));
    CATALOG.put("mode", ClientSetting.of("billing.mode", Mode.class, Mode.MANUAL));
    CATALOG.put("limit", ClientSetting.of("api.limit", Integer.class, 100));
    CATALOG.put("ratio", ClientSetting.of("tax.ratio", Double.class, 0.2));
    CATALOG.put("big", ClientSetting.of("counter.big", Long.class, 0L));
    CATALOG.put("tags", ClientSetting.of("ticket.tags", List.class, List.of()));
    CATALOG.put("flag", ClientSetting.of("ui.beta", Boolean.class, false));
  }

  static PostgresFixture pg;

  @TempDir Path dir;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stopPostgres() {
    pg.close();
  }

  // ---- the scripts -------------------------------------------------------------------------

  static ObjectNode op(String op, Object... kv) {
    ObjectNode n = JsonVectors.mapper().createObjectNode();
    n.put("op", op);
    for (int i = 0; i < kv.length; i += 2) {
      String key = (String) kv[i];
      Object value = kv[i + 1];
      if (value instanceof JsonNode node) {
        n.set(key, node);
      } else if (value instanceof String s) {
        n.put(key, s);
      } else if (value instanceof Long l) {
        n.put(key, l);
      } else if (value instanceof Integer i2) {
        n.put(key, i2);
      } else {
        throw new IllegalArgumentException(key);
      }
    }
    return n;
  }

  static JsonNode json(String text) {
    return JsonVectors.mapper().readTree(text);
  }

  record Script(String name, List<ObjectNode> ops) {}

  static Script script(String name, ObjectNode... ops) {
    return new Script(name, List.of(ops));
  }

  static List<Script> scripts() {
    List<Script> s = new ArrayList<>();
    s.add(script("no row gives the default", op("get", "client", "acme", "setting", "sla"), op("get", "client", "acme", "setting", "prefix"), op("load", "as", "a", "client", "acme")));
    s.add(
        script(
            "a written value is read back and another client is untouched",
            op("set", "client", "acme", "setting", "sla", "value", json("{\"low\":100,\"normal\":50,\"high\":20,\"urgent\":10}")),
            op("get", "client", "acme", "setting", "sla"),
            op("get", "client", "globex", "setting", "sla"),
            op("get", "client", "acme", "setting", "prefix")));
    s.add(
        script(
            "version increments by one per write and several settings share the row",
            op("set", "client", "acme", "setting", "prefix", "value", json("\"ACME-\"")),
            op("set", "client", "acme", "setting", "mode", "value", json("\"AUTO\"")),
            op("set", "client", "acme", "setting", "limit", "value", json("250")),
            op("set", "client", "globex", "setting", "prefix", "value", json("\"G-\"")),
            op("get", "client", "acme", "setting", "prefix"),
            op("get", "client", "acme", "setting", "mode"),
            op("get", "client", "acme", "setting", "limit")));
    s.add(
        script(
            "without removes the key and restores the default",
            op("set", "client", "acme", "setting", "prefix", "value", json("\"ACME-\"")),
            op("set", "client", "acme", "setting", "limit", "value", json("5")),
            op("unset", "client", "acme", "setting", "prefix"),
            op("get", "client", "acme", "setting", "prefix"),
            op("get", "client", "acme", "setting", "limit"),
            op("unset", "client", "acme", "setting", "prefix")));
    s.add(
        script(
            "reading: a row without the key gives the default",
            op("rawSet", "client", "acme", "version", 3, "settings", json("{\"other.key\":1}")),
            op("get", "client", "acme", "setting", "prefix")));
    s.add(
        script(
            "reading: a value that does not decode is an error naming the key and the client",
            op("rawSet", "client", "acme", "version", 1, "settings", json("{\"tickets.sla\":\"seven\"}")),
            op("get", "client", "acme", "setting", "sla"),
            op("rawSet", "client", "globex", "version", 1, "settings", json("{\"billing.mode\":\"SOMETIMES\"}")),
            op("get", "client", "globex", "setting", "mode"),
            op("rawSet", "client", "initech", "version", 1, "settings", json("{\"api.limit\":\"12\",\"counter.big\":true,\"ui.beta\":\"yes\"}")),
            op("get", "client", "initech", "setting", "limit", "note", "Java (Jackson) coerces the string \"12\" to the integer 12; a stricter reader may refuse it, recorded as a Java leniency"),
            op("get", "client", "initech", "setting", "big"),
            op("get", "client", "initech", "setting", "flag")));
    s.add(
        script(
            "reading: a stored null and an array where an object is expected",
            op("rawSet", "client", "acme", "version", 1, "settings", json("{\"invoice.prefix\":null,\"tickets.sla\":[1,2]}")),
            op("get", "client", "acme", "setting", "prefix", "note", "a stored JSON null for a key: the store answers the default (ClientSettings.get itself would return null; see the load op's note)"),
            op("get", "client", "acme", "setting", "sla")));
    s.add(
        script(
            "reading: unknown fields in a stored record are ignored and missing ones are zero",
            op("rawSet", "client", "acme", "version", 1, "settings", json("{\"tickets.sla\":{\"low\":5,\"normal\":4,\"high\":3,\"urgent\":1,\"extra\":true}}")),
            op("get", "client", "acme", "setting", "sla"),
            op("rawSet", "client", "globex", "version", 1, "settings", json("{\"tickets.sla\":{\"low\":5}}")),
            op("get", "client", "globex", "setting", "sla")));
    s.add(
        script(
            "reading: a number stored as a float for an integer setting",
            op("rawSet", "client", "acme", "version", 1, "settings", json("{\"api.limit\":12.0,\"counter.big\":12345678901234,\"tax.ratio\":1}")),
            op("get", "client", "acme", "setting", "limit", "note", "a float with a zero fraction decodes as an integer; Jackson default"),
            op("get", "client", "acme", "setting", "big"),
            op("get", "client", "acme", "setting", "ratio", "note", "an integer decodes as a double")));
    s.add(
        script(
            "rules: a refused value throws with the rule message",
            op("set", "client", "acme", "setting", "sla", "value", json("{\"low\":10,\"normal\":20,\"high\":30,\"urgent\":40}")),
            op("set", "client", "acme", "setting", "sla", "value", json("{\"low\":0,\"normal\":20,\"high\":30,\"urgent\":40}")),
            op("set", "client", "acme", "setting", "sla", "value", json("{\"low\":43201,\"normal\":20,\"high\":30,\"urgent\":4}")),
            op("check", "setting", "sla", "value", json("{\"low\":10,\"normal\":20,\"high\":30,\"urgent\":40}")),
            op("check", "setting", "sla", "value", json("{\"low\":100,\"normal\":50,\"high\":20,\"urgent\":10}")),
            op("check", "setting", "sla", "value", json("{\"low\":43200,\"normal\":43200,\"high\":43200,\"urgent\":1}"))));
    s.add(
        script(
            "stored JSON for a record, an enum, a string, numbers, a list and a flag",
            op("set", "client", "acme", "setting", "sla", "value", json("{\"low\":100,\"normal\":50,\"high\":20,\"urgent\":10}")),
            op("set", "client", "acme", "setting", "mode", "value", json("\"AUTO\"")),
            op("set", "client", "acme", "setting", "prefix", "value", json("\"a<b>&c \\\"q\\\" \\\\ \\n \\u00e9 \\u2028 \\ud83d\\ude00\"")),
            op("set", "client", "acme", "setting", "limit", "value", json("2147483647")),
            op("set", "client", "acme", "setting", "big", "value", json("9007199254740993")),
            op("set", "client", "acme", "setting", "ratio", "value", json("0.1")),
            op("set", "client", "acme", "setting", "tags", "value", json("[\"b\",\"a\",\"b\"]")),
            op("set", "client", "acme", "setting", "flag", "value", json("true")),
            op("get", "client", "acme", "setting", "prefix"),
            op("get", "client", "acme", "setting", "big"),
            op("get", "client", "acme", "setting", "tags")));
    s.add(
        script(
            "stored JSON for floating point numbers",
            op("set", "client", "acme", "setting", "ratio", "value", json("1.0")),
            op("set", "client", "globex", "setting", "ratio", "value", json("1.5E10")),
            op("set", "client", "initech", "setting", "ratio", "value", json("0.000001")),
            op("set", "client", "umbrella", "setting", "ratio", "value", json("-0.0"))));
    s.add(
        script(
            "two writers on one row: the loser is a stale aggregate",
            op("set", "client", "acme", "setting", "prefix", "value", json("\"A-\"")),
            op("load", "as", "first", "client", "acme"),
            op("load", "as", "second", "client", "acme"),
            op("with", "as", "first", "setting", "prefix", "value", json("\"ONE-\"")),
            op("with", "as", "second", "setting", "prefix", "value", json("\"TWO-\"")),
            op("save", "as", "first"),
            op("save", "as", "second"),
            op("get", "client", "acme", "setting", "prefix")));
    s.add(
        script(
            "two first writes at once: the second is a unique violation",
            op("load", "as", "a", "client", "acme"),
            op("load", "as", "b", "client", "acme"),
            op("with", "as", "a", "setting", "prefix", "value", json("\"A-\"")),
            op("with", "as", "b", "setting", "prefix", "value", json("\"B-\"")),
            op("save", "as", "a"),
            op("save", "as", "b"),
            op("get", "client", "acme", "setting", "prefix")));
    s.add(
        script(
            "the aggregate's id is the client code and the repository finds only the caller's client",
            op("set", "client", "acme", "setting", "prefix", "value", json("\"A-\"")),
            op("find", "client", "acme", "id", "acme"),
            op("find", "client", "globex", "id", "acme"),
            op("find", "client", "acme", "id", "globex"),
            op("load", "as", "a", "client", "acme")));
    s.add(
        script(
            "a settings aggregate of another client is refused by the runner",
            op("load", "as", "g", "client", "globex"),
            op("with", "as", "g", "setting", "prefix", "value", json("\"G-\"")),
            op("saveAs", "as", "g", "client", "acme"),
            op("get", "client", "globex", "setting", "prefix")));
    return s;
  }

  static final List<String> KEYS =
      List.of(
          "a", "tickets.sla", "a.b_c-d", "x".repeat(100), "", "Tickets", "1a", "x".repeat(101), "a:b", "a b", "a/b", "_a", "-a", ".a", "a@b", "é", "a\n");

  // ---- the test ----------------------------------------------------------------------------

  @Test
  void theVectorsMatchWhatTheJavaModuleDoes() throws Exception {
    ObjectNode doc = JsonVectors.mapper().createObjectNode();
    doc.put(
        "description",
        "Per-client settings (client-settings contract sections 2 to 5). Table fw_client_settings(client_id TEXT PRIMARY KEY,"
            + " version BIGINT NOT NULL, settings JSONB NOT NULL DEFAULT '{}' CHECK object), created by the module's migration"
            + " set 'client-settings' (migrations/client-settings.json). keys: ClientSetting keys, accepted or refused"
            + " ([a-z][a-z0-9._-]{0,99}). catalog: the settings the scripts use, name -> key, type, default, and the rule"
            + " of 'sla' (each of low, normal, high, urgent in 1..43200, and urgent <= high <= normal <= low; the messages are"
            + " the rule results; the codes MINUTES_INVALID and POLICY_NOT_ORDERED belong to the application that owns the rule,"
            + " the framework only carries the message). scripts: each runs on an empty table; ops in order: get (the store's"
            + " read: value or error), set (load the client's aggregate or an empty one, apply 'with' then save through a use"
            + " case: result committed or failed with the error code, or a thrown IllegalArgumentException with its message),"
            + " unset (without + save), rawSet (insert or replace the row directly), load / with / without / save / saveAs"
            + " (named aggregates, to interleave writers; saveAs saves it run as another client), find (repository find"
            + " with the caller's client and an id), check (the setting's rule alone). expect per op: ok + value, or error"
            + " {exception, message} ('messageJava' is Java's text and is not compared except that it names the key and the"
            + " client where the contract says so); rows after each script: client_id, version, settings (parsed JSON),"
            + " storedText (jsonb::text exactly as PostgreSQL prints it, which normalises key order, spacing and number"
            + " spelling, so a writer in another language need only produce JSON that is equal as jsonb; the text Java"
            + " sends is not recorded because its key order is unspecified, the aggregate holds an immutable map).");
    doc.put(
        "howToRun",
        "Per script: empty the table, run the ops in order with the catalog, compare each op's expect and the rows. Regenerate"
            + " from Java with -Dconformance.update=true (ClientSettingsConformanceTest).");
    ArrayNode keys = doc.putArray("keys");
    for (String key : KEYS) {
      ObjectNode k = keys.addObject();
      k.put("key", key);
      boolean accepted;
      try {
        ClientSetting.of(key, String.class, "x");
        accepted = true;
      } catch (IllegalArgumentException e) {
        accepted = false;
      }
      k.put("accepted", accepted);
    }
    ObjectNode catalog = doc.putObject("catalog");
    for (Map.Entry<String, ClientSetting<?>> e : CATALOG.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      ObjectNode c = catalog.putObject(e.getKey());
      c.put("key", e.getValue().key());
      c.put("type", e.getValue().type().getSimpleName());
      c.set("default", SettingsJson.mapper().valueToTree(e.getValue().defaultValue()));
    }
    ArrayNode out = doc.putArray("scripts");
    for (Script sc : scripts()) {
      ObjectNode item = out.addObject();
      item.put("name", sc.name());
      ArrayNode ops = item.putArray("ops");
      ArrayNode results = item.putArray("expect");
      Run run = start();
      try {
        for (ObjectNode op : sc.ops()) {
          ops.add(op);
          results.add(run.apply(op));
        }
        ArrayNode rows = item.putArray("rows");
        run.rows(rows);
      } finally {
        run.close();
      }
    }
    JsonVectors.check(FILE, doc);
  }

  @Test
  void theMigrationHistoryMatches() throws Exception {
    Run run = start();
    try {
      ObjectNode doc = JsonVectors.mapper().createObjectNode();
      doc.put(
          "description",
          "The fw_schema_history rows the Java migration runner writes for the set of module client-settings (applied_at left out)."
              + " Same form as locks-database.json. The migration file is db/migration/client-settings/V0001__create_client_settings.sql.");
      doc.put("module", ClientSettingsModule.ID);
      ArrayNode rows = doc.putArray("rows");
      try (Connection c = run.db.dataSource().getConnection();
          Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery("SELECT version, description, checksum FROM fw_schema_history WHERE module = 'client-settings' ORDER BY version")) {
        while (rs.next()) {
          ObjectNode r = rows.addObject();
          r.put("version", rs.getInt(1));
          r.put("description", rs.getString(2));
          r.put("checksum", rs.getString(3));
        }
      }
      JsonVectors.check(HISTORY, doc);
    } finally {
      run.close();
    }
  }

  private Run start() throws IOException {
    pg.reset();
    Path cfg = Files.createTempDirectory(dir, "app");
    Files.writeString(
        cfg.resolve("application.yaml"),
        """
        database:
          instances:
            main: { url: %s, username: %s, password: %s }
        """
            .formatted(pg.jdbcUrl(), pg.username(), pg.password()));
    App app =
        App.builder()
            .configDirectory(cfg)
            .environment(Map.of())
            .module(new DatabaseModule())
            .module(new OutboxModule())
            .module(new UseCaseModule())
            .module(new ClientSettingsModule())
            .start();
    return new Run(app);
  }

  /** One script's world. */
  static final class Run {
    final App app;
    final Database db;
    final ClientSettingsRepository repo;
    final ClientSettingsStore store;
    final UseCases useCases;
    final Events events;
    final Map<String, ClientSettings> named = new HashMap<>();

    Run(App app) {
      this.app = app;
      this.db = app.get(Databases.class).defaultInstance();
      this.repo = app.get(ClientSettingsRepository.class);
      this.store = app.get(ClientSettingsStore.class);
      this.useCases = app.get(UseCases.class);
      this.events = app.get(Events.class);
    }

    void close() {
      app.close();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ClientSetting<Object> setting(String name) {
      return (ClientSetting) CATALOG.get(name);
    }

    private static Object typed(ClientSetting<?> s, JsonNode value) {
      return SettingsJson.mapper().treeToValue(value, s.type());
    }

    ObjectNode apply(ObjectNode op) throws Exception {
      ObjectNode r = JsonVectors.mapper().createObjectNode();
      String kind = op.get("op").asString();
      try {
        switch (kind) {
          case "get" -> {
            Object v = store.get(new ClientId(op.get("client").asString()), setting(op.get("setting").asString()));
            r.put("ok", true);
            r.set("value", v == null ? JsonVectors.mapper().nullNode() : SettingsJson.mapper().valueToTree(v));
          }
          case "set" -> {
            ClientId client = new ClientId(op.get("client").asString());
            ClientSetting<Object> s = setting(op.get("setting").asString());
            ClientSettings changed = repo.forClient(db, client).with(s, typed(s, op.get("value")));
            saveResult(r, changed, client);
          }
          case "unset" -> {
            ClientId client = new ClientId(op.get("client").asString());
            ClientSettings changed = repo.forClient(db, client).without(setting(op.get("setting").asString()));
            saveResult(r, changed, client);
          }
          case "rawSet" -> {
            pg.execute(
                "INSERT INTO fw_client_settings (client_id, version, settings) VALUES ('" + op.get("client").asString() + "', "
                    + op.get("version").asInt() + ", '" + op.get("settings").toString().replace("'", "''")
                    + "'::jsonb) ON CONFLICT (client_id) DO UPDATE SET version = EXCLUDED.version, settings = EXCLUDED.settings");
            r.put("ok", true);
          }
          case "load" -> {
            ClientId client = new ClientId(op.get("client").asString());
            ClientSettings loaded = repo.forClient(db, client);
            named.put(op.get("as").asString(), loaded);
            r.put("ok", true);
            r.put("version", loaded.version());
            r.put("id", loaded.id().value());
            ObjectNode values = r.putObject("values");
            loaded.values().forEach(values::set);
          }
          case "with" -> {
            ClientSetting<Object> s = setting(op.get("setting").asString());
            String as = op.get("as").asString();
            named.put(as, named.get(as).with(s, typed(s, op.get("value"))));
            r.put("ok", true);
          }
          case "without" -> {
            String as = op.get("as").asString();
            named.put(as, named.get(as).without(setting(op.get("setting").asString())));
            r.put("ok", true);
          }
          case "save" -> {
            ClientSettings toSave = named.get(op.get("as").asString());
            saveResult(r, toSave, toSave.client());
          }
          case "saveAs" -> {
            ClientSettings toSave = named.get(op.get("as").asString());
            saveResult(r, toSave, new ClientId(op.get("client").asString()));
          }
          case "find" -> {
            Optional<ClientSettings> found =
                repo.find(db, new ClientId(op.get("client").asString()), new ClientSettingsId(op.get("id").asString()));
            r.put("ok", true);
            r.put("found", found.isPresent());
          }
          case "check" -> {
            ClientSetting<Object> s = setting(op.get("setting").asString());
            Optional<String> problem = s.check(typed(s, op.get("value")));
            r.put("ok", true);
            if (problem.isPresent()) {
              r.put("problem", problem.get());
            } else {
              r.putNull("problem");
            }
          }
          default -> throw new IllegalArgumentException(kind);
        }
      } catch (IllegalArgumentException | IllegalStateException e) {
        r.removeAll();
        r.put("ok", false);
        ObjectNode err = r.putObject("error");
        err.put("exception", e.getClass().getSimpleName());
        err.put("messageJava", e.getMessage());
        if (e instanceof IllegalStateException) {
          String m = e.getMessage();
          err.put("namesKey", op.has("setting") && m.contains(setting(op.get("setting").asString()).key()));
          err.put("namesClient", op.has("client") && m.contains(op.get("client").asString()));
        }
        if (e instanceof IllegalArgumentException) {
          err.put("namesKey", op.has("setting") && e.getMessage().contains(setting(op.get("setting").asString()).key()));
        }
      }
      return r;
    }

    private void saveResult(ObjectNode r, ClientSettings toSave, ClientId asClient) {
      UseCase<Save, Changed> uc =
          UseCase.named("save-settings", Save.class, Changed.class)
              .authorize(Authorize.PUBLIC)
              .execute((c, cmd) -> Plan.save(toSave, repo, new Changed(events.metadata(c, CHANGED, toSave.id()), c.client().value())));
      Context ctx = Context.of(asClient).withPrincipal(new PrincipalId("usr_1"));
      UseCaseResult<Changed> result = useCases.run(uc, ctx, new Save(asClient.value()));
      switch (result) {
        case UseCaseResult.Committed<Changed> c -> {
          r.put("ok", true);
          r.put("result", "committed");
        }
        case UseCaseResult.Failed<Changed> f -> {
          r.put("ok", false);
          r.put("result", "failed");
          r.put("code", f.error().code());
          r.put("kind", f.error().kind().name());
        }
      }
    }

    void rows(ArrayNode out) throws Exception {
      try (Connection c = db.dataSource().getConnection();
          Statement st = c.createStatement();
          ResultSet rs = st.executeQuery("SELECT client_id, version, settings::text FROM fw_client_settings ORDER BY client_id")) {
        while (rs.next()) {
          ObjectNode row = out.addObject();
          row.put("client_id", rs.getString(1));
          row.put("version", rs.getLong(2));
          row.set("settings", JsonVectors.mapper().readTree(rs.getString(3)));
          row.put("storedText", rs.getString(3));
        }
      }
    }
  }
}
