package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.examples.ordersdemo.OrderSql.FindHeaderRow;
import io.flowcatalyst.framework.usecase.Repository;
import io.flowcatalyst.framework.usecase.Tx;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * The SQL of the order and its lines lives in {@code OrderSql.sql}, checked against the schema
 * when the project builds (checked-SQL contract); this class calls the generated {@link OrderSql}.
 * The root row carries the version; the lines are rewritten with it, in the same transaction.
 */
public class OrderRepository implements Repository<OrderId, Order> {

  @Override
  public Optional<Order> find(Database db, ClientId client, OrderId id) throws SQLException {
    try (Connection c = db.dataSource().getConnection()) {
      Optional<FindHeaderRow> header = OrderSql.findHeader(c, id, client);
      if (header.isEmpty()) {
        return Optional.empty();
      }
      List<OrderLine> lines =
          OrderSql.linesOf(c, id).stream()
              .map(r -> new OrderLine(r.lineId(), r.sku(), r.quantity(), r.unitPriceCents()))
              .toList();
      FindHeaderRow h = header.get();
      return Optional.of(new Order(id, client, h.version(), h.customer(), h.status(), lines));
    }
  }

  @Override
  public void insert(Tx tx, Order order) throws SQLException {
    Connection c = tx.connection();
    OrderSql.insertOrder(c, order.id(), order.client(), order.customer(), order.status());
    writeLines(c, order);
  }

  @Override
  public int update(Tx tx, Order order) throws SQLException {
    Connection c = tx.connection();
    int rows =
        OrderSql.updateOrder(
            c,
            order.version() + 1,
            order.customer(),
            order.status(),
            order.id(),
            order.client(),
            order.version());
    if (rows == 1) {
      OrderSql.deleteLines(c, order.id());
      writeLines(c, order);
    }
    return rows;
  }

  @Override
  public int delete(Tx tx, Order order) throws SQLException {
    // The lines go with the order: ON DELETE CASCADE.
    return OrderSql.deleteOrder(tx.connection(), order.id(), order.client(), order.version());
  }

  private static void writeLines(Connection c, Order order) throws SQLException {
    int position = 0;
    for (OrderLine line : order.lines()) {
      OrderSql.insertLine(
          c,
          order.id(),
          position++,
          line.lineId(),
          line.sku(),
          line.quantity(),
          line.unitPriceCents());
    }
  }
}
