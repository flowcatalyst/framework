<!-- types: FlowCatalystModule, PlatformClient, PlatformException, Definitions, Definition, DefinitionCatalog, DefinitionSync, SyncReport, CategoryResult, SyncException, SyncCommand, Command, Launch, FakePlatform -->
# The FlowCatalyst module

`framework-flowcatalyst` connects the application to the FlowCatalyst platform: it holds the
application's service-account credentials, and **syncs the application's definitions** (event
types, roles and permissions, connections, dispatch pools, subscriptions, processes, scheduled jobs)
to the platform at deploy time. It is the only framework module that imports the FlowCatalyst Java
SDK, and nothing else depends on it.

Be clear about what it is *not*, today:

| Not built | Where |
|---|---|
| Receivers: HTTP endpoints for dispatch jobs and scheduled-job firings, signature checks, ack / defer / reject | M4b, not started |
| A health check on the outbox backlog (`outbox`) | planned in M4a, not built |
| Dispatch-job rows in the outbox writer | MA-4 |
| Per-client sync, principals sync, OpenAPI sync | not exposed |
| Docs sync (`Definitions.docs(...)`) | accepted, skipped (the Java SDK cannot sync docs yet) |
| Wrapper methods for platform APIs (events, dispatch jobs, users) | not built; `PlatformClient` gives you the token only |

See [STATUS](STATUS.md). The contract, with the as-built notes in section 4a, is
[contracts/flowcatalyst.md](../contracts/flowcatalyst.md).

## Settings

List `FlowCatalystModule` among your modules and configure it:

```yaml
flowcatalyst:
  base-url: https://platform.example.com
  application: orders
  client-id: orders-service
  # client-secret: from the environment, FLOWCATALYST_CLIENT_SECRET
```

| Key | Type | Default | Meaning |
|---|---|---|---|
| `flowcatalyst.enabled` | boolean | `true` | `false` makes the module inert: no setting below is needed, the catalog is empty, `PlatformClient.isEnabled()` is false and every other method throws |
| `flowcatalyst.base-url` | URI | required while enabled | absolute `http(s)` URL of the platform |
| `flowcatalyst.application` | string | required while enabled | this application's code on the platform, `[a-z][a-z0-9-]*` |
| `flowcatalyst.client-id` | string | required while enabled | OAuth client id of the application's service account |
| `flowcatalyst.client-secret` | secret | required while enabled | its secret. Use `FLOWCATALYST_CLIENT_SECRET` or `config/secrets.local.yaml` ([Config](config.md#secrets)) |
| `flowcatalyst.timeout` | duration | `30s` | timeout of calls to the platform |
| `flowcatalyst.subscription-target-base-url` | URI | none | what subscription targets given as a path (`/webhooks/billing`) resolve against |
| `flowcatalyst.sync.on-boot` | boolean | `false` | also sync definitions in `started`; failures are logged, never fatal |

The service account (client id and secret) and the application code must already exist on the
platform; the framework does not create them. When the module is enabled, missing or malformed
settings fail start-up with one `StartupException` naming every one.

The platform is **not on the request path**. If it cannot be reached at start-up the application
still starts; the failure is logged and the `flowcatalyst` health check reports it. That check
fetches a token with the service-account credentials; it uses the SDK's cached token, so it can
stay `Up` briefly after the platform goes down.

## `PlatformClient`

A provided service wrapping the SDK client built with client credentials. Today it offers
`isEnabled()`, `application()` (an `ApplicationCode`), `verifyCredentials()`, `accessToken()`
(a bearer token, cached by the SDK; never log it) and `clearToken()`. Failures are the unchecked
`PlatformException`. SDK types do not appear in its API.

## Declaring definitions

A module contributes definitions in `register` with `Definitions.in(app)`. Nothing is checked at
that point (settings are not readable yet); everything is validated together at start-up and each
problem names the contributing module.

```java
@Override
public void register(AppBuilder app) {
  Definitions.in(app)
      .eventType("orders:sales:order:placed", "Order placed", "A customer placed an order.")
      .permission("orders:sales:order:read", "Read orders")
      .role("order-clerk", "orders:sales:order:read")
      .connection("main", "Main")
      .subscription(
          Definition.Subscription.of(
                  "billing-on-placed", "Bill placed orders", "/webhooks/billing", "orders:sales:order:placed")
              .viaConnection("main"))
      .scheduledJob(Definition.ScheduledJob.of("nightly-export", "Nightly export", "0 0 2 * * *").tracking(true));
}
```

The methods are `eventType`, `permission`, `role` (a name and permission codes, or a
`Definition.Role`), `subscription`, `connection`, `dispatchPool`, `scheduledJob`, `process` and
`docs`. The `Definition` records have `of(...)` factories and `with...` copies (for example
`Subscription.viaConnection`, `viaSharedConnection`, `inPool`, `withMode`, `ScheduledJob.tracking`,
`inZone`).

Validation, which is **stricter than the platform's** (owner may relax it): lowercase
`[a-z0-9][a-z0-9_-]*` segments; event type, permission and process codes start with
`flowcatalyst.application`; role names carry no `app:` prefix (the platform adds it); cron
expressions have **6 fields, seconds first**; subscriptions name at least one event type and a
target that is an absolute URL, or a path when `subscription-target-base-url` is set; codes are
unique across modules. Permissions travel only inside roles: a declared permission that no role
grants is logged as a warning.

Removing a definition from code does not delete it on the platform; deletions are an operator action.

## Syncing: the `sync` command

Sync is a **deploy-time** action, not a start-up one. The module claims the command `sync`, so your
`main` should use `launch`:

```java
switch (App.builder()
    .module(new DatabaseModule())
    .module(new OutboxModule())
    .module(new UseCaseModule())
    .module(new FlowCatalystModule())
    .module(new OrdersModule())
    .module(new JettyHttpModule())
    .shutdownHook(true)
    .launch(args)) {
  case Launch.Ran ran -> System.exit(ran.exitCode());
  case Launch.Started started -> { }
}
```

```
java -jar app.jar sync --dry-run     # validate and report; contacts nobody
java -jar app.jar sync               # push to the platform
```

- Exit codes: `0` success, `1` a category failed, `2` bad argument or module disabled.
- `--dry-run` **never contacts the platform** (neither the SDK nor the platform has a preview):
  it validates and prints `WOULD_SYNC` with the codes.
- The command runs after services are built and **before any `boot`**. That means the database
  module has connected and **applied migrations** by then, so the deploy needs database access, and
  no server starts.
- Each category is synced by its own SDK call so one failure does not stop the others. Order:
  roles, event types, connections, dispatch pools, subscriptions, processes, scheduled jobs, docs.
  It prints one line per category, for example `ROLES SYNCED sent=2 created=1 updated=1 deleted=0`.
- Programmatically: `app.get(DefinitionSync.class).run(DefinitionSync.Mode.APPLY)` (or
  `DRY_RUN`) returns a `SyncReport` of `CategoryResult`s, and throws `SyncException` (carrying the
  report) if any category failed, after attempting all of them. It is idempotent.

## The outbox: the platform delivers it

The framework's [write path](write-path.md#6-the-outbox) writes event and audit rows to
`outbox_messages` in your database. **It does not deliver them.** Delivery is the platform's outbox
processor, configured with the application's own service-account credentials and pointed at your
database; it posts events to the platform's batch endpoints, and the platform then fans them out to
subscriptions. Consequences:

- An application that wants events, dispatch jobs, subscriptions or scheduled jobs needs a platform.
  There is no local fan-out, subscription or job dispatch in the framework.
- Operating the processor (its settings, table name, database) is a platform concern.
- A health check on the oldest pending row's age and a generated docs page describing the processor
  are planned (M4a) but not built. Today nothing in the framework tells you the backlog is growing.

## Receivers (not built)

Planned (M4b): `POST /fc/dispatch` and `POST /fc/scheduled-jobs` routes with HMAC signature checks,
handler registration by event type, ack / defer / reject results, and lock-based overlap protection
for scheduled jobs. Until then, an application that wants to receive deliveries writes its own
HTTP routes (see [HTTP](http.md)) and its own signature check.

## Testing

`FakePlatform` is an in-process stand-in for the platform (a token endpoint and the sync endpoints),
published in the `framework-flowcatalyst` test-jar. See [Testing](testing.md#the-fake-platform).
