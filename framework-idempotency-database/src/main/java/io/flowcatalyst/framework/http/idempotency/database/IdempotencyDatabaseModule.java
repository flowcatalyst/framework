package io.flowcatalyst.framework.http.idempotency.database;

import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Background;
import io.flowcatalyst.framework.core.LoopOptions;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.MigrationSet;
import io.flowcatalyst.framework.http.idempotency.IdempotencyStore;
import java.time.Duration;
import java.time.InstantSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The database store of inbound {@code Idempotency-Key} requests: {@code fw_idempotency_key} in the
 * default database (this module's migration), provided as the {@link IdempotencyStore} that {@code
 * Idempotency.middleware} takes. A background loop, {@code idempotency-key-prune}, deletes keys older
 * than {@code idempotency.retention} every 10 minutes, 1000 at a time.
 *
 * <pre>{@code
 * .module(new DatabaseModule())
 * .module(new IdempotencyDatabaseModule())
 * // in a module's boot:
 * Middleware<Request, Response> once =
 *     Idempotency.middleware(app.get(IdempotencyStore.class), app.get(InstantSource.class));
 * }</pre>
 */
public final class IdempotencyDatabaseModule implements Module {

  /** The module id; the migration history is keyed by it. */
  public static final String ID = "idempotency-database";

  /** {@code idempotency.retention}: how long a key and its answer are kept. */
  public static final Setting<Duration> RETENTION =
      Setting.duration("idempotency.retention")
          .withDefault(Duration.ofHours(24))
          .describedAs("How long an Idempotency-Key and its answer are kept; a repeat after that runs again.");

  static final int PRUNE_BATCH = 1000;

  private static final Logger LOG = LoggerFactory.getLogger(IdempotencyDatabaseModule.class);

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.claimComponent("idempotency");
    app.declare(RETENTION);
    MigrationSet.contribute(app, IdempotencyDatabaseModule.class, "db/migration/idempotency");
    app.provide(
        IdempotencyStore.class,
        lookup -> new DatabaseIdempotencyStore(lookup.get(Databases.class).defaultInstance().dataSource()));
  }

  @Override
  public void boot(App app) {
    Duration retention = app.config().get(RETENTION);
    if (retention.isNegative() || retention.isZero()) {
      throw new IllegalArgumentException("idempotency.retention must be positive");
    }
    DatabaseIdempotencyStore store = (DatabaseIdempotencyStore) app.get(IdempotencyStore.class);
    InstantSource clock = app.get(InstantSource.class);
    app.get(Background.class)
        .loop(
            "idempotency-key-prune",
            LoopOptions.every(Duration.ofMinutes(10)),
            tick -> {
              int total = 0;
              int deleted;
              do {
                deleted = store.prune(clock.instant().minus(retention), PRUNE_BATCH);
                total += deleted;
              } while (deleted == PRUNE_BATCH && !tick.stopping());
              if (total > 0) {
                LOG.debug("forgot {} idempotency keys older than {}", total, retention);
              }
            });
  }
}
