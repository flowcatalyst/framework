package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.database.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
    try (Connection c = db.dataSource().getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "SELECT version, name, code FROM thing WHERE id = ? AND client_id = ?")) {
      s.setString(1, id.value());
      s.setString(2, client.value());
      try (ResultSet rs = s.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new Thing(id, client, rs.getLong(1), rs.getString(2), rs.getString(3)));
      }
    }
  }

  @Override
  public void insert(Tx tx, Thing thing) throws SQLException {
    try (PreparedStatement s =
        tx.connection()
            .prepareStatement(
                "INSERT INTO thing (id, client_id, version, name, code) VALUES (?, ?, 1, ?, ?)")) {
      s.setString(1, thing.id().value());
      s.setString(2, thing.client().value());
      s.setString(3, thing.name());
      s.setString(4, thing.code());
      s.executeUpdate();
    }
  }

  @Override
  public int update(Tx tx, Thing thing) throws SQLException {
    UpdateHook before = beforeUpdate;
    if (before != null) {
      before.after(thing);
    }
    int rows;
    try (PreparedStatement s =
        tx.connection()
            .prepareStatement(
                "UPDATE thing SET version = ?, name = ?, code = ? "
                    + "WHERE id = ? AND client_id = ? AND version = ?")) {
      s.setLong(1, thing.version() + 1);
      s.setString(2, thing.name());
      s.setString(3, thing.code());
      s.setString(4, thing.id().value());
      s.setString(5, thing.client().value());
      s.setLong(6, thing.version());
      rows = s.executeUpdate();
    }
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
    try (PreparedStatement s =
        tx.connection()
            .prepareStatement("DELETE FROM thing WHERE id = ? AND client_id = ? AND version = ?")) {
      s.setString(1, thing.id().value());
      s.setString(2, thing.client().value());
      s.setLong(3, thing.version());
      return s.executeUpdate();
    }
  }
}
