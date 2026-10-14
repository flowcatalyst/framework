package io.flowcatalyst.framework.examples.ordersdemo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.framework.auth.TestOidcProvider;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.database.PostgresFixture;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * The orders demo for the browser end-to-end suite ({@code ui/apps/orders/e2e}): the real {@link
 * OrdersWeb} application, serving the built SPA from the classpath, against a real PostgreSQL
 * (Testcontainers, a random port) and a {@link TestOidcProvider} standing in for the platform. A
 * small control server on its own port lets the suite change who signs in, empty the data and end
 * every session. Nothing here touches a developer's database or port 8080.
 *
 * <p>{@code java -cp ... OrdersE2e <app-port> <control-port>}, from {@code examples/orders-demo}
 * (it reads {@code config/application.yaml}). It prints {@code READY} when both servers answer.
 *
 * <p>Control endpoints (all {@code POST} except {@code GET /control/ready}):
 *
 * <ul>
 *   <li>{@code /control/sign-in-as?roles=orders-demo:clerk,...&name=...} the next sign-in
 *   <li>{@code /control/reset} deletes every order (sessions stay)
 *   <li>{@code /control/end-sessions} deletes every session, as an expiry would
 *   <li>{@code /control/provider-down?value=true|false} makes the platform answer 503
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
    String origin = "http://127.0.0.1:" + appPort;

    PostgresFixture pg = PostgresFixture.start();
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
    App app = OrdersWeb.builder(auth -> auth.noRateLimit()).configDirectory(Path.of("config")).environment(env).start();

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
                  pg.close();
                }));
    System.out.println("READY app=" + origin + " control=http://127.0.0.1:" + controlPort);
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
