package io.flowcatalyst.framework.locks.database;

import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.MigrationSet;
import io.flowcatalyst.framework.locks.LockStore;

/**
 * Registers the {@code database} lock driver and contributes the {@code fw_lock} migration.
 * List it together with the database module and the locks module.
 *
 * <pre>{@code
 * locks:
 *   instances:
 *     main: { driver: database, database: main }   # database: optional, default instance if omitted
 * }</pre>
 *
 * <p>If the chosen database instance is not the default one, migrations do not run there, so
 * start-up checks that {@code fw_lock} exists and fails if it does not.
 */
public final class LocksDatabaseModule implements Module {

  /** The module id; the migration history is keyed by it. */
  public static final String ID = "locks-database";

  /** The driver name used in configuration. */
  public static final String DRIVER = "database";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    MigrationSet.contribute(app, LocksDatabaseModule.class, "db/migration/locks");
    app.drivers()
        .register(
            LockStore.class,
            DRIVER,
            (name, config, lookup, built) -> {
              Databases databases = lookup.get(Databases.class);
              Database database =
                  config
                      .findString("database")
                      .map(databases::get)
                      .orElseGet(databases::defaultInstance);
              if (database != databases.defaultInstance()) {
                database.requireTable("fw_lock", "the database lock driver (instance '" + name + "')");
              }
              return new LockStore(
                  new DatabaseLockDriver(database.dataSource()),
                  lookup.get(MonotonicClock.class));
            });
  }
}
