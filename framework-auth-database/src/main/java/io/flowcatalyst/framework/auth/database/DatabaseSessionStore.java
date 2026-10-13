package io.flowcatalyst.framework.auth.database;

import io.flowcatalyst.framework.auth.SessionHandles;
import io.flowcatalyst.framework.auth.SessionStore;
import io.flowcatalyst.framework.auth.SessionStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.InstantSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Sessions in PostgreSQL, table {@code fw_session} (contract section 6.3). Only the SHA-256 of a
 * handle is stored. Expiry is decided by the application's clock, passed to every read, so it
 * agrees with the session code's own checks.
 */
public final class DatabaseSessionStore implements SessionStore {

  static final String CREATE = "INSERT INTO fw_session (id_hash, kind, data, expires_at) VALUES (?, ?, ?::jsonb, ?)";
  static final String READ = "SELECT data::text FROM fw_session WHERE id_hash = ? AND kind = ? AND expires_at > ?";
  static final String TAKE = "DELETE FROM fw_session WHERE id_hash = ? AND kind = ? RETURNING data::text, expires_at";
  static final String UPDATE =
      "UPDATE fw_session SET data = ?::jsonb, expires_at = ? WHERE id_hash = ? AND kind = ? AND expires_at > ?";
  static final String DELETE = "DELETE FROM fw_session WHERE id_hash = ? AND kind = ?";
  static final String PURGE =
      "DELETE FROM fw_session WHERE id_hash IN (SELECT id_hash FROM fw_session WHERE expires_at <= ? LIMIT ?)";

  private final DataSource dataSource;
  private final InstantSource clock;

  private DatabaseSessionStore(DataSource dataSource, InstantSource clock) {
    this.dataSource = dataSource;
    this.clock = clock;
  }

  /**
   * Creates the store.
   *
   * @param dataSource a database with the {@code fw_session} table
   * @param clock the application's clock
   * @return the store
   */
  public static DatabaseSessionStore create(DataSource dataSource, InstantSource clock) {
    return new DatabaseSessionStore(dataSource, clock);
  }

  @Override
  public String create(String kind, String json, Instant expiresAt) {
    String handle = SessionHandles.newHandle();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(CREATE)) {
      ps.setString(1, SessionHandles.hash(handle));
      ps.setString(2, kind);
      ps.setString(3, json);
      ps.setObject(4, at(expiresAt));
      ps.executeUpdate();
      return handle;
    } catch (SQLException e) {
      throw failed("create", e);
    }
  }

  @Override
  public Optional<String> read(String kind, String handle) {
    if (!SessionHandles.isWellFormed(handle)) {
      return Optional.empty();
    }
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(READ)) {
      ps.setString(1, SessionHandles.hash(handle));
      ps.setString(2, kind);
      ps.setObject(3, at(clock.instant()));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw failed("read", e);
    }
  }

  @Override
  public Optional<String> take(String kind, String handle) {
    if (!SessionHandles.isWellFormed(handle)) {
      return Optional.empty();
    }
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(TAKE)) {
      ps.setString(1, SessionHandles.hash(handle));
      ps.setString(2, kind);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        String json = rs.getString(1);
        Instant expiresAt = rs.getObject(2, OffsetDateTime.class).toInstant();
        return clock.instant().isBefore(expiresAt) ? Optional.of(json) : Optional.empty();
      }
    } catch (SQLException e) {
      throw failed("take", e);
    }
  }

  @Override
  public String update(String kind, String handle, String json, Instant expiresAt) {
    if (!SessionHandles.isWellFormed(handle)) {
      return handle;
    }
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(UPDATE)) {
      ps.setString(1, json);
      ps.setObject(2, at(expiresAt));
      ps.setString(3, SessionHandles.hash(handle));
      ps.setString(4, kind);
      ps.setObject(5, at(clock.instant()));
      ps.executeUpdate();
      return handle;
    } catch (SQLException e) {
      throw failed("update", e);
    }
  }

  @Override
  public void delete(String kind, String handle) {
    if (!SessionHandles.isWellFormed(handle)) {
      return;
    }
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(DELETE)) {
      ps.setString(1, SessionHandles.hash(handle));
      ps.setString(2, kind);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw failed("delete", e);
    }
  }

  /**
   * Deletes expired records, at most {@code limit} of them.
   *
   * @param limit the batch size
   * @return how many were deleted
   */
  public int purge(int limit) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(PURGE)) {
      ps.setObject(1, at(clock.instant()));
      ps.setInt(2, limit);
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw failed("purge", e);
    }
  }

  private static OffsetDateTime at(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static SessionStoreException failed(String what, SQLException e) {
    return new SessionStoreException("the session store could not " + what + " (" + e.getSQLState() + ")", e);
  }
}
