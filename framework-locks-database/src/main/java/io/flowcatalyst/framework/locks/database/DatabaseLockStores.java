package io.flowcatalyst.framework.locks.database;

import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.locks.LockStore;
import io.flowcatalyst.framework.locks.LockStoreFactory;

/**
 * The {@code database} lock driver (locks contract section 5), chosen in code (config-and-code
 * contract, section 3.4). List {@code DatabaseModule} and {@link LocksDatabaseModule} (which
 * migrates {@code fw_lock}) too.
 *
 * <pre>{@code
 * LocksModule.builder().store("main", DatabaseLockStores.database()).build()
 * }</pre>
 */
public final class DatabaseLockStores {

  private DatabaseLockStores() {}

  /**
   * Locks in the default database instance.
   *
   * @return the factory
   */
  public static LockStoreFactory database() {
    return (name, lookup) -> build(name, lookup.get(Databases.class).defaultInstance(), lookup.get(MonotonicClock.class));
  }

  /**
   * Locks in a named database instance. Migrations do not run there, so its {@code fw_lock} table
   * must exist already.
   *
   * @param instance a database instance under {@code database.instances}
   * @return the factory
   */
  public static LockStoreFactory database(String instance) {
    return (name, lookup) -> build(name, lookup.get(Databases.class).get(instance), lookup.get(MonotonicClock.class));
  }

  private static LockStore build(String name, Database database, MonotonicClock clock) {
    // Checked on every instance: on the default one the table is missing only when
    // LocksDatabaseModule, whose migration creates it, is not listed.
    database.requireTable(
        "fw_lock",
        "the database lock store '" + name + "' (list LocksDatabaseModule, which migrates it on the default instance)");
    return new LockStore(new DatabaseLockDriver(database.dataSource()), clock);
  }
}
