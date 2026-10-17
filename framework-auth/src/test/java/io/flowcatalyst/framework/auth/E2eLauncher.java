package io.flowcatalyst.framework.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * The launcher every application's browser end-to-end suite shares ({@code @flowcatalyst/ui/e2e} is
 * the TypeScript half; docs/guide/e2e-kit.md). An application's {@code main} method:
 *
 * <ol>
 *   <li>{@link #start(String[], String) starts} the launcher from its arguments ({@code <app-port>
 *       <control-port> [<public-origin>]}): a {@link TestOidcProvider} standing in for the platform's
 *       sign-in, with the application's OIDC client registered and the end-session redirect pointed at
 *       the SPA's signed-out page;
 *   <li>starts what else it needs (a PostgreSQL, a fake platform) and {@link #own owns} it, so it is
 *       closed on shutdown;
 *   <li>builds its {@code App} from {@link #environment()} plus its own entries and {@link #own owns}
 *       that too;
 *   <li>registers its own control endpoints with {@link #control} ({@code /control/reset} truncating its
 *       tables, and whatever else its suite needs) and {@link #endSessions};
 *   <li>calls {@link #serve}, which starts the control server, prints {@code READY} and waits.
 * </ol>
 *
 * <p>The standard control endpoints (any method; {@code GET /control/ready} is the Playwright web
 * server's readiness probe):
 *
 * <ul>
 *   <li>{@code /control/ready}
 *   <li>{@code /control/sign-in-as?roles=a,b&name=...&client=acme&sub=prn_ada} the next sign-in; with
 *       neither {@code client} nor {@code sub} the person is {@link #person(String, String)}
 *   <li>{@code /control/provider-down?value=true|false} the platform answers 503
 *   <li>{@code /control/authorize-error?value=access_denied} the platform refuses the next sign-ins
 *       (empty: normal)
 *   <li>{@code /control/end-sessions} when {@link #endSessions} was called
 * </ul>
 *
 * <p>Nothing here touches a developer's database or port 8080: the ports come from the arguments and
 * the services are fakes or Testcontainers.
 */
public final class E2eLauncher implements AutoCloseable {

  /** An application's own control endpoint: the query parameters in, the body of the 200 answer out. */
  @FunctionalInterface
  public interface Handler {
    /**
     * Handles one control request.
     *
     * @param query the decoded query parameters
     * @return the answer's body; an exception becomes a 500 whose body is its message
     * @throws Exception when the request cannot be served
     */
    String handle(Map<String, String> query) throws Exception;
  }

  private final int appPort;
  private final int controlPort;
  private final String origin;
  private final String clientId;
  private final TestOidcProvider provider;
  private final Map<String, Handler> endpoints = new LinkedHashMap<>();
  private final Deque<AutoCloseable> owned = new ArrayDeque<>();
  private HttpServer server;
  private boolean closed;

  private E2eLauncher(int appPort, int controlPort, String origin, String clientId, TestOidcProvider provider) {
    this.appPort = appPort;
    this.controlPort = controlPort;
    this.origin = origin;
    this.clientId = clientId;
    this.provider = provider;
    endpoints.put("/control/ready", q -> "ready");
    endpoints.put("/control/sign-in-as", this::signIn);
    endpoints.put(
        "/control/provider-down",
        q -> {
          provider.down("true".equals(q.get("value")));
          return "ok";
        });
    endpoints.put(
        "/control/authorize-error",
        q -> {
          provider.authorizeError(q.getOrDefault("value", ""));
          return "ok";
        });
  }

  /**
   * Starts the launcher with the system clock.
   *
   * @param args the application's port, the control server's port, optionally the origin the browser sees
   * @param clientId the application's OIDC client id (its redirect URI is {@code <origin>/auth/callback})
   * @return the launcher
   */
  public static E2eLauncher start(String[] args, String clientId) {
    return start(args, clientId, Instant::now);
  }

  /**
   * Starts the launcher; the provider follows {@code clock} (an application whose suite moves its clock
   * passes the same clock to its {@code App}).
   *
   * @param args the application's port, the control server's port, optionally the origin the browser sees
   * @param clientId the application's OIDC client id (its redirect URI is {@code <origin>/auth/callback})
   * @param clock the provider's clock
   * @return the launcher
   */
  public static E2eLauncher start(String[] args, String clientId, InstantSource clock) {
    if (args.length < 2) {
      throw new IllegalArgumentException("usage: <app-port> <control-port> [<public-origin>]");
    }
    int appPort = Integer.parseInt(args[0]);
    int controlPort = Integer.parseInt(args[1]);
    // The origin the browser sees: the application's own, or (development) the Vite dev server's,
    // which proxies to it (static-assets contract section 9).
    String origin = args.length > 2 ? args[2] : "http://127.0.0.1:" + appPort;
    TestOidcProvider provider = TestOidcProvider.start(clock);
    provider.client(clientId, "", origin + "/auth/callback");
    // The platform sends the browser back to the post-logout redirect; this one goes to the SPA's
    // signed-out page the same way.
    provider.discoveryChanges(Map.of("end_session_endpoint", origin + "/signed-out"));
    return new E2eLauncher(appPort, controlPort, origin, clientId, provider);
  }

  /**
   * The person who signs in: no client (a platform-wide user).
   *
   * @param roles comma-separated role names (blank: a signed-in user with no permission)
   * @param name the display name
   * @return the id token's claims
   */
  public static Map<String, Object> person(String roles, String name) {
    return claims(roles, name, null, null);
  }

  /**
   * The person who signs in: one client's user.
   *
   * @param roles comma-separated role names (blank: a signed-in user with no permission)
   * @param name the display name
   * @param client the client's code
   * @param sub the principal id
   * @return the id token's claims
   */
  public static Map<String, Object> person(String roles, String name, String client, String sub) {
    return claims(roles, name, client, sub);
  }

  private static Map<String, Object> claims(String roles, String name, String client, String sub) {
    List<String> list = roles.isBlank() ? List.of() : List.of(roles.split(","));
    Map<String, Object> u = new LinkedHashMap<>();
    if (sub != null) {
      u.put("sub", sub);
    }
    if (client != null) {
      u.put("type", "USER");
      u.put("tier", "CLIENT");
      u.put("clients", List.of("clt_" + client + ":" + client));
    }
    u.put("name", name);
    u.put("email", (sub == null ? "ada" : sub) + "@example.test");
    u.put("roles", list);
    return u;
  }

  private String signIn(Map<String, String> q) {
    String roles = q.getOrDefault("roles", "");
    String name = q.getOrDefault("name", "Ada Lovelace");
    if (q.containsKey("client") || q.containsKey("sub")) {
      provider.signInAs(person(roles, name, q.getOrDefault("client", "acme"), q.getOrDefault("sub", "prn_ada")));
    } else {
      provider.signInAs(person(roles, name));
    }
    return "ok";
  }

  /** @return the port the application listens on */
  public int appPort() {
    return appPort;
  }

  /** @return the control server's port */
  public synchronized int controlPort() {
    return server == null ? controlPort : server.getAddress().getPort();
  }

  /** @return the origin the browser sees (the application's own, or the Vite dev server's) */
  public String origin() {
    return origin;
  }

  /** @return the test sign-in provider (for what the standard endpoints do not cover) */
  public TestOidcProvider provider() {
    return provider;
  }

  /**
   * The next sign-in is this person.
   *
   * @param claims the id token's claims, normally from {@link #person}
   * @return this
   */
  public E2eLauncher signInAs(Map<String, Object> claims) {
    provider.signInAs(claims);
    return this;
  }

  /**
   * The environment every application needs to log in against the provider and to listen where the suite
   * expects; a fresh map each call, for the application to add its own entries to.
   *
   * @return {@code APP_ENV=test}, {@code HTTP_HOST}, {@code HTTP_PORT}, {@code AUTH_ISSUER}, {@code
   *     AUTH_CLIENT_ID} and {@code AUTH_PUBLIC_BASE_URL}
   */
  public Map<String, String> environment() {
    Map<String, String> env = new HashMap<>();
    env.put("APP_ENV", "test");
    env.put("HTTP_HOST", "127.0.0.1");
    env.put("HTTP_PORT", String.valueOf(appPort));
    env.put("AUTH_ISSUER", provider.issuer().toString());
    env.put("AUTH_CLIENT_ID", clientId);
    env.put("AUTH_PUBLIC_BASE_URL", origin);
    return env;
  }

  /**
   * Hands a resource to the launcher: closed on shutdown, last owned first, before the provider.
   *
   * @param resource a database fixture, a fake platform, the application
   * @param <T> its type
   * @return the resource
   */
  public <T extends AutoCloseable> T own(T resource) {
    owned.push(resource);
    return resource;
  }

  /**
   * Registers {@code /control/end-sessions}.
   *
   * @param action deletes every session, as an expiry would
   * @return this
   */
  public E2eLauncher endSessions(Runnable action) {
    return control(
        "/control/end-sessions",
        q -> {
          action.run();
          return "ok";
        });
  }

  /**
   * Registers an application's own control endpoint (any method).
   *
   * @param path starts with {@code /control/}
   * @param handler serves it
   * @return this
   */
  public E2eLauncher control(String path, Handler handler) {
    if (!path.startsWith("/control/")) {
      throw new IllegalArgumentException("a control path starts with /control/: " + path);
    }
    endpoints.put(path, handler);
    return this;
  }

  /**
   * Starts the control server (port 0 picks a free one; {@link #controlPort()} then answers it).
   *
   * @return this
   * @throws IOException when the control port cannot be bound
   */
  public synchronized E2eLauncher listen() throws IOException {
    HttpServer started = HttpServer.create(new InetSocketAddress("127.0.0.1", controlPort), 0);
    endpoints.forEach((path, handler) -> started.createContext(path, exchange -> answer(exchange, handler)));
    started.start();
    server = started;
    return this;
  }

  /**
   * {@link #listen() Listens}, prints {@code READY} and waits until the JVM is stopped (its shutdown hook
   * closes everything).
   *
   * @throws IOException when the control port cannot be bound
   * @throws InterruptedException when the wait is interrupted
   */
  public void serve() throws IOException, InterruptedException {
    listen();
    Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    System.out.println("READY app=http://127.0.0.1:" + appPort + " public=" + origin + " control=http://127.0.0.1:" + controlPort());
    new CountDownLatch(1).await();
  }

  /** Stops the control server, closes what was owned (last first), then the provider. */
  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    if (server != null) {
      server.stop(0);
    }
    while (!owned.isEmpty()) {
      try {
        owned.pop().close();
      } catch (Exception e) {
        System.err.println("closing: " + e);
      }
    }
    provider.close();
  }

  private static void answer(HttpExchange exchange, Handler handler) throws IOException {
    try {
      reply(exchange, 200, handler.handle(query(exchange.getRequestURI())));
    } catch (Exception e) {
      reply(exchange, 500, String.valueOf(e.getMessage()));
    }
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
