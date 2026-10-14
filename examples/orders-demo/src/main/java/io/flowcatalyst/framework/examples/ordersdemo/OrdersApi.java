package io.flowcatalyst.framework.examples.ordersdemo;

import static io.flowcatalyst.framework.http.Rules.maxLength;
import static io.flowcatalyst.framework.http.Rules.notBlank;
import static io.flowcatalyst.framework.http.Rules.pattern;
import static io.flowcatalyst.framework.http.Rules.range;

import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.PathParam;
import io.flowcatalyst.framework.http.QueryParam;
import io.flowcatalyst.framework.http.TypeRef;
import io.flowcatalyst.framework.http.Validator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The orders API as typed operations (OpenAPI contract): values written once, declared by {@link
 * OrdersApiModule} in {@code register} and bound in {@code boot}. The committed document, {@code
 * web/openapi.json}, is written from them; the SPA's client is generated from that file.
 *
 * <p>The records are the wire types, separate from the aggregate: {@link Order} has methods such as
 * {@code isOpen()} that Jackson would write as properties.
 */
public final class OrdersApi {

  private OrdersApi() {}

  /** Who may read orders. */
  public static final String READ = "orders:order:read";

  /** Who may place and change orders. */
  public static final String MANAGE = "orders:order:manage";

  // ---- wire types -------------------------------------------------------------------------------

  /**
   * One line of an order.
   *
   * @param lineId the line's id
   * @param sku the stock-keeping unit
   * @param quantity how many
   * @param unitPriceCents the price of one, in cents
   * @param totalCents quantity times unit price
   */
  public record OrderLineView(String lineId, String sku, int quantity, long unitPriceCents, long totalCents) {}

  /**
   * An order with its lines.
   *
   * @param id the order id
   * @param customer the customer
   * @param status open or cancelled
   * @param version the optimistic-locking version
   * @param lines the lines, in the order they were added
   * @param totalCents the sum of the lines
   */
  public record OrderView(
      String id, String customer, OrderStatus status, long version, List<OrderLineView> lines, long totalCents) {

    static OrderView of(Order o) {
      List<OrderLineView> lines =
          o.lines().stream()
              .map(l -> new OrderLineView(l.lineId(), l.sku(), l.quantity(), l.unitPriceCents(), l.totalCents()))
              .toList();
      return new OrderView(o.id().value(), o.customer(), o.status(), o.version(), lines, o.totalCents());
    }
  }

  /**
   * An order in a list.
   *
   * @param id the order id
   * @param customer the customer
   * @param status open or cancelled
   * @param version the optimistic-locking version
   * @param lineCount how many lines
   * @param totalCents the sum of the lines
   * @param firstSku the first sku in alphabetical order, absent for an order without lines
   */
  public record OrderSummaryView(
      String id, String customer, OrderStatus status, long version, int lineCount, long totalCents, @Nullable String firstSku) {}

  /**
   * One page of a list.
   *
   * @param <T> the item type
   * @param items the items
   * @param nextCursor pass it as {@code after} for the next page; absent on the last page
   */
  public record Page<T>(List<T> items, @Nullable String nextCursor) {}

  /**
   * Places an order.
   *
   * @param customer who it is for
   */
  public record PlaceOrderBody(String customer) {
    static final Validator<PlaceOrderBody> V =
        Validator.of(PlaceOrderBody.class).field("customer", PlaceOrderBody::customer, notBlank(), maxLength(200));
  }

  /**
   * Adds a line.
   *
   * @param sku the stock-keeping unit, upper case
   * @param quantity how many, 1 to 10 000
   * @param unitPriceCents the price of one, in cents
   */
  public record AddLineBody(String sku, int quantity, long unitPriceCents) {
    static final Validator<AddLineBody> V =
        Validator.of(AddLineBody.class)
            .field("sku", AddLineBody::sku, notBlank(), maxLength(64), pattern("[A-Z0-9][A-Z0-9-]*"))
            .field("quantity", AddLineBody::quantity, range(1, 10_000))
            .field("unitPriceCents", AddLineBody::unitPriceCents, range(0, 100_000_000));
  }

  /**
   * Cancels an order.
   *
   * @param reason why
   */
  public record CancelOrderBody(String reason) {
    static final Validator<CancelOrderBody> V =
        Validator.of(CancelOrderBody.class).field("reason", CancelOrderBody::reason, notBlank(), maxLength(500));
  }

  // ---- parameters and operations ----------------------------------------------------------------

  /** The order in the path. */
  public static final PathParam<String> ORDER_ID = PathParam.string("orderId");

  /** Only orders with this status. */
  public static final QueryParam<@Nullable OrderStatus> STATUS = QueryParam.enumOf("status", OrderStatus.class).optional();

  /** How many orders a page holds. */
  public static final QueryParam<Integer> LIMIT = QueryParam.integer("limit").defaultValue(50);

  /** The {@code nextCursor} of the previous page. */
  public static final QueryParam<@Nullable String> AFTER = QueryParam.string("after").optional();

  /** {@code GET /api/orders}. */
  public static final Operation<NoBody, Page<OrderSummaryView>> LIST =
      Operation.get("/api/orders", new TypeRef<Page<OrderSummaryView>>() {})
          .id("listOrders")
          .summary("List the client's orders")
          .tag("orders")
          .query(STATUS, LIMIT, AFTER)
          .permission(READ)
          .build();

  /** {@code GET /api/orders/{orderId}}. */
  public static final Operation<NoBody, OrderView> GET =
      Operation.get("/api/orders/{orderId}", OrderView.class)
          .id("getOrder")
          .summary("Read an order")
          .tag("orders")
          .path(ORDER_ID)
          .permission(READ)
          .error(404, "ORDER_NOT_FOUND", "there is no such order")
          .build();

  /** {@code POST /api/orders}. */
  public static final Operation<PlaceOrderBody, OrderView> PLACE =
      Operation.post("/api/orders", PlaceOrderBody.V, OrderView.class)
          .id("placeOrder")
          .summary("Place an order")
          .tag("orders")
          .status(201)
          .permission(MANAGE)
          .build();

  /** {@code POST /api/orders/{orderId}/lines}. */
  public static final Operation<AddLineBody, OrderView> ADD_LINE =
      Operation.post("/api/orders/{orderId}/lines", AddLineBody.V, OrderView.class)
          .id("addOrderLine")
          .summary("Add a line to an open order")
          .tag("orders")
          .path(ORDER_ID)
          .permission(MANAGE)
          .error(404, "ORDER_NOT_FOUND", "there is no such order")
          .error(409, "ORDER_NOT_OPEN", "the order is cancelled and cannot change")
          .error(409, "STALE_AGGREGATE", "the order was changed by someone else; reload it and try again")
          .build();

  /** {@code POST /api/orders/{orderId}/cancel}. */
  public static final Operation<CancelOrderBody, OrderView> CANCEL =
      Operation.post("/api/orders/{orderId}/cancel", CancelOrderBody.V, OrderView.class)
          .id("cancelOrder")
          .summary("Cancel an open order")
          .tag("orders")
          .path(ORDER_ID)
          .permission(MANAGE)
          .error(404, "ORDER_NOT_FOUND", "there is no such order")
          .error(409, "ORDER_NOT_OPEN", "the order is already cancelled")
          .error(409, "STALE_AGGREGATE", "the order was changed by someone else; reload it and try again")
          .build();
}
