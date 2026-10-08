package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.usecase.Repository;
import io.flowcatalyst.framework.usecase.Tx;
import io.flowcatalyst.framework.usecase.jdbc.Jdbc;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for the order and its lines (write-path contract, section 2). The root row carries
 * the version; the lines are rewritten with it, in the same transaction.
 */
public class OrderRepository implements Repository<OrderId, Order> {

  private record Header(long version, String customer, OrderStatus status) {}

  @Override
  public Optional<Order> find(Database db, ClientId client, OrderId id) throws SQLException {
    Optional<Header> header =
        Jdbc.queryOne(
            db.dataSource(),
            "SELECT version, customer, status FROM orders WHERE id = ? AND client_id = ?",
            row ->
                new Header(
                    row.longValue("version"),
                    row.string("customer"),
                    OrderStatus.valueOf(row.string("status"))),
            id,
            client);
    if (header.isEmpty()) {
      return Optional.empty();
    }
    List<OrderLine> lines =
        Jdbc.query(
            db.dataSource(),
            "SELECT line_id, sku, quantity, unit_price_cents FROM order_lines "
                + "WHERE order_id = ? ORDER BY position",
            row ->
                new OrderLine(
                    row.string("line_id"),
                    row.string("sku"),
                    row.integer("quantity"),
                    row.longValue("unit_price_cents")),
            id);
    Header h = header.get();
    return Optional.of(new Order(id, client, h.version(), h.customer(), h.status(), lines));
  }

  @Override
  public void insert(Tx tx, Order order) throws SQLException {
    Connection c = tx.connection();
    Jdbc.update(
        c,
        "INSERT INTO orders (id, client_id, version, customer, status) VALUES (?, ?, 1, ?, ?)",
        order.id(),
        order.client(),
        order.customer(),
        order.status());
    writeLines(c, order);
  }

  @Override
  public int update(Tx tx, Order order) throws SQLException {
    Connection c = tx.connection();
    int rows =
        Jdbc.update(
            c,
            "UPDATE orders SET version = ?, customer = ?, status = ? "
                + "WHERE id = ? AND client_id = ? AND version = ?",
            order.version() + 1,
            order.customer(),
            order.status(),
            order.id(),
            order.client(),
            order.version());
    if (rows == 1) {
      Jdbc.update(c, "DELETE FROM order_lines WHERE order_id = ?", order.id());
      writeLines(c, order);
    }
    return rows;
  }

  @Override
  public int delete(Tx tx, Order order) throws SQLException {
    // The lines go with the order: ON DELETE CASCADE.
    return Jdbc.update(
        tx.connection(),
        "DELETE FROM orders WHERE id = ? AND client_id = ? AND version = ?",
        order.id(),
        order.client(),
        order.version());
  }

  private static void writeLines(Connection c, Order order) throws SQLException {
    int position = 0;
    for (OrderLine line : order.lines()) {
      Jdbc.update(
          c,
          "INSERT INTO order_lines (order_id, position, line_id, sku, quantity, unit_price_cents) "
              + "VALUES (?, ?, ?, ?, ?, ?)",
          order.id(),
          position++,
          line.lineId(),
          line.sku(),
          line.quantity(),
          line.unitPriceCents());
    }
  }
}
