package io.flowcatalyst.framework.partitions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The statements the module sends that change the catalog, built in one place so a test can pin each
 * one byte for byte against the Go implementation's (the DDL is part of the contract: names, bounds
 * and statements are the same in both frameworks), and small JDBC helpers.
 */
final class Sql {

  /** The {@code lock_timeout} of every DDL transaction: a pass fails and retries rather than queueing inserts. */
  static final String DDL_LOCK_TIMEOUT = "10s";

  private Sql() {}

  /** Quotes identifier parts as {@code "a"."b"}, doubling embedded quotes. */
  static String quote(String... parts) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        sb.append('.');
      }
      sb.append('"').append(parts[i].replace("\"", "\"\"")).append('"');
    }
    return sb.toString();
  }

  /** {@code SET LOCAL lock_timeout = '10s'}. */
  static String setLockTimeout() {
    return "SET LOCAL lock_timeout = '" + DDL_LOCK_TIMEOUT + "'";
  }

  /** The advisory lock that serialises the creation of one table's partitions. */
  static String advisoryLock() {
    return "SELECT pg_advisory_xact_lock(?)";
  }

  /** {@code SELECT to_regclass(?) IS NOT NULL}: whether a relation of that name exists, attached or not. */
  static String relationExists() {
    return "SELECT to_regclass(?) IS NOT NULL";
  }

  /** Creates one partition of a parent for the bounds of an interval. */
  static String createPartition(String child, String parent, String lower, String upper) {
    return "CREATE TABLE IF NOT EXISTS "
        + child
        + " PARTITION OF "
        + parent
        + " FOR VALUES FROM ('"
        + lower
        + "') TO ('"
        + upper
        + "')";
  }

  /** The plain (not CONCURRENTLY) detach. */
  static String detach(String parent, String child) {
    return "ALTER TABLE " + parent + " DETACH PARTITION " + child;
  }

  static String drop(String relation) {
    return "DROP TABLE " + relation;
  }

  /** The detach mark as a comment (COMMENT takes no parameters; the mark holds only validated names). */
  static String comment(String relation, String mark) {
    return "COMMENT ON TABLE " + relation + " IS '" + mark.replace("'", "''") + "'";
  }

  /** The archive's read: one JSON object per row. */
  static String archiveSelect(String relation) {
    return "SELECT row_to_json(t)::text FROM " + relation + " t";
  }

  /** The detach mark that recovery requires (contract section 5). */
  static String detachMark(String qualified) {
    return "fw-partitions: detached from " + qualified + " for archiving";
  }

  // ---- JDBC helpers --------------------------------------------------------------------------

  /** Maps the current row of a result set. */
  @FunctionalInterface
  interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
  }

  static void bind(PreparedStatement ps, @Nullable Object... params) throws SQLException {
    for (int i = 0; i < params.length; i++) {
      ps.setObject(i + 1, params[i]);
    }
  }

  /** Runs a query on a connection and maps every row. */
  static <T> List<T> query(Connection c, String sql, RowMapper<T> mapper, @Nullable Object... params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        List<T> out = new ArrayList<>();
        while (rs.next()) {
          out.add(mapper.map(rs));
        }
        return out;
      }
    }
  }

  /** Runs a query that returns exactly one row. */
  static <T> T queryOne(Connection c, String sql, RowMapper<T> mapper, @Nullable Object... params) throws SQLException {
    List<T> rows = query(c, sql, mapper, params);
    if (rows.size() != 1) {
      throw new SQLException("expected one row, got " + rows.size() + ": " + sql);
    }
    return rows.get(0);
  }

  static int update(Connection c, String sql, @Nullable Object... params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, params);
      return ps.executeUpdate();
    }
  }

  static void execute(Connection c, String sql) throws SQLException {
    try (var s = c.createStatement()) {
      s.execute(sql);
    }
  }
}
