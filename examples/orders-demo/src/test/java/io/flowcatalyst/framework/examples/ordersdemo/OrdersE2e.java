package io.flowcatalyst.framework.examples.ordersdemo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.framework.auth.TestOidcProvider;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.flowcatalyst.FakePlatform;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.ratelimit.KeyResolvers;
import io.flowcatalyst.framework.http.ratelimit.MemoryRateLimiter;
import io.flowcatalyst.framework.http.ratelimit.RateLimit;
import io.flowcatalyst.framework.http.ratelimit.RateLimitPolicy;
import io.flowcatalyst.framework.pipeline.Middleware;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The orders demo for the browser end-to-end suite ({@code ui/apps/orders/e2e}): the real {@link
 * OrdersWeb} application, serving the built SPA from the classpath, against a real PostgreSQL
 * (Testcontainers, a random port) and a {@link TestOidcProvider} standing in for the platform. A
 * small control server on its own port lets the suite change who signs in, empty the data and end
 * every session. Nothing here touches a developer's database or port 8080.
 *
 * <p>{@code java -cp ... OrdersE2e <app-port> <control-port> [<public-origin>]}, from {@code examples/orders-demo}
 * (it reads {@code config/application.yaml}). It prints {@code READY} when both servers answer.
 *
 * <p>Control endpoints (all {@code POST} except {@code GET /control/ready}):
 *
 * <ul>
 *   <li>{@code /control/sign-in-as?roles=orders-demo:clerk,...&name=...} the next sign-in
 *   <li>{@code /control/reset} deletes every order and puts the fake platform's users and invitations back (sessions stay)
 *   <li>{@code /control/end-sessions} deletes every session, as an expiry would
 *   <li>{@code /control/provider-down?value=true|false} makes the platform answer 503
 *   <li>{@code /control/login-rate-limit?value=true|false} puts a real limiter of one request a minute on the sign-in routes
 *       (a fresh budget each time it is switched on); the suite otherwise runs without a limit
 *   <li>{@code /control/platform-reads?value=403|500} makes the platform's reads and writes (event types, roles, users, invitations, events, dispatch jobs) fail (empty: normal)
 *   <li>{@code /control/authorize-error?value=access_denied} makes the platform refuse the next sign-ins (empty: normal)
 * </ul>
 */
public final class OrdersE2e {

  private OrdersE2e() {}

  /**
   * Starts and waits.
   *
   * @param args the application's port and the control server's port
   * @throws Exception when something does not start
   */
  public static void main(String[] args) throws Exception {
    int appPort = Integer.parseInt(args[0]);
    int controlPort = Integer.parseInt(args[1]);
    // The origin the browser sees: the application's own, or (development) the Vite dev server's,
    // which proxies to it (static-assets contract section 9).
    String origin = args.length > 2 ? args[2] : "http://127.0.0.1:" + appPort;

    PostgresFixture pg = PostgresFixture.start();
    // The platform the admin pages read (event types, roles): a fake, never a real one.
    FakePlatform platform = DemoPlatform.start();
    TestOidcProvider provider = TestOidcProvider.start(Instant::now);
    provider.client("orders-web", "", origin + "/auth/callback");
    // The platform sends the browser back to the post-logout redirect; this one goes to the SPA's
    // signed-out page the same way.
    provider.discoveryChanges(Map.of("end_session_endpoint", origin + "/signed-out"));
    provider.signInAs(user("orders-demo:clerk", "Ada Lovelace"));

    Map<String, String> env = new HashMap<>();
    env.put("APP_ENV", "test");
    env.put("HTTP_HOST", "127.0.0.1");
    env.put("HTTP_PORT", String.valueOf(appPort));
    env.put("DATABASE_INSTANCES_MAIN_URL", pg.jdbcUrl());
    env.put("DATABASE_INSTANCES_MAIN_USERNAME", pg.username());
    env.put("DATABASE_INSTANCES_MAIN_PASSWORD", pg.password());
    env.put("AUTH_ISSUER", provider.issuer().toString());
    env.put("AUTH_CLIENT_ID", "orders-web");
    env.put("AUTH_PUBLIC_BASE_URL", origin);
    env.putAll(DemoPlatform.env(platform));
    // No limit unless the suite asks (a test run signs in far more than 20 times a minute); then a real
    // limiter, one request a minute, so a refused navigation can be seen.
    AtomicReference<Middleware<Request, Response>> limit = new AtomicReference<>();
    Middleware<Request, Response> switchable =
        (request, next) -> {
          Middleware<Request, Response> current = limit.get();
          return current == null ? next.handle(request) : current.handle(request, next);
        };
    App app = OrdersWeb.builder(auth -> auth.rateLimit(switchable)).configDirectory(Path.of("config")).environment(env).start();

    HttpServer control = HttpServer.create(new InetSocketAddress("127.0.0.1", controlPort), 0);
    control.createContext("/control/ready", e -> reply(e, 200, "ready"));
    control.createContext(
        "/control/sign-in-as",
        e -> {
          Map<String, String> q = query(e.getRequestURI());
          String roles = q.getOrDefault("roles", "");
          provider.signInAs(
              user(roles, q.getOrDefault("name", "Ada Lovelace")));
          reply(e, 200, "ok");
        });
    control.createContext(
        "/control/reset",
        e -> {
          pg.execute("TRUNCATE order_lines, orders, outbox_messages CASCADE");
          DemoPlatform.resetDirectory(platform);
          reply(e, 200, "ok");
        });
    control.createContext(
        "/control/end-sessions",
        e -> {
          pg.execute("DELETE FROM fw_session");
          reply(e, 200, "ok");
        });
    control.createContext(
        "/control/authorize-error",
        e -> {
          provider.authorizeError(query(e.getRequestURI()).getOrDefault("value", ""));
          reply(e, 200, "ok");
        });
    AtomicInteger limiters = new AtomicInteger();
    control.createContext(
        "/control/login-rate-limit",
        e -> {
          if ("true".equals(query(e.getRequestURI()).get("value"))) {
            limit.set(
                RateLimit.by(
                        MemoryRateLimiter.create("auth-e2e-" + limiters.incrementAndGet(), new RateLimitPolicy(1, Duration.ofMinutes(1), 1)),
                        KeyResolvers.clientAddress())
                    .build());
          } else {
            limit.set(null);
          }
          reply(e, 200, "ok");
        });
    control.createContext(
        "/control/platform-reads",
        e -> {
          // status=403 (or 500): the platform refuses the admin pages' reads; empty: normal again
          String status = query(e.getRequestURI()).getOrDefault("value", "");
          DemoPlatform.reads(platform, status.isEmpty() ? 200 : Integer.parseInt(status));
          reply(e, 200, "ok");
        });
    control.createContext(
        "/control/provider-down",
        e -> {
          provider.down("true".equals(query(e.getRequestURI()).get("value")));
          reply(e, 200, "ok");
        });
    control.start();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  control.stop(0);
                  app.close();
                  provider.close();
                  platform.close();
                  pg.close();
                }));
    System.out.println("READY app=http://127.0.0.1:" + appPort + " public=" + origin + " control=http://127.0.0.1:" + controlPort);
    new CountDownLatch(1).await();
  }

  private static Map<String, Object> user(String roles, String name) {
    List<String> list = roles.isBlank() ? List.of() : List.of(roles.split(","));
    return Map.of("name", name, "email", "ada@example.test", "roles", list);
  }

  private static Map<String, String> query(URI uri) {
    Map<String, String> out = new HashMap<>();
    String raw = uri.getRawQuery();
    if (raw != null) {
      for (String pair : raw.split("&")) {
        int eq = pair.indexOf('=');
        if (eq > 0) {
          out.put(
              URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
              URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
      }
    }
    return out;
  }

  private static void reply(HttpExchange e, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }
}
