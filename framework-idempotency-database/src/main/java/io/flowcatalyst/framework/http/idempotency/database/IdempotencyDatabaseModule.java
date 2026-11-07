package io.flowcatalyst.framework.http.idempotency.database;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Background;
import io.flowcatalyst.framework.core.LoopOptions;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.MigrationSet;
import io.flowcatalyst.framework.http.idempotency.Idempotency;
import io.flowcatalyst.framework.http.idempotency.IdempotencyStore;
import java.time.Duration;
import java.time.InstantSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The database store of inbound {@code Idempotency-Key} requests: {@code fw_idempotency_keys} in the
 * default database (this module's migration set, module {@code http-idempotency}, the same as the Go
 * framework's), provided as the {@link IdempotencyStore} that {@code Idempotency.middleware} takes,
 * with the middleware's {@link Idempotency.Options} built from this module's settings. A background
 * loop, {@code http-idempotency-prune}, deletes the keys older than {@code idempotency.retention}
 * every hour, 1000 at a time.
 *
 * <pre>{@code
 * .module(new DatabaseModule())
 * .module(new IdempotencyDatabaseModule())
 * // in a module's boot:
 * Middleware<Request, Response> once =
 *     Idempotency.middleware(
 *         app.get(IdempotencyStore.class), app.get(InstantSource.class), app.get(Idempotency.Options.class));
 * }</pre>
 */
public final class IdempotencyDatabaseModule implements Module {

  /** The module id; the migration history is keyed by it (the Go framework's is the same). */
  public static final String ID = "http-idempotency";

  /** {@code idempotency.retention}: how long a key and its answer are kept. */
  public static final Setting<Duration> RETENTION =
      Setting.duration("idempotency.retention")
          .withDefault(Idempotency.DEFAULT_RETENTION)
          .describedAs("How long an Idempotency-Key and its answer are kept; a repeat after that runs again.");

  /** {@code idempotency.wait}: how long a repeat waits for the first request with its key. */
  public static final Setting<Duration> WAIT =
      Setting.duration("idempotency.wait")
          .withDefault(Idempotency.DEFAULT_WAIT)
          .describedAs(
              "How long a repeat that arrives while the first request with its Idempotency-Key runs waits for"
                  + " its answer; then 409 IDEMPOTENCY_IN_PROGRESS.");

  /** {@code idempotency.in-flight-timeout}: how long an unfinished reservation holds its key. */
  public static final Setting<Duration> IN_FLIGHT_TIMEOUT =
      Setting.duration("idempotency.in-flight-timeout")
          .withDefault(Idempotency.DEFAULT_IN_FLIGHT_TIMEOUT)
          .describedAs(
              "How long a request that took an Idempotency-Key and has not finished holds it (its process may"
                  + " have stopped); after it another request takes the key over.");

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
    app.declare(WAIT);
    app.declare(IN_FLIGHT_TIMEOUT);
    MigrationSet.contribute(app, IdempotencyDatabaseModule.class, "db/migration/http-idempotency");
    app.provide(
        IdempotencyStore.class,
        lookup -> new DatabaseIdempotencyStore(lookup.get(Databases.class).defaultInstance().dataSource()));
    app.provide(
        Idempotency.Options.class,
        lookup -> {
          Config config = lookup.get(Config.class);
          return new Idempotency.Options(config.get(RETENTION), config.get(WAIT), config.get(IN_FLIGHT_TIMEOUT));
        });
  }

  @Override
  public void boot(App app) {
    Idempotency.Options options = app.get(Idempotency.Options.class); // checks the settings
    Duration retention = options.retention();
    DatabaseIdempotencyStore store = (DatabaseIdempotencyStore) app.get(IdempotencyStore.class);
    InstantSource clock = app.get(InstantSource.class);
    app.get(Background.class)
        .loop(
            "http-idempotency-prune",
            LoopOptions.every(Duration.ofHours(1)),
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
