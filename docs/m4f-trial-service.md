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
| 4 | SPA pages (`ui/apps/tickets`: tickets list with drawer, SLA policy, admin About, Configuration and Queue overview) and the browser suite `TicketsE2e` + 23 Playwright tests (tickets 6, clients 3, SLA and notifications 3, admin 2, auth 4, axe 5) | see the commit log |
| 5 | Guide page, STATUS row, handover | |

## Friction log

Severity: **blocker** (could not do it the intended way), **high** (works but needs a workaround a
second team would also need), **medium**, **low**. "Fixed" means a framework commit in this
repository; everything else is for the owner.

| # | What I tried | What the framework made hard | Sev | Suggested fix | State |
|---|---|---|---|---|---|
| F1 | A rule that depends on the loaded record in `authorize` ("a requester may comment only on a ticket they opened"). The guide calls `authorize` the "resource-level access check" | `Check<C>` sees the `Context` and the command only; the record is loaded in `execute`. The rule has to live in `execute` as a `Decision.reject(authorization(...))`, away from the other access checks. The guide's wording suggests otherwise | medium | Guide: say that `authorize` is permission-level and that record-level rules go in `execute`; or add a second, post-load check phase | **fixed** 2026-10-18 (`load` + `authorizeLoaded`, write-path contract 3a; tickets `add-comment` uses it) |
| F2 | `execute` needs the acting principal as a non-null string | `authorize` guarantees a signed-in principal, but NullAway cannot know; every use case re-checks `context.principal() == null` and throws | low | A `Context.requirePrincipal()` that throws `IllegalStateException`, or an `Authorize.signedIn()` that returns a typed `Context` | open |
| F3 | A client-scoped use case being run by the SLA sweep, which receives a `_platform` context for a job | `Context` has no `withClient(...)`; `SlaSweep.forClient` calls the eight-argument canonical constructor to act for a ticket's client. Easy to get wrong (a mutation that passed the job's own context made the sweep test fail, as it should) | low | `Context.forClient(ClientId)` that keeps principal, kind, correlation, causation and execution ids | **fixed** 2026-10-18 (`Context.forClient`, `PerClient.each`, core contract 3a) |
| F4 | `-- param: now java.time.Instant` in checked SQL to type a timestamp parameter | A type given with `param:` is treated as a typed-value record: the generated code calls `now.value()`, so the project does not compile and the message is a `javac` error in generated code, not a message from the generator | low | The generator should refuse a typed-value type it can tell is not a record with a `value` component, or document "do not name JDK types here" | open |
| F5 | A list with optional filters and a keyset cursor in checked SQL | Works, but only with `(:p::text IS NULL OR col = :p::text)`; an optional enum has to be passed as `String` (the `enum` override applies to results and to parameters that are not nullable together); a nullable parameter cannot also be typed | low | Allow `-- param: status ? enum X` | open |
| F6 | A per-client setting (the SLA policy) | There is no per-client configuration capability (M5 is not built), so a table, a repository, SQL, an aggregate, a use case, an event, an operation and a page had to be written for four integers | medium (known) | M5; until then the guide should show the pattern, which this service now is | **fixed** 2026-10-18 (M5 first slice: `framework-client-settings`, contract client-settings.md; `SlaPolicy` and its table, repository, SQL and id are gone, the SLA is one `ClientSetting`) |
| F7 | Asserting on the events a use case wrote | The outbox row's `payload` holds `data` as a JSON **string** inside the JSON payload, and the test has to parse twice in SQL (`(payload::jsonb ->> 'data')::jsonb ->> 'x'`). Every application test repeats this | medium | A test helper in a test-jar (`OutboxTesting.events(pg)` returning type, client, group and parsed data) | open |
| F8 | Test timestamps through `PostgresFixture.query` | `timestamptz` comes back in the JVM or session zone (`+01`), so assertions on instants need `AT TIME ZONE 'UTC'` | low | `PostgresFixture` could set the session zone to UTC | open |

| F9 | Roles: the definition sync wants `Definitions.role(name, permissions)`, the sign-in wants `PermissionMapper.byRole(Map<platformName, permissions>)` | The same role-to-permission table is written twice, with two naming forms (`agent` for the sync, `tickets-trial:agent` for the sign-in) and nothing ties them. The service wrote a `Roles` class that feeds both | medium | `PermissionMapper.fromRoles(application, roles)` over the same `Definition.Role` values the sync takes | open |
| F10 | The OpenAPI document of an operation behind a rate limit | A middleware's `429 RATE_LIMITED` is not known to the document; the operation lists it by hand with `.error(429, ...)` and nothing checks it matches the middleware | low | `RouteOptions`/`RateLimit` could contribute the declared error (or the build could warn) | open |
| F11 | A new application in the UI workspace (`ui/apps/tickets`) | Five config files (`tsconfig*.json`, `openapi-ts.config.ts`, `package.json`) are copied from `apps/orders` and edited by hand; the workspace has no template or generator, and the root `pnpm check` / Playwright ports (4392/4393, 5175) are per application by convention only | low | A `create-fw-app` script, or the app configs `extend` shared ones | open |

| F12 | Testing the receivers: posting a signed delivery | `DeliverySignature.hmacHex` is package-private and `Receiving` (the framework's helper) is a test class of the module, so the guide's "sign it like this" is a recipe every application re-implements in its tests (`Deliveries.sign`, 8 lines of HMAC). The platform's envelope also has to be rebuilt by hand from an outbox row | medium | A public `ReceiverTesting` in the `framework-flowcatalyst` test-jar (the signing and the two deliveries are done; building the envelope from an outbox row stays with F7) | **fixed** `4075a39` (`ReceiverTesting`, 5 tests, receivers and testing guide) |
| F13 | A dispatch handler that runs a use case, made idempotent with `DispatchDelivery.once` (the guide's advice) | `once` makes the handler's own database work and its record atomic. A use case runs in the runner's own transaction, so `once` and a use case do not share a commit; and an HTTP call (this service's notification) is in no transaction at all. The guide does not say what to do then. The service sends the job id as the HTTP call's idempotency key and documents it in `NotifyAssignee` | medium | Guide: say so, with the idempotency-key recipe; or a `UseCases.run` overload that takes the caller's `Tx` | **fixed** 2026-10-18 (`UseCases.run(.., Tx.of(connection))`, write-path contract 4a; the guide says what still does not compose) |
| F14 | A per-client credential on an outgoing call (the client's own notification key) | `ClientAuth`/`Auth` strategies take a supplier with no `Context`; per-client credentials mean building the `Authorization` header by hand with `secret.reveal()` on every call. Works, and `SecretResolver` itself was easy; but it is the one place a secret is revealed in application code | low | `Auth.bearer(Context -> secret)` or a request-level `.auth(Auth)` | open |
| F15 | A scheduled job that has to act per client (the SLA sweep) | The firing's context is `_platform`; the job definition cannot ask the platform for one firing per client. The service needs one deliberate query across every client (`TicketSql.overdue`), then re-scopes each step (F3). It is correct and tested, but it is the one place where "the tenant is always present" is worked around | medium | A client-scoped job (the platform fires once per active client with `X-FlowCatalyst-Client`), or a documented sweep pattern | **fixed** 2026-10-18 as the documented pattern (owner: the application fans out; the platform does not fire per client): client codes from one unscoped query, then `PerClient.each`; `SlaSweep` migrated |
| F16 | Declaring the job with `concurrent`, a payload or a timeout | `Definition.ScheduledJob` has withers for `tracking` and `inZone` only; everything else needs the 11-argument constructor | low | Withers for the other fields | open |
| F17 | `TestClient.of(app.get(Routes.class))` for the receiver routes of an application whose other routes need a context | `StartupException: Application failed to start: 13 problems` (one cause, repeated per route: no `ContextResolver`); the call that works is `TestClient.of(routes, resolver)` | low | Collapse the repeated problem, or name the fix in the message | open |

| F18 | The admin area on a second application: the shell, About and Configuration worked at once (`FwAdminShell`, `adminRoutes`, `FwAdminAbout`, the server's navigation) and the service's own section took one page | The data pages (event types, roles, users, invitations, activity) are **not in `@flowcatalyst/ui`**: they are 13 files, 1,536 lines in `ui/apps/orders/src/pages/admin`, bound to the orders application's generated client. The service copied only the Configuration page (120 lines, one import changed) and left the platform sections out; a team that wants them copies 1,536 lines and keeps them in step by hand. The contract and the plan call them shared pages | high | Move them into the package, parameterised by the generated calls (props or one `AdminApi` object), as `FwAdminAbout` already is | **fixed** 2026-10-18 (`standardAdminPages`, the `admin` plugin option, `replaceAdminPage`; contract admin.md section 13; orders and tickets migrated) |
| F19 | A form dialog on a body with an optional field (`TransitionBody.note`) | `FwFormDialog`'s `schema: ZodType<O, I>` does not accept the generated schema under `exactOptionalPropertyTypes` (the schema's `note` is `string \| undefined`, the generated type's is `note?: string`); `tsc` rejects it. The page narrows with `zTransitionBody.extend({ note: z.string().min(1) })`, which also gives it the client-side rule. A parallel change (`c73855e`, `optionalText`) fixes the related problem that `UInput` cannot bind an optional property | medium | Type `schema` loosely (`ZodType<unknown, I>`) or generate the schema without `.optional()` under the flag | partly fixed by another change |
| F20 | Priority badges (`FwStatusBadge`, `variant="subtle"`, colour `warning`) | In light mode the text is `#c2410c` on `#f9ece7`, 4.48:1, under AA's 4.5 (and 4.1:1 on a hovered row); strict axe failed the list, the drawer and the filters popover. The orders application never used the combination. The solid variant (documented as passing) is what the service uses | medium | Darken the subtle `warning` text token, or have `FwStatusBadge` refuse the combination in its tests | **fixed** 2026-10-18 (`fcComponentTheme.badge`: soft and subtle success and warning use the 800 shade in light; contrast test) |
| F21 | An edit form (`require-change`) with `UInputNumber` fields | Save stays disabled while the number is being typed and enables on blur, because `UInputNumber` commits its model on blur; Playwright's `fill` never saw it enable until the test pressed Tab. A user who types and goes straight for the button may meet a disabled button | medium | Commit on input in the partial, or have `requireChange` read the field's draft value | **fixed** 2026-10-18 (`FwFormDialog` compares the fields' drafts; tickets e2e test) |
| F22 | The browser end-to-end suite for a second application | `start-backend.ts`, `playwright.config.ts`, `fixtures.ts` (the CSP monitor, `fetchJson`), `axe-tools.ts` are copied from `apps/orders` and the Java launcher `TicketsE2e` is a 250-line adaptation of `OrdersE2e` (control server, fake provider, query parsing). The pieces that are not about tickets are about 600 lines | medium | `@flowcatalyst/ui/e2e` (fixtures, CSP monitor, axe) and a framework test-jar launcher that takes the application builder and a control-endpoint list | open |
| F23 | Working through the sign-in as a person of a client | `TestOidcProvider.signInAs(Map)` takes raw claim names (`tier`, `clients` as `clt_x:x` strings); the control endpoint builds them by hand. `AuthTesting.clientUser(...)` is the typed form, but only for sessions, not the provider | low | `TestOidcProvider.signInAs(Principal)` | open |

| F24 | Event types: the use case names one (`TicketEvents.ASSIGNED`, an `EventType` record) and the sync declares it (`Definitions.eventType("tickets-trial:support:ticket:assigned", ...)`) | The two are linked by hand (`TicketEvents.X.type()` into the string-taking method); nothing fails when a use case writes an event type the sync never declared, and the platform drops what it does not know. `Definitions.eventType` takes strings only | medium | `Definitions.eventType(EventType, name, description)`; a test helper that asserts every `EventType` constant is declared | open |
| F25 | A length rule (the subject's 200 characters) | It is written in four places: the operation's `Validator` (the document and the 400), the use case's `validate` (it also runs from a receiver), the table's `CHECK` and the SPA through the generated schema. Shared constants (`SUBJECT_MAX`) keep the numbers equal but the code four times | low | `UseCase.validate` accepting a `Validator<C>` so the operation's rule and the use case's are one | open |

What worked at once (worth saying, since a trial should record both): the module list, checked SQL and the generator, optimistic locking with an append-only child collection, the runner's failure table (rollback, cross-client refusal), the error envelope, typed operations and the committed document, the generated client and its Zod schemas, the session store and `AuthTesting` with two clients, the receivers and `FakePlatform` (sync, signature, completion calls, the service principal), `LockGate`, the strict CSP with the build's own hash, the list/drawer/dialog partials, axe in both themes, and 23 browser tests that needed three small fixes in total.

(More rows are added with each slice.)

## Results

| Check | Result |
|---|---|
| `TicketUseCasesTest` | 13 tests on PostgreSQL 18: the status machine, the SLA policy and its effect on new tickets only, comments appended at the next free position under a concurrent post, authorization by permission and by kind of principal, client isolation, rollback of a failed comment, the sweep's use case, optimistic conflicts |
| `TicketsApiTest` | 9: the life of a ticket over HTTP with real session cookies, the error envelope, validation, roles, two clients, cursor paging, the policy, a per-person rate limit, the admin overview |
| `TicketsPlatformTest` | 10: the sync (event types, roles incl. the framework's `admin`, connection, subscription, job) and its dry run, the notification with the client's own key and its four outcomes, an unsigned delivery, the sweep across two clients with the service principal as the actor and the firing as the cause, the overlap lock, the gauges |
| `TicketsOpenApiTest`, `TicketsSpaTest` | 1 and 2: the committed document is current; the jar serves its build under the strict CSP |
| `ReceiverTestingTest` (framework) | 5 |
| Playwright (`ui/apps/tickets/e2e`) | 23: tickets 6, clients 3, SLA and notifications 3, admin 2, auth 4, axe 5 (light and dark, overlays, 390 px). Strict CSP monitor in every test |
| `pnpm lint`, `pnpm typecheck` in `ui/` | clean |
| Mutation checks | the sweep acting with the job's own context instead of the ticket's client: `TicketsPlatformTest` fails |

The whole repository, 2026-10-17, offline, after the last change: `./mvnw -o -q verify` exit 0 with **1908
tests in 144 suites, none failing or skipped** (counted from the surefire reports); in `ui/`: `pnpm build`,
`typecheck`, `lint`, `test` (api-runtime 47, ui 94, sample 5), `api:drift` (tickets included), `licences` all
clean, and the browser suites `tickets:e2e` 23 passed, `orders:e2e` 49 passed (4 screenshot cases skipped
without `SCREENSHOTS=1`), `gallery:e2e` 112 passed (4 skipped). `docs/guide/verify.sh` prints OK.

## What could not be run

- **A real platform.** Nothing talked to FlowCatalyst: `FakePlatform` plays the token endpoint, the sync
  and the job-completion calls; `TestOidcProvider` plays the sign-in; the notification service is a local
  server. The definitions are syncable against the fake only (the real platform's validation is
  stricter or looser in places the fake does not model). The outbox was never delivered: the rows are
  read back from `outbox_messages`.
- **A second replica.** The overlap lock and the lock-gated loop are tested against one process (the
  lock held by the test stands in for the other replica).
- **A browser other than Chromium** (the Playwright build in the user cache), and no visual review: no
  screenshots were taken.
- **Docker** was available (OrbStack); Testcontainers ran PostgreSQL 18 for every Java test and for the
  checked-SQL generator and check. The owner's database container on port 5432 was never touched: every
  test and the launcher start their own container on a random port, and nothing connects to 8080 or 15432.
- **Offline only.** Maven ran with `-o` and pnpm with `--offline` (one `pnpm install --offline` for the new
  workspace app); nothing was downloaded.

## Owner decisions needed

1. **Admin data pages (F18).** (Ruled 2026-10-18 and built.) Promote the 13 admin page files from `ui/apps/orders` into
   `@flowcatalyst/ui`, parameterised by the generated calls? Until then every application copies 1,536 lines.
2. **Client-scoped scheduled jobs (F15).** (Ruled 2026-10-18: `Context.forClient` plus the documented sweep pattern, built.) Should the platform fire a job once per client (with the client
   header), or is "one deliberate cross-client query and a re-scope" (what `SlaSweep` does) the pattern to
   document? It decides whether `Context.forClient` (F3) is a framework method.
3. **Per-client settings (F6).** (Ruled 2026-10-18: build the first slice; built.) M5 is not built; `SlaPolicy` is now the worked pattern (about 350 lines for four
   integers). Prioritise M5, or accept the pattern and document it?
4. **A post-load authorization step (F1).** (Ruled 2026-10-18 and built.) Resource-level rules live in `execute` today. Add a phase, or
   change the guide's wording only?
5. **`once` and use cases (F13).** (Ruled 2026-10-18 and built: the overload.) A `UseCases.run` overload that takes the caller's transaction, or the
   idempotency-key recipe as the documented answer?
6. **The e2e kit (F22).** The first application's agent proposed MA-11 on the same evidence; this service is
   the second data point (about 600 lines of launcher and fixtures copied).
7. **The UI theme token (F20)** for the subtle warning badge (4.48:1), and **requireChange on number fields
   (F21)**: both are the package's, small, and I did not change them (the package is another change's).
