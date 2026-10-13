<!-- types: TestClient, TestResponse, PostgresFixture, RedisFixture, FakePlatform, LockStoreContract, AdjustableClock, MemoryLockDriver, AppBuilder, App, MonotonicClock, AuthTesting, TestOidcProvider -->
# Testing

The framework tests itself with JUnit 5 and AssertJ against **real PostgreSQL 18 and Redis**
(Testcontainers, one container per test class), and expects you to test your application the
same way. The helpers come from two places: `TestClient` is in `framework-http`'s ordinary jar, and
the rest are published as test-jars.

> **Replacing a service:** `AppBuilder.override(Class, instance)` swaps what a module provides for
> a fake, in **test mode only** (`APP_ENV=test`). See [Replacing a service](#replacing-a-service).

## What you can use today

| Helper | Artifact | Gives you |
|---|---|---|
| `TestClient`, `TestResponse` | `framework-http` (main) | the full HTTP pipeline in-process, no socket |
| `PostgresFixture` | test-jar of `framework-database` | a real PostgreSQL 18 for one test class |
| `RedisFixture` | test-jar of `framework-redis` | a real Redis for one test class |
| `FakePlatform` | test-jar of `framework-flowcatalyst` | an in-process stand-in for the platform |
| `LockStoreContract`, `AdjustableClock` | test-jar of `framework-locks` | the lock driver contract suite; a movable monotonic clock |

Dependencies for a test-jar (the BOM import manages the version; see
[Getting started](getting-started.md#2-import-framework-bom)):

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

- `builder.environment(Map)` replaces the process environment, so tests never read the real one
  ([Config](config.md#environment-variable-names)). It also supplies `APP_ENV`: when the map has no
  `APP_ENV`, the environment is `local`, and `local` (like `test`) **reads
  `config/secrets.local.yaml`** from the config directory. With
  `configDirectory(Path.of("config"))` a test therefore reads the developer's own secrets file
  unless the map sets `APP_ENV` to something else (a name for which that file is ignored, such as
  `ci`), or the test points `configDirectory` at a directory of its own. Include an `APP_ENV` entry in
  the map to make the config the same on every machine.
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

**Signed-in requests:** with `AuthModule` listed, `AuthTesting.of(app).cookie(principal)` gives a
real session cookie in the configured store, and `TestOidcProvider` (the `framework-auth` test-jar)
mints platform access tokens and plays the platform for the login flow. See
[Sessions and login: testing](sessions-and-login.md#testing).

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
`respond` to script a sync answer. For scheduled jobs it also records the instance callbacks a job
makes: `instanceCalls("complete")` and `instanceCalls("log")`, `awaitInstanceCalls(action, count,
timeoutMillis)` to wait for them, and `failInstanceCalls(action, times, status)` to make the next
calls fail. It does **not** simulate event ingestion, dispatch jobs or the outbox processor. Testing
the receivers is shown on the [Receivers](receivers.md#testing-receivers) page.

## Testing locks

For your own code, configure a `memory` lock instance in test config; it is exclusive only
within the process, which is what a test wants. To test a *driver*, extend `LockStoreContract`
(supply `newStore()`, `letTimePass(Duration)` and `expireNow(LockKey)`); that is how the three
framework drivers are tested, and a custom driver is done when it passes the suite. `AdjustableClock`
is a `MonotonicClock` that follows real time and can be advanced at once.

## Replacing a service

`AppBuilder.override(type, instance)` replaces the service a module provides for `type`. The
module's provider is not run, and every lookup of `type` gets your instance, including other
providers that ask for it. From `framework-core`'s `OverrideTest`:

```java
FakeMailer fake = new FakeMailer();
try (App app = builder("test").module(mailModule()).override(Mailer.class, fake).start()) {
  assertThat(app.get(Mailer.class)).isSameAs(fake);
  // Another provider that asked for Mailer got the override too.
  assertThat(app.get(Notifier.class).mailer()).isSameAs(fake);
  assertThat(providerRuns).hasValue(0);
}
```

where `builder("test")` is `App.builder().configDirectory(dir).environment(Map.of("APP_ENV", "test"))`.

- **Test mode is `APP_ENV=test`** in the environment the builder reads (the map given to
  `environment(...)`, else the process environment). With any override in another environment,
  including the default `local`, `start()` (and `launch`) fails with a `StartupException` before any
  module is registered: an override can never reach a deployed application. `test`, like `local`,
  also reads `config/secrets.local.yaml` from the config directory (see the note on `APP_ENV` above).
- Override the **type modules look up** (`Mailer`, not `SmtpMailer`): overriding a type no module
  provides fails start-up, because it would otherwise change nothing. The framework's own types
  (`Config`, `Drivers`, `InstantSource`, `Ids`, `MonotonicClock`) cannot be overridden; use
  `instantSource(...)`, `monotonicClock(...)` and `environment(...)`.
- Overriding one type twice, or with an instance of the wrong type, throws
  `IllegalArgumentException` at once.
- The application does **not** close an override at shutdown; the test owns it. Each override is
  logged at WARN.
- Commands started with `launch` see the overrides too.

Driver names are still unique per capability, so a framework *driver* cannot be replaced by name,
and the framework's registries (`Locks`, `Databases`, `RedisConnections`) have package-private
constructors, so they cannot be built around a fake either; configure a test instance instead (a
`memory` lock instance). Other techniques still work and are often simpler:
construct the object under test yourself (`OrdersDemoTest` builds `new OrderUseCases(...)` with a
different repository and conflict policy, taking `Databases`, `Events` and `Ids` from the running
app), start fewer modules, or use the real thing in a container.
