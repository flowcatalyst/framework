package io.flowcatalyst.framework.auth.database;

import io.flowcatalyst.framework.auth.SessionStore;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Background;
import io.flowcatalyst.framework.core.LoopOptions;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.MigrationSet;
import java.time.Duration;
import java.time.InstantSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default session store: {@code fw_session} in the default database. List it with the database
 * module and {@code AuthModule}; {@code AuthModule} uses the {@link SessionStore} it provides when
 * its builder names no other store. A background loop, {@code auth-session-purge}, deletes expired
 * rows every 10 minutes, 1000 at a time.
 */
public final class AuthDatabaseModule implements Module {

  /** The module id; the migration history is keyed by it. */
  public static final String ID = "auth-database";

  static final int PURGE_BATCH = 1000;

  private static final Logger LOG = LoggerFactory.getLogger(AuthDatabaseModule.class);

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    MigrationSet.contribute(app, AuthDatabaseModule.class, "db/migration/auth");
    app.provide(
        SessionStore.class,
        lookup ->
            DatabaseSessionStore.create(
                lookup.get(Databases.class).defaultInstance().dataSource(), lookup.get(InstantSource.class)));
  }

  @Override
  public void boot(App app) {
    DatabaseSessionStore store = (DatabaseSessionStore) app.get(SessionStore.class);
    app.get(Background.class)
        .loop(
            "auth-session-purge",
            LoopOptions.every(Duration.ofMinutes(10)),
            tick -> {
              int total = 0;
              int deleted;
              do {
                deleted = store.purge(PURGE_BATCH);
                total += deleted;
              } while (deleted == PURGE_BATCH && !tick.stopping());
              if (total > 0) {
                LOG.debug("purged {} expired sessions and login records", total);
              }
            });
  }
}
