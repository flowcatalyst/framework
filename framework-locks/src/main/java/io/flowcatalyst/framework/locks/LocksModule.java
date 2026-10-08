package io.flowcatalyst.framework.locks;

import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Instances;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.MonotonicClock;

/**
 * Provides {@link Locks}: the instances configured under {@code locks}, and the {@code memory}
 * driver. The {@code database} and {@code redis} drivers come from their own modules, which may
 * be listed before or after this one.
 *
 * <pre>{@code
 * locks:
 *   default: main
 *   instances:
 *     main:   { driver: database }
 *     shared: { driver: redis, connection: default, prefix: "fw:lock:" }
 *     local:  { driver: memory }
 * }</pre>
 */
public final class LocksModule implements Module {

  /** The module id. */
  public static final String ID = "locks";

  /** The driver name of {@link MemoryLockDriver}. */
  public static final String MEMORY = "memory";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.drivers()
        .register(
            LockStore.class,
            MEMORY,
            (name, config, lookup, built) -> {
              MonotonicClock clock = lookup.get(MonotonicClock.class);
              return new LockStore(new MemoryLockDriver(clock::nanos), clock);
            });
    app.provide(
        Locks.class, lookup -> new Locks(Instances.build(LockStore.class, "locks", lookup)));
  }
}
