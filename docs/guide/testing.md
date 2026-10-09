<!-- types: TestClient, TestResponse, PostgresFixture, RedisFixture, FakePlatform, LockStoreContract, AdjustableClock, MemoryLockDriver, AppBuilder, App, MonotonicClock -->
# Testing

The framework tests itself with JUnit 5 and AssertJ against **real PostgreSQL 18 and Redis**
(Testcontainers, one container per test class), and expects you to test your application the
same way. The test helpers are published as test-jars.

> **Known limitation: services cannot be replaced in tests.** Each service type has exactly one
> provider, and a test cannot swap one for a fake without changing your module. A planned
> `AppBuilder.override(Class, instance)`, allowed only in test mode, will fix this (plan item MA-2,
> see [STATUS](STATUS.md)). [Workarounds](#the-known-limitation-services-cannot-be-replaced) are
> below.

## What you can use today

| Helper | Artifact | Gives you |
|---|---|---|
| `TestClient`, `TestResponse` | `framework-http` (main) | the full HTTP pipeline in-process, no socket |
| `PostgresFixture` | test-jar of `framework-database` | a real PostgreSQL 18 for one test class |
| `RedisFixture` | test-jar of `framework-redis` | a real Redis for one test class |
| `FakePlatform` | test-jar of `framework-flowcatalyst` | an in-process stand-in for the platform |
| `LockStoreContract`, `AdjustableClock` | test-jar of `framework-locks` | the lock driver contract suite; a movable monotonic clock |

Dependencies for a test-jar (the BOM import manages the version; see
[Getting started](getting-started.md#2-import-framework-parent-as-a-bom)):

```xml
<dependency>
  <groupId>io.flowcatalyst.framework</groupId>
  <artifactId>framework-database</artifactId>
  <type>test-jar</type>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>testcontainers-postgresql</artifactId>
  <scope>test</scope>
</dependency>
```

A test needs Docker. This combination (the test-jar plus `testcontainers-postgresql`) was resolved
and run from a scratch application.

## Testing with a real application

Start the real `App` against a container, pointing it at your `config/` and overriding the
connection through the environment. This is `examples/orders-demo`'s `OrdersDemoTest`:

```java
static PostgresFixture pg;

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
}

@AfterEach
void stop() {
  app.close();
}
```

- `builder.environment(Map)` replaces the process environment, so tests never read the real one and
  config is deterministic ([Config](config.md#environment-variable-names)).
- `builder.instantSource(() -> NOW)` fixes the clock; event times and audit rows follow it.
  `builder.monotonicClock(...)` does the same for locks and timeouts.
- `pg.reset()` drops and recreates the `public` schema, so each test starts clean and migrations
  re-run on `start()`.
- `PostgresFixture` also has `jdbcUrl(database)`, `execute(sql)`, `createDatabase(name)` and
  `query(sql)` (a single value), which tests use to assert on rows directly, for instance on
  `outbox_messages`.
- Redis: `RedisFixture.start()`, `redis.url()`, `redis.close()`, as `examples/locks-demo`'s
  `LocksDemoTest` does.

## Testing use cases

Run the real use case against the container and assert on the result and on the rows. The write
path example tests, among others, that a rejected command writes nothing, that another client
cannot see or change an order, that a database failure while writing lines rolls back the header and
the outbox rows, and that conflicts behave under each `ConflictPolicy` (a repository subclass that
pauses after its first load lets a test commit a competing change in between). Read
`OrdersDemoTest`; it is the best reference for testing the [write path](write-path.md).

```java
UseCaseResult<OrderPlaced> r = useCases.run(orders.placeOrder(), context(acme), new PlaceOrder("Ada"));
assertThat(r).isInstanceOf(UseCaseResult.Committed.class);
assertThat(count("SELECT count(*) FROM outbox_messages WHERE type = 'EVENT'")).isEqualTo(1);
```

## Testing HTTP handlers

Without a socket, with `TestClient`:

```java
Routes routes = new Routes();
routes.get("/ping", r -> Response.text(200, "pong"));
TestResponse response = TestClient.of(routes).get("/ping").header("X-Correlation-Id", "t-1").send();
```

`TestClient.of(routes, resolver)` takes a `ContextResolver`; so a test can resolve every request to
a chosen `Context`:

```java
TestClient client = TestClient.of(routes, r -> Context.of(new ClientId("acme")));
```

Calls: `get`, `post`, `put`, `patch`, `delete`, `request(Method, target)`, then `.header(...)`,
`.body(text)`, `.jsonBody(json-or-object)`, `.send()`. `TestResponse` has `status()`,
`header(name)`, `body()`, `json(Class)` and `tree()`. `TestClient.of` compiles the routes, so
duplicate routes and routes needing a missing `ContextResolver` fail there as `StartupException`.

For a real socket on a free port (virtual-thread behaviour, body limits, health endpoints,
graceful shutdown) start the app with `JettyHttpModule` and `HTTP_PORT=0`, and read the port from
`app.get(HttpServer.class).port()`; see `framework-http-jetty`'s tests.

## The fake platform

`FakePlatform.start()` runs a local HTTP server that accepts one client id and secret
(`FakePlatform.CLIENT_ID`, `FakePlatform.CLIENT_SECRET`), serves a client-credentials token
endpoint and the application sync endpoints, and records every request:

```java
try (FakePlatform platform = FakePlatform.start()) {
  Map<String, String> env =
      Map.of(
          "FLOWCATALYST_BASE_URL", platform.baseUrl(),
          "FLOWCATALYST_APPLICATION", "orders",
          "FLOWCATALYST_CLIENT_ID", FakePlatform.CLIENT_ID,
          "FLOWCATALYST_CLIENT_SECRET", FakePlatform.CLIENT_SECRET);
  // build the app with .environment(env), run sync, then:
  platform.syncedResources();   // for example [roles, event-types, connections]
}
```

Also: `requests()`, `syncRequests()` (each `Recorded` has `json()` and `isSyncOf(resource)`),
`tokenRequests()`, `clearRequests()`, `failTokens(status)` (make the token endpoint fail), and
`respond` to script a sync answer. It does **not** simulate event ingestion, dispatch jobs or the
outbox processor.

## Testing locks

For your own code, configure a `memory` lock instance in test config; it is exclusive only
within the process, which is what a test wants. To test a *driver*, extend `LockStoreContract`
(supply `newStore()`, `letTimePass(Duration)` and `expireNow(LockKey)`); that is how the three
framework drivers are tested, and a custom driver is done when it passes the suite. `AdjustableClock`
is a `MonotonicClock` that follows real time and can be advanced at once.

## The known limitation: services cannot be replaced

`app.provide(SomeService.class, provider)` is the only way to supply a service and a second
provider for the type fails start-up; framework driver names are also unique per capability. So you
cannot, for example, start the full app with a fake `Mailer`, or a `LockStore` that fails on demand,
by replacing what a module provides.

What works today:

1. **Construct the object under test yourself.** Your use cases, repositories and handlers are plain
   classes whose dependencies come in through the constructor. `OrdersModule` only wires them, and
   `OrdersDemoTest` builds `new OrderUseCases(repository, db, events, ids, policies)` with a
   different repository and a different conflict policy, taking the real `Databases`, `Events` and
   `Ids` from the running app (`app.get(...)`).
2. **Put the seam in your own module.** Provide a small interface and decide the implementation from
   a setting declared by your module (for example `mail.driver`), then set it from the test's
   `environment(Map)`.
3. **Start fewer modules.** A test that does not need HTTP or the platform simply leaves those
   modules out of the builder.
4. **Use the real thing in a container.** That is what the framework does; it is slower but needs
   no fakes.

When `AppBuilder.override` exists this page will change. Track it in [STATUS](STATUS.md).
