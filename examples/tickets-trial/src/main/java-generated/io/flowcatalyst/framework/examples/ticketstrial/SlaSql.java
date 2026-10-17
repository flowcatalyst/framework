// Generated from src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/SlaSql.sql. Do not edit.
package io.flowcatalyst.framework.examples.ticketstrial;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Typed queries of src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/SlaSql.sql. Generated; do not edit. */
public final class SlaSql {

  private SlaSql() {}

  /** One row of {@link #findByClient}. */
  public record FindByClientRow(
      io.flowcatalyst.framework.examples.ticketstrial.SlaPolicyId id,
      long version,
      int lowMinutes,
      int normalMinutes,
      int highMinutes,
      int urgentMinutes) {}

  /** The text of {@link #findByClient}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/SlaSql.sql:7
  public static final String FIND_BY_CLIENT_SQL =
      """
      SELECT id, version, low_minutes, normal_minutes, high_minutes, urgent_minutes
        FROM sla_policies
       WHERE client_id = ?""";

  /**
   * The client's policy, if it set one.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/SlaSql.sql:3.
   *
   * @param connection the connection, for example a transaction's
   * @param clientId :client_id
   * @return the row, or empty when there is none
   * @throws SQLException on a database error
   */
  public static Optional<FindByClientRow> findByClient(
      Connection connection,
      io.flowcatalyst.framework.context.ClientId clientId)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(FIND_BY_CLIENT_SQL)) {
      ps.setString(1, clientId.value());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        FindByClientRow row = readFindByClientRow(rs);
        if (rs.next()) {
          throw new IllegalStateException(
              "findByClient: expected at most one row, got more");
        }
        return Optional.of(row);
      }
    }
  }

  private static FindByClientRow readFindByClientRow(ResultSet rs) throws SQLException {
    String r0 = rs.getString(1);
    if (r0 == null) {
      throw nullRead("findByClient", "id");
    }
    io.flowcatalyst.framework.examples.ticketstrial.SlaPolicyId v0 = new io.flowcatalyst.framework.examples.ticketstrial.SlaPolicyId(r0);
    long v1 = rs.getLong(2);
    int v2 = rs.getInt(3);
    int v3 = rs.getInt(4);
    int v4 = rs.getInt(5);
    int v5 = rs.getInt(6);
    return new FindByClientRow(v0, v1, v2, v3, v4, v5);
  }

  /** One row of {@link #findPolicy}. */
  public record FindPolicyRow(
      long version,
      int lowMinutes,
      int normalMinutes,
      int highMinutes,
      int urgentMinutes) {}

  /** The text of {@link #findPolicy}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/SlaSql.sql:15
  public static final String FIND_POLICY_SQL =
      """
      SELECT version, low_minutes, normal_minutes, high_minutes, urgent_minutes
        FROM sla_policies
       WHERE id = ? AND client_id = ?""";

  /**
   * By id, for the client only.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/SlaSql.sql:11.
   *
   * @param connection the connection, for example a transaction's
   * @param id :id
   * @param clientId :client_id
   * @return the row, or empty when there is none
   * @throws SQLException on a database error
   */
  public static Optional<FindPolicyRow> findPolicy(
      Connection connection,
      io.flowcatalyst.framework.examples.ticketstrial.SlaPolicyId id,
      io.flowcatalyst.framework.context.ClientId clientId)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(FIND_POLICY_SQL)) {
      ps.setString(1, id.value());
      ps.setString(2, clientId.value());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        FindPolicyRow row = readFindPolicyRow(rs);
        if (rs.next()) {
          throw new IllegalStateException(
              "findPolicy: expected at most one row, got more");
        }
        return Optional.of(row);
      }
    }
  }

  private static FindPolicyRow readFindPolicyRow(ResultSet rs) throws SQLException {
    long v0 = rs.getLong(1);
    int v1 = rs.getInt(2);
    int v2 = rs.getInt(3);
    int v3 = rs.getInt(4);
    int v4 = rs.getInt(5);
    return new FindPolicyRow(v0, v1, v2, v3, v4);
  }

  /** The text of {@link #insertPolicy}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/SlaSql.sql:22
  public static final String INSERT_POLICY_SQL =
      """
      INSERT INTO sla_policies (id, client_id, version, low_minutes, normal_minutes, high_minutes, urgent_minutes)
      VALUES (?, ?, 1, ?, ?, ?, ?)""";

  /**
   * The {@code insertPolicy} query.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/SlaSql.sql:19.
   *
   * @param connection the connection, for example a transaction's
   * @param id :id
   * @param clientId :client_id
   * @param lowMinutes :low_minutes
   * @param normalMinutes :normal_minutes
   * @param highMinutes :high_minutes
   * @param urgentMinutes :urgent_minutes
   * @return the number of rows affected
   * @throws SQLException on a database error
   */
  public static int insertPolicy(
      Connection connection,
      io.flowcatalyst.framework.examples.ticketstrial.SlaPolicyId id,
      io.flowcatalyst.framework.context.ClientId clientId,
      int lowMinutes,
      int normalMinutes,
      int highMinutes,
      int urgentMinutes)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(INSERT_POLICY_SQL)) {
      ps.setString(1, id.value());
      ps.setString(2, clientId.value());
      ps.setInt(3, lowMinutes);
      ps.setInt(4, normalMinutes);
      ps.setInt(5, highMinutes);
      ps.setInt(6, urgentMinutes);
      return ps.executeUpdate();
    }
  }

  /** The text of {@link #updatePolicy}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/SlaSql.sql:29
  public static final String UPDATE_POLICY_SQL =
      """
      UPDATE sla_policies
         SET version = ?, low_minutes = ?, normal_minutes = ?,
             high_minutes = ?, urgent_minutes = ?
       WHERE id = ? AND client_id = ? AND version = ?""";

  /**
   * The optimistic-locking write.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/SlaSql.sql:25.
   *
   * @param connection the connection, for example a transaction's
   * @param newVersion :new_version
   * @param lowMinutes :low_minutes
   * @param normalMinutes :normal_minutes
   * @param highMinutes :high_minutes
   * @param urgentMinutes :urgent_minutes
   * @param id :id
   * @param clientId :client_id
   * @param version :version
   * @return the number of rows affected
   * @throws SQLException on a database error
   */
  public static int updatePolicy(
      Connection connection,
      long newVersion,
      int lowMinutes,
      int normalMinutes,
      int highMinutes,
      int urgentMinutes,
      io.flowcatalyst.framework.examples.ticketstrial.SlaPolicyId id,
      io.flowcatalyst.framework.context.ClientId clientId,
      long version)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(UPDATE_POLICY_SQL)) {
      ps.setLong(1, newVersion);
      ps.setInt(2, lowMinutes);
      ps.setInt(3, normalMinutes);
      ps.setInt(4, highMinutes);
      ps.setInt(5, urgentMinutes);
      ps.setString(6, id.value());
      ps.setString(7, clientId.value());
      ps.setLong(8, version);
      return ps.executeUpdate();
    }
  }

  /** The text of {@link #deletePolicy}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/SlaSql.sql:37
  public static final String DELETE_POLICY_SQL =
      """
      DELETE FROM sla_policies
       WHERE id = ? AND client_id = ? AND version = ?""";

  /**
   * The {@code deletePolicy} query.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/SlaSql.sql:34.
   *
   * @param connection the connection, for example a transaction's
   * @param id :id
   * @param clientId :client_id
   * @param version :version
   * @return the number of rows affected
   * @throws SQLException on a database error
   */
  public static int deletePolicy(
      Connection connection,
      io.flowcatalyst.framework.examples.ticketstrial.SlaPolicyId id,
      io.flowcatalyst.framework.context.ClientId clientId,
      long version)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(DELETE_POLICY_SQL)) {
      ps.setString(1, id.value());
      ps.setString(2, clientId.value());
      ps.setLong(3, version);
      return ps.executeUpdate();
    }
  }

  private static IllegalStateException nullRead(String query, String column) {
    return new IllegalStateException(
        "query " + query + ": column " + column
            + " was NULL but is declared non-null with '!'");
  }
}
