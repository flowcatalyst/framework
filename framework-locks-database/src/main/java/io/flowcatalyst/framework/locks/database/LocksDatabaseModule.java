package io.flowcatalyst.framework.locks.database;

import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.database.MigrationSet;

/**
 * Contributes the {@code fw_lock} migration, applied to the default database instance. List it
 * with {@code DatabaseModule} when a lock store uses {@link DatabaseLockStores}.
 *
 * <pre>{@code
 * .module(new DatabaseModule())
 * .module(new LocksDatabaseModule())
 * .module(LocksModule.builder().store("main", DatabaseLockStores.database()).build())
 * }</pre>
 *
 * <p>On another instance migrations do not run: {@link DatabaseLockStores#database(String)}
 * checks that {@code fw_lock} exists there and fails start-up if it does not.
 */
public final class LocksDatabaseModule implements Module {

  /** The module id; the migration history is keyed by it. */
  public static final String ID = "locks-database";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    MigrationSet.contribute(app, LocksDatabaseModule.class, "db/migration/locks");
  }
}
