package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.HttpErrors;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.RouteOptions;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;

/** An application with {@link AuthModule} against a {@link TestOidcProvider}, for the tests. */
final class AuthApp implements AutoCloseable {

  static final String ORIGIN = "https://app.example";
  static final String CLIENT_ID = "app-client";
  static final Instant START = Instant.parse("2026-10-13T08:00:00Z");

  final MutableClock clock;
  final TestOidcProvider provider;
  final MemorySessionStore store;
  final App app;
  final TestClient client;

  private AuthApp(MutableClock clock, TestOidcProvider provider, MemorySessionStore store, App app) {
    this.clock = clock;
    this.provider = provider;
    this.store = store;
    this.app = app;
    this.client =
        TestClient.of(app.get(Routes.class), app.find(ContextResolver.class).orElseGet(() -> app.get(AuthContextResolver.class)));
  }

  static AuthApp start(Path dir) {
    return start(dir, b -> {}, Map.of());
  }

  static AuthApp start(Path dir, Consumer<AuthModule.Builder> customise, Map<String, String> envChanges) {
    MutableClock clock = new MutableClock(START);
    TestOidcProvider provider = TestOidcProvider.start(clock);
    String origin = envChanges.getOrDefault("AUTH_PUBLIC_BASE_URL", ORIGIN);
    provider.client(CLIENT_ID, envChanges.containsKey("AUTH_CLIENT_SECRET") ? envChanges.get("AUTH_CLIENT_SECRET") : "", origin + "/auth/callback");
    MemorySessionStore store = MemorySessionStore.create(clock);
    Map<String, String> env = new HashMap<>();
    env.put("APP_ENV", "test");
    env.put("AUTH_ISSUER", provider.issuer().toString());
    env.put("AUTH_CLIENT_ID", CLIENT_ID);
    env.put("AUTH_PUBLIC_BASE_URL", ORIGIN);
    envChanges.forEach((k, v) -> {
      if (v == null) {
        env.remove(k);
      } else {
        env.put(k, v);
      }
    });
    AuthModule.Builder builder =
        AuthModule.builder()
            .sessions(lookup -> store)
            .permissions(
                PermissionMapper.scopes()
                    .and(PermissionMapper.byRole(Map.of("orders:clerk", java.util.Set.of("orders:thing:create", "orders:thing:view")))));
    customise.accept(builder);
    try {
      App app =
          App.builder()
              .configDirectory(dir)
              .environment(env)
              .instantSource(clock)
              .module(routes())
              .module(builder.build())
              .module(testRoutes())
              .start();
      return new AuthApp(clock, provider, store, app);
    } catch (RuntimeException e) {
      provider.close();
      throw e;
    }
  }

  Browser browser() {
    return new Browser(client, ORIGIN);
  }

  @Override
  public void close() {
    app.close();
    provider.close();
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

  static Module testRoutes() {
    return new Module() {
      @Override
      public String id() {
        return "test-routes";
      }

      @Override
      public void boot(App app) {
        Routes routes = app.get(Routes.class).forModule(id());
        routes.get(
            "/api/me",
            request -> {
              Map<String, Object> body = new LinkedHashMap<>();
              body.put("principal", String.valueOf(request.context().principal()));
              body.put("client", request.context().client().value());
              body.put("application", String.valueOf(request.context().application()));
              body.put("permissions", new TreeSet<>(request.context().permissions()));
              body.put("source", Auth.current(request).map(a -> a.source().name()).orElse("none"));
              return Response.ok(body);
            },
            RouteOptions.requiresContext());
        routes.get("/page", request -> Response.html(200, "<h1>hello</h1>"), RouteOptions.requiresContext());
        routes.post(
            "/api/things",
            request -> Response.json(201, Map.of("created", true)),
            RouteOptions.requiresContext().with(Auth.require("orders:thing:create")));
        routes.post("/api/echo", request -> Response.ok(Map.of("ok", true)), RouteOptions.requiresContext());
        routes.get("/public", request -> HttpErrors.unauthenticated("handler says no"));
      }
    };
  }
}
