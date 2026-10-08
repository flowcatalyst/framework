// Generated from src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql. Do not edit.
package io.flowcatalyst.framework.examples.ordersdemo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Typed queries of src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql. Generated; do not edit. */
public final class OrderSql {

  private OrderSql() {}

  /** One row of {@link #findHeader}. */
  public record FindHeaderRow(
      long version,
      String customer,
      io.flowcatalyst.framework.examples.ordersdemo.OrderStatus status) {}

  /** The text of {@link #findHeader}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql:9
  public static final String FIND_HEADER_SQL =
      """
      SELECT version, customer, status
        FROM orders
       WHERE id = ? AND client_id = ?""";

  /**
   * The order's root row, for its client only.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql:4.
   *
   * @param connection the connection, for example a transaction's
   * @param id :id
   * @param clientId :client_id
   * @return the row, or empty when there is none
   * @throws SQLException on a database error
   */
  public static Optional<FindHeaderRow> findHeader(
      Connection connection,
      io.flowcatalyst.framework.examples.ordersdemo.OrderId id,
      io.flowcatalyst.framework.context.ClientId clientId)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(FIND_HEADER_SQL)) {
      ps.setString(1, id.value());
      ps.setString(2, clientId.value());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        FindHeaderRow row = readFindHeaderRow(rs);
        if (rs.next()) {
          throw new IllegalStateException(
              "findHeader: expected at most one row, got more");
        }
        return Optional.of(row);
      }
    }
  }

  private static FindHeaderRow readFindHeaderRow(ResultSet rs) throws SQLException {
    long v0 = rs.getLong(1);
    String v1 = rs.getString(2);
    io.flowcatalyst.framework.examples.ordersdemo.OrderStatus v2 = io.flowcatalyst.framework.examples.ordersdemo.OrderStatus.valueOf(rs.getString(3));
    return new FindHeaderRow(v0, v1, v2);
  }

  /** One row of {@link #linesOf}. */
  public record LinesOfRow(
      String lineId,
      String sku,
      int quantity,
      long unitPriceCents) {}

  /** The text of {@link #linesOf}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql:16
  public static final String LINES_OF_SQL =
      """
      SELECT line_id, sku, quantity, unit_price_cents
        FROM order_lines
       WHERE order_id = ?
       ORDER BY position""";

  /**
   * The order's lines in the order they were added.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql:13.
   *
   * @param connection the connection, for example a transaction's
   * @param orderId :order_id
   * @return the rows, in the order the database returned them
   * @throws SQLException on a database error
   */
  public static List<LinesOfRow> linesOf(
      Connection connection,
      io.flowcatalyst.framework.examples.ordersdemo.OrderId orderId)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(LINES_OF_SQL)) {
      ps.setString(1, orderId.value());
      try (ResultSet rs = ps.executeQuery()) {
        List<LinesOfRow> list = new ArrayList<>();
        while (rs.next()) {
          list.add(readLinesOfRow(rs));
        }
        return list;
      }
    }
  }

  private static LinesOfRow readLinesOfRow(ResultSet rs) throws SQLException {
    String v0 = rs.getString(1);
    String v1 = rs.getString(2);
    int v2 = rs.getInt(3);
    long v3 = rs.getLong(4);
    return new LinesOfRow(v0, v1, v2, v3);
  }

  /** The text of {@link #insertOrder}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql:25
  public static final String INSERT_ORDER_SQL =
      """
      INSERT INTO orders (id, client_id, version, customer, status)
      VALUES (?, ?, 1, ?, ?)""";

  /**
   * The {@code insertOrder} query.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql:21.
   *
   * @param connection the connection, for example a transaction's
   * @param id :id
   * @param clientId :client_id
   * @param customer :customer
   * @param status :status
   * @return the number of rows affected
   * @throws SQLException on a database error
   */
  public static int insertOrder(
      Connection connection,
      io.flowcatalyst.framework.examples.ordersdemo.OrderId id,
      io.flowcatalyst.framework.context.ClientId clientId,
      String customer,
      io.flowcatalyst.framework.examples.ordersdemo.OrderStatus status)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(INSERT_ORDER_SQL)) {
      ps.setString(1, id.value());
      ps.setString(2, clientId.value());
      ps.setString(3, customer);
      ps.setString(4, status.name());
      return ps.executeUpdate();
    }
  }

  /** The text of {@link #updateOrder}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql:33
  public static final String UPDATE_ORDER_SQL =
      """
      UPDATE orders
         SET version = ?, customer = ?, status = ?
       WHERE id = ? AND client_id = ? AND version = ?""";

  /**
   * The optimistic-locking write: the row count says whether the version still matched.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql:28.
   *
   * @param connection the connection, for example a transaction's
   * @param newVersion :new_version
   * @param customer :customer
   * @param status :status
   * @param id :id
   * @param clientId :client_id
   * @param version :version
   * @return the number of rows affected
   * @throws SQLException on a database error
   */
  public static int updateOrder(
      Connection connection,
      long newVersion,
      String customer,
      io.flowcatalyst.framework.examples.ordersdemo.OrderStatus status,
      io.flowcatalyst.framework.examples.ordersdemo.OrderId id,
      io.flowcatalyst.framework.context.ClientId clientId,
      long version)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(UPDATE_ORDER_SQL)) {
      ps.setLong(1, newVersion);
      ps.setString(2, customer);
      ps.setString(3, status.name());
      ps.setString(4, id.value());
      ps.setString(5, clientId.value());
      ps.setLong(6, version);
      return ps.executeUpdate();
    }
  }

  /** The text of {@link #deleteOrder}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql:40
  public static final String DELETE_ORDER_SQL =
      """
      DELETE FROM orders
       WHERE id = ? AND client_id = ? AND version = ?""";

  /**
   * The {@code deleteOrder} query.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql:37.
   *
   * @param connection the connection, for example a transaction's
   * @param id :id
   * @param clientId :client_id
   * @param version :version
   * @return the number of rows affected
   * @throws SQLException on a database error
   */
  public static int deleteOrder(
      Connection connection,
      io.flowcatalyst.framework.examples.ordersdemo.OrderId id,
      io.flowcatalyst.framework.context.ClientId clientId,
      long version)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(DELETE_ORDER_SQL)) {
      ps.setString(1, id.value());
      ps.setString(2, clientId.value());
      ps.setLong(3, version);
      return ps.executeUpdate();
    }
  }

  /** The text of {@link #deleteLines}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql:45
  public static final String DELETE_LINES_SQL =
      """
      DELETE FROM order_lines WHERE order_id = ?""";

  /**
   * The {@code deleteLines} query.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql:43.
   *
   * @param connection the connection, for example a transaction's
   * @param orderId :order_id
   * @return the number of rows affected
   * @throws SQLException on a database error
   */
  public static int deleteLines(
      Connection connection,
      io.flowcatalyst.framework.examples.ordersdemo.OrderId orderId)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(DELETE_LINES_SQL)) {
      ps.setString(1, orderId.value());
      return ps.executeUpdate();
    }
  }

  /** The text of {@link #insertLine}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql:49
  public static final String INSERT_LINE_SQL =
      """
      INSERT INTO order_lines (order_id, position, line_id, sku, quantity, unit_price_cents)
      VALUES (?, ?, ?, ?, ?, ?)""";

  /**
   * The {@code insertLine} query.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql:47.
   *
   * @param connection the connection, for example a transaction's
   * @param orderId :order_id
   * @param position :position
   * @param lineId :line_id
   * @param sku :sku
   * @param quantity :quantity
   * @param unitPriceCents :unit_price_cents
   * @return the number of rows affected
   * @throws SQLException on a database error
   */
  public static int insertLine(
      Connection connection,
      io.flowcatalyst.framework.examples.ordersdemo.OrderId orderId,
      int position,
      String lineId,
      String sku,
      int quantity,
      long unitPriceCents)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(INSERT_LINE_SQL)) {
      ps.setString(1, orderId.value());
      ps.setInt(2, position);
      ps.setString(3, lineId);
      ps.setString(4, sku);
      ps.setInt(5, quantity);
      ps.setLong(6, unitPriceCents);
      return ps.executeUpdate();
    }
  }

  /** One row of {@link #summaries}. */
  public record SummariesRow(
      io.flowcatalyst.framework.examples.ordersdemo.OrderId id,
      String customer,
      io.flowcatalyst.framework.examples.ordersdemo.OrderStatus status,
      long version,
      long lineCount,
      long totalCents,
      @Nullable String firstSku) {}

  /** The text of {@link #summaries}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql:62
  public static final String SUMMARIES_SQL =
      """
      SELECT o.id, o.customer, o.status, o.version,
             count(l.position) AS "line_count!",
             coalesce(sum(l.quantity * l.unit_price_cents), 0)::bigint AS "total_cents!",
             min(l.sku) AS first_sku
        FROM orders o LEFT JOIN order_lines l ON l.order_id = o.id
       WHERE o.client_id = ?
       GROUP BY o.id
       ORDER BY o.id""";

  /**
   * One row per order of a client, with its line count and total. The LEFT JOIN makes every
   * column nullable to the generator, so the ones that cannot be say so with !; first_sku is null for an order
   * with no lines.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ordersdemo/OrderSql.sql:52.
   *
   * @param connection the connection, for example a transaction's
   * @param clientId :client_id
   * @return the rows, in the order the database returned them
   * @throws SQLException on a database error
   */
  public static List<SummariesRow> summaries(
      Connection connection,
      io.flowcatalyst.framework.context.ClientId clientId)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(SUMMARIES_SQL)) {
      ps.setString(1, clientId.value());
      try (ResultSet rs = ps.executeQuery()) {
        List<SummariesRow> list = new ArrayList<>();
        while (rs.next()) {
          list.add(readSummariesRow(rs));
        }
        return list;
      }
    }
  }

  private static SummariesRow readSummariesRow(ResultSet rs) throws SQLException {
    String r0 = rs.getString(1);
    if (r0 == null) {
      throw nullRead("summaries", "id");
    }
    io.flowcatalyst.framework.examples.ordersdemo.OrderId v0 = new io.flowcatalyst.framework.examples.ordersdemo.OrderId(r0);
    String r1 = rs.getString(2);
    if (r1 == null) {
      throw nullRead("summaries", "customer");
    }
    String v1 = r1;
    String r2 = rs.getString(3);
    if (r2 == null) {
      throw nullRead("summaries", "status");
    }
    io.flowcatalyst.framework.examples.ordersdemo.OrderStatus v2 = io.flowcatalyst.framework.examples.ordersdemo.OrderStatus.valueOf(r2);
    long r3 = rs.getLong(4);
    if (rs.wasNull()) {
      throw nullRead("summaries", "version");
    }
    long v3 = r3;
    long r4 = rs.getLong(5);
    if (rs.wasNull()) {
      throw nullRead("summaries", "line_count");
    }
    long v4 = r4;
    long r5 = rs.getLong(6);
    if (rs.wasNull()) {
      throw nullRead("summaries", "total_cents");
    }
    long v5 = r5;
    String r6 = rs.getString(7);
    @Nullable String v6 = r6 == null ? null : r6;
    return new SummariesRow(v0, v1, v2, v3, v4, v5, v6);
  }

  private static IllegalStateException nullRead(String query, String column) {
    return new IllegalStateException(
        "query " + query + ": column " + column
            + " was NULL but is declared non-null with '!'");
  }
}
