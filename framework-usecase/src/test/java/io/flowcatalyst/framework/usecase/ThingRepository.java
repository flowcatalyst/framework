package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.usecase.jdbc.Jdbc;
import java.sql.SQLException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Explicit SQL over {@code thing}, with hooks the tests use to inject failures and interleavings. */
final class ThingRepository implements Repository<ThingId, Thing> {

  static final String DDL =
      """
      CREATE TABLE thing (
        id text PRIMARY KEY,
        client_id text NOT NULL,
        version bigint NOT NULL,
        name text NOT NULL,
        code text UNIQUE
      )
      """;

  /** Runs inside {@code update} after the SQL ran, with the aggregate being written. */
  @FunctionalInterface
  interface UpdateHook {
    void after(Thing thing) throws SQLException;
  }

  /** What to answer instead of the real row count, when set. */
  @FunctionalInterface
  interface CountOverride {
    @Nullable Integer count(Thing thing);
  }

  volatile @Nullable UpdateHook afterUpdate;
  volatile @Nullable UpdateHook beforeUpdate;
  volatile @Nullable CountOverride updateCount;

  @Override
  public Optional<Thing> find(Database db, ClientId client, ThingId id) throws SQLException {
    return Jdbc.queryOne(
        db.dataSource(),
        "SELECT version, name, code FROM thing WHERE id = ? AND client_id = ?",
        row ->
            new Thing(
                id,
                client,
                row.longValue("version"),
                row.string("name"),
                row.optionalString("code").orElse(null)),
        id,
        client);
  }

  @Override
  public void insert(Tx tx, Thing thing) throws SQLException {
    Jdbc.update(
        tx.connection(),
        "INSERT INTO thing (id, client_id, version, name, code) VALUES (?, ?, 1, ?, ?)",
        thing.id(),
        thing.client(),
        thing.name(),
        thing.code());
  }

  @Override
  public int update(Tx tx, Thing thing) throws SQLException {
    UpdateHook before = beforeUpdate;
    if (before != null) {
      before.after(thing);
    }
    int rows =
        Jdbc.update(
            tx.connection(),
            "UPDATE thing SET version = ?, name = ?, code = ? "
                + "WHERE id = ? AND client_id = ? AND version = ?",
            thing.version() + 1,
            thing.name(),
            thing.code(),
            thing.id(),
            thing.client(),
            thing.version());
    UpdateHook after = afterUpdate;
    if (after != null) {
      after.after(thing);
    }
    CountOverride override = updateCount;
    Integer forced = override == null ? null : override.count(thing);
    return forced != null ? forced : rows;
  }

  @Override
  public int delete(Tx tx, Thing thing) throws SQLException {
    return Jdbc.update(
        tx.connection(),
        "DELETE FROM thing WHERE id = ? AND client_id = ? AND version = ?",
        thing.id(),
        thing.client(),
        thing.version());
  }
}
