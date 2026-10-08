package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.examples.ordersdemo.Commands.AddLine;
import io.flowcatalyst.framework.examples.ordersdemo.Commands.CancelOrder;
import io.flowcatalyst.framework.examples.ordersdemo.Commands.PlaceOrder;
import io.flowcatalyst.framework.usecase.OutboxModule;
import io.flowcatalyst.framework.usecase.UseCaseModule;
import io.flowcatalyst.framework.usecase.UseCaseResult;
import io.flowcatalyst.framework.usecase.UseCases;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the application, places an order, adds two lines and cancels it, then lists the
 * client's orders. Run it with a PostgreSQL reachable at the address in
 * {@code config/application.yaml}.
 */
public final class OrdersDemo {

  private OrdersDemo() {}

  /**
   * The application: every module it needs, listed in code.
   *
   * @return a builder with the modules added
   */
  public static AppBuilder builder() {
    return App.builder()
        .module(new DatabaseModule())
        .module(new OutboxModule())
        .module(new UseCaseModule())
        .module(new OrdersModule());
  }

  /**
   * Runs the scenario for one client.
   *
   * @param app a running application
   * @param client the client the orders belong to
   * @return one line per step, then the order list
   * @throws SQLException when listing the orders fails
   */
  public static List<String> run(App app, ClientId client) throws SQLException {
    UseCases useCases = app.get(UseCases.class);
    OrderUseCases orders = app.get(OrderUseCases.class);
    Context context = Context.of(client).withPrincipal(new PrincipalId("usr_demo"));
    List<String> report = new ArrayList<>();

    UseCaseResult<OrderEvents.OrderPlaced> placed =
        useCases.run(orders.placeOrder(), context, new PlaceOrder("Ada Lovelace"));
    report.add("place: " + describe(placed));
    if (!(placed instanceof UseCaseResult.Committed<OrderEvents.OrderPlaced> c)) {
      return report;
    }
    String orderId = c.event().orderId();
    report.add(
        "add: "
            + describe(useCases.run(orders.addLine(), context, new AddLine(orderId, "ANALYTICAL-ENGINE", 1, 120_000))));
    report.add(
        "add: "
            + describe(useCases.run(orders.addLine(), context, new AddLine(orderId, "PUNCH-CARDS", 500, 3))));
    report.add(
        "cancel: "
            + describe(useCases.run(orders.cancelOrder(), context, new CancelOrder(orderId, "changed my mind"))));
    report.add(
        "add after cancel: "
            + describe(useCases.run(orders.addLine(), context, new AddLine(orderId, "PUNCH-CARDS", 1, 3))));
    for (OrderQueries.Summary s :
        OrderQueries.summaries(app.get(Databases.class).defaultInstance(), client)) {
      report.add(
          "order %s %s v%d: %d lines, %d cents"
              .formatted(s.id(), s.status(), s.version(), s.lineCount(), s.totalCents()));
    }
    return report;
  }

  private static String describe(UseCaseResult<?> result) {
    return switch (result) {
      case UseCaseResult.Committed<?> ok -> "committed " + ok.event().metadata().type();
      case UseCaseResult.Failed<?> failed ->
          failed.error().kind() + " " + failed.error().code();
    };
  }

  /**
   * Starts, runs once for the client {@code demo} and shuts down.
   *
   * @param args ignored
   * @throws SQLException when listing the orders fails
   */
  public static void main(String[] args) throws SQLException {
    try (App app = builder().shutdownHook(true).start()) {
      run(app, new ClientId("demo")).forEach(System.out::println);
    }
  }
}
