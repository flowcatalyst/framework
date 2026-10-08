package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.usecase.Aggregate;
import java.util.ArrayList;
import java.util.List;

/**
 * The order aggregate. Its lines belong to it: they are saved with it and covered by its version,
 * so adding a line is a change to the order. Methods that change it return a new value.
 *
 * @param id the order id
 * @param client the client that owns it
 * @param version {@code 0} until stored
 * @param customer who ordered
 * @param status where it is in its life
 * @param lines its lines, in the order they were added
 */
public record Order(
    OrderId id,
    ClientId client,
    long version,
    String customer,
    OrderStatus status,
    List<OrderLine> lines)
    implements Aggregate {

  /** Copies the lines. */
  public Order {
    lines = List.copyOf(lines);
  }

  /**
   * A new, open order with no lines.
   *
   * @param id its id
   * @param client its client
   * @param customer who ordered
   * @return the order, at version {@code 0}
   */
  public static Order place(OrderId id, ClientId client, String customer) {
    return new Order(id, client, 0, customer, OrderStatus.OPEN, List.of());
  }

  /**
   * Whether lines may still be added.
   *
   * @return true while the order is open
   */
  public boolean isOpen() {
    return status == OrderStatus.OPEN;
  }

  /**
   * The order with one more line.
   *
   * @param line the line
   * @return the new order
   */
  public Order withLine(OrderLine line) {
    List<OrderLine> more = new ArrayList<>(lines);
    more.add(line);
    return new Order(id, client, version, customer, status, more);
  }

  /**
   * The order, cancelled.
   *
   * @return the new order
   */
  public Order cancel() {
    return new Order(id, client, version, customer, OrderStatus.CANCELLED, lines);
  }

  /**
   * The sum of the lines.
   *
   * @return the total in cents
   */
  public long totalCents() {
    return lines.stream().mapToLong(OrderLine::totalCents).sum();
  }
}
