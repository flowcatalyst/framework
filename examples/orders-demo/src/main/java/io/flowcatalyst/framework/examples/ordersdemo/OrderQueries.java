package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.usecase.jdbc.Jdbc;
import java.sql.SQLException;
import java.util.List;

/**
 * Reads for screens and lists: plain typed queries, not aggregates (write-path contract,
 * section 9).
 */
public final class OrderQueries {

  /**
   * One row of an order list.
   *
   * @param id the order id
   * @param customer who ordered
   * @param status where it is in its life
   * @param version its version
   * @param lineCount how many lines
   * @param totalCents the sum of the lines, in cents
   */
  public record Summary(
      String id, String customer, OrderStatus status, long version, int lineCount, long totalCents) {}

  private OrderQueries() {}

  /**
   * A client's orders, by id.
   *
   * @param db the database
   * @param client the client; only its orders are listed
   * @return the summaries
   * @throws SQLException on a database error
   */
  public static List<Summary> summaries(Database db, ClientId client) throws SQLException {
    return Jdbc.query(
        db.dataSource(),
        """
        SELECT o.id, o.customer, o.status, o.version,
               count(l.position) AS line_count,
               coalesce(sum(l.quantity * l.unit_price_cents), 0) AS total_cents
          FROM orders o LEFT JOIN order_lines l ON l.order_id = o.id
         WHERE o.client_id = ?
         GROUP BY o.id
         ORDER BY o.id
        """,
        row ->
            new Summary(
                row.string("id"),
                row.string("customer"),
                OrderStatus.valueOf(row.string("status")),
                row.longValue("version"),
                row.integer("line_count"),
                row.longValue("total_cents")),
        client);
  }
}
