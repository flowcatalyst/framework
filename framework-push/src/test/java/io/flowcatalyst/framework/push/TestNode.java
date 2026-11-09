package io.flowcatalyst.framework.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.CorrelationId;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.jetty.HttpServer;
import io.flowcatalyst.framework.http.jetty.JettyHttpModule;
import io.flowcatalyst.framework.locks.LocksModule;
import io.flowcatalyst.framework.locks.database.DatabaseLockStores;
import io.flowcatalyst.framework.locks.database.LocksDatabaseModule;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.MetricsSnapshot;
import io.flowcatalyst.framework.usecase.OutboxModule;
import io.flowcatalyst.framework.usecase.Push;
import io.flowcatalyst.framework.usecase.UseCaseModule;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * One instance of an application using push: its own hub, relay loop and HTTP server, on a database
 * it may share with other nodes. Several nodes on one database are several hosts.
 */
final class TestNode implements AutoCloseable {

  /** The TEST keys of conformance/push/tickets.json: never use them for anything real. */
  static final String KEY_CURRENT = "test-2026-a:5BgJptHbkz4uIeRqgLz3Ws_38Ovwi1uCpSdyW45yMdc";

  static final String KEY_PREVIOUS = "test-2025-z:aMAmPCO41Sx6WwDvG_MWCgxTdygpi14ynrtSTxTfFrM";
  static final String SECRET = "CMY325HtlvFwAGl8Fh0YFzJiXVcxbQO905RbLlbkbiZlcknycLNMYxgoAXXKt2b";

  /** The tests' clock start, on a whole second. */
  static final Instant BEGIN = Instant.parse("2026-11-08T12:00:00Z");

  static final ClientId ACME = new ClientId("acme");
  static final ClientId OTHER = new ClientId("other");

  static final ObjectMapper JSON = JsonMapper.builder().build();

  /** What a test node is started with. */
  record Config(Map<String, String> env, @Nullable TestClock clock, Consumer<PushModule.Builder> push, List<Module> extra, boolean useCases) {

    static Config defaults() {
      return new Config(Map.of(), null, b -> {}, List.of(), false);
    }

    Config withEnv(String... pairs) {
      Map<String, String> m = new HashMap<>(env);
      for (int i = 0; i + 1 < pairs.length; i += 2) {
        m.put(pairs[i], pairs[i + 1]);
      }
      return new Config(m, clock, push, extra, useCases);
    }

    Config withClock(TestClock c) {
      return new Config(env, c, push, extra, useCases);
    }

    Config withPush(Consumer<PushModule.Builder> p) {
      return new Config(env, clock, p, extra, useCases);
    }

    Config withExtra(Module... modules) {
      return new Config(env, clock, push, List.of(modules), useCases);
    }

    Config withUseCases() {
      return new Config(env, clock, push, extra, true);
    }
  }

  /** The rules every test node declares unless the test sets its own. */
  static void defaultRules(PushModule.Builder b) {
    b.topic("test:order:{id}", "test:order:view");
    b.topic("test:user:{principal}");
    b.topic("test:client:{client}:news", "test:news:view");
    b.publicTopic("test:public");
  }

  /**
   * The tests' authenticator: X-Client (with X-Principal, X-Permissions, X-Expires in unix seconds,
   * X-Proof-Key) is a credential; X-Fail is a bad one; neither is none.
   */
  static final PushAuthenticator HEADER_AUTH =
      request -> {
        if (request.header("X-Fail") != null) {
          throw new HttpException(401, "UNAUTHENTICATED", "bad credentials");
        }
        String client = request.header("X-Client");
        if (client == null) {
          return Optional.empty();
        }
        String principal = request.header("X-Principal");
        Set<String> permissions = new HashSet<>();
        String header = request.header("X-Permissions");
        if (header != null) {
          for (String p : header.split(",")) {
            if (!p.isBlank()) {
              permissions.add(p);
            }
          }
        }
        Context context =
            new Context(
                new ClientId(client), principal == null ? null : new PrincipalId(principal), null, CorrelationId.generate(), null, null, permissions);
        String expires = request.header("X-Expires");
        String proof = request.header("X-Proof-Key");
        return Optional.of(
            new Credentials(context, expires == null ? null : Instant.ofEpochSecond(Long.parseLong(expires)), proof == null ? "" : proof, "", ""));
      };

  final App app;
  final Pushes pushes;
  final String base;
  final TestClock clock;
  final HttpClient http = HttpClient.newHttpClient();
  final DataSource dataSource;
  private final List<Sse> streams = new ArrayList<>();

  private TestNode(App app, TestClock clock) {
    this.app = app;
    this.pushes = app.get(Pushes.class);
    this.clock = clock;
    this.base = "http://127.0.0.1:" + app.get(HttpServer.class).port();
    this.dataSource = app.get(Databases.class).defaultInstance().dataSource();
  }

  static TestNode start(PostgresFixture pg, Path dir, Config config) throws Exception {
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        database:
          default: main
          instances:
            main: { url: %s, username: %s, password: %s }
        """
            .formatted(pg.jdbcUrl(), pg.username(), pg.password()));
    Map<String, String> env = new LinkedHashMap<>();
    env.put("APP_ENV", "test");
    env.put("HTTP_HOST", "127.0.0.1");
    env.put("HTTP_PORT", "0");
    env.put("PUSH_STREAM_SECRET", SECRET);
    env.put("PUSH_TICKET_KEY", KEY_CURRENT + "," + KEY_PREVIOUS);
    env.put("PUSH_RELAY_INTERVAL", "50ms");
    env.putAll(config.env());
    TestClock clock = config.clock() == null ? new TestClock(BEGIN) : config.clock();
    PushModule.Builder builder = PushModule.builder().application(new io.flowcatalyst.framework.context.ApplicationCode("test")).authenticate(lookup -> HEADER_AUTH);
    defaultRules(builder);
    config.push().accept(builder);
    AppBuilder app =
        App.builder()
            .configDirectory(dir)
            .environment(env)
            .instantSource(clock)
            .shutdownHook(false)
            .module(new DatabaseModule())
            .module(LocksModule.builder().store("main", DatabaseLockStores.database()).build())
            .module(new LocksDatabaseModule());
    if (config.useCases()) {
      app.module(new OutboxModule()).module(new UseCaseModule());
    }
    app.module(new JettyHttpModule());
    for (Module m : config.extra()) {
      app.module(m);
    }
    app.module(builder.build());
    return new TestNode(app.start(), clock);
  }

  /** Writes a push for a client in its own transaction. */
  void publish(ClientId client, String topic, String type, Map<String, Object> data) {
    try {
      pushes.publishAlone(Context.of(client), Push.of(topic, type, data));
    } catch (SQLException e) {
      fail("publish", e);
    }
  }

  /** The log's ids, in order. */
  List<Long> logIds() {
    return query("SELECT id FROM fw_push_log ORDER BY id").stream().map(Long::parseLong).toList();
  }

  List<String> query(String sql) {
    try (Connection c = dataSource.getConnection();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      List<String> out = new ArrayList<>();
      while (rs.next()) {
        out.add(rs.getString(1));
      }
      return out;
    } catch (SQLException e) {
      return fail("query " + sql, e);
    }
  }

  void execute(String sql) {
    try (Connection c = dataSource.getConnection();
        Statement s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      fail("execute " + sql, e);
    }
  }

  /** Waits until n pushes are logged and this node's hub has dispatched them; returns their ids. */
  List<Long> settle(int n) {
    await("the relay to log " + n + " pushes", () -> logIds().size() >= n && pushes.hub().lastId() >= logIds().get(n - 1));
    return logIds();
  }

  static void await(String what, BooleanSupplier condition) {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted waiting for " + what);
      }
    }
    fail("timed out waiting for " + what);
  }

  /** An HTTP answer. */
  record Answer(int status, HttpResponse<String> response) {
    String body() {
      return response.body();
    }

    JsonNode json() {
      return JSON.readTree(response.body());
    }

    String code() {
      return json().path("error").asString("");
    }
  }

  /** Sends a request; headers are name, value pairs. */
  Answer call(String method, String path, @Nullable String body, String... headers) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base + path))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    for (int i = 0; i + 1 < headers.length; i += 2) {
      request.header(headers[i], headers[i + 1]);
    }
    try {
      HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      return new Answer(response.statusCode(), response);
    } catch (Exception e) {
      return fail("call " + method + " " + path, e);
    }
  }

  static String subscribeBody(List<String> topics, List<String> tickets) {
    return JSON.writeValueAsString(Map.of("topics", topics, "tickets", tickets));
  }

  /** Subscribes and returns the stream token; fails the test unless the answer is 200. */
  String subscribe(List<String> topics, List<String> tickets, String... headers) {
    Answer a = call("POST", "/push/subscriptions", subscribeBody(topics, tickets), headers);
    assertThat(a.status()).as("subscribe %s: %s", topics, a.body()).isEqualTo(200);
    return a.json().path("streamToken").asString();
  }

  /** Opens GET /push/stream with the token; last, when not null, is the Last-Event-ID. */
  Sse listen(String token, @Nullable String last) {
    try {
      Sse sse = Sse.open(http, base + "/push/stream?st=" + token, last);
      synchronized (streams) {
        streams.add(sse);
      }
      return sse;
    } catch (Exception e) {
      return fail("listen", e);
    }
  }

  /** Subscribes as client (with a principal, and the permission to view orders) and opens the stream. */
  Sse open(String client, List<String> topics) {
    return open(client, topics, null);
  }

  /** The same, with a Last-Event-ID. */
  Sse open(String client, List<String> topics, @Nullable String last) {
    String token = subscribe(topics, List.of(), "X-Client", client, "X-Principal", "prn_" + client, "X-Permissions", "test:order:view");
    Sse sse = listen(token, last);
    sse.greeting();
    return sse;
  }

  double counter(String name, String... labels) {
    MetricsSnapshot snapshot = app.get(Metrics.class).snapshot();
    for (MetricsSnapshot.Family f : snapshot.families()) {
      if (f.name().equals(name)) {
        for (MetricsSnapshot.Sample s : f.samples()) {
          if (s.labelValues().equals(Arrays.asList(labels))) {
            return s.value();
          }
        }
      }
    }
    return 0;
  }

  @Override
  public void close() {
    synchronized (streams) {
      streams.forEach(Sse::close);
    }
    app.close();
    http.close();
  }
}
