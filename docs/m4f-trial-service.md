# M4f: the trial business service (support tickets)

Milestone M4f of [`implementation-plan.md`](implementation-plan.md) ("trial business service on these
contracts"). Owner approved the task 2026-10-17 after the ruling of 2026-10-16 that the core domains
are built on the Java framework: before that trust is given, a **second real service**, built only
on the framework's public contracts and modules, looks for usability gaps. The service is
[`examples/tickets-trial`](../examples/tickets-trial) (Java) with its SPA in
[`ui/apps/tickets`](../ui/apps/tickets). Nothing in the framework's modules was changed to make it
work unless the friction log says so.

This page has the step-0 analysis (what `orders-demo` already proves and what it does not), the
domain chosen and why, the slices, the **friction log** (what the framework made hard, with a
severity and a suggested fix) and what could not be run.

## Step 0: what `orders-demo` covers

Read from the code and its tests, not from the guide. "Covered" means a test or the end-to-end
suite exercises it in the example.

| Capability | `orders-demo` | Not covered there (so the trial must) |
|---|---|---|
| Multi-client scoping | Use cases and queries are scoped by `client_id`; `OrdersDemoTest` uses two clients (`acme`, `globex`), `OrdersApiTest` picks the client from an `X-Client` test header | The **web path**: the end-to-end launcher signs in as an anchor (`clients: ["*"]`), so every browser flow runs as `_platform`. No test resolves the client from a real sign-in, and the SPA never runs as a client's own user |
| Use cases, events, outbox, audit rows | Yes: one aggregate with a child collection, three use cases, conflict policies, rollback, cross-client refusal | A **second aggregate**; a **status machine**; resource-level rules (who may touch which record); a use case run by something other than HTTP (a receiver); `plan.andDispatch` (not covered here either) |
| Checked SQL | Yes, fixed-shape queries | Optional filters, keyset paging in SQL, a deliberate cross-client query, a partial index |
| Typed operations, OpenAPI, generated TS client | Yes: five orders operations plus the admin ones | Enum request bodies, `PUT`, an operation whose body is a union-like action, query filters with several enums |
| Sessions and login | Yes, against `TestOidcProvider` (anchor user) | A client-tier user whose home client scopes the data |
| SPA on `@flowcatalyst/ui` | Yes: list, drawer, form dialog, 28 browser tests, axe | **A second application** on the package (the real reuse test); filters beyond one status; row actions that call state transitions |
| Admin area | Skeleton, config, event types, roles, users, activity pages (in `ui/apps/orders`) and a section of its own | Reuse of those pages by another application |
| Dispatch and scheduled-job receivers | **None**: `flowcatalyst.enabled` and `receivers.enabled` are `false` in its config | All of it: handlers, signatures, service principal, `once`, the overlap lock |
| Definitions sync (event types, roles, subscriptions, scheduled jobs) | **None** (only the admin role is dry-run in `OrdersAdminTest`) | All of it, including a subscription pointing at the app's own receiver |
| Per-client configuration | None (the framework has no such capability yet, M5) | What an application does today for a per-client setting |
| Rate limiting | Only on the framework's own `/auth/*` routes | A limiter on an application operation |
| Locks | None in the example (the framework's tests and `locks-demo` only) | The scheduled-job overlap lock as an application meets it; a lock-gated loop |
| Background loops | None | A loop of the application's |
| Per-client secrets | None | `SecretResolver` for a client's credential |
| HTTP client | None | A named client calling another service |

## The domain: support tickets

`orders-demo` is a CRUD-ish aggregate with a trivial two-state life. A **ticketing / work-queue**
service reaches every row of the right-hand column naturally, and is small:

| Gap | Where the tickets service reaches it |
|---|---|
| Status machine, second aggregate, resource-level rules | `Ticket` (OPEN, IN_PROGRESS, RESOLVED, CLOSED) with comments; `SlaPolicy`, a client's own settings; a requester comments only on their own ticket |
| Receivers and definitions | The platform delivers `ticket:assigned` to a **dispatch receiver** that notifies the assignee; a **scheduled job** runs the SLA sweep; event types, roles, a connection, a subscription and the job are declared in code and synced |
| Use case run by a receiver | The sweep runs `flag-sla-breach` for each overdue ticket as the service principal, acting for that ticket's client |
| Per-client config | `SlaPolicy`: one row per client, applied to the tickets it opens afterwards |
| Per-client secrets, HTTP client | The notification goes out through a named client with the **client's** key from `SecretResolver` |
| Rate limiting | Comments are limited per principal |
| Locks, background | The sweep's lock is the scheduled-job overlap lock (database store); a lock-gated loop publishes the open/overdue gauges |
| Multi-client in the browser | The end-to-end suite signs in as users of two clients and proves neither sees the other's tickets |

I looked for a better, gap-driven choice and did not find one: a file-import service would cover
secrets, the HTTP client and loops but not the status machine, the second aggregate, the SPA reuse
or the receivers; an inventory service has the orders-demo's shape again. Tickets cover all of it
with an obvious reason for each piece.

**Scope kept modest.** Two aggregates (`Ticket` with its comments, `SlaPolicy`); six use cases;
nine operations; four SPA pages (tickets list with a record drawer, SLA settings, and the shared
admin shell with its about, configuration and queue pages); two receivers.

| Operation | Method and path | Permission |
|---|---|---|
| `listTickets` | `GET /api/tickets?status&assignee&requester&limit&after` | `ticket:read` |
| `getTicket` | `GET /api/tickets/{ticketId}` | `ticket:read` |
| `openTicket` | `POST /api/tickets` | `ticket:open` |
| `assignTicket` | `POST /api/tickets/{ticketId}/assign` | `ticket:work` |
| `transitionTicket` | `POST /api/tickets/{ticketId}/transition` | `ticket:work` |
| `commentOnTicket` | `POST /api/tickets/{ticketId}/comments` | `ticket:comment` |
| `getSlaPolicy` | `GET /api/sla-policy` | `ticket:read` |
| `setSlaPolicy` | `PUT /api/sla-policy` | `sla:manage` |
| `getAdminTicketsOverview` | `GET /api/admin/tickets/overview` | `admin:tickets:view` |

(The permissions are `tickets-trial:support:<aggregate>:<action>`, the platform's four-segment form.)

## Slices

| # | Slice | State |
|---|---|---|
| 1 | This page, the domain, checked SQL, migration, use cases, tests (`TicketUseCasesTest`, 13 tests on PostgreSQL) | see the commit log |
| 2 | Typed operations, `TicketsWeb`, committed OpenAPI document (`examples/tickets-trial/web/openapi.json`), generated TypeScript client (`ui/apps/tickets/src/api/tickets`); `TicketsApiTest` (9 tests: roles, two clients, validation, paging, rate limit) | see the commit log |
| 3 | Definitions sync, dispatch receiver (`NotifyAssignee`, per-client key, named HTTP client), scheduled-job receiver (`SlaSweep`, overlap lock), gauge loop; `TicketsPlatformTest` (10 tests on PostgreSQL, `FakePlatform` and a fake notification service) | see the commit log |
| 4 | SPA pages and the browser end-to-end suite | |
| 5 | Guide page, STATUS row, handover | |

## Friction log

Severity: **blocker** (could not do it the intended way), **high** (works but needs a workaround a
second team would also need), **medium**, **low**. "Fixed" means a framework commit in this
repository; everything else is for the owner.

| # | What I tried | What the framework made hard | Sev | Suggested fix | State |
|---|---|---|---|---|---|
| F1 | A rule that depends on the loaded record in `authorize` ("a requester may comment only on a ticket they opened"). The guide calls `authorize` the "resource-level access check" | `Check<C>` sees the `Context` and the command only; the record is loaded in `execute`. The rule has to live in `execute` as a `Decision.reject(authorization(...))`, away from the other access checks. The guide's wording suggests otherwise | medium | Guide: say that `authorize` is permission-level and that record-level rules go in `execute`; or add a second, post-load check phase | open |
| F2 | `execute` needs the acting principal as a non-null string | `authorize` guarantees a signed-in principal, but NullAway cannot know; every use case re-checks `context.principal() == null` and throws | low | A `Context.requirePrincipal()` that throws `IllegalStateException`, or an `Authorize.signedIn()` that returns a typed `Context` | open |
| F3 | A client-scoped use case being run by the SLA sweep, which receives a `_platform` context for a job | `Context` has no `withClient(...)`; `SlaSweep.forClient` calls the eight-argument canonical constructor to act for a ticket's client. Easy to get wrong (a mutation that passed the job's own context made the sweep test fail, as it should) | low | `Context.forClient(ClientId)` that keeps principal, kind, correlation, causation and execution ids | open |
| F4 | `-- param: now java.time.Instant` in checked SQL to type a timestamp parameter | A type given with `param:` is treated as a typed-value record: the generated code calls `now.value()`, so the project does not compile and the message is a `javac` error in generated code, not a message from the generator | low | The generator should refuse a typed-value type it can tell is not a record with a `value` component, or document "do not name JDK types here" | open |
| F5 | A list with optional filters and a keyset cursor in checked SQL | Works, but only with `(:p::text IS NULL OR col = :p::text)`; an optional enum has to be passed as `String` (the `enum` override applies to results and to parameters that are not nullable together); a nullable parameter cannot also be typed | low | Allow `-- param: status ? enum X` | open |
| F6 | A per-client setting (the SLA policy) | There is no per-client configuration capability (M5 is not built), so a table, a repository, SQL, an aggregate, a use case, an event, an operation and a page had to be written for four integers | medium (known) | M5; until then the guide should show the pattern, which this service now is | open (known gap) |
| F7 | Asserting on the events a use case wrote | The outbox row's `payload` holds `data` as a JSON **string** inside the JSON payload, and the test has to parse twice in SQL (`(payload::jsonb ->> 'data')::jsonb ->> 'x'`). Every application test repeats this | medium | A test helper in a test-jar (`OutboxTesting.events(pg)` returning type, client, group and parsed data) | open |
| F8 | Test timestamps through `PostgresFixture.query` | `timestamptz` comes back in the JVM or session zone (`+01`), so assertions on instants need `AT TIME ZONE 'UTC'` | low | `PostgresFixture` could set the session zone to UTC | open |

| F9 | Roles: the definition sync wants `Definitions.role(name, permissions)`, the sign-in wants `PermissionMapper.byRole(Map<platformName, permissions>)` | The same role-to-permission table is written twice, with two naming forms (`agent` for the sync, `tickets-trial:agent` for the sign-in) and nothing ties them. The service wrote a `Roles` class that feeds both | medium | `PermissionMapper.fromRoles(application, roles)` over the same `Definition.Role` values the sync takes | open |
| F10 | The OpenAPI document of an operation behind a rate limit | A middleware's `429 RATE_LIMITED` is not known to the document; the operation lists it by hand with `.error(429, ...)` and nothing checks it matches the middleware | low | `RouteOptions`/`RateLimit` could contribute the declared error (or the build could warn) | open |
| F11 | A new application in the UI workspace (`ui/apps/tickets`) | Five config files (`tsconfig*.json`, `openapi-ts.config.ts`, `package.json`) are copied from `apps/orders` and edited by hand; the workspace has no template or generator, and the root `pnpm check` / Playwright ports (4392/4393, 5175) are per application by convention only | low | A `create-fw-app` script, or the app configs `extend` shared ones | open |

| F12 | Testing the receivers: posting a signed delivery | `DeliverySignature.hmacHex` is package-private and `Receiving` (the framework's helper) is a test class of the module, so the guide's "sign it like this" is a recipe every application re-implements in its tests (`Deliveries.sign`, 8 lines of HMAC). The platform's envelope also has to be rebuilt by hand from an outbox row | medium | A public `ReceiverTesting` in the `framework-flowcatalyst` test-jar: `signed(secret, now, body)`, `dispatchOf(outboxEventRow, client)`, `firing(code, instance)` | open |
| F13 | A dispatch handler that runs a use case, made idempotent with `DispatchDelivery.once` (the guide's advice) | `once` makes the handler's own database work and its record atomic. A use case runs in the runner's own transaction, so `once` and a use case do not share a commit; and an HTTP call (this service's notification) is in no transaction at all. The guide does not say what to do then. The service sends the job id as the HTTP call's idempotency key and documents it in `NotifyAssignee` | medium | Guide: say so, with the idempotency-key recipe; or a `UseCases.run` overload that takes the caller's `Tx` | open |
| F14 | A per-client credential on an outgoing call (the client's own notification key) | `ClientAuth`/`Auth` strategies take a supplier with no `Context`; per-client credentials mean building the `Authorization` header by hand with `secret.reveal()` on every call. Works, and `SecretResolver` itself was easy; but it is the one place a secret is revealed in application code | low | `Auth.bearer(Context -> secret)` or a request-level `.auth(Auth)` | open |
| F15 | A scheduled job that has to act per client (the SLA sweep) | The firing's context is `_platform`; the job definition cannot ask the platform for one firing per client. The service needs one deliberate query across every client (`TicketSql.overdue`), then re-scopes each step (F3). It is correct and tested, but it is the one place where "the tenant is always present" is worked around | medium | A client-scoped job (the platform fires once per active client with `X-FlowCatalyst-Client`), or a documented sweep pattern | open |
| F16 | Declaring the job with `concurrent`, a payload or a timeout | `Definition.ScheduledJob` has withers for `tracking` and `inZone` only; everything else needs the 11-argument constructor | low | Withers for the other fields | open |
| F17 | `TestClient.of(app.get(Routes.class))` for the receiver routes of an application whose other routes need a context | `StartupException: Application failed to start: 13 problems` (one cause, repeated per route: no `ContextResolver`); the call that works is `TestClient.of(routes, resolver)` | low | Collapse the repeated problem, or name the fix in the message | open |

(More rows are added with each slice.)

## What could not be run

Filled in at the end of the work.
