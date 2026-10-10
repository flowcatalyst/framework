package io.flowcatalyst.framework.httpclient.module;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.config.InstanceConfig;
import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.config.SettingType;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Instances;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.httpclient.Auth;
import io.flowcatalyst.framework.httpclient.ClientCredentials;
import io.flowcatalyst.framework.httpclient.HttpClients;
import io.flowcatalyst.framework.httpclient.RetryPolicy;
import io.flowcatalyst.framework.httpclient.ServiceClient;
import io.flowcatalyst.framework.httpclient.ServiceOptions;
import io.flowcatalyst.framework.metrics.Metrics;
import java.time.Duration;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * The HTTP client module (id {@code http-client}, http-client contract section 5): provides {@link
 * HttpClients} (closed at shutdown) and {@link ServiceClients}, built from {@code
 * http-clients.instances}.
 *
 * <pre>{@code
 * http-clients:
 *   instances:
 *     billing:
 *       base-url: https://billing.example.com
 *       request-timeout: 10s
 *       retry: { max-attempts: 3 }
 *       auth: { type: client-credentials, token-url: https://login.example.com/oauth/token, client-id: orders }
 * }</pre>
 */
public final class HttpClientModule implements Module {

  /** The module id. */
  public static final String ID = "http-client";

  /** The only driver of service client instances. */
  public static final String DRIVER = "http";

  /** {@code http-client.event-loops}: the engine's event loops. */
  public static final Setting<Integer> EVENT_LOOPS =
      Setting.integer("http-client.event-loops")
          .withDefault(2)
          .describedAs("Vert.x event loops of the HTTP client engine (1 to 64).");

  /** {@code http-client.max-connections-per-host}: the pool size per host. */
  public static final Setting<Integer> MAX_CONNECTIONS =
      Setting.integer("http-client.max-connections-per-host")
          .withDefault(50)
          .describedAs("Pooled connections per host (1 to 1000).");

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.declare(EVENT_LOOPS);
    app.declare(MAX_CONNECTIONS);
    app.provide(
        HttpClients.class,
        lookup -> {
          Config config = lookup.get(Config.class);
          MonotonicClock clock = lookup.get(MonotonicClock.class);
          return HttpClients.create(
              new HttpClients.Settings(
                  config.get(EVENT_LOOPS), config.get(MAX_CONNECTIONS), clock::nanos, lookup.get(InstantSource.class)));
        });
    app.provide(ClientMetrics.class, lookup -> new ClientMetrics(lookup.get(Metrics.class)));
    app.drivers()
        .register(
            ServiceClient.class,
            DRIVER,
            (name, config, lookup, built) ->
                lookup
                    .get(HttpClients.class)
                    .service(observed(options(name, config), lookup.get(ClientMetrics.class))));
    app.provide(
        ServiceClients.class,
        lookup -> {
          Instances<ServiceClient> configured =
              Instances.build(ServiceClient.class, "http-clients", lookup, DRIVER);
          return new ServiceClients(
              lookup.get(HttpClients.class),
              lookup.get(ClientMetrics.class),
              new LinkedHashMap<>(configured.all()));
        });
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

  /** Reads one instance section; every key is read so an unknown one fails start-up. */
  static ServiceOptions options(String name, InstanceConfig config) {
    ServiceOptions.Builder b = ServiceOptions.builder(name, config.get("base-url", SettingType.URI));
    b.connectTimeout(config.duration("connect-timeout", Duration.ofSeconds(5)));
    b.requestTimeout(config.duration("request-timeout", Duration.ofSeconds(30)));
    b.maxResponseBytes(config.get("max-response-bytes", SettingType.LONG, 10L * 1024 * 1024));

    InstanceConfig retry = config.section("retry");
    RetryPolicy defaults = RetryPolicy.defaults();
    b.retry(
        defaults
            .withMaxAttempts(retry.integer("max-attempts", defaults.maxAttempts()))
            .withBackoff(
                retry.duration("initial-backoff", defaults.initialBackoff()),
                retry.duration("max-backoff", defaults.maxBackoff()))
            .withMaxRetryAfter(retry.duration("max-retry-after", defaults.maxRetryAfter())));

    InstanceConfig auth = config.section("auth");
    String type = auth.string("type", "none").toLowerCase(Locale.ROOT);
    b.auth(
        switch (type) {
          case "none" -> Auth.none();
          case "bearer" -> {
            Secret token = auth.secret("token");
            yield Auth.bearer(token::reveal);
          }
          case "basic" -> {
            Secret password = auth.secret("password");
            yield Auth.basic(auth.string("username"), password::reveal);
          }
          case "header" -> {
            Secret value = auth.secret("value");
            yield Auth.header(auth.string("name"), value::reveal);
          }
          case "client-credentials" -> {
            Secret secret = auth.secret("client-secret");
            ClientCredentials.Builder cc =
                ClientCredentials.builder(
                    auth.get("token-url", SettingType.URI), auth.string("client-id"), secret::reveal);
            auth.findString("scope").ifPresent(cc::scope);
            auth.findString("audience").ifPresent(cc::audience);
            String method = auth.string("method", "post").toLowerCase(Locale.ROOT);
            cc.method(
                switch (method) {
                  case "post" -> ClientCredentials.Method.POST;
                  case "basic" -> ClientCredentials.Method.BASIC;
                  default -> throw new IllegalArgumentException("auth.method must be post or basic");
                });
            yield Auth.clientCredentials(cc.build());
          }
          default ->
              throw new IllegalArgumentException(
                  "auth.type must be none, bearer, basic, header or client-credentials");
        });
    return b.build();
  }
}
