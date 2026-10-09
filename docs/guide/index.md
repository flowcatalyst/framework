<!-- types: App, AppBuilder, Module, UseCases, Routes, JettyHttpModule, DatabaseModule, OutboxModule, UseCaseModule, Launch -->
# FlowCatalyst application framework

A Java 25 framework for **business applications** that run next to the FlowCatalyst platform:
modules and a start-up lifecycle, typed configuration, PostgreSQL with migrations, a use-case
write path with optimistic locking and a transactional outbox, checked SQL, a small HTTP layer on
Jetty, locks, and a client for the platform's definition sync.

It is for a developer, or an AI agent, building one service on top of it. You do not need to have
read the framework's code. The pages here say how to use it; the specifications are the
[contracts](reference/contracts.md), which are written before the code.

**Read [STATUS.md](STATUS.md) first.** The framework is pre-release: there are no published
artifacts and no BOM, no login or sessions, no UI module, and several smaller gaps. This guide
describes what is built and says plainly where something is not.

## Pages

| Page | Read it for |
|---|---|
| [Getting started](getting-started.md) | Prerequisites, depending on the framework today, the compiler setup |
| [Modules and lifecycle](modules-and-lifecycle.md) | `Module`, `AppBuilder`, services, drivers, named instances, commands |
| [Config](config.md) | Declaring settings, sources and their order, environment variables, secrets |
| [Context](context.md) | `Context`, client, principal, correlation id |
| [Locks](locks.md) | Leases and fencing tokens, `withLock`, the three drivers |
| [The write path](write-path.md) | Aggregates, repositories, use cases, plans, optimistic locking, the outbox |
| [Checked SQL](checked-sql.md) | Queries in `.sql` files, checked at build time |
| [HTTP](http.md) | Routes, validation, errors, middleware, Datastar, the Jetty module |
| [The FlowCatalyst module](flowcatalyst-module.md) | The platform client and definition sync |
| [Receivers](receivers.md) | Handling dispatch jobs and scheduled-job firings the platform delivers |
| [Testing](testing.md) | Testing an application today, and the known limitation |
| [STATUS](STATUS.md) | What exists, what is planned, known gaps |
| [Contracts](reference/contracts.md) | The specifications |

## One-screen tour

An application is a list of modules, written in code. Nothing is discovered by scanning.

```java
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    switch (App.builder()
        .module(new DatabaseModule())
        .module(new OutboxModule())
        .module(new UseCaseModule())
        .module(new OrdersModule())
        .module(new JettyHttpModule())
        .shutdownHook(true)
        .launch(args)) {
      case Launch.Ran ran -> System.exit(ran.exitCode());
      case Launch.Started started -> { }
    }
  }
}
```

`OrdersModule` is your own module (it is [`examples/orders-demo`](https://github.com/flowcatalyst/framework/blob/main/examples/orders-demo/src/main/java/io/flowcatalyst/framework/examples/ordersdemo/OrdersModule.java)):
it contributes its migrations and provides its use cases. Behaviour is a *use case*, run with a
`Context` that says which client is acting:

```java
UseCaseResult<OrderEvents.OrderPlaced> placed =
    useCases.run(orders.placeOrder(), context, new PlaceOrder("Ada Lovelace"));
```

The run validates, authorizes, loads, decides, and then writes the aggregate, the domain event and
an audit row in **one transaction**. The result is `Committed` or `Failed`; expected outcomes never
throw. An HTTP route calls it like this (`request.context()` needs a
[`ContextResolver`](http.md#context-and-authentication)):

```java
routes.post(
    "/orders",
    request -> {
      PlaceOrderBody body = request.json(PlaceOrderBody.V);
      return switch (useCases.run(
          orders.placeOrder(), request.context(), new PlaceOrder(body.customer()))) {
        case UseCaseResult.Committed<OrderPlaced> ok ->
            Response.json(201, Map.of("orderId", ok.event().orderId()));
        case UseCaseResult.Failed<OrderPlaced> failed -> HttpErrors.of(failed.error());
      };
    },
    RouteOptions.requiresContext());
```

The event row lands in the `outbox_messages` table. The framework does not deliver it: the
platform's outbox processor reads that table and does (see
[The FlowCatalyst module](flowcatalyst-module.md)).

## Conventions you will see everywhere

- **Values are records.** No Lombok, no mutable beans.
- **Expected outcomes are sealed result types**, handled with an exhaustive `switch`. Exceptions
  are for programming errors and infrastructure failure.
- **Nullness is checked at build time** (JSpecify `@NullMarked`, Error Prone and NullAway).
- **No reflection, no annotation scanning, no classpath scanning** for wiring.
- **Blocking code on virtual threads.** No reactive APIs.
- **Time comes from an injected `InstantSource`**, never `Instant.now()` in library code.
- **Logging goes through SLF4J.** The libraries bring no logging backend; your application does.

The design rules behind these are in [`framework-plumbing.md`](../framework-plumbing.md).
