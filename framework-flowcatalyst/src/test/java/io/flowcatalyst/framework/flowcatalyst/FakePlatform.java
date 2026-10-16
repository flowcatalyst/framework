package io.flowcatalyst.framework.flowcatalyst;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An in-process stand-in for the FlowCatalyst platform, for tests: a client-credentials token
 * endpoint, the application sync endpoints ({@code POST /api/applications/{app}/{resource}/sync})
 * and the scheduled-job instance callbacks ({@code POST
 * /api/scheduled-jobs/instances/{id}/complete} and {@code .../log}, both 204).
 *
 * <p>Every request is recorded. A sync call answers with the platform's shape for a success, built
 * from the request body, unless a response was set for that resource with {@link #respond}. An
 * instance callback can be made to fail a number of times with {@link #failInstanceCalls}.
 * Tokens are accepted only for {@link #CLIENT_ID} and {@link #CLIENT_SECRET}. Like the platform's,
 * a token is a JWT whose {@code sub} is the service account's principal ({@link
 * #SERVICE_PRINCIPAL} unless changed with {@link #tokenPrincipal}) and whose {@code type} is
 * {@code SERVICE}; its signature is a placeholder. Only tokens it issued are accepted as bearers.
 */
public final class FakePlatform implements AutoCloseable {

  /** The client id the token endpoint accepts. */
  public static final String CLIENT_ID = "fake-client";

  /** The client secret the token endpoint accepts. */
  public static final String CLIENT_SECRET = "fake-secret";

  /** The principal id the issued tokens name in {@code sub}, by default. */
  public static final String SERVICE_PRINCIPAL = "prn_0FAKEAPPSVC01";

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern SYNC = Pattern.compile("/api/applications/([^/]+)/([a-z-]+)/sync");
  private static final Pattern INSTANCE =
      Pattern.compile("/api/scheduled-jobs/instances/([^/]+)/(complete|log)");

  /**
   * One request the fake received.
   *
   * @param method the HTTP method
   * @param path the path, without the query
   * @param query the raw query string, empty when there is none
   * @param headers header values by lower-cased name
   * @param body the request body as text
   */
  public record Recorded(
      String method, String path, String query, Map<String, String> headers, String body) {

    /**
     * The body parsed as JSON.
     *
     * @return the tree
     */
    public JsonNode json() {
      return JSON.readTree(body);
    }

    /**
     * Whether this is a sync call for a resource, such as {@code roles} or {@code event-types}.
     *
     * @param resource the resource path segment
     * @return true for {@code POST /api/applications/{app}/{resource}/sync}
     */
    public boolean isSyncOf(String resource) {
      Matcher m = SYNC.matcher(path);
      return "POST".equals(method) && m.matches() && m.group(2).equals(resource);
    }
  }

  private record Canned(int status, String body) {}

  private record Failing(int status, AtomicInteger remaining) {}

  private final HttpServer server;
  private final List<Recorded> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Canned> canned = new ConcurrentHashMap<>();
  private final AtomicInteger tokens = new AtomicInteger();
  private final Map<String, Failing> failing = new ConcurrentHashMap<>();
  private final Map<String, Canned> reads = new ConcurrentHashMap<>();
  private volatile int tokenFailure;
  private final Set<String> issued = ConcurrentHashMap.newKeySet();
  private volatile String tokenSub = SERVICE_PRINCIPAL;
  private volatile String tokenType = "SERVICE";
  private volatile boolean opaqueTokens;

  private FakePlatform(HttpServer server) {
    this.server = server;
  }

  /**
   * Starts a fake on a free local port.
   *
   * @return the running fake
   */
  public static FakePlatform start() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      FakePlatform fake = new FakePlatform(server);
      server.createContext("/", fake::handle);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.start();
      return fake;
    } catch (IOException e) {
      throw new IllegalStateException("could not start the fake platform", e);
    }
  }

  /**
   * The base URL to give the client under test.
   *
   * @return for example {@code http://127.0.0.1:41234}
   */
  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /**
   * Every request received so far, in arrival order.
   *
   * @return a snapshot
   */
  public List<Recorded> requests() {
    return List.copyOf(requests);
  }

  /**
   * The sync calls received so far, in arrival order.
   *
   * @return a snapshot
   */
  public List<Recorded> syncRequests() {
    return requests.stream().filter(r -> SYNC.matcher(r.path()).matches()).toList();
  }

  /**
   * The resources that received a sync call, in order, such as {@code [roles, event-types]}.
   *
   * @return resource path segments
   */
  public List<String> syncedResources() {
    List<String> out = new ArrayList<>();
    for (Recorded r : syncRequests()) {
      Matcher m = SYNC.matcher(r.path());
      if (m.matches()) {
        out.add(m.group(2));
      }
    }
    return out;
  }

  /**
   * How many token requests have been received.
   *
   * @return the count
   */
  public int tokenRequests() {
    return tokens.get();
  }

  /** Forgets the recorded requests. */
  public void clearRequests() {
    requests.clear();
    tokens.set(0);
  }

  /**
   * Makes the token endpoint answer with a status.
   *
   * @param status an HTTP status, or 0 to accept valid credentials again
   */
  public void failTokens(int status) {
    this.tokenFailure = status;
  }

  /**
   * Sets the {@code sub} and {@code type} claims of the tokens issued from now on.
   *
   * @param sub the principal id, or null to leave the claim out
   * @param type {@code SERVICE} for a service account, {@code USER} for a person, or null to leave
   *     the claim out
   */
  public void tokenPrincipal(String sub, String type) {
    this.tokenSub = sub;
    this.tokenType = type;
  }

  /**
   * Makes the token endpoint issue opaque tokens ({@code tok-N}) instead of JWTs.
   *
   * @param opaque true for opaque tokens
   */
  public void opaqueTokens(boolean opaque) {
    this.opaqueTokens = opaque;
  }

  /**
   * Whether a token was issued by this fake.
   *
   * @param token the token
   * @return true when the token endpoint issued it
   */
  public boolean issued(String token) {
    return issued.contains(token);
  }

  /**
   * Makes the sync of one resource answer with a fixed response.
   *
   * @param resource the resource path segment, such as {@code roles}
   * @param status the HTTP status
   * @param body the response body
   */
  public void respond(String resource, int status, String body) {
    canned.put(resource, new Canned(status, body));
  }

  /**
   * Makes a {@code GET} of one path answer with a fixed response (the platform's read endpoints,
   * for the admin pages). The path is matched without the query string, which is recorded.
   *
   * @param path for example {@code /api/event-types} or {@code /api/roles/orders:clerk}
   * @param status the HTTP status
   * @param body the response body
   */
  public void respondGet(String path, int status, String body) {
    reads.put(path, new Canned(status, body));
  }

  /**
   * Makes the next calls to an instance callback fail.
   *
   * @param action {@code complete} or {@code log}
   * @param times how many calls fail before it answers 204 again
   * @param status the HTTP status of the failures
   */
  public void failInstanceCalls(String action, int times, int status) {
    failing.put(action, new Failing(status, new AtomicInteger(times)));
  }

  /**
   * The instance callbacks received so far, in arrival order.
   *
   * @param action {@code complete} or {@code log}
   * @return the requests, including those answered with a failure
   */
  public List<Recorded> instanceCalls(String action) {
    return requests.stream()
        .filter(
            r -> {
              Matcher m = INSTANCE.matcher(r.path());
              return m.matches() && m.group(2).equals(action);
            })
        .toList();
  }

  /**
   * Waits until at least {@code count} instance callbacks of a kind have arrived.
   *
   * @param action {@code complete} or {@code log}
   * @param count how many
   * @param timeoutMillis how long to wait
   * @return the calls received, possibly fewer when the wait ran out
   * @throws InterruptedException when interrupted
   */
  public List<Recorded> awaitInstanceCalls(String action, int count, long timeoutMillis)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
    while (instanceCalls(action).size() < count && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    return instanceCalls(action);
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void handle(HttpExchange ex) throws IOException {
    try (ex) {
      String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      Map<String, String> headers = new LinkedHashMap<>();
      ex.getRequestHeaders()
          .forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), String.join(",", v)));
      String path = ex.getRequestURI().getPath();
      String query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
      Recorded recorded = new Recorded(ex.getRequestMethod(), path, query, headers, body);
      requests.add(recorded);

      if ("/oauth/token".equals(path)) {
        tokens.incrementAndGet();
        token(ex, body);
        return;
      }
      Matcher instance = INSTANCE.matcher(path);
      if (instance.matches() && "POST".equals(ex.getRequestMethod())) {
        if (!bearer(headers)) {
          reply(ex, 401, "{\"error\":\"unauthorized\"}");
          return;
        }
        Failing f = failing.get(instance.group(2));
        if (f != null && f.remaining().getAndDecrement() > 0) {
          reply(ex, f.status(), "{\"error\":\"failing on purpose\"}");
          return;
        }
        reply(ex, 204, "");
        return;
      }
      Canned read = reads.get(path);
      if (read != null && "GET".equals(ex.getRequestMethod())) {
        if (!bearer(headers)) {
          reply(ex, 401, "{\"error\":\"unauthorized\"}");
          return;
        }
        reply(ex, read.status(), read.body());
        return;
      }
      Matcher m = SYNC.matcher(path);
      if (!m.matches() || !"POST".equals(ex.getRequestMethod())) {
        reply(ex, 404, "{\"error\":\"not found\"}");
        return;
      }
      if (!bearer(headers)) {
        reply(ex, 401, "{\"error\":\"unauthorized\"}");
        return;
      }
      Canned c = canned.get(m.group(2));
      if (c != null) {
        reply(ex, c.status(), c.body());
        return;
      }
      reply(ex, 200, success(m.group(1), m.group(2), body));
    }
  }

  private void token(HttpExchange ex, String form) throws IOException {
    int failure = tokenFailure;
    if (failure != 0) {
      reply(ex, failure, "{\"error\":\"temporarily_unavailable\"}");
      return;
    }
    Map<String, String> fields = new LinkedHashMap<>();
    for (String pair : form.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        fields.put(
            URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    boolean ok =
        "client_credentials".equals(fields.get("grant_type"))
            && CLIENT_ID.equals(fields.get("client_id"))
            && CLIENT_SECRET.equals(fields.get("client_secret"));
    if (!ok) {
      reply(ex, 401, "{\"error\":\"invalid_client\"}");
      return;
    }
    String token = mint("tok-" + tokens.get());
    issued.add(token);
    reply(ex, 200, "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
  }

  /** A JWT as the platform shapes a client-credentials token, or the opaque id. */
  private String mint(String jti) {
    if (opaqueTokens) {
      return jti;
    }
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", baseUrl());
    if (tokenSub != null) {
      claims.put("sub", tokenSub);
    }
    if (tokenType != null) {
      claims.put("type", tokenType);
    }
    claims.put("tier", "ANCHOR");
    claims.put("jti", jti);
    claims.put("token_use", "api");
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    return b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
        + "."
        + b64.encodeToString(JSON.writeValueAsBytes(claims))
        + ".ZmFrZS1zaWduYXR1cmU";
  }

  private boolean bearer(Map<String, String> headers) {
    String auth = headers.getOrDefault("authorization", "");
    return auth.startsWith("Bearer ") && issued.contains(auth.substring(7));
  }

  /** The platform's success response for a sync, built from the request body. */
  private static String success(String app, String resource, String body) {
    List<String> codes = new ArrayList<>();
    JsonNode root = JSON.readTree(body);
    for (JsonNode child : root) {
      if (child.isArray()) {
        for (JsonNode item : child) {
          JsonNode code = item.has("code") ? item.get("code") : item.get("name");
          if (code != null) {
            codes.add(code.asString());
          }
        }
        break;
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("applicationCode", app);
    if ("scheduled-jobs".equals(resource)) {
      out.put("created", codes);
      out.put("updated", List.of());
      out.put("archived", List.of());
    } else {
      out.put("created", codes.size());
      out.put("updated", 0);
      out.put("deleted", 0);
      out.put("syncedCodes", codes);
    }
    return JSON.writeValueAsString(out);
  }

  private static void reply(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      ex.getResponseBody().write(bytes);
    }
  }
}
