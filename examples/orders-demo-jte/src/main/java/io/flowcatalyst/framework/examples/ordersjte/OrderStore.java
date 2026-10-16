package io.flowcatalyst.framework.examples.ordersjte;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/** The demo's orders, in memory. The demo has no database and no sign-in. */
public final class OrderStore {

  /**
   * One page of a listing.
   *
   * @param rows the orders on the page
   * @param total how many orders match
   * @param pages how many pages there are
   * @param page this page, clamped into range
   */
  public record Listing(List<Order> rows, int total, int pages, int page) {}

  private final List<Order> orders = new CopyOnWriteArrayList<>();
  private final AtomicInteger sequence = new AtomicInteger();

  /**
   * A store with a small seed.
   *
   * @param now the instant the seed is dated from
   * @return the store
   */
  public static OrderStore seeded(Instant now) {
    OrderStore s = new OrderStore();
    String[] names = {
      "Ada Lovelace", "Grace Hopper", "Alan Turing", "Edsger Dijkstra", "Barbara Liskov", "Donald Knuth",
      "Margaret Hamilton", "Dennis Ritchie", "Ken Thompson", "Frances Allen", "Tony Hoare", "Leslie Lamport"
    };
    String[] statuses = {"PENDING", "PAID", "SHIPPED", "PAID", "CANCELLED", "PENDING"};
    for (int i = 0; i < 23; i++) {
      s.add(
          names[i % names.length],
          statuses[i % statuses.length],
          1_250 + (i * 3_719L) % 98_000,
          now.minus(i * 7L + 1, ChronoUnit.HOURS));
    }
    return s;
  }

  /**
   * Adds an order.
   *
   * @param customer the customer
   * @param status the status
   * @param totalCents the total in cents
   * @param createdAt when it was placed
   * @return the new order
   */
  public Order add(String customer, String status, long totalCents, Instant createdAt) {
    Order o = new Order(String.format("ord-%04d", sequence.incrementAndGet()), customer, status, totalCents, createdAt);
    orders.add(o);
    return o;
  }

  /**
   * One order.
   *
   * @param id the id
   * @return the order, if there is one
   */
  public Optional<Order> find(String id) {
    return orders.stream().filter(o -> o.id().equals(id)).findFirst();
  }

  /**
   * Cancels an order that can still be cancelled.
   *
   * @param id the id
   * @return the cancelled order, or empty when there is none or it cannot be cancelled
   */
  public synchronized Optional<Order> cancel(String id) {
    for (int i = 0; i < orders.size(); i++) {
      Order o = orders.get(i);
      if (o.id().equals(id)) {
        if (!o.cancellable()) {
          return Optional.empty();
        }
        Order c = new Order(o.id(), o.customer(), "CANCELLED", o.totalCents(), o.createdAt());
        orders.set(i, c);
        return Optional.of(c);
      }
    }
    return Optional.empty();
  }

  /**
   * Lists orders.
   *
   * @param q a case-insensitive part of the customer's name, or null
   * @param status a status, or null for all
   * @param sort customer, status, total or created
   * @param descending the direction
   * @param page the page, 1-based
   * @param rows rows per page
   * @return the page
   */
  public Listing list(@Nullable String q, @Nullable String status, String sort, boolean descending, int page, int rows) {
    List<Order> matching = new ArrayList<>();
    String needle = q == null ? "" : q.toLowerCase(java.util.Locale.ROOT).strip();
    for (Order o : orders) {
      if ((needle.isEmpty() || o.customer().toLowerCase(java.util.Locale.ROOT).contains(needle))
          && (status == null || o.status().equals(status))) {
        matching.add(o);
      }
    }
    Comparator<Order> by =
        switch (sort) {
          case "customer" -> Comparator.comparing(o -> o.customer().toLowerCase(java.util.Locale.ROOT));
          case "status" -> Comparator.comparing(Order::status);
          case "total" -> Comparator.comparingLong(Order::totalCents);
          default -> Comparator.comparing(Order::createdAt);
        };
    matching.sort(descending ? by.reversed().thenComparing(Order::id) : by.thenComparing(Order::id));
    int pages = Math.max(1, (matching.size() + rows - 1) / rows);
    int p = Math.min(Math.max(page, 1), pages);
    int from = (p - 1) * rows;
    return new Listing(List.copyOf(matching.subList(from, Math.min(from + rows, matching.size()))), matching.size(), pages, p);
  }
}
