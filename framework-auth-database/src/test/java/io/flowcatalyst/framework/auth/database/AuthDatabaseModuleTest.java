package io.flowcatalyst.framework.auth.database;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.auth.AuthModule;
import io.flowcatalyst.framework.auth.AuthTesting;
import io.flowcatalyst.framework.auth.MutableClock;
import io.flowcatalyst.framework.auth.PermissionMapper;
import io.flowcatalyst.framework.auth.SessionStore;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.RouteOptions;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AuthModule with no store named in code uses this module's database store. */
class AuthDatabaseModuleTest {

  static PostgresFixture pg;

  @TempDir Path dir;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stop() {
    pg.close();
  }

  @Test
  void theDatabaseStoreIsTheDefaultAndSessionsLiveInFwSession() throws Exception {
    pg.reset();
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        database:
          instances:
            main: { url: %s, username: %s, password: %s }
        """.formatted(pg.jdbcUrl(), pg.username(), pg.password()));
    MutableClock clock = new MutableClock(Instant.parse("2026-10-13T08:00:00Z"));
    try (App app =
        App.builder()
            .configDirectory(dir)
            .environment(
                Map.of(
                    "APP_ENV", "test",
                    "AUTH_ISSUER", "https://platform.example",
                    "AUTH_CLIENT_ID", "app-client",
                    "AUTH_PUBLIC_BASE_URL", "https://app.example"))
            .instantSource(clock)
            .module(routes())
            .module(new DatabaseModule())
            .module(new AuthDatabaseModule())
            .module(AuthModule.builder().permissions(PermissionMapper.byRole(Map.of("orders:clerk", Set.of("orders:thing:view")))).build())
            .start()) {
      assertThat(app.get(SessionStore.class)).isInstanceOf(DatabaseSessionStore.class);
      app.get(Routes.class).get("/api/me", r -> Response.ok(Map.of("p", r.context().permissions())), RouteOptions.requiresContext());
      String cookie = AuthTesting.of(app).cookie(AuthTesting.user("prn_1", "orders:clerk"));
      assertThat(pg.query("SELECT count(*) FROM fw_session WHERE kind = 'session'")).isEqualTo("1");
      assertThat(pg.query("SELECT data->'principal'->>'id' FROM fw_session")).isEqualTo("prn_1");
      TestClient client = TestClient.of(app.get(Routes.class), app.get(ContextResolver.class));
      assertThat(client.get("/api/me").header("Cookie", cookie).send().body()).isEqualTo("{\"p\":[\"orders:thing:view\"]}");
      assertThat(client.post("/auth/logout").header("Cookie", cookie).header("Sec-Fetch-Site", "same-origin").send().status()).isEqualTo(200);
      assertThat(pg.query("SELECT count(*) FROM fw_session")).isEqualTo("0");
      assertThat(client.get("/api/me").header("Cookie", cookie).send().status()).isEqualTo(401);
    }
  }

  static Module routes() {
    return new Module() {
      @Override
      public String id() {
        return "http";
      }

      @Override
      public void register(AppBuilder app) {
        app.provide(Routes.class, lookup -> new Routes());
      }
    };
  }
}
