package io.flowcatalyst.framework.httpclient.module;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.httpclient.HttpClients;
import io.flowcatalyst.framework.httpclient.ServiceClient;
import io.flowcatalyst.framework.httpclient.ServiceOptions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The configured service clients, by name, plus clients registered in code. Provided by {@link
 * HttpClientModule}.
 *
 * <pre>{@code
 * ServiceClient billing = app.get(ServiceClients.class).get("billing", ctx);
 * }</pre>
 */
public final class ServiceClients {

  /** The header the context's correlation id travels in. */
  public static final String CORRELATION_HEADER = "X-Correlation-Id";

  private final HttpClients engine;
  private final ClientMetrics metrics;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, ServiceClient> clients;

  ServiceClients(HttpClients engine, ClientMetrics metrics, Map<String, ServiceClient> configured) {
    this.engine = engine;
    this.metrics = metrics;
    this.clients = new LinkedHashMap<>(configured);
  }

  /**
   * A client by name.
   *
   * @param name the instance name under {@code http-clients.instances}, or a registered name
   * @return the client
   * @throws IllegalArgumentException when there is no such client
   */
  public ServiceClient get(String name) {
    lock.lock();
    try {
      ServiceClient client = clients.get(name);
      if (client == null) {
        throw new IllegalArgumentException(
            "no service client named '" + name + "'; known: " + String.join(", ", clients.keySet()));
      }
      return client;
    } finally {
      lock.unlock();
    }
  }

  /**
   * A client by name that sends the context's correlation id ({@value #CORRELATION_HEADER}) on
   * every request. Nothing else from the context is sent.
   *
   * @param name the client's name
   * @param context the context of the work making the calls
   * @return the client
   */
  public ServiceClient get(String name, Context context) {
    return get(name).withHeaders(Map.of(CORRELATION_HEADER, context.correlationId().value()));
  }

  /**
   * Adds a client built in code, for example one authenticated with the platform's token. Its
   * attempts are recorded in the client metrics; the options' own observer is replaced.
   *
   * @param options the service
   * @return the client
   * @throws IllegalArgumentException when the name is taken
   */
  public ServiceClient register(ServiceOptions options) {
    ServiceClient client = engine.service(HttpClientModule.observed(options, metrics));
    lock.lock();
    try {
      if (clients.putIfAbsent(options.name(), client) != null) {
        throw new IllegalArgumentException("service client '" + options.name() + "' already exists");
      }
    } finally {
      lock.unlock();
    }
    return client;
  }

  /**
   * The names of every client.
   *
   * @return names, configured first
   */
  public Set<String> names() {
    lock.lock();
    try {
      return Set.copyOf(clients.keySet());
    } finally {
      lock.unlock();
    }
  }
}
