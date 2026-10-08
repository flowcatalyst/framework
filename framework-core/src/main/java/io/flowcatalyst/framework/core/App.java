package io.flowcatalyst.framework.core;

import io.flowcatalyst.framework.config.Config;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A running application: typed service lookup, configuration, health checks and shutdown.
 *
 * <p>Created only by {@link AppBuilder#start()}.
 */
public final class App implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(App.class);

  private final Config config;
  private final Health health;
  private final Duration drain;
  private final Map<Class<?>, Object> services;
  private final List<Class<?>> buildOrder;
  private final List<Module> modules;
  private final List<Module> booted = new ArrayList<>();
  private final ReentrantLock closeLock = new ReentrantLock();
  private boolean closed;
  private @Nullable Thread shutdownHook;

  App(
      Config config,
      Health health,
      Duration drain,
      Map<Class<?>, Object> services,
      List<Class<?>> buildOrder,
      List<Module> modules) {
    this.config = config;
    this.health = health;
    this.drain = drain;
    this.services = services;
    this.buildOrder = List.copyOf(buildOrder);
    this.modules = List.copyOf(modules);
  }

  /**
   * Starts describing an application.
   *
   * @return a new builder
   */
  public static AppBuilder builder() {
    return new AppBuilder();
  }

  /**
   * Returns the service of that type.
   *
   * @param <T> the service type
   * @param type the service type
   * @return the service
   * @throws IllegalArgumentException when no module provides it
   */
  public <T> T get(Class<T> type) {
    Object service = services.get(type);
    if (service == null) {
      throw new IllegalArgumentException("no service of type " + type.getName() + " is provided");
    }
    return type.cast(service);
  }

  /**
   * The resolved configuration.
   *
   * @return the configuration
   */
  public Config config() {
    return config;
  }

  /**
   * The health checks.
   *
   * @return the registry, to which modules add checks at boot
   */
  public Health health() {
    return health;
  }

  /**
   * The order in which provided services finished building.
   *
   * @return service types, first built first
   */
  public List<Class<?>> buildOrder() {
    return buildOrder;
  }

  /**
   * The module ids in registration order.
   *
   * @return ids, starting with the built-in {@code core}
   */
  public List<String> moduleIds() {
    return modules.stream().map(Module::id).toList();
  }

  void markBooted(Module module) {
    booted.add(module);
  }

  void installShutdownHook() {
    Thread hook = new Thread(this::close, "app-shutdown-hook");
    shutdownHook = hook;
    Runtime.getRuntime().addShutdownHook(hook);
  }

  /**
   * Shuts down: module {@code shutdown} hooks in reverse module order, then every built service
   * that is {@link AutoCloseable}, in reverse build order. A failure is logged and does not stop
   * the rest; a step that takes longer than {@code app.shutdown.drain} is logged as an overrun
   * and interrupted. Safe to call more than once.
   */
  @Override
  public void close() {
    closeLock.lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
      Thread hook = shutdownHook;
      if (hook != null && hook != Thread.currentThread()) {
        try {
          Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
          LOG.debug("shutdown already in progress");
        }
      }
      List<Module> reversed = new ArrayList<>(booted);
      Collections.reverse(reversed);
      for (Module m : reversed) {
        Shutdown.runBounded("shutdown of module '" + m.id() + "'", drain, () -> m.shutdown(this));
      }
      Shutdown.closeInReverse(builtServicesInBuildOrder(), drain);
    } finally {
      closeLock.unlock();
    }
  }

  private List<Object> builtServicesInBuildOrder() {
    List<Object> out = new ArrayList<>();
    Map<Object, Boolean> seen = new IdentityHashMap<>();
    for (Class<?> type : buildOrder) {
      Object service = services.get(type);
      if (service != null && seen.put(service, Boolean.TRUE) == null) {
        out.add(service);
      }
    }
    return out;
  }
}
