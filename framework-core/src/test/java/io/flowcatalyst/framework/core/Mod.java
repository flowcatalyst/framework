package io.flowcatalyst.framework.core;

import org.junit.jupiter.api.function.ThrowingConsumer;

/** A module assembled from lambdas, for tests. */
final class Mod implements Module {

  private final String id;
  private ThrowingConsumer<AppBuilder> register = b -> {};
  private ThrowingConsumer<App> boot = a -> {};
  private ThrowingConsumer<App> started = a -> {};
  private ThrowingConsumer<App> shutdown = a -> {};

  private Mod(String id) {
    this.id = id;
  }

  static Mod of(String id) {
    return new Mod(id);
  }

  Mod register(ThrowingConsumer<AppBuilder> hook) {
    this.register = hook;
    return this;
  }

  Mod boot(ThrowingConsumer<App> hook) {
    this.boot = hook;
    return this;
  }

  Mod started(ThrowingConsumer<App> hook) {
    this.started = hook;
    return this;
  }

  Mod shutdown(ThrowingConsumer<App> hook) {
    this.shutdown = hook;
    return this;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public void register(AppBuilder app) throws Exception {
    try {
      register.accept(app);
    } catch (Exception | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }

  @Override
  public void boot(App app) throws Exception {
    try {
      boot.accept(app);
    } catch (Exception | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }

  @Override
  public void started(App app) throws Exception {
    try {
      started.accept(app);
    } catch (Exception | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }

  @Override
  public void shutdown(App app) throws Exception {
    try {
      shutdown.accept(app);
    } catch (Exception | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }
}
