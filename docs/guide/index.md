<!-- types: App, AppBuilder, Module, UseCases, Routes, JettyHttpModule, DatabaseModule, OutboxModule, UseCaseModule, Launch, ContextResolver, FlowCatalystModule, ClientId -->
# FlowCatalyst application framework

A Java 25 framework for **business applications** that run next to the FlowCatalyst platform:
modules and a start-up lifecycle, typed configuration, PostgreSQL with migrations, a use-case
write path with optimistic locking and a transactional outbox, checked SQL, a small HTTP layer on
Jetty, locks, and a FlowCatalyst platform module: the application's service-account client, the
sync of its definitions (event types, roles, subscriptions, scheduled jobs) and receivers for the
dispatch jobs and scheduled-job firings the platform delivers to it.

It is for a developer, or an AI agent, building one service on top of it. You do not need to have
read the framework's code. The pages here say how to use it; the specifications are the
[contracts](reference/contracts.md), which are written before the code.

**Read [STATUS.md](STATUS.md) first.** The framework is pre-release: nothing is published yet,
there is no UI module, and there are several smaller gaps. This guide
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
| [Per-client secrets](secrets.md) | Resolving a client's credential from a reference: `file` and `env` stores, caching, the threat model |
| [Background and metrics](background-and-metrics.md) | Managed loops (lock-gated, health-visible), counters, gauges, timers, the Prometheus endpoint |
| [HTTP](http.md) | Routes, validation, errors, middleware, Datastar, the Jetty module |
| [HTTP client](http-client.md) | Calling other services: a client per service, auth, retries, `Context` propagation |
| [Rate limiting](rate-limiting.md) | 429 with `Retry-After`: limiters in memory or Redis, choosing a safe key |
| [The FlowCatalyst module](flowcatalyst-module.md) | The platform client, definition sync, how the outbox is delivered, health |
| [Receivers](receivers.md) | Handling dispatch jobs and scheduled-job firings the platform delivers (and switching the routes off) |
| [Migrating the ingest control plane](migrating-the-ingest-control-plane.md) | The first application's operator API and pages, step by step: typed operations, a `web/` SPA, sessions, the strict CSP, tests, open gaps and decisions |
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
        .module(new FlowCatalystModule())
        .module(new OrdersModule())
        .module(new OrdersHttpModule())
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
it contributes its migrations and provides its use cases. `FlowCatalystModule` holds the
application's service-account credentials, syncs its definitions and mounts the receivers; set
`flowcatalyst.enabled: false` to run without a platform. `JettyHttpModule` is listed last on
purpose: it opens the port in its `started` hook, so the port opens only once every other module has
started ([why](http.md#wiring-it-up)). Behaviour is a *use case*, run with a `Context` that says
which client is acting (the **client code**, such as `acme`, in a
[`ClientId`](context.md#what-goes-in-a-clientid)):

```java
UseCaseResult<OrderEvents.OrderPlaced> placed =
    useCases.run(orders.placeOrder(), context, new PlaceOrder("Ada Lovelace"));
```

The run validates, authorizes, loads, decides, and then writes the aggregate, the domain event and
an audit row in **one transaction**. The result is `Committed` or `Failed`; expected outcomes never
throw. An HTTP route calls it like this. `request.context()` only works if your application
provides a [`ContextResolver`](http.md#context-and-authentication): list `AuthModule` for the real
one ([Sessions and login](sessions-and-login.md)), or, as here in a first example, provide a
placeholder that treats every request as one fixed user. The route is registered in `boot`:

```java
// in register(AppBuilder app):
app.provide(
    ContextResolver.class,
    lookup -> request -> Context.of(new ClientId("acme")).withPrincipal(new PrincipalId("usr_demo")));

// in boot(App app), with Routes routes = app.get(Routes.class).forModule(id()):
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
platform's outbox processor reads that table and does, which needs a platform and a processor
pointed at your database ([how](flowcatalyst-module.md#how-the-outbox-gets-delivered)). Listing
`FlowCatalystModule` gives the application its credentials, the definition sync and the receivers;
it does not move the outbox rows.

## Conventions you will see everywhere

- **Values are records.** No Lombok, no mutable beans.
- **Expected outcomes are sealed result types**, handled with an exhaustive `switch`. Exceptions
  are for programming errors and infrastructure failure.
- **Nullness is checked at build time in the framework** (JSpecify `@NullMarked`, Error Prone and
  NullAway), and the annotations are there for your tools. Your own build checks it only if you
  add the same compiler setup, which is optional ([Getting started](getting-started.md#optional-error-prone-and-nullaway)).
- **No reflection, no annotation scanning, no classpath scanning** for wiring.
- **Blocking code on virtual threads.** No reactive APIs.
- **Time comes from an injected `InstantSource`**, never `Instant.now()` in library code.
- **Logging goes through SLF4J.** The libraries bring no logging backend; your application does.

The design rules behind these are in [`framework-plumbing.md`](../framework-plumbing.md).
