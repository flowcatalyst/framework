package io.flowcatalyst.framework.examples.ordersdemo;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Ids;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.examples.ordersdemo.Commands.AddLine;
import io.flowcatalyst.framework.examples.ordersdemo.Commands.CancelOrder;
import io.flowcatalyst.framework.examples.ordersdemo.Commands.PlaceOrder;
import io.flowcatalyst.framework.examples.ordersdemo.OrderEvents.OrderCancelled;
import io.flowcatalyst.framework.examples.ordersdemo.OrderEvents.OrderLineAdded;
import io.flowcatalyst.framework.examples.ordersdemo.OrderEvents.OrderPlaced;
import io.flowcatalyst.framework.usecase.Authorize;
import io.flowcatalyst.framework.usecase.ConflictPolicy;
import io.flowcatalyst.framework.usecase.DomainEvent;
import io.flowcatalyst.framework.usecase.ErrorKind;
import io.flowcatalyst.framework.usecase.Events;
import io.flowcatalyst.framework.usecase.Plan;
import io.flowcatalyst.framework.usecase.UseCase;
import io.flowcatalyst.framework.usecase.UseCaseError;
import io.flowcatalyst.framework.usecase.UseCaseResult;
import io.flowcatalyst.framework.usecase.UseCases;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The write path end to end: real use cases, real PostgreSQL, the config the example ships. */
class OrdersDemoTest {

  static final Instant NOW = Instant.parse("2026-10-08T10:15:30Z");

  static PostgresFixture pg;

  final ClientId acme = new ClientId("acme");
  final ClientId globex = new ClientId("globex");
  final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

  App app;
  Database db;
  UseCases useCases;
  OrderUseCases orders;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stopPostgres() {
    pg.close();
  }

  @BeforeEach
  void start() {
    pg.reset();
    app =
        OrdersDemo.builder()
            .configDirectory(Path.of("config"))
            .environment(
                Map.of(
                    "DATABASE_INSTANCES_MAIN_URL", pg.jdbcUrl(),
                    "DATABASE_INSTANCES_MAIN_USERNAME", pg.username(),
                    "DATABASE_INSTANCES_MAIN_PASSWORD", pg.password()))
            .instantSource(() -> NOW)
            .start();
    db = app.get(Databases.class).defaultInstance();
    useCases = app.get(UseCases.class);
    orders = app.get(OrderUseCases.class);
  }

  @AfterEach
  void stop() {
    threads.close();
    app.close();
  }

  Context context(ClientId client) {
    return Context.of(client).withPrincipal(new PrincipalId("usr_1"));
  }

  String placeOrder(ClientId client) {
    return committed(useCases.run(orders.placeOrder(), context(client), new PlaceOrder("Ada"))).orderId();
  }

  static <E extends DomainEvent> E committed(UseCaseResult<E> r) {
    assertThat(r).isInstanceOf(UseCaseResult.Committed.class);
    return ((UseCaseResult.Committed<E>) r).event();
  }

  static UseCaseError failed(UseCaseResult<?> r) {
    assertThat(r).isInstanceOf(UseCaseResult.Failed.class);
    return ((UseCaseResult.Failed<?>) r).error();
  }

  int count(String sql) {
    return Integer.parseInt(pg.query(sql));
  }

  String order(String id) {
    return pg.query(
        "SELECT version || ':' || status || ':' || (SELECT count(*) FROM order_lines WHERE order_id = id) "
            + "FROM orders WHERE id = '"
            + id
            + "'");
  }

  // ---- the scenario ------------------------------------------------------------------------

  @Test
  void placeAddAddCancelWritesTheOrderItsLinesAndOneEventAndAuditRowPerStep() throws Exception {
    List<String> report = OrdersDemo.run(app, acme);

    assertThat(report.subList(0, 5))
        .containsExactly(
            "place: committed orders:sales:order:placed",
            "add: committed orders:sales:order:line-added",
            "add: committed orders:sales:order:line-added",
            "cancel: committed orders:sales:order:cancelled",
            "add after cancel: BUSINESS_RULE ORDER_NOT_OPEN");
    assertThat(report.get(5)).matches("order ord_[0-9A-HJKMNP-TV-Z]{13} CANCELLED v4: 2 lines, 121500 cents");

    assertThat(count("SELECT count(*) FROM outbox_messages WHERE type = 'EVENT'")).isEqualTo(4);
    assertThat(count("SELECT count(*) FROM outbox_messages WHERE type = 'AUDIT_LOG'")).isEqualTo(4);
    assertThat(pg.query("SELECT string_agg(payload::jsonb ->> 'type', ',' ORDER BY created_at, id) "
            + "FROM outbox_messages WHERE type = 'EVENT'"))
        .contains("orders:sales:order:placed")
        .contains("orders:sales:order:cancelled");
    assertThat(
            pg.query(
                "SELECT count(DISTINCT message_group) FROM outbox_messages WHERE type = 'EVENT'"))
        .isEqualTo("1"); // every event of one order shares its message group
  }

  @Test
  void aRejectedCommandWritesNothing() {
    String id = placeOrder(acme);
    int outbox = count("SELECT count(*) FROM outbox_messages");

    assertThat(failed(useCases.run(orders.addLine(), context(acme), new AddLine("ord_missing", "X", 1, 1))))
        .satisfies(e -> assertThat(e.kind()).isEqualTo(ErrorKind.NOT_FOUND));
    assertThat(failed(useCases.run(orders.addLine(), context(acme), new AddLine(id, "X", 0, 1))).code())
        .isEqualTo("QUANTITY_INVALID");
    assertThat(failed(useCases.run(orders.placeOrder(), context(acme), new PlaceOrder(" "))).code())
        .isEqualTo("CUSTOMER_REQUIRED");
    assertThat(
            failed(useCases.run(orders.placeOrder(), Context.of(acme), new PlaceOrder("Ada"))).kind())
        .isEqualTo(ErrorKind.AUTHORIZATION);

    assertThat(order(id)).isEqualTo("1:OPEN:0");
    assertThat(count("SELECT count(*) FROM outbox_messages")).isEqualTo(outbox);
  }

  @Test
  void anotherClientCannotSeeOrChangeTheOrder() throws Exception {
    String id = placeOrder(acme);

    UseCaseError error =
        failed(useCases.run(orders.cancelOrder(), context(globex), new CancelOrder(id, "mine now")));

    assertThat(error.code()).isEqualTo("ORDER_NOT_FOUND");
    assertThat(order(id)).isEqualTo("1:OPEN:0");
    assertThat(OrderQueries.summaries(db, globex)).isEmpty();
    assertThat(OrderQueries.summaries(db, acme)).hasSize(1);
  }

  @Test
  void aPlanThatTouchesAnotherClientsOrderIsRefusedAndWritesNothing() {
    String id = placeOrder(acme);
    int outbox = count("SELECT count(*) FROM outbox_messages");
    Events events = app.get(Events.class);
    OrderRepository repository = new OrderRepository();
    UseCase<CancelOrder, OrderCancelled> sneaky =
        UseCase.named("sneaky-cancel", CancelOrder.class, OrderCancelled.class)
            .authorize(Authorize.PUBLIC)
            .execute(
                (context, command) -> {
                  Order theirs = repository.find(db, acme, new OrderId(command.orderId())).orElseThrow();
                  return Plan.save(
                      theirs.cancel(),
                      repository,
                      new OrderCancelled(
                          events.metadata(context, OrderEvents.CANCELLED, theirs.id()),
                          id,
                          "x",
                          0));
                });

    UseCaseError error = failed(useCases.run(sneaky, context(globex), new CancelOrder(id, "x")));

    assertThat(error.kind()).isEqualTo(ErrorKind.INTERNAL);
    assertThat(error.code()).isEqualTo("CROSS_CLIENT_WRITE");
    assertThat(order(id)).isEqualTo("1:OPEN:0");
    assertThat(count("SELECT count(*) FROM outbox_messages")).isEqualTo(outbox);
  }

  @Test
  void aDatabaseFailureWhileWritingTheLinesRollsBackTheHeaderAndTheOutbox() {
    String id = placeOrder(acme);
    int outbox = count("SELECT count(*) FROM outbox_messages");

    // A sku longer than the table allows: validate passes, the line insert fails after the
    // header row was updated.
    UseCaseError error =
        failed(
            useCases.run(
                orders.addLine(), context(acme), new AddLine(id, "S".repeat(30), 1, 100)));

    assertThat(error.kind()).isEqualTo(ErrorKind.INTERNAL);
    assertThat(error.code()).isEqualTo("PERSIST");
    assertThat(error.message()).doesNotContain("sku").doesNotContain("order_lines");
    assertThat(order(id)).isEqualTo("1:OPEN:0");
    assertThat(count("SELECT count(*) FROM outbox_messages")).isEqualTo(outbox);
  }

  // ---- optimistic locking, with the child collection ---------------------------------------

  /** Holds the first load of an order until the test lets the attempt go on. */
  static final class GatedRepository extends OrderRepository {
    final CountDownLatch loaded = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final AtomicBoolean first = new AtomicBoolean(true);

    @Override
    public Optional<Order> find(Database db, ClientId client, OrderId id) throws SQLException {
      Optional<Order> found = super.find(db, client, id);
      if (first.getAndSet(false)) {
        loaded.countDown();
        try {
          if (!proceed.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("test never released the first attempt");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      return found;
    }
  }

  private OrderUseCases gated(GatedRepository repository, OrderUseCases.Policies policies) {
    return new OrderUseCases(
        repository, db, app.get(Events.class), app.get(Ids.class), policies);
  }

  @Test
  void addingALineBumpsTheOrdersVersionSoAConcurrentHeaderEditConflicts() throws Exception {
    String id = placeOrder(acme);
    GatedRepository repository = new GatedRepository();
    OrderUseCases slow =
        gated(repository, new OrderUseCases.Policies(ConflictPolicy.fail(), ConflictPolicy.fail()));

    Future<UseCaseResult<OrderCancelled>> cancel =
        threads.submit(
            () -> useCases.run(slow.cancelOrder(), context(acme), new CancelOrder(id, "too slow")));
    assertThat(repository.loaded.await(20, TimeUnit.SECONDS)).isTrue();
    committed(useCases.run(orders.addLine(), context(acme), new AddLine(id, "BOLT", 2, 50)));
    repository.proceed.countDown();

    UseCaseError error = failed(cancel.get(20, TimeUnit.SECONDS));
    assertThat(error.kind()).isEqualTo(ErrorKind.CONFLICT);
    assertThat(error.code()).isEqualTo("STALE_AGGREGATE");
    assertThat(order(id)).isEqualTo("2:OPEN:1");
  }

  @Test
  void underRetryTheCancelReloadsAndCommitsOnTopOfTheNewLine() throws Exception {
    String id = placeOrder(acme);
    GatedRepository repository = new GatedRepository();
    OrderUseCases slow =
        gated(
            repository,
            new OrderUseCases.Policies(
                ConflictPolicy.fail(), ConflictPolicy.retry(3, Duration.ofMillis(10))));

    Future<UseCaseResult<OrderCancelled>> cancel =
        threads.submit(
            () -> useCases.run(slow.cancelOrder(), context(acme), new CancelOrder(id, "too slow")));
    assertThat(repository.loaded.await(20, TimeUnit.SECONDS)).isTrue();
    committed(useCases.run(orders.addLine(), context(acme), new AddLine(id, "BOLT", 2, 50)));
    repository.proceed.countDown();

    OrderCancelled event = committed(cancel.get(20, TimeUnit.SECONDS));
    assertThat(event.lineCount()).isEqualTo(1); // it saw the line the other use case added
    assertThat(order(id)).isEqualTo("3:CANCELLED:1"); // both changes, version 3
  }

  @Test
  void twoConcurrentLineAdditionsBothLandUnderRetryAndTheVersionCountsBoth() throws Exception {
    String id = placeOrder(acme);
    committed(useCases.run(orders.addLine(), context(acme), new AddLine(id, "FIRST", 1, 10)));
    GatedRepository repository = new GatedRepository();
    OrderUseCases slow =
        gated(
            repository,
            new OrderUseCases.Policies(
                ConflictPolicy.retry(3, Duration.ofMillis(10)), ConflictPolicy.fail()));

    Future<UseCaseResult<OrderLineAdded>> a =
        threads.submit(
            () -> useCases.run(slow.addLine(), context(acme), new AddLine(id, "FROM-A", 1, 10)));
    assertThat(repository.loaded.await(20, TimeUnit.SECONDS)).isTrue();
    committed(useCases.run(orders.addLine(), context(acme), new AddLine(id, "FROM-B", 1, 10)));
    repository.proceed.countDown();

    OrderLineAdded event = committed(a.get(20, TimeUnit.SECONDS));
    assertThat(event.lineCount()).isEqualTo(3);
    assertThat(order(id)).isEqualTo("4:OPEN:3"); // placed, FIRST, FROM-B, FROM-A
    assertThat(pg.query("SELECT string_agg(sku, ',' ORDER BY position) FROM order_lines"))
        .isEqualTo("FIRST,FROM-B,FROM-A");
  }

  @Test
  void theSameConflictWithoutRetryLeavesTheOtherUseCasesLineAloneAndNoExtraOutboxRows()
      throws Exception {
    String id = placeOrder(acme);
    GatedRepository repository = new GatedRepository();
    OrderUseCases slow =
        gated(repository, new OrderUseCases.Policies(ConflictPolicy.fail(), ConflictPolicy.fail()));

    Future<UseCaseResult<OrderLineAdded>> a =
        threads.submit(
            () -> useCases.run(slow.addLine(), context(acme), new AddLine(id, "FROM-A", 1, 10)));
    assertThat(repository.loaded.await(20, TimeUnit.SECONDS)).isTrue();
    committed(useCases.run(orders.addLine(), context(acme), new AddLine(id, "FROM-B", 1, 10)));
    int outbox = count("SELECT count(*) FROM outbox_messages");
    repository.proceed.countDown();

    assertThat(failed(a.get(20, TimeUnit.SECONDS)).code()).isEqualTo("STALE_AGGREGATE");
    assertThat(pg.query("SELECT string_agg(sku, ',' ORDER BY position) FROM order_lines"))
        .isEqualTo("FROM-B");
    assertThat(count("SELECT count(*) FROM outbox_messages")).isEqualTo(outbox);
  }
}
