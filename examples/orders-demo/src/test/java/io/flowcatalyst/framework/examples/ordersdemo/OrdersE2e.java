package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.auth.E2eLauncher;
import io.flowcatalyst.framework.auth.TestOidcProvider;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.flowcatalyst.FakePlatform;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.ratelimit.KeyResolvers;
import io.flowcatalyst.framework.http.ratelimit.MemoryRateLimiter;
import io.flowcatalyst.framework.http.ratelimit.RateLimit;
import io.flowcatalyst.framework.http.ratelimit.RateLimitPolicy;
import io.flowcatalyst.framework.pipeline.Middleware;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The orders demo for the browser end-to-end suite ({@code ui/apps/orders/e2e}): the real {@link
 * OrdersWeb} application, serving the built SPA from the classpath, against a real PostgreSQL
 * (Testcontainers, a random port) and a {@link TestOidcProvider} standing in for the platform. The
 * shared {@link E2eLauncher} (docs/guide/e2e-kit.md) provides the provider and the standard control
 * endpoints; this class adds the orders' own. Nothing here touches a developer's database or port 8080.
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
    E2eLauncher e2e = E2eLauncher.start(args, "orders-web");
    PostgresFixture pg = e2e.own(PostgresFixture.start());
    // The platform the admin pages read (event types, roles): a fake, never a real one.
    FakePlatform platform = e2e.own(DemoPlatform.start());
    e2e.signInAs(E2eLauncher.person("orders-demo:clerk", "Ada Lovelace"));

    Map<String, String> env = e2e.environment();
    env.put("DATABASE_INSTANCES_MAIN_URL", pg.jdbcUrl());
    env.put("DATABASE_INSTANCES_MAIN_USERNAME", pg.username());
    env.put("DATABASE_INSTANCES_MAIN_PASSWORD", pg.password());
    env.putAll(DemoPlatform.env(platform));
    // No limit unless the suite asks (a test run signs in far more than 20 times a minute); then a real
    // limiter, one request a minute, so a refused navigation can be seen.
    AtomicReference<Middleware<Request, Response>> limit = new AtomicReference<>();
    Middleware<Request, Response> switchable =
        (request, next) -> {
          Middleware<Request, Response> current = limit.get();
          return current == null ? next.handle(request) : current.handle(request, next);
        };
    e2e.own(OrdersWeb.builder(auth -> auth.rateLimit(switchable)).configDirectory(Path.of("config")).environment(env).start());

    AtomicInteger limiters = new AtomicInteger();
    e2e.endSessions(() -> pg.execute("DELETE FROM fw_session"))
        .control(
            "/control/reset",
            q -> {
              pg.execute("TRUNCATE order_lines, orders, outbox_messages CASCADE");
              DemoPlatform.resetDirectory(platform);
              return "ok";
            })
        .control(
            "/control/login-rate-limit",
            q -> {
              if ("true".equals(q.get("value"))) {
                limit.set(
                    RateLimit.by(
                            MemoryRateLimiter.create("auth-e2e-" + limiters.incrementAndGet(), new RateLimitPolicy(1, Duration.ofMinutes(1), 1)),
                            KeyResolvers.clientAddress())
                        .build());
              } else {
                limit.set(null);
              }
              return "ok";
            })
        .control(
            "/control/platform-reads",
            q -> {
              // status=403 (or 500): the platform refuses the admin pages' reads; empty: normal again
              String status = q.getOrDefault("value", "");
              DemoPlatform.reads(platform, status.isEmpty() ? 200 : Integer.parseInt(status));
              return "ok";
            })
        .serve();
  }
}
