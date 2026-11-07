package io.flowcatalyst.framework.http.idempotency.database;

import io.flowcatalyst.framework.http.idempotency.IdempotencyRecord;
import io.flowcatalyst.framework.http.idempotency.IdempotencyScope;
import io.flowcatalyst.framework.http.idempotency.IdempotencyStore;
import io.flowcatalyst.framework.http.idempotency.StoredAnswer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Idempotency keys in PostgreSQL ({@code fw_idempotency_keys}, the migration set of module {@code
 * http-idempotency}, the same table as the Go framework's), shared by every instance. Each statement
 * runs in autocommit; {@link #reserve} is one {@code INSERT ... ON CONFLICT DO UPDATE ... WHERE}, so
 * of two concurrent reservations (or takeovers) exactly one wins.
 */
public final class DatabaseIdempotencyStore implements IdempotencyStore {

  static final String RESERVE =
      """
      INSERT INTO fw_idempotency_keys (client_id, principal_id, idempotency_key, request_hash, token, created_at)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT (client_id, principal_id, idempotency_key) DO UPDATE
         SET request_hash = EXCLUDED.request_hash, token = EXCLUDED.token, status = NULL, content_type = NULL,
             response_body = NULL, created_at = EXCLUDED.created_at, completed_at = NULL
       WHERE fw_idempotency_keys.created_at < ?
          OR (fw_idempotency_keys.status IS NULL AND fw_idempotency_keys.created_at < ?)""";

  static final String FIND =
      """
      SELECT request_hash, status, content_type, response_body FROM fw_idempotency_keys
       WHERE client_id = ? AND principal_id = ? AND idempotency_key = ?""";

  static final String COMPLETE =
      """
      UPDATE fw_idempotency_keys
         SET status = ?, content_type = ?, response_body = ?, completed_at = ?
       WHERE client_id = ? AND principal_id = ? AND idempotency_key = ? AND token = ? AND status IS NULL""";

  static final String RELEASE =
      """
      DELETE FROM fw_idempotency_keys
       WHERE client_id = ? AND principal_id = ? AND idempotency_key = ? AND token = ? AND status IS NULL""";

  static final String PRUNE =
      """
      DELETE FROM fw_idempotency_keys
       WHERE ctid IN (SELECT ctid FROM fw_idempotency_keys WHERE created_at < ? LIMIT ?)""";

  private final DataSource dataSource;

  /**
   * Creates the store.
   *
   * @param dataSource a pooled data source on a database with the {@code fw_idempotency_keys} table
   */
  public DatabaseIdempotencyStore(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public boolean reserve(
      IdempotencyScope scope, String requestHash, String token, Instant now, Instant expiredBefore, Instant unfinishedBefore) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(RESERVE)) {
      bind(ps, 1, scope);
      ps.setString(4, requestHash);
      ps.setString(5, token);
      ps.setTimestamp(6, Timestamp.from(now));
      ps.setTimestamp(7, Timestamp.from(expiredBefore));
      ps.setTimestamp(8, Timestamp.from(unfinishedBefore));
      return ps.executeUpdate() == 1;
    } catch (SQLException e) {
      throw failed("reserve", e);
    }
  }

  @Override
  public Optional<IdempotencyRecord> find(IdempotencyScope scope) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(FIND)) {
      bind(ps, 1, scope);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        String hash = rs.getString(1);
        int status = rs.getInt(2);
        if (rs.wasNull()) {
          return Optional.of(new IdempotencyRecord(hash, null));
        }
        String contentType = rs.getString(3);
        byte[] body = rs.getBytes(4);
        return Optional.of(
            new IdempotencyRecord(
                hash,
                new StoredAnswer(status, contentType == null || contentType.isEmpty() ? null : contentType, body == null ? new byte[0] : body)));
      }
    } catch (SQLException e) {
      throw failed("read", e);
    }
  }

  @Override
  public void complete(IdempotencyScope scope, String token, StoredAnswer answer, Instant now) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(COMPLETE)) {
      ps.setInt(1, answer.status());
      ps.setString(2, answer.contentType());
      ps.setBytes(3, answer.body());
      ps.setTimestamp(4, Timestamp.from(now));
      bind(ps, 5, scope);
      ps.setString(8, token);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw failed("complete", e);
    }
  }

  @Override
  public void release(IdempotencyScope scope, String token) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(RELEASE)) {
      bind(ps, 1, scope);
      ps.setString(4, token);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw failed("release", e);
    }
  }

  /**
   * Forgets up to {@code limit} keys taken before a time.
   *
   * @param before the cut-off
   * @param limit the most rows to delete
   * @return how many were deleted
   */
  public int prune(Instant before, int limit) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(PRUNE)) {
      ps.setTimestamp(1, Timestamp.from(before));
      ps.setInt(2, limit);
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw failed("prune", e);
    }
  }

  private static void bind(PreparedStatement ps, int first, IdempotencyScope scope) throws SQLException {
    ps.setString(first, scope.clientId());
    ps.setString(first + 1, scope.principalId());
    ps.setString(first + 2, scope.key());
  }

  private static IllegalStateException failed(String what, SQLException e) {
    return new IllegalStateException("idempotency key " + what + " failed: " + e.getMessage(), e);
  }
}
