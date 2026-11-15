package io.flowcatalyst.framework.partitions.admin;

import io.flowcatalyst.framework.admin.AdminModule;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.context.PrincipalKind;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Ids;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.http.openapi.OpenApi;
import io.flowcatalyst.framework.http.openapi.SecurityScheme;
import io.flowcatalyst.framework.locks.LockStore;
import io.flowcatalyst.framework.locks.LocksModule;
import io.flowcatalyst.framework.locks.MemoryLockDriver;
import io.flowcatalyst.framework.objectstore.ObjectStoreFactories;
import io.flowcatalyst.framework.objectstore.ObjectStoresModule;
import io.flowcatalyst.framework.partitions.Interval;
import io.flowcatalyst.framework.partitions.Key;
import io.flowcatalyst.framework.partitions.Partitions;
import io.flowcatalyst.framework.partitions.PartitionsModule;
import io.flowcatalyst.framework.partitions.Retention;
import io.flowcatalyst.framework.partitions.Table;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Application "acme" with the admin module, the partitions module over two tables and the partitions
 * section, on a real PostgreSQL (the Go rig, {@code partitionsadmin/rig_test.go}). A request carries its
 * permissions in {@code X-Perms} (none: not signed in) and its tier in {@code X-Tier} (none: no tier).
 */
abstract class PartitionsAdminTestBase {

  static final Instant T0 = Instant.parse("2026-11-14T10:00:00Z");
  static final ApplicationCode APP = new ApplicationCode("acme");

  static final String ID_TABLE = "CREATE TABLE readings (id text COLLATE \"C\" PRIMARY KEY, payload text) PARTITION BY RANGE (id)";
  static final String COL_TABLE =
      "CREATE TABLE samples (id text NOT NULL, recorded_at timestamptz NOT NULL, payload text, PRIMARY KEY (id, recorded_at)) PARTITION BY RANGE (recorded_at)";

  static PostgresFixture pg;

  @TempDir Path dir;

  TestClock clock = new TestClock(T0);
  LogCapture log;
  private final List<App> apps = new ArrayList<>();

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stopPostgres() {
    pg.close();
  }

  @BeforeEach
  void resetDatabase() {
    log = LogCapture.start();
    pg.reset();
  }

  @AfterEach
  void closeApps() {
    for (int i = apps.size() - 1; i >= 0; i--) {
      apps.get(i).close();
    }
    apps.clear();
    log.close();
  }

  /** A started application. */
  record Rig(App app, Partitions ps, TestClient client, LockStore locks) {}

  /**
   * Starts the application over the two tables {@code readings} (monthly, ahead 2, archive then drop
   * after 90 days) and {@code samples} (weekly, ahead 1, drop after 30 days).
   *
   * @param operatorsOnlyArea whether the admin area is declared for platform operators only
   */
  Rig start(boolean operatorsOnlyArea) throws IOException {
    pg.execute(ID_TABLE);
    pg.execute(COL_TABLE);
    LockStore locks = newLocks();
    App app = builder(operatorsOnlyArea, locks).start();
    apps.add(app);
    return new Rig(app, app.get(Partitions.class), TestClient.of(app.get(Routes.class), app.get(ContextResolver.class)), locks);
  }

  static LockStore newLocks() {
    return new LockStore(new MemoryLockDriver(MonotonicClock.system()::nanos), MonotonicClock.system());
  }

  /** The application, not started (its declarations are what the OpenAPI document is made from). */
  AppBuilder builder(boolean operatorsOnlyArea, LockStore locks) throws IOException {
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        database:
          default: main
          instances:
            main: { url: "%s", username: %s, password: %s, pool: { max: 10, min-idle: 1 } }
        """
            .formatted(pg.jdbcUrl(), pg.username(), pg.password()),
        StandardCharsets.UTF_8);
    PartitionsModule partitions =
        PartitionsModule.builder()
            .table(Table.named("readings").by(Key.byId("rdg")).interval(Interval.MONTH).ahead(2).retention(Retention.archiveThenDropAfter(Duration.ofDays(90), "archive")))
            .table(Table.named("samples").by(Key.byColumn("recorded_at")).interval(Interval.WEEK).ahead(1).retention(Retention.dropAfter(Duration.ofDays(30))))
            .locks()
            .every(Duration.ofHours(1))
            .build();
    return App.builder()
        .configDirectory(dir)
        .environment(Map.of("APP_ENV", "test"))
        .instantSource(clock)
        .module(http())
        .module(operatorsOnlyArea ? AdminModule.of(APP, AdminModule.Option.OPERATORS_ONLY) : AdminModule.of(APP))
        .module(new DatabaseModule())
        .module(ObjectStoresModule.builder().store("archive", ObjectStoreFactories.memory()).build())
        .module(LocksModule.builder().store("main", (name, lookup) -> locks).build())
        .module(partitions)
        .module(PartitionsAdminModule.of(APP));
  }

  /** Routes that know the declared operations, and a context from the headers. */
  static Module http() {
    return new Module() {
      @Override
      public String id() {
        return "http";
      }

      @Override
      public void register(AppBuilder app) {
        OpenApi.info(app, "acme", "1");
        OpenApi.securityScheme(app, "session", SecurityScheme.cookie("__Host-fw_session"));
        app.provide(
            Routes.class,
            lookup -> {
              Routes routes = new Routes();
              routes.declare(OpenApi.operations(lookup));
              return routes;
            });
        app.provide(
            ContextResolver.class,
            lookup ->
                request -> {
                  String perms = request.header("X-Perms");
                  if (perms == null) {
                    throw new HttpException(401, "UNAUTHENTICATED", "sign in");
                  }
                  Context context =
                      Context.of(new ClientId("demo"))
                          .withPrincipal(new PrincipalId("prn_TESTOP"), PrincipalKind.USER)
                          .withPermissions(perms.isEmpty() ? Set.of() : Set.of(perms.split(",")));
                  String tier = request.header("X-Tier");
                  return tier == null || tier.isEmpty() ? context : context.withTier(tier);
                });
      }
    };
  }

  // ---- who sends a request -------------------------------------------------------------------

  /** A caller: its permissions (null: not signed in) and its tier (null: none). */
  record Caller(@Nullable List<String> perms, @Nullable String tier) {
    Caller withTier(@Nullable String other) {
      return new Caller(perms, other);
    }
  }

  static final Caller OPERATOR_WITH_BOTH =
      new Caller(List.of("acme:admin:console:view", "acme:admin:partitions:view", "acme:admin:partitions:manage"), "ANCHOR");
  static final Caller OPERATOR_WILD = new Caller(List.of("acme:admin:*:*"), "ANCHOR");
  static final Caller OPERATOR_NO_PERMS = new Caller(List.of("acme:admin:console:view"), "ANCHOR");
  static final Caller OPERATOR_VIEW_ONLY = new Caller(List.of("acme:admin:console:view", "acme:admin:partitions:view"), "ANCHOR");
  static final Caller NOT_SIGNED_IN = new Caller(null, null);

  static TestResponse send(Rig rig, String method, String target, @Nullable String body, Caller caller) {
    TestClient.Call call = rig.client().request(io.flowcatalyst.framework.http.Method.valueOf(method), target);
    if (caller.perms() != null) {
      call = call.header("X-Perms", String.join(",", caller.perms()));
    }
    if (caller.tier() != null) {
      call = call.header("X-Tier", caller.tier());
    }
    if (body != null && !body.isEmpty()) {
      call = call.jsonBody(body);
    }
    return call.send();
  }

  static String errorCode(TestResponse r) {
    JsonNode e = r.tree().get("error");
    return e == null ? "" : e.asString();
  }

  static String errorMessage(TestResponse r) {
    JsonNode m = r.tree().get("message");
    return m == null ? "" : m.asString();
  }

  static List<String> sectionIds(TestResponse r) {
    List<String> ids = new ArrayList<>();
    for (JsonNode s : r.tree().get("sections")) {
      ids.add(s.get("id").asString());
    }
    return ids;
  }

  static String idAt(Instant at) {
    return new Ids(() -> at).generate("rdg");
  }
}
