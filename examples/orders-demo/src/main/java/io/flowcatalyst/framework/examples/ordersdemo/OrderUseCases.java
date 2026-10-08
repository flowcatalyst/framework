package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.core.Ids;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.examples.ordersdemo.Commands.AddLine;
import io.flowcatalyst.framework.examples.ordersdemo.Commands.CancelOrder;
import io.flowcatalyst.framework.examples.ordersdemo.Commands.PlaceOrder;
import io.flowcatalyst.framework.examples.ordersdemo.OrderEvents.OrderCancelled;
import io.flowcatalyst.framework.examples.ordersdemo.OrderEvents.OrderLineAdded;
import io.flowcatalyst.framework.examples.ordersdemo.OrderEvents.OrderPlaced;
import io.flowcatalyst.framework.usecase.Check;
import io.flowcatalyst.framework.usecase.ConflictPolicy;
import io.flowcatalyst.framework.usecase.Decision;
import io.flowcatalyst.framework.usecase.DomainEvent;
import io.flowcatalyst.framework.usecase.Events;
import io.flowcatalyst.framework.usecase.Plan;
import io.flowcatalyst.framework.usecase.UseCase;
import io.flowcatalyst.framework.usecase.UseCaseError;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

/**
 * The three use cases on an order, each defined once: validate the command's shape, authorize,
 * then execute (load once, apply the rules, return a plan). Run them with
 * {@link io.flowcatalyst.framework.usecase.UseCases#run}.
 */
public final class OrderUseCases {

  /**
   * The conflict policy of each use case that edits an existing order.
   *
   * @param addLine what to do when the order changed under an {@code addLine}
   * @param cancel what to do when the order changed under a {@code cancelOrder}
   */
  public record Policies(ConflictPolicy addLine, ConflictPolicy cancel) {

    /**
     * Adding a line is safe to repeat on fresh state, so it retries; a cancellation made on a
     * stale view is for the caller to decide again, so it fails.
     *
     * @return the defaults
     */
    public static Policies defaults() {
      return new Policies(ConflictPolicy.retry(3, Duration.ofMillis(25)), ConflictPolicy.fail());
    }
  }

  private static final Check<Object> PRINCIPAL_REQUIRED =
      (context, command) ->
          context.principal() == null
              ? Optional.of(
                  UseCaseError.authorization("PRINCIPAL_REQUIRED", "Sign in to change orders."))
              : Optional.empty();

  private final OrderRepository repository;
  private final Database database;
  private final Events events;
  private final Ids ids;
  private final UseCase<PlaceOrder, OrderPlaced> placeOrder;
  private final UseCase<AddLine, OrderLineAdded> addLine;
  private final UseCase<CancelOrder, OrderCancelled> cancelOrder;

  /**
   * Defines the use cases.
   *
   * @param repository the order repository
   * @param database the database the use cases read from
   * @param events makes event metadata
   * @param ids generates order and line ids
   * @param policies the conflict policies
   */
  public OrderUseCases(
      OrderRepository repository, Database database, Events events, Ids ids, Policies policies) {
    this.repository = repository;
    this.database = database;
    this.events = events;
    this.ids = ids;
    this.placeOrder = definePlaceOrder();
    this.addLine = defineAddLine(policies.addLine());
    this.cancelOrder = defineCancelOrder(policies.cancel());
  }

  /**
   * Place a new order.
   *
   * @return the use case
   */
  public UseCase<PlaceOrder, OrderPlaced> placeOrder() {
    return placeOrder;
  }

  /**
   * Add a line to an open order.
   *
   * @return the use case
   */
  public UseCase<AddLine, OrderLineAdded> addLine() {
    return addLine;
  }

  /**
   * Cancel an open order.
   *
   * @return the use case
   */
  public UseCase<CancelOrder, OrderCancelled> cancelOrder() {
    return cancelOrder;
  }

  private UseCase<PlaceOrder, OrderPlaced> definePlaceOrder() {
    return UseCase.named("place-order", PlaceOrder.class, OrderPlaced.class)
        .validate(
            (context, command) ->
                command.customer().isBlank()
                    ? Optional.of(UseCaseError.validation("CUSTOMER_REQUIRED", "A customer is required."))
                    : Optional.empty())
        .authorize(PRINCIPAL_REQUIRED)
        .execute(
            (context, command) -> {
              Order order =
                  Order.place(
                      new OrderId(ids.generate("ord")), context.client(), command.customer().strip());
              return Plan.save(
                  order,
                  repository,
                  new OrderPlaced(
                      events.metadata(context, OrderEvents.PLACED, order.id()),
                      order.id().value(),
                      order.customer()));
            });
  }

  private UseCase<AddLine, OrderLineAdded> defineAddLine(ConflictPolicy policy) {
    return UseCase.named("add-line", AddLine.class, OrderLineAdded.class)
        .validate(
            (context, command) -> {
              if (command.sku().isBlank()) {
                return Optional.of(UseCaseError.validation("SKU_REQUIRED", "A sku is required."));
              }
              if (command.quantity() <= 0) {
                return Optional.of(
                    UseCaseError.validation("QUANTITY_INVALID", "The quantity must be positive."));
              }
              if (command.unitPriceCents() < 0) {
                return Optional.of(
                    UseCaseError.validation("PRICE_INVALID", "The price must not be negative."));
              }
              return Optional.empty();
            })
        .authorize(PRINCIPAL_REQUIRED)
        .conflict(policy)
        .execute(
            (context, command) -> {
              Optional<Order> found = load(context, command.orderId());
              if (found.isEmpty()) {
                return orderNotFound();
              }
              Order order = found.get();
              if (!order.isOpen()) {
                return orderNotOpen();
              }
              OrderLine line =
                  new OrderLine(
                      ids.generate("lin"),
                      command.sku(),
                      command.quantity(),
                      command.unitPriceCents());
              Order updated = order.withLine(line);
              return Plan.save(
                  updated,
                  repository,
                  new OrderLineAdded(
                      events.metadata(context, OrderEvents.LINE_ADDED, order.id()),
                      order.id().value(),
                      line.lineId(),
                      line.sku(),
                      line.quantity(),
                      line.unitPriceCents(),
                      updated.lines().size()));
            });
  }

  private UseCase<CancelOrder, OrderCancelled> defineCancelOrder(ConflictPolicy policy) {
    return UseCase.named("cancel-order", CancelOrder.class, OrderCancelled.class)
        .validate(
            (context, command) ->
                command.reason().isBlank()
                    ? Optional.of(UseCaseError.validation("REASON_REQUIRED", "A reason is required."))
                    : Optional.empty())
        .authorize(PRINCIPAL_REQUIRED)
        .conflict(policy)
        .execute(
            (context, command) -> {
              Optional<Order> found = load(context, command.orderId());
              if (found.isEmpty()) {
                return orderNotFound();
              }
              Order order = found.get();
              if (!order.isOpen()) {
                return orderNotOpen();
              }
              Order cancelled = order.cancel();
              return Plan.save(
                  cancelled,
                  repository,
                  new OrderCancelled(
                      events.metadata(context, OrderEvents.CANCELLED, order.id()),
                      order.id().value(),
                      command.reason(),
                      order.lines().size()));
            });
  }

  private Optional<Order> load(Context context, String orderId) throws SQLException {
    return repository.find(database, context.client(), new OrderId(orderId));
  }

  private static <E extends DomainEvent> Decision<E> orderNotFound() {
    return Decision.reject(UseCaseError.notFound("ORDER_NOT_FOUND", "There is no such order."));
  }

  private static <E extends DomainEvent> Decision<E> orderNotOpen() {
    return Decision.reject(
        UseCaseError.businessRule("ORDER_NOT_OPEN", "The order is cancelled and cannot change."));
  }
}
