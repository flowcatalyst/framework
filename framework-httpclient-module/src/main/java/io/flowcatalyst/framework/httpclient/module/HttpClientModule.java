package io.flowcatalyst.framework.httpclient.module;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.config.ConfigException;
import io.flowcatalyst.framework.config.InstanceConfig;
import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.config.SettingType;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Lookup;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.httpclient.Auth;
import io.flowcatalyst.framework.httpclient.ClientCredentials;
import io.flowcatalyst.framework.httpclient.HttpClients;
import io.flowcatalyst.framework.httpclient.ServiceClient;
import io.flowcatalyst.framework.httpclient.ServiceOptions;
import io.flowcatalyst.framework.metrics.Metrics;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The HTTP client module (id {@code http-client}, http-client contract section 5): provides {@link
 * HttpClients} (closed at shutdown) and {@link ServiceClients}.
 *
 * <p>Clients are named in code with their behaviour (config-and-code contract, section 3.6);
 * configuration holds each one's base URL and the credentials its {@link ClientAuth} needs:
 *
 * <pre>{@code
 * HttpClientModule.builder()
 *     .client("billing", HttpClientOptions.defaults()
 *         .withRequestTimeout(Duration.ofSeconds(10))
 *         .withAuth(ClientAuth.clientCredentials().withScope("billing")))
 *     .build()
 * }</pre>
 *
 * <pre>{@code
 * http-clients:
 *   instances:
 *     billing:
 *       base-url: https://billing.example.com
 *       auth: { token-url: https://login.example.com/oauth/token, client-id: orders }
 *       # auth.client-secret: from HTTP_CLIENTS_INSTANCES_BILLING_AUTH_CLIENT_SECRET
 * }</pre>
 *
 * <p>A key in a client's section that its strategy does not read, and a client section with no
 * client of that name in code, fail start-up. The module claims the {@code http-client}
 * component, whose engine settings moved to {@link Builder#eventLoops} and {@link
 * Builder#maxConnectionsPerHost}.
 */
public final class HttpClientModule implements Module {

  /** The module id. */
  public static final String ID = "http-client";

  private static final String SECTION = "http-clients";
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");

  private final int eventLoops;
  private final int maxConnectionsPerHost;
  private final Map<String, Function<Config, HttpClientOptions>> clients;

  /** Two event loops, 50 connections per host, no client named in code. */
  public HttpClientModule() {
    this(builder());
  }

  private HttpClientModule(Builder b) {
    this.eventLoops = b.eventLoops;
    this.maxConnectionsPerHost = b.maxConnectionsPerHost;
    this.clients = new LinkedHashMap<>(b.clients);
  }

  /**
   * Starts a module definition with the defaults.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builds an {@link HttpClientModule}. */
  public static final class Builder {
    private int eventLoops = 2;
    private int maxConnectionsPerHost = 50;
    private final Map<String, Function<Config, HttpClientOptions>> clients = new LinkedHashMap<>();

    private Builder() {}

    /**
     * The engine's Vert.x event loops (default 2).
     *
     * @param value 1 to 64
     * @return this builder
     * @throws IllegalArgumentException when out of range
     */
    public Builder eventLoops(int value) {
      if (value < 1 || value > 64) {
        throw new IllegalArgumentException("eventLoops must be 1 to 64, was " + value);
      }
      this.eventLoops = value;
      return this;
    }

    /**
     * Pooled connections per host (default 50).
     *
     * @param value 1 to 1000
     * @return this builder
     * @throws IllegalArgumentException when out of range
     */
    public Builder maxConnectionsPerHost(int value) {
      if (value < 1 || value > 1000) {
        throw new IllegalArgumentException("maxConnectionsPerHost must be 1 to 1000, was " + value);
      }
      this.maxConnectionsPerHost = value;
      return this;
    }

    /**
     * Names a client. Its base URL and credentials are read from {@code
     * http-clients.instances.<name>}.
     *
     * @param name {@code [a-z][a-z0-9-]*}, unique
     * @param options how it behaves
     * @return this builder
     * @throws IllegalArgumentException for an invalid or repeated name
     */
    public Builder client(String name, HttpClientOptions options) {
      return client(name, config -> options);
    }

    /**
     * As {@link #client(String, HttpClientOptions)}, with the options computed from configuration
     * once it is loaded (an application's own settings).
     *
     * @param name {@code [a-z][a-z0-9-]*}, unique
     * @param options computes the options
     * @return this builder
     * @throws IllegalArgumentException for an invalid or repeated name
     */
    public Builder client(String name, Function<Config, HttpClientOptions> options) {
      if (name.length() > 100 || !NAME.matcher(name).matches()) {
        throw new IllegalArgumentException("client name must match [a-z][a-z0-9-]*, was '" + name + "'");
      }
      if (clients.putIfAbsent(name, options) != null) {
        throw new IllegalArgumentException("client '" + name + "' is already given");
      }
      return this;
    }

    /**
     * Builds the module.
     *
     * @return the module
     */
    public HttpClientModule build() {
      return new HttpClientModule(this);
    }
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.claimComponent("http-client");
    app.provide(
        HttpClients.class,
        lookup -> {
          MonotonicClock clock = lookup.get(MonotonicClock.class);
          return HttpClients.create(
              new HttpClients.Settings(
                  eventLoops, maxConnectionsPerHost, clock::nanos, lookup.get(InstantSource.class)));
        });
    app.provide(ClientMetrics.class, lookup -> new ClientMetrics(lookup.get(Metrics.class)));
    app.provide(
        ServiceClients.class,
        lookup ->
            new ServiceClients(
                lookup.get(HttpClients.class), lookup.get(ClientMetrics.class), named(lookup)));
  }

  /** Builds the clients named in code from their sections; every problem fails start-up. */
  private Map<String, ServiceClient> named(Lookup lookup) {
    Config config = lookup.get(Config.class);
    HttpClients engine = lookup.get(HttpClients.class);
    ClientMetrics metrics = lookup.get(ClientMetrics.class);
    InstanceConfig top = config.section(SECTION);
    InstanceConfig instances = top.section("instances");
    List<Problem> problems = new ArrayList<>();
    for (String stray : top.keys()) {
      if (!stray.equals("instances")) {
        problems.add(new Problem(ID, SECTION + "." + stray, top.sourceOf(stray), "unknown key", null));
      }
    }
    for (String configured : instances.keys()) {
      if (!clients.containsKey(configured)) {
        problems.add(
            Problem.of(
                ID,
                SECTION + ".instances." + configured,
                "no client named '"
                    + configured
                    + "' in code: name it with HttpClientModule.builder().client(\""
                    + configured
                    + "\", ...) or delete the section; named: "
                    + (clients.isEmpty() ? "(none)" : String.join(", ", clients.keySet()))));
      }
    }
    Map<String, ServiceClient> built = new LinkedHashMap<>();
    for (Map.Entry<String, Function<Config, HttpClientOptions>> e : clients.entrySet()) {
      String name = e.getKey();
      String path = SECTION + ".instances." + name;
      InstanceConfig section = instances.section(name);
      try {
        ServiceOptions options = serviceOptions(name, e.getValue().apply(config), section);
        built.put(name, engine.service(observed(options, metrics)));
      } catch (ConfigException ce) {
        problems.add(new Problem(ID, ce.problem().key(), ce.problem().source(), ce.problem().message(), null));
      } catch (RuntimeException re) {
        problems.add(new Problem(ID, path, null, "client '" + name + "': " + re.getMessage(), re));
      }
      for (String unknown : section.unreadKeys()) {
        problems.add(
            new Problem(
                ID,
                path + "." + unknown,
                section.sourceOf(unknown),
                "unknown key for client '" + name + "' (its ClientAuth reads only its own credentials)",
                null));
      }
    }
    if (!problems.isEmpty()) {
      throw new StartupException(problems);
    }
    return built;
  }

  static ServiceOptions observed(ServiceOptions options, ClientMetrics metrics) {
    return new ServiceOptions(
        options.name(),
        options.baseUrl(),
        options.connectTimeout(),
        options.requestTimeout(),
        options.maxResponseBytes(),
        options.retry(),
        options.auth(),
        options.defaultHeaders(),
        options.mapper(),
        metrics);
  }

  /** The code's options plus the section's base URL and credentials. */
  static ServiceOptions serviceOptions(String name, HttpClientOptions o, InstanceConfig section) {
    ServiceOptions.Builder b = ServiceOptions.builder(name, section.get("base-url", SettingType.URI));
    b.connectTimeout(o.connectTimeout());
    b.requestTimeout(o.requestTimeout());
    b.maxResponseBytes(o.maxResponseBytes());
    b.retry(o.retry());
    InstanceConfig auth = section.section("auth");
    b.auth(
        switch (o.auth()) {
          case ClientAuth.None none -> Auth.none();
          case ClientAuth.Bearer bearer -> {
            Secret token = auth.secret("token");
            yield Auth.bearer(token::reveal);
          }
          case ClientAuth.Basic basic -> {
            String user = auth.string("username");
            Secret password = auth.secret("password");
            yield Auth.basic(user, password::reveal);
          }
          case ClientAuth.Header header -> {
            Secret value = auth.secret("value");
            yield Auth.header(header.name(), value::reveal);
          }
          case ClientAuth.ClientCredentialsGrant grant -> {
            java.net.URI tokenUrl = auth.get("token-url", SettingType.URI);
            String clientId = auth.string("client-id");
            Secret secret = auth.secret("client-secret");
            ClientCredentials.Builder cc = ClientCredentials.builder(tokenUrl, clientId, secret::reveal);
            String scope = grant.scope();
            if (scope != null) {
              cc.scope(scope);
            }
            String audience = grant.audience();
            if (audience != null) {
              cc.audience(audience);
            }
            cc.method(grant.method());
            yield Auth.clientCredentials(cc.build());
          }
        });
    return b.build();
  }
}
