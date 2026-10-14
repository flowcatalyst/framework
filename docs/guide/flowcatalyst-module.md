<!-- types: FlowCatalystModule, Receivers, PlatformClient, PlatformException, Definitions, Definition, DefinitionCatalog, DefinitionSync, SyncReport, CategoryResult, SyncException, SyncCommand, Command, Launch, FakePlatform -->
# The FlowCatalyst module

`framework-flowcatalyst` connects the application to the FlowCatalyst platform: it holds the
application's service-account credentials, and **syncs the application's definitions** (event
types, roles and permissions, connections, dispatch pools, subscriptions, processes, scheduled jobs)
to the platform at deploy time. It is the only framework module that imports the FlowCatalyst Java
SDK, and nothing else depends on it.

Be clear about what it is *not*, today:

| Not built | Where |
|---|---|
| A framework call that creates a dispatch job outside a use case | see [Creating a dispatch job](#creating-a-dispatch-job) |
| Per-client sync, principals sync, OpenAPI sync | not exposed |
| Docs sync (`Definitions.docs(...)`) | accepted, skipped (the Java SDK cannot sync docs yet) |
| Wrapper methods for platform APIs (events, dispatch jobs, users) | not built; `PlatformClient` gives you the token, and [Calling a platform API yourself](#calling-a-platform-api-yourself) shows the HTTP client with it |

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
| `flowcatalyst.outbox.stale-after` | duration | `5m` | the `outbox` health check is down when the oldest undelivered row is older than this; must be positive |
| `flowcatalyst.receivers.*` | | | the receivers' settings, listed on the [Receivers](receivers.md#settings) page, including `enabled` (default true) which can switch the receiver routes off |

The service account (client id and secret) and the application code must already exist on the
platform; the framework does not create them. When the module is enabled, missing or malformed
settings fail start-up with one `StartupException` naming every one.

The platform is **not on the request path**. If it cannot be reached at start-up the application
still starts; the failure is logged and the `flowcatalyst` health check reports it. That check
fetches a token with the service-account credentials; it uses the SDK's cached token, so it can
stay `Up` briefly after the platform goes down.

**Readiness does not depend on it by default.** The `flowcatalyst` check is `Down` when fetching a
token fails: the platform refuses the credentials or cannot be reached, and the cached token (the
SDK refreshes it about a minute before it expires) has run out. While a receiver has handlers it is
also `Down` when the token does not name the application's service principal, because then no
handler can run ([who runs the handler](receivers.md#who-runs-the-handler)). Both the `flowcatalyst`
check and the `outbox` check below are **informational**
([criticality](modules-and-lifecycle.md#critical-and-informational-checks)): `/health/ready`
reports them (top-level `DEGRADED`, still `200`) but they do not make it answer `503`, so a
platform outage does not take every replica out of rotation. To make either decide readiness:

```yaml
app:
  health:
    critical: [flowcatalyst, outbox]
```

## `PlatformClient`

A provided service wrapping the SDK client built with client credentials. Today it offers
`isEnabled()`, `application()` (an `ApplicationCode`), `verifyCredentials()`, `accessToken()`
(a bearer token, cached by the SDK; never log it), `clearToken()` and `servicePrincipal()` (the
`sub` of that token when its `type` is `SERVICE`: the principal the receivers run handlers as; read
once per token). The receivers use two more,
`completeJobInstance(instanceId, Completion, result)` and `logJobInstance(instanceId, level,
message)`, to report a scheduled-job instance. Failures are the unchecked `PlatformException`. SDK types do not appear in its API.

## Calling a platform API yourself

`PlatformClient` has no wrapper for the platform's own APIs (events, dispatch jobs, users, ...).
Call them with the [HTTP client](http-client.md), authenticated with the application's token. From
`PlatformApiCallTest`:

```java
ServiceClient api =
    app.get(ServiceClients.class)
        .register(
            ServiceOptions.builder("platform", app.config().get(FlowCatalystModule.BASE_URL))
                .auth(Auth.bearer(platformClient::accessToken, platformClient::clearToken))
                .build());
```

- **Token:** `platform.accessToken()` is the application's service account (cached by the SDK;
  never log it), so the platform authorises the call by that account's roles. After a `401` the
  client calls `clearToken()` and sends the request once more with a fresh token.
- **Timeouts and retries** are the client's (`request-timeout`, retries for idempotent requests
  only); `flowcatalyst.timeout` covers only the module's own calls.

## Creating a dispatch job

A dispatch job is the platform's unit of delivery. Most are made by the platform itself, from an
event and a subscription. To create one from your application, add it to a use case's plan with
`plan.andDispatch(DispatchJob)`: it is written to the outbox in the same transaction and the
platform's outbox processor delivers it to `POST /api/dispatch-jobs/batch`. See
[the write path](write-path.md#dispatch-jobs).

- The service account the processor runs with needs **`platform:messaging:batch:dispatch-jobs-write`**,
  which the platform's `application-service` role grants.
- The receiver handles the delivered job like any other: the platform sets `X-Event-Type` to the
  job's `code`, so `receivers.onDispatch("<code>", ...)` receives it
  ([Receivers](receivers.md#dispatch-jobs)).

Outside a use case there is no framework call that creates a job; calling the endpoint yourself
works with the recipe above (`{"items": [ ... ]}`, at most 1000 items, the answer is
`{"results": [{"id", "status", "error?"}]}` in item order). Send `metadata` as an array of
`{"key", "value"}`: the platform refuses the whole batch for an object.

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
java -jar app.jar sync --dry-run     # validate and report; sends nothing to the platform
java -jar app.jar sync               # push to the platform
```

- Exit codes: `0` success, `1` a category failed, `2` bad argument or module disabled.
- `--dry-run` **never contacts the platform** (neither the SDK nor the platform has a preview):
  it validates and prints `WOULD_SYNC` with the codes. It is *not* free of side effects elsewhere:
  see the next point.
- The command runs after services are built and **before any `boot`**. Building services includes
  the database module, which connects and **applies migrations** to the default database. So even
  a dry run needs database access and **changes the schema** (new migrations are applied, and a
  checksum mismatch fails it). If the deploy step must not touch the database, set
  `database.instances.<name>.migrate: false` (or the environment variable
  `DATABASE_INSTANCES_<NAME>_MIGRATE=false`) for that run; the instance is still connected to, so
  it must be reachable. No server starts and no `boot` hook runs.
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
- Operating the processor is a platform concern, but it has to be pointed at **your** database;
  how is described next.
- The `outbox` health check (below) watches the backlog.

### How the outbox gets delivered

The processor is part of the platform repository (`github.com/flowcatalyst/flowcatalyst`, the Go
platform). There are two ways to run it; only one of them reads your application's database.

**`fcdev outbox`: the standalone poller for an application's own PostgreSQL.** `fcdev` is the
platform's developer binary (that repository's README installs it with
`curl -fsSL https://raw.githubusercontent.com/flowcatalyst/flowcatalyst/main/install.sh | sh` and
updates it with `fcdev upgrade`). Run it beside your application:

```
fcdev outbox \
  --source-db-url 'postgres://user:pass@db-host:5432/app' \
  --target-url https://platform.example.com \
  --client-id orders-service --client-secret "$FLOWCATALYST_CLIENT_SECRET"
```

Every setting is a flag or an environment variable (a flag wins; a `.env` file in the working
directory, or the file named by `--env-file`, is loaded without overriding the real environment):

| Flag | Environment variable | Meaning |
|---|---|---|
| `--source-db-url` | `FC_OUTBOX_SOURCE_DB_URL` | your application's PostgreSQL URL. **Required** |
| `--target-url` | `FC_OUTBOX_PLATFORM_URL` | the platform's base URL (default `http://localhost:8080`) |
| `--client-id` | `FC_OUTBOX_CLIENT_ID`, else `FLOWCATALYST_CLIENT_ID` | the application's service account (the one `flowcatalyst.client-id` names) |
| `--client-secret` | `FC_OUTBOX_CLIENT_SECRET`, else `FLOWCATALYST_CLIENT_SECRET` | its secret |
| `--token-url` | `FC_OUTBOX_TOKEN_URL` | token endpoint; default `<target-url>/oauth/token` |
| `--scope` | `FC_OUTBOX_SCOPE` | optional scope to narrow the token |
| `--auth-token` | `FC_OUTBOX_PLATFORM_AUTH_TOKEN` | a static bearer token, used only when no client id and secret are given |
| `--batch-size` | `FC_OUTBOX_BATCH_SIZE` | rows per poll (default 100) |
| `--max-in-flight` | `FC_OUTBOX_MAX_IN_FLIGHT` | cap on outstanding requests (default 1000) |
| `--poll-interval-ms` | `FC_OUTBOX_POLL_INTERVAL_MS` | wait between empty polls (default 1000) |

It mints and refreshes the token with the client-credentials grant, claims rows with `status = 0`
using `FOR UPDATE SKIP LOCKED`, posts them by row type (`EVENT` to `/api/events/batch`,
`DISPATCH_JOB` to `/api/dispatch-jobs/batch`, `AUDIT_LOG` to `/api/audit-logs/batch`), and
**deletes a row once the platform accepts it**, so the table holds only the backlog. At start-up it
runs `CREATE TABLE IF NOT EXISTS outbox_messages` and the same three indexes, under the same names,
that the framework's `OutboxModule` migration creates, so against a migrated database it changes
nothing. `fcdev outbox create-table --db-type pg --db-url ...` creates the table without running
the poller, for a database the framework did not migrate. Two pollers on one table never take the
same row (`SKIP LOCKED`), but a message group's order is enforced inside one process, so run one
poller per outbox.

**`fc-server` with `FC_OUTBOX_ENABLED=true`** (the platform's own server; it also needs
`FC_OUTBOX_PLATFORM_URL` and takes the other `FC_OUTBOX_*` variables listed in that repository's
`docs/environment-variables.md`) runs the same processor over the **platform's own database
pool** (or MongoDB with `FC_OUTBOX_BACKEND=mongo`). It does **not** read an application's separate
PostgreSQL. Use it only when the application's `outbox_messages` lives in the platform's database;
otherwise use `fcdev outbox`.

All of this was read from the platform source (`cmd/fcdev/outbox.go`,
`internal/server/subsystems.go`, `internal/outbox/postgres/postgres.go`, `internal/common/outbox.go`)
and its environment-variable reference; no framework application was run against a poller here.
The service account needs the batch-ingest permissions that the platform's `application-service`
role carries.

### The `outbox` health check

Added at `boot` when the module is enabled, a `Databases` service exists, and the **default**
database has an `outbox_messages` table (migrations have run by then); otherwise there is no
check, and an INFO line says why. It reports `Down` when the oldest undelivered row (status
`PENDING` or `IN_PROGRESS`; terminal failures are not a backlog) is **strictly older** than
`flowcatalyst.outbox.stale-after`, measured with the application's `InstantSource`. It runs two
`min(created_at)` queries and writes nothing. A failing lookup reports the database error.
The check is informational: a stale backlog is reported (`DEGRADED`) but does not make
`/health/ready` answer `503` unless `app.health.critical` lists `outbox`.

## Receivers

The module also mounts the receivers for dispatch jobs and scheduled-job firings
(`POST /fc/dispatch`, `POST /fc/scheduled-jobs`), with signature checks and ack / defer / reject
results. They have their own page: [Receivers](receivers.md), which also lists the
`flowcatalyst.receivers.*` settings. A receiver you register no handler for answers `404`, and
`flowcatalyst.receivers.enabled: false` removes both routes (for an application that only wants the
token).

## Testing

`FakePlatform` is an in-process stand-in for the platform (a token endpoint and the sync endpoints),
published in the `framework-flowcatalyst` test-jar. See [Testing](testing.md#the-fake-platform).
