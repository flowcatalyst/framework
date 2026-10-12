package io.flowcatalyst.framework.locks.database;

import io.flowcatalyst.framework.locks.LockDriver;
import io.flowcatalyst.framework.locks.LockStoreUnavailableException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.OptionalLong;
import javax.sql.DataSource;

/**
 * Locks in PostgreSQL, with expiry on the database's clock (locks contract, section 5).
 *
 * <p>Each statement runs in autocommit and uses {@code clock_timestamp()}, which is the time of
 * the statement rather than of the transaction. Fencing tokens come from one sequence for all
 * keys.
 */
public final class DatabaseLockDriver implements LockDriver {

  // The three statements are shared with the Rust framework byte for byte: conformance/sql/locks/
  // holds them, and LockConformanceTest checks these constants against those files.

  static final String ACQUIRE =
      """
      INSERT INTO fw_lock (lock_key, owner_token, fencing_token, expires_at)
      VALUES (?, ?, nextval('fw_lock_fence'), clock_timestamp() + ? * interval '1 millisecond')
      ON CONFLICT (lock_key) DO UPDATE
         SET owner_token = EXCLUDED.owner_token,
             fencing_token = EXCLUDED.fencing_token,
             expires_at = EXCLUDED.expires_at
       WHERE fw_lock.expires_at <= clock_timestamp()
      RETURNING fencing_token""";

  static final String REFRESH =
      """
      UPDATE fw_lock SET expires_at = clock_timestamp() + ? * interval '1 millisecond'
       WHERE lock_key = ? AND owner_token = ? AND fencing_token = ? AND expires_at > clock_timestamp()""";

  static final String RELEASE =
      """
      UPDATE fw_lock SET owner_token = NULL, expires_at = clock_timestamp()
       WHERE lock_key = ? AND owner_token = ? AND fencing_token = ? AND expires_at > clock_timestamp()""";

  private final DataSource dataSource;

  /**
   * Creates a driver.
   *
   * @param dataSource a pooled data source on a database with the {@code fw_lock} table
   */
  public DatabaseLockDriver(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public OptionalLong tryAcquire(String key, String ownerToken, long leaseMillis) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(ACQUIRE)) {
      ps.setString(1, key);
      ps.setString(2, ownerToken);
      ps.setLong(3, leaseMillis);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
      }
    } catch (SQLException e) {
      throw unavailable("acquire", e);
    }
  }

  @Override
  public boolean refresh(String key, String ownerToken, long fencingToken, long leaseMillis) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(REFRESH)) {
      ps.setLong(1, leaseMillis);
      ps.setString(2, key);
      ps.setString(3, ownerToken);
      ps.setLong(4, fencingToken);
      return ps.executeUpdate() == 1;
    } catch (SQLException e) {
      throw unavailable("refresh", e);
    }
  }

  @Override
  public boolean release(String key, String ownerToken, long fencingToken) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(RELEASE)) {
      ps.setString(1, key);
      ps.setString(2, ownerToken);
      ps.setLong(3, fencingToken);
      return ps.executeUpdate() == 1;
    } catch (SQLException e) {
      throw unavailable("release", e);
    }
  }

  private static LockStoreUnavailableException unavailable(String what, SQLException e) {
    return new LockStoreUnavailableException("database lock " + what + " failed", e);
  }
}
