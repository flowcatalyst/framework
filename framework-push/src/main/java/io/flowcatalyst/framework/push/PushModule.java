package io.flowcatalyst.framework.push;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Background;
import io.flowcatalyst.framework.core.Criticality;
import io.flowcatalyst.framework.core.Lookup;
import io.flowcatalyst.framework.core.LoopOptions;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.Provider;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.MigrationSet;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.jetty.HttpServer;
import io.flowcatalyst.framework.http.jetty.JettyHttpModule;
import io.flowcatalyst.framework.locks.LockGate;
import io.flowcatalyst.framework.locks.Locks;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.usecase.Push;
import io.flowcatalyst.framework.usecase.UseCases;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Push to clients (push contract): the settings {@code push.*}, the migration set (module
 * {@code push}: {@code fw_push_outbox} and {@code fw_push_log}, the same tables as the Go
 * framework's), the relay and retention loops, the hub, the health check {@code push:log} and the
 * three routes. List it after the database, locks, use case, HTTP and (for
 * {@link PushAuth#authModule()}) auth modules.
 *
 * <pre>{@code
 * PushModule.builder()
 *     .application(new ApplicationCode("fulfil"))
 *     .authenticate(PushAuth.authModule())
 *     .topic("fulfil:order:{id}", "fulfil:order:view") // any order of the stream's client
 *     .topic("fulfil:user:{principal}") // only your own
 *     .build();
 * }</pre>
 *
 * <p>Needs a lock instance for the relay's lock {@code svc:push-relay}.
 */
public final class PushModule implements Module {

  /** The module id; the migration history is keyed by it (the Go framework's is the same). */
  public static final String ID = "push";

  /** The log drivers (push contract section 4). */
  public enum Driver {
    /** The table {@code fw_push_log} (A1, the default). */
    POSTGRES,
    /** A Redis stream (A2); give the driver with {@link Builder#log}. */
    REDIS
  }

  /** {@code push.driver}. */
  public static final Setting<Driver> DRIVER =
      Setting.enumOf("push.driver", List.of(Driver.values()))
          .withDefault(Driver.POSTGRES)
          .describedAs("Where the push log lives: postgres (fw_push_log) or redis (a Redis stream).");

  /** {@code push.redis-instance}. */
  public static final Setting<String> REDIS_INSTANCE =
      Setting.string("push.redis-instance").describedAs("The Redis instance of the redis driver (default: the default instance).");

  /** {@code push.retention}. */
  public static final Setting<Duration> RETENTION =
      Setting.duration("push.retention")
          .withDefault(Duration.ofHours(24))
          .describedAs("How long the log keeps a push for clients that resume (10m to 24h).");

  /** {@code push.max-data-bytes}. */
  public static final Setting<Integer> MAX_DATA_BYTES =
      Setting.integer("push.max-data-bytes")
          .withDefault(Push.DEFAULT_MAX_DATA_BYTES)
          .describedAs("Largest push data, in bytes of JSON (1 to 65536).");

  /** {@code push.max-topics}. */
  public static final Setting<Integer> MAX_TOPICS =
      Setting.integer("push.max-topics").withDefault(100).describedAs("Most topics one subscribe may ask for (1 to 1000).");

  /** {@code push.relay.batch}. */
  public static final Setting<Integer> RELAY_BATCH =
      Setting.integer("push.relay.batch").withDefault(500).describedAs("Most outbox rows one relay run moves (1 to 5000).");

  /** {@code push.relay.interval}. */
  public static final Setting<Duration> RELAY_INTERVAL =
      Setting.duration("push.relay.interval")
          .withDefault(Duration.ofSeconds(1))
          .describedAs("Longest wait between relay runs, and the hubs' poll (50ms to 10s).");

  /** {@code push.relay.lease}. */
  public static final Setting<Duration> RELAY_LEASE =
      Setting.duration("push.relay.lease")
          .withDefault(Duration.ofSeconds(5))
          .describedAs(
              "The relay lock's lease: how long pushes wait to be logged after the relay's instance died; refreshed every third of it (1s to 60s).");

  /** {@code push.stream.buffer}. */
  public static final Setting<Integer> STREAM_BUFFER =
      Setting.integer("push.stream.buffer")
          .withDefault(256)
          .describedAs("Pushes a stream may fall behind before it is ended (16 to 10000).");

  /** {@code push.stream.max-lifetime}. */
  public static final Setting<Duration> STREAM_MAX_LIFETIME =
      Setting.duration("push.stream.max-lifetime")
          .withDefault(Duration.ofHours(1))
          .describedAs("Longest life of a stream token (1m to 24h).");

  /** {@code push.recent}. */
  public static final Setting<Integer> RECENT =
      Setting.integer("push.recent")
          .withDefault(10_000)
          .describedAs("Recent pushes each instance keeps in memory for replays (0 to 1000000).");

  /** {@code push.max-streams}. */
  public static final Setting<Integer> MAX_STREAMS =
      Setting.integer("push.max-streams").withDefault(50_000).describedAs("Most streams open on one instance (at least 1).");

  /** {@code push.max-streams-per-principal}. */
  public static final Setting<Integer> MAX_STREAMS_PER_PRINCIPAL =
      Setting.integer("push.max-streams-per-principal")
          .withDefault(20)
          .describedAs("Most streams of one principal on one instance (at least 1).");

  /** {@code push.stream.secret}. */
  public static final Setting<Secret> STREAM_SECRET =
      Setting.secret("push.stream.secret")
          .asRequired()
          .describedAs("The key that signs stream tokens (HMAC-SHA256), at least 32 characters, the same on every instance.");

  /** {@code push.ticket.key}. */
  public static final Setting<Secret> TICKET_KEY =
      Setting.secret("push.ticket.key")
          .asRequired()
          .describedAs(
              "Ticket signing keys: <kid>:<base64url of a 32-byte Ed25519 seed>, comma-separated, the current key first, then the previous one.");

  private static final Logger LOG = LoggerFactory.getLogger(PushModule.class);

  private static final long START_WAIT_MILLIS = 10_000;

  private final Builder b;

  private PushModule(Builder builder) {
    this.b = builder;
  }

  /**
   * Starts a module description.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.claimComponent("push");
    app.declare(DRIVER);
    app.declare(REDIS_INSTANCE);
    app.declare(RETENTION);
    app.declare(MAX_DATA_BYTES);
    app.declare(MAX_TOPICS);
    app.declare(RELAY_BATCH);
    app.declare(RELAY_INTERVAL);
    app.declare(RELAY_LEASE);
    app.declare(STREAM_BUFFER);
    app.declare(STREAM_MAX_LIFETIME);
    app.declare(RECENT);
    app.declare(MAX_STREAMS);
    app.declare(MAX_STREAMS_PER_PRINCIPAL);
    app.declare(STREAM_SECRET);
    app.declare(TICKET_KEY);
    MigrationSet.contribute(app, PushModule.class, "db/migration/push");
    app.provide(Pushes.class, this::build);
  }

  private static void range(List<Problem> problems, String key, long value, long min, long max) {
    if (value < min || value > max) {
      problems.add(Problem.of(ID, key, "must be " + min + " to " + max + ", was " + value));
    }
  }

  private static void rangeOf(List<Problem> problems, Setting<Duration> setting, Duration value, Duration min, Duration max) {
    if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
      problems.add(Problem.of(ID, setting.key(), "must be " + min + " to " + max + ", was " + value));
    }
  }

  private Pushes build(Lookup lookup) throws Exception {
    Config config = lookup.get(Config.class);
    List<Problem> problems = new ArrayList<>();
    for (String message : b.problems) {
      problems.add(Problem.of(ID, null, message));
    }
    ApplicationCode application = b.application;
    if (application == null) {
      problems.add(Problem.of(ID, null, "the application code is required: PushModule.builder().application(new ApplicationCode(...))"));
    } else {
      for (Rule r : b.rules) {
        if (!r.application().equals(application.value())) {
          problems.add(Problem.of(ID, null, "push rule " + r.pattern() + ": a topic starts with the application's code (" + application.value() + ")"));
        }
      }
    }
    if (!b.basePath.isEmpty() && (!b.basePath.startsWith("/") || b.basePath.endsWith("/"))) {
      problems.add(Problem.of(ID, null, "the base path starts with / and does not end with one: " + b.basePath));
    }
    Duration retention = config.get(RETENTION);
    rangeOf(problems, RETENTION, retention, Duration.ofMinutes(10), Duration.ofHours(24));
    int maxData = config.get(MAX_DATA_BYTES);
    range(problems, MAX_DATA_BYTES.key(), maxData, 1, Push.MAX_DATA_BYTES);
    int maxTopics = config.get(MAX_TOPICS);
    range(problems, MAX_TOPICS.key(), maxTopics, 1, 1000);
    int batch = config.get(RELAY_BATCH);
    range(problems, RELAY_BATCH.key(), batch, 1, 5000);
    Duration interval = config.get(RELAY_INTERVAL);
    rangeOf(problems, RELAY_INTERVAL, interval, Duration.ofMillis(50), Duration.ofSeconds(10));
    Duration lease = config.get(RELAY_LEASE);
    rangeOf(problems, RELAY_LEASE, lease, Duration.ofSeconds(1), Duration.ofMinutes(1));
    int buffer = config.get(STREAM_BUFFER);
    range(problems, STREAM_BUFFER.key(), buffer, 16, 10_000);
    Duration maxLifetime = config.get(STREAM_MAX_LIFETIME);
    rangeOf(problems, STREAM_MAX_LIFETIME, maxLifetime, Duration.ofMinutes(1), Duration.ofHours(24));
    int recent = config.get(RECENT);
    range(problems, RECENT.key(), recent, 0, 1_000_000);
    int maxStreams = config.get(MAX_STREAMS);
    range(problems, MAX_STREAMS.key(), maxStreams, 1, Integer.MAX_VALUE);
    int perPrincipal = config.get(MAX_STREAMS_PER_PRINCIPAL);
    range(problems, MAX_STREAMS_PER_PRINCIPAL.key(), perPrincipal, 1, Integer.MAX_VALUE);
    String secret = config.get(STREAM_SECRET).reveal();
    if (secret.length() < 32) {
      problems.add(Problem.of(ID, STREAM_SECRET.key(), "must be at least 32 characters"));
    }
    TicketKeys keys = null;
    try {
      keys = TicketKeys.parse(config.get(TICKET_KEY).reveal());
    } catch (IllegalArgumentException e) {
      problems.add(Problem.of(ID, TICKET_KEY.key(), String.valueOf(e.getMessage())));
    }
    Driver driver = config.get(DRIVER);
    Provider<? extends Log> custom = b.log;
    if (driver == Driver.REDIS && custom == null) {
      problems.add(Problem.of(ID, DRIVER.key(), "is redis, but PushModule.builder().log(...) was not given the redis driver"));
    }
    if (!problems.isEmpty() || keys == null || application == null) {
      throw new StartupException(problems);
    }
    var dataSource = lookup.get(Databases.class).defaultInstance().dataSource();
    InstantSource clock = lookup.get(InstantSource.class);
    PushMetrics metrics = new PushMetrics(lookup.get(Metrics.class));
    Log log = custom == null ? new PostgresLog(dataSource) : custom.create(lookup);
    PushAuthenticator authenticator = b.authenticator == null ? null : b.authenticator.create(lookup);
    Hub hub = new Hub(log, metrics, new Hub.Config(buffer, recent, maxStreams, perPrincipal, interval.toMillis()));
    Relay relay = new Relay(dataSource, log, clock, metrics, batch, interval.toMillis());
    Granter granter = new Granter(application.value(), b.rules, keys, maxLifetime);
    Pushes pushes =
        new Pushes(
            application.value(),
            dataSource,
            log,
            hub,
            relay,
            granter,
            new StreamTokens(secret),
            keys,
            authenticator,
            clock,
            metrics,
            new Pushes.Settings(maxData, maxTopics, retention, interval, LockGate.service(lookup.get(Locks.class), Relay.LOOP, lease)));
    return pushes;
  }

  @Override
  public void boot(App app) {
    Pushes pushes = app.get(Pushes.class);
    // the last id and the newest push.recent pushes, before anything is served
    pushes.hub().warm();
    app.find(UseCases.class).ifPresent(u -> u.pushMaxDataBytes(pushes.settings().maxDataBytes()));
    Background background = app.get(Background.class);
    background.loop(Relay.LOOP, LoopOptions.every(pushes.settings().relayInterval()).gatedBy(pushes.settings().relayGate()), pushes.relay()::run);
    background.loop(
        "push-retention",
        LoopOptions.every(Duration.ofMinutes(1)),
        tick -> pushes.log().prune(pushes.clock().instant().minus(pushes.settings().retention())));
    app.health().add("push:log", pushes.log()::ping, Criticality.INFORMATIONAL);
    Routes routes = app.get(Routes.class).forModule(ID);
    PushHttp http = pushes.http();
    http.heartbeat = app.config().get(JettyHttpModule.SSE_HEARTBEAT); // http.sse.heartbeat, as every event stream
    routes.post(b.basePath + "/push/subscriptions", http::subscribe);
    routes.get(b.basePath + "/push/stream", http::listen);
    routes.get(b.basePath + "/push/keys", http::keys);
    // the hub ends every stream at once when the server's drain begins (push contract section 8)
    app.find(HttpServer.class).ifPresent(server -> server.beforeDrain(pushes.hub()::drain));
  }

  @Override
  public void started(App app) throws Exception {
    Hub hub = app.get(Pushes.class).hub();
    hub.start();
    // streams open only once the hub follows the log, so no end of streams is missed; if the
    // driver cannot follow yet, the hub polls and the start goes on
    if (!hub.awaitFollowing(START_WAIT_MILLIS)) {
      LOG.warn("push: the hub does not follow the log yet; it polls every push.relay.interval meanwhile");
    }
  }

  @Override
  public void shutdown(App app) {
    app.get(Pushes.class).hub().stop(Relay.RUN_TIMEOUT_MILLIS);
  }

  /** Describes the module: the application, the rules, the authenticator and the base path. */
  public static final class Builder {

    private @Nullable ApplicationCode application;
    private String basePath = "";
    private @Nullable Provider<? extends PushAuthenticator> authenticator;
    private @Nullable Provider<? extends Log> log;
    private final List<Rule> rules = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    private Builder() {}

    /**
     * Sets the application code: the first segment of every topic, the {@code iss} of the tickets
     * this host mints and the only issuer it accepts. Required.
     *
     * @param code the code
     * @return this
     */
    public Builder application(ApplicationCode code) {
      this.application = code;
      return this;
    }

    /**
     * Puts the routes under a path ({@code /api} serves {@code /api/push/subscriptions}). Default:
     * none.
     *
     * @param path starts with {@code /}, does not end with one
     * @return this
     */
    public Builder basePath(String path) {
      this.basePath = path;
      return this;
    }

    /**
     * Sets how a subscribe request is authenticated: {@link PushAuth#authModule()} for the auth
     * module. Without it every subscribe is unauthenticated and needs tickets.
     *
     * @param provider the authenticator
     * @return this
     */
    public Builder authenticate(Provider<? extends PushAuthenticator> provider) {
      this.authenticator = provider;
      return this;
    }

    /**
     * Gives the log driver, used instead of the Postgres one (the Redis driver, A2).
     *
     * @param provider the driver
     * @return this
     */
    public Builder log(Provider<? extends Log> provider) {
      this.log = provider;
      return this;
    }

    /**
     * Declares a rule (push contract 6.3): who may subscribe to the topics of a pattern without a
     * ticket. A pattern with neither {@code {principal}} nor a required permission is refused (a
     * start-up problem); its first segment must be the application's code.
     *
     * @param pattern the topic pattern
     * @param requires the permissions needed, all of them
     * @return this
     */
    public Builder topic(String pattern, String... requires) {
      try {
        rules.add(Rule.of(pattern, requires));
      } catch (IllegalArgumentException e) {
        problems.add(String.valueOf(e.getMessage()));
      }
      return this;
    }

    /**
     * Declares a deliberately open topic pattern: anyone may subscribe, with or without
     * credentials.
     *
     * @param pattern the topic pattern
     * @return this
     */
    public Builder publicTopic(String pattern) {
      try {
        rules.add(Rule.open(pattern));
      } catch (IllegalArgumentException e) {
        problems.add(String.valueOf(e.getMessage()));
      }
      return this;
    }

    /**
     * Builds the module.
     *
     * @return the module
     */
    public PushModule build() {
      return new PushModule(this);
    }
  }
}
