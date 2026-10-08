package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.database.Database;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
   * @param firstSku the alphabetically first sku among the lines, or null when there are none
   */
  public record Summary(
      String id,
      String customer,
      OrderStatus status,
      long version,
      int lineCount,
      long totalCents,
      @Nullable String firstSku) {}

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
    try (Connection c = db.dataSource().getConnection()) {
      return OrderSql.summaries(c, client).stream()
          .map(
              r ->
                  new Summary(
                      r.id().value(),
                      r.customer(),
                      r.status(),
                      r.version(),
                      (int) r.lineCount(),
                      r.totalCents(),
                      r.firstSku()))
          .toList();
    }
  }
}
