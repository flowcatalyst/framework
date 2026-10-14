package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.examples.ordersdemo.Commands.AddLine;
import io.flowcatalyst.framework.examples.ordersdemo.Commands.CancelOrder;
import io.flowcatalyst.framework.examples.ordersdemo.Commands.PlaceOrder;
import io.flowcatalyst.framework.examples.ordersdemo.OrdersApi.OrderSummaryView;
import io.flowcatalyst.framework.examples.ordersdemo.OrdersApi.OrderView;
import io.flowcatalyst.framework.examples.ordersdemo.OrdersApi.Page;
import io.flowcatalyst.framework.http.Outcome;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.openapi.OpenApi;
import io.flowcatalyst.framework.usecase.DomainEvent;
import io.flowcatalyst.framework.usecase.UseCaseError;
import io.flowcatalyst.framework.usecase.UseCaseResult;
import io.flowcatalyst.framework.usecase.UseCases;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Declares the orders API in {@code register} (so {@code openapi} writes the document with no
 * database) and binds it in {@code boot}. The use cases decide; a failed one is answered with its
 * {@code UseCaseError}, which is a {@code ServiceError}.
 */
public final class OrdersApiModule implements Module {

  /** The module id. */
  public static final String ID = "orders-api";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    OpenApi.declare(app, OrdersApi.LIST, OrdersApi.GET, OrdersApi.PLACE, OrdersApi.ADD_LINE, OrdersApi.CANCEL);
    OpenApi.info(app, "orders-demo", "1");
  }

  @Override
  public void boot(App app) {
    UseCases useCases = app.get(UseCases.class);
    OrderUseCases orders = app.get(OrderUseCases.class);
    OrderRepository repository = new OrderRepository();
    Database db = app.get(Databases.class).defaultInstance();
    Routes routes = app.get(Routes.class).forModule(ID);

    routes.bind(OrdersApi.LIST, r -> {
      List<OrderSummaryView> all = new ArrayList<>();
      for (OrderQueries.Summary s : OrderQueries.summaries(db, r.context().client())) {
        if (r.query(OrdersApi.STATUS) == null || s.status() == r.query(OrdersApi.STATUS)) {
          all.add(new OrderSummaryView(s.id(), s.customer(), s.status(), s.version(), s.lineCount(), s.totalCents(), s.firstSku()));
        }
      }
      // summaries come sorted by id; the cursor is the last id of the previous page
      String after = r.query(OrdersApi.AFTER);
      List<OrderSummaryView> rest = all.stream().filter(s -> after == null || s.id().compareTo(after) > 0).toList();
      int limit = Math.max(1, Math.min(r.query(OrdersApi.LIMIT), 200));
      List<OrderSummaryView> page = rest.subList(0, Math.min(limit, rest.size()));
      String next = rest.size() > limit ? page.getLast().id() : null;
      return Outcome.ok(new Page<>(page, next));
    });

    routes.bind(OrdersApi.GET, r -> view(repository, db, r.context(), r.path(OrdersApi.ORDER_ID)));

    routes.bind(OrdersApi.PLACE, r -> {
      Context context = r.context();
      Outcome<OrderView> out =
          then(
              useCases.run(orders.placeOrder(), context, new PlaceOrder(r.body().customer())),
              placed -> view(repository, db, context, placed.orderId()));
      return out instanceof Outcome.Ok<OrderView> ok ? ok.header("Location", "/api/orders/" + ok.value().id()) : out;
    });

    routes.bind(OrdersApi.ADD_LINE, r -> {
      String id = r.path(OrdersApi.ORDER_ID);
      OrdersApi.AddLineBody b = r.body();
      return then(
          useCases.run(orders.addLine(), r.context(), new AddLine(id, b.sku(), b.quantity(), b.unitPriceCents())),
          added -> view(repository, db, r.context(), id));
    });

    routes.bind(OrdersApi.CANCEL, r -> {
      String id = r.path(OrdersApi.ORDER_ID);
      return then(
          useCases.run(orders.cancelOrder(), r.context(), new CancelOrder(id, r.body().reason())),
          cancelled -> view(repository, db, r.context(), id));
    });
  }

  private static Outcome<OrderView> view(OrderRepository repository, Database db, Context context, String id)
      throws SQLException {
    Optional<Order> order = repository.find(db, context.client(), new OrderId(id));
    return order.<Outcome<OrderView>>map(o -> Outcome.ok(OrderView.of(o)))
        .orElseGet(() -> Outcome.failed(UseCaseError.notFound("ORDER_NOT_FOUND", "There is no such order.")));
  }

  /** What to answer once a use case committed. */
  private interface Step<E> {
    Outcome<OrderView> apply(E event) throws Exception;
  }

  /** A committed use case becomes the next outcome; a failed one is answered with its error. */
  private static <E extends DomainEvent> Outcome<OrderView> then(UseCaseResult<E> result, Step<E> next) throws Exception {
    return switch (result) {
      case UseCaseResult.Committed<E> c -> next.apply(c.event());
      case UseCaseResult.Failed<E> f -> Outcome.failed(f.error());
    };
  }
}
