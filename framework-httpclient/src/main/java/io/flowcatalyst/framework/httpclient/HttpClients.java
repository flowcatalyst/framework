package io.flowcatalyst.framework.httpclient;

import java.time.InstantSource;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Owns the client engine (one Vert.x instance and its connection pools) and creates a {@link
 * ServiceClient} per consumed service. Thread-safe; create one per application and close it at
 * shutdown.
 *
 * <pre>{@code
 * try (HttpClients clients = HttpClients.create()) {
 *   ServiceClient orders =
 *       clients.service(ServiceOptions.builder("orders", URI.create("https://orders.example.com")).build());
 * }
 * }</pre>
 */
public final class HttpClients implements AutoCloseable {

  /**
   * Engine settings.
   *
   * @param eventLoops Vert.x event loops (1 to 64); the blocking callers are virtual threads
   * @param maxConnectionsPerHost pooled connections per host (1 to 1000)
   * @param nanoClock a monotonic clock, for token expiry and durations
   * @param wallClock the wall clock, for {@code Retry-After} dates
   */
  public record Settings(int eventLoops, int maxConnectionsPerHost, LongSupplier nanoClock, InstantSource wallClock) {

    /** Checks the ranges. */
    public Settings {
      if (eventLoops < 1 || eventLoops > 64) {
        throw new IllegalArgumentException("eventLoops must be 1 to 64");
      }
      if (maxConnectionsPerHost < 1 || maxConnectionsPerHost > 1000) {
        throw new IllegalArgumentException("maxConnectionsPerHost must be 1 to 1000");
      }
    }

    /**
     * Two event loops, 50 connections per host, the system clocks.
     *
     * @return the settings
     */
    public static Settings defaults() {
      return new Settings(2, 50, System::nanoTime, InstantSource.system());
    }
  }

  private final Transport transport;
  private final Settings settings;

  private HttpClients(Settings settings) {
    this.settings = settings;
    this.transport = new Transport(settings.eventLoops(), settings.maxConnectionsPerHost());
  }

  /**
   * An engine with the default settings.
   *
   * @return the engine
   */
  public static HttpClients create() {
    return new HttpClients(Settings.defaults());
  }

  /**
   * An engine with given settings.
   *
   * @param settings the settings
   * @return the engine
   */
  public static HttpClients create(Settings settings) {
    return new HttpClients(settings);
  }

  /**
   * A client for one service. A client-credentials auth gets its own token cache.
   *
   * @param options the service
   * @return the client
   */
  public ServiceClient service(ServiceOptions options) {
    TokenCache tokens =
        options.auth() instanceof Auth.OAuthClientCredentials c
            ? new TokenCache(c.credentials(), transport, options, settings.nanoClock())
            : null;
    return new ServiceClient(options, transport, settings.nanoClock(), settings.wallClock(), tokens, Map.of());
  }

  /** Closes connections and the engine. */
  @Override
  public void close() {
    transport.close();
  }
}
