<!-- types: UseCase, UseCases, Plan, Decision, Receivers, DispatchHandler, DispatchResult, ScheduledJobHandler, JobResult, Definitions, Definition, DefinitionSync, SecretResolver, SecretRef, ServiceClients, LockGate, Background, LocksModule, AuthModule, PermissionMapper, AuthTesting, FakePlatform, ReceiverTesting, Operation, Routes, RateLimit, AdminSection, Context, TestOidcProvider -->
# A second service: support tickets

`examples/tickets-trial` is the framework's second real service (milestone **M4f**, 2026-10-17):
support tickets with assignees, a status machine, comments, a per-client SLA, an SLA timer run by a
scheduled job and a notification run by a dispatch job. It is built only on the framework's public
contracts and modules, with its single-page application in `ui/apps/tickets`, to find what is hard
before the framework carries the core domains. The analysis, the friction log and what could not be
run are in [`docs/m4f-trial-service.md`](../m4f-trial-service.md); this page is the map from "I want to
do X" to the file that does it, so a new service can start from a working one.

[`orders-demo`](write-path.md) is the smaller example (one aggregate, three use cases). Read it first;
this service adds everything it leaves out:

| You want | Look at | Page |
|---|---|---|
| A status machine on an aggregate, with comments appended and never rewritten | `TicketStatus.after`, `Ticket`, `TicketRepository.update` | [write path](write-path.md) |
| A second aggregate that is a client's own setting (one per client) | `SlaPolicy`, `SlaPolicyRepository.findByClient`, `TicketUseCases.defineSetSlaPolicy` | [write path](write-path.md) |
| Permissions checked in the use case, so a receiver and an HTTP call are checked alike | `TicketUseCases.requires`, `SERVICE_ONLY`, `Permissions` | [write path](write-path.md), [context](context.md) |
| Roles written once for the platform sync and for the sign-in | `Roles`, `TicketsDefinitionsModule`, `TicketsWeb` | [FlowCatalyst module](flowcatalyst-module.md), [sessions](sessions-and-login.md) |
| Checked SQL with optional filters, a cursor, a query across every client | `TicketSql.sql` (`list`, `overdue`) | [checked SQL](checked-sql.md) |
| Typed operations, a committed OpenAPI document and a generated client | `TicketsApi`, `TicketsApiModule`, `web/openapi.json`, `ui/apps/tickets/src/api` | [typed operations](openapi.md) |
| A rate limit on one operation | `TicketsApiModule` (`RouteOptions.none().with(RateLimit...)`) | [rate limiting](rate-limiting.md) |
| Definitions in code, synced at deploy time | `TicketsDefinitionsModule` | [FlowCatalyst module](flowcatalyst-module.md) |
| A dispatch receiver that calls another service with the client's own key | `NotifyAssignee`, `TicketsWeb` (`SecretsModule`, `HttpClientModule`) | [receivers](receivers.md), [secrets](secrets.md), [HTTP client](http-client.md) |
| A scheduled job that works across clients | `SlaSweep` | [receivers](receivers.md) |
| A loop on one replica at a time | `TicketsReceiversModule` (`Background`, `LockGate`) | [background and metrics](background-and-metrics.md) |
| An admin section of your own next to the framework's | `TicketsAdminModule` | [admin](admin.md) |
| Tests: use cases, the API with two clients, the platform's part, the browser | `TicketUseCasesTest`, `TicketsApiTest`, `TicketsPlatformTest`, `ui/apps/tickets/e2e` | [testing](testing.md) |

## The shape

```java
// TicketsCore: the write path without a server
App.builder()
    .module(new DatabaseModule())
    .module(new OutboxModule())
    .module(new UseCaseModule())
    .module(new TicketsModule());
```

`TicketsWeb` adds the HTTP server, sessions, the admin area, the platform module with the definitions,
the lock store the scheduled-job receiver needs, the secret store and the named HTTP client, the
receivers, the API and the SPA, each as a line in code. `java -jar ... openapi --out web/openapi.json`
writes the document without a database; `TicketsOpenApiTest` fails when the committed one is stale.

## Patterns worth copying

**Every change goes through a use case, whoever asks.** The HTTP operation, the dispatch handler and
the scheduled job all call `useCases.run(...)`; none writes SQL. So the permission is checked inside the
use case (`requires(Permissions.WORK)` in `authorize`), and the use case the sweep runs admits only
the application's service principal (`context.principalKind() == PrincipalKind.SERVICE`). A rule that
needs the loaded record (a requester comments only on their own ticket) is not an `authorize` rule
(that sees the command only): it is `authorizeLoaded`, after `load` and before `execute`
([write path](write-path.md#a-rule-that-needs-the-record-load-and-authorizeloaded)).

**Comments are appended, not rewritten.** The ticket's version covers its comments, but
`TicketRepository.update` writes only the positions that are not stored yet, so a ticket with a
thousand comments does not rewrite a thousand rows for the next one. The optimistic-lock test posts
two comments at once and checks both land, at the next two positions.

**A job that works across clients is a deliberate query and a re-scope.** The scheduled-job receiver
runs as the application's service principal in the client `_platform`. The SLA sweep reads the one
query that is not scoped to a client (`TicketSql.overdue`), then acts for each ticket's own client:

```java
static Context forClient(Context ctx, ClientId client) {
  return new Context(
      client, ctx.principal(), ctx.principalKind(), ctx.application(), ctx.correlationId(), ctx.causationId(), ctx.executionId(), ctx.permissions());
}
```

The events it writes carry that client, the service principal and the job instance as the cause. The
framework holds the `scheduled-job:sla-sweep` lock around the whole run.

**A per-client credential never leaves its client.** `NotifyAssignee` resolves
`SecretRef.parse("notify-key")` for `ctx.client()` (the client of the delivery), never from the event's
data, and answers the platform by what happened:

| Outcome | Answer |
|---|---|
| sent, or assigned to yourself | `ack` |
| the client has no key; the service refused with a 4xx other than 429 | `reject` (terminal, visible on the dispatch job) |
| the service is unavailable (429, 5xx, no answer) | throw: a transient error the platform retries |

The request carries the job id as its idempotency key, because a delivery can arrive twice and
`DispatchDelivery.once` covers database work only, not an HTTP call.

**The roles are written once.** `Roles` holds each role's name and permissions; the definition sync
sends them (`Definition.Role.of(name, permissions...)`) and `PermissionMapper.byRole(Roles.byPlatformName())`
maps the sign-in with them.

## Testing it

- **Use cases** (`TicketUseCasesTest`): a real `App` on a Testcontainers PostgreSQL, a clock the test
  moves, rows read back for the outbox, a repository subclass that pauses the first load for the
  conflict tests.
- **The API with real sessions** (`TicketsApiTest`): `AuthTesting.clientUser(id, "clt_acme", "acme", role)`
  gives a session cookie for a person of a client; the client of every request is the one in that
  sign-in, so the tests with two clients are plain.
- **The platform's part** (`TicketsPlatformTest`): `FakePlatform` for the service account, the sync and
  the completion calls; `ReceiverTesting.dispatch` and `scheduledJob` for the signed deliveries; the
  event the receiver is given is built from the outbox row the use case wrote, so a change to an
  event's fields breaks the handler's test.
- **The browser** (`ui/apps/tickets/e2e`, `TicketsE2e`): 23 Playwright tests against the real service
  with the strict CSP monitor and axe at WCAG 2.2 AA; the launcher plays the platform (sign-in provider,
  fake platform, notification service, a movable clock). Run with `pnpm tickets:e2e` in `ui/`.

## Running it

```
./mvnw -q verify                      # Docker: Testcontainers PostgreSQL 18
cd ui && pnpm install && pnpm tickets:e2e
```

The service reads `examples/tickets-trial/config/application.yaml` (a database on `localhost:5432`
named `app`: override `DATABASE_INSTANCES_MAIN_URL`, never point a test at a database you care about;
the tests and the launcher use their own containers). The platform connection is off by default
(`flowcatalyst.enabled: false`).

## What this does not show

It was not run against a real platform, a second replica or a browser other than Chromium; the
notification service is a local fake. The friction found, with a suggested fix for each, is in
[`docs/m4f-trial-service.md`](../m4f-trial-service.md); [STATUS](STATUS.md) has the milestone row.
