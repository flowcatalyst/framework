package io.flowcatalyst.framework.httpclient.module;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.httpclient.HttpClients;
import io.flowcatalyst.framework.httpclient.ServiceClient;
import io.flowcatalyst.framework.httpclient.ServiceOptions;
import io.flowcatalyst.framework.metrics.Metrics;
import java.util.HashMap;
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
  private final Map<String, ClientMetrics.Scoped> registered = new HashMap<>();

  ServiceClients(HttpClients engine, ClientMetrics metrics, Map<String, ServiceClient> configured) {
    this.engine = engine;
    this.metrics = metrics;
    this.clients = new LinkedHashMap<>(configured);
  }

  /**
   * A registry with no configured clients, built without an application: for unit tests of code
   * that registers clients in code ({@link #register}, {@link #replace}, {@link #registerScoped})
   * and for tools that run outside the framework. Behaves exactly as the one the module provides.
   *
   * <pre>{@code
   * ServiceClients clients = ServiceClients.create(HttpClients.create(), Metrics.memory(1000, MonotonicClock.system()));
   * }</pre>
   *
   * @param engine the HTTP engine the clients are built on
   * @param metrics where client metrics go ({@code Metrics.memory(...)} in tests)
   * @return an empty registry
   */
  public static ServiceClients create(HttpClients engine, Metrics metrics) {
    return new ServiceClients(engine, new ClientMetrics(metrics), Map.of());
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
    ClientMetrics.Scoped observer = metrics.scoped();
    ServiceClient client = engine.service(HttpClientModule.observed(options, observer));
    lock.lock();
    try {
      if (clients.putIfAbsent(options.name(), client) != null) {
        throw new IllegalArgumentException("service client '" + options.name() + "' already exists");
      }
      registered.put(options.name(), observer);
    } finally {
      lock.unlock();
    }
    return client;
  }

  /**
   * Adds a client built in code, or swaps the registered client of that name for a new one (for
   * example when a route's endpoint or credentials changed). The previous client is
   * {@linkplain #unregister(String) unregistered}.
   *
   * @param options the service
   * @return the new client
   * @throws IllegalArgumentException when the name belongs to a configured client
   */
  public ServiceClient replace(ServiceOptions options) {
    ClientMetrics.Scoped observer = metrics.scoped();
    ServiceClient client = engine.service(HttpClientModule.observed(options, observer));
    String name = options.name();
    lock.lock();
    try {
      if (clients.containsKey(name) && !registered.containsKey(name)) {
        throw new IllegalArgumentException("service client '" + name + "' is configured and cannot be replaced");
      }
      unregister(name);
      clients.put(name, client);
      registered.put(name, observer);
    } finally {
      lock.unlock();
    }
    return client;
  }

  /**
   * Removes a client added with {@link #register} or {@link #replace}, and its client metrics
   * series (the {@code service} label value), so per-route or per-tenant clients do not
   * accumulate. Calls already in flight finish normally; a reference still held keeps working but
   * is no longer measured, and {@link #get(String)} no longer finds it. The shared connection pool
   * belongs to the engine and is untouched. Configured clients cannot be removed.
   *
   * @param name the client's name
   * @return true when a registered client was removed, false when there was none by that name
   * @throws IllegalArgumentException when the name is a configured client
   */
  public boolean unregister(String name) {
    lock.lock();
    try {
      ClientMetrics.Scoped observer = registered.remove(name);
      if (observer == null) {
        if (clients.containsKey(name)) {
          throw new IllegalArgumentException("service client '" + name + "' is configured and cannot be removed");
        }
        return false;
      }
      clients.remove(name);
      observer.close(name);
      return true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Registers a client that is removed when the returned handle is closed.
   *
   * <pre>{@code
   * try (ServiceClients.Registration r = clients.registerScoped(options)) {
   *   r.client().get("/ping").send();
   * }
   * }</pre>
   *
   * @param options the service
   * @return the handle; closing it unregisters the client (closing twice is harmless)
   * @throws IllegalArgumentException when the name is taken
   */
  public Registration registerScoped(ServiceOptions options) {
    return new Registration(this, options.name(), register(options));
  }

  /** A registered client that unregisters itself on {@link #close()}. */
  public static final class Registration implements AutoCloseable {
    private final ServiceClients owner;
    private final String name;
    private final ServiceClient client;

    private Registration(ServiceClients owner, String name, ServiceClient client) {
      this.owner = owner;
      this.name = name;
      this.client = client;
    }

    /**
     * The client.
     *
     * @return the client
     */
    public ServiceClient client() {
      return client;
    }

    /** Unregisters the client, only if it is still this registration's (not replaced meanwhile). */
    @Override
    public void close() {
      owner.lock.lock();
      try {
        if (owner.clients.get(name) == client) {
          owner.unregister(name);
        }
      } finally {
        owner.lock.unlock();
      }
    }
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
