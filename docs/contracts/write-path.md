# Contract: the write path (use cases, unit of work, optimistic locking, outbox)

Status: M2a. Adopts the use-case envelope of the FlowCatalyst SDKs (Go `pkg/fcsdk/usecaseop`):
validate, authorize, execute returning a plan, and one runner that applies the plan atomically.
What this contract adds: optimistic locking, a declared conflict policy, client scoping, and the
local outbox. Build-time SQL checking is M2b and has its own contract.

## 1. Aggregates

An aggregate is a record implementing `Aggregate`:

| Method | Meaning |
|---|---|
| `id()` | its id, a typed id record |
| `client()` | the `ClientId` it belongs to (design rule 8) |
| `version()` | `long`; `0` for an aggregate not yet stored |

The aggregate holds what its rules need to decide (framework-plumbing.md section 7). Methods
that change it return a new value; they do not write anything. Child collections belong to the
aggregate: they are saved with it and covered by its version.

## 2. Repositories

Written by the application, with explicit SQL. A repository for aggregate `A`:

| Operation | Runs in | Contract |
|---|---|---|
| `find(db, client, id)` | no transaction (reads) | returns `Optional<A>`; filters by `client_id`; `db` is the `Database` instance, `id` the aggregate's typed id (`Repository<I, A>`) |
| `insert(tx, a)` | the attempt's transaction | stores `a` with version `1` |
| `update(tx, a)` | the attempt's transaction | updates the root row with `SET version = <a.version() + 1> … WHERE id = ? AND client_id = ? AND version = <a.version()>`, then writes children; returns the root update's row count |
| `delete(tx, a)` | the attempt's transaction | `DELETE … WHERE id = ? AND client_id = ? AND version = ?` (children by cascade or explicitly); returns the root delete's row count |

The runner calls `insert` when `version() == 0` and `update` otherwise. A row count of `0` from
`update` or `delete` means the aggregate is stale: someone else committed a change since it was
loaded. A count above `1` is a bug and fails the attempt as `INTERNAL`.

A unique-constraint violation (SQLSTATE `23505`) from any write fails the use case as `CONFLICT`
with code `UNIQUE_VIOLATION` and the constraint name in the details. It is not retried.

## 3. Use cases

A use case is defined once, with:

| Part | Required | Contract |
|---|---|---|
| name | yes | `[a-z][a-z0-9-]*`, for logs and the audit row |
| validate | no | checks the command's shape without loading data; returns `Optional<UseCaseError>` |
| authorize | **yes** | resource-level access check; returns `Optional<UseCaseError>`. `Authorize.PUBLIC` declares an operation deliberately open. A use case built without one fails when it is defined (the builder's `execute(...)` is the last call and throws `IllegalStateException`). |
| execute | yes | loads what it needs (once), applies the rules, returns a `Decision`: `Plan` or `Rejected(UseCaseError)` |
| load, authorizeLoaded | no (amendment 2026-10-18, friction F1) | for a use case that works on one stored record: `load(Load<C, L>, whenMissing)` finds it once per attempt, `authorizeLoaded(LoadedCheck<C, L>)` is the record-level access check, and `execute` then receives the record: `LoadedExecute<C, L, E>`. See "The load phase" below. |
| conflict policy | no | `ConflictPolicy.fail()` (default) or `ConflictPolicy.retry(maxAttempts, backoff)` |

Each phase receives the `Context` and the command. **Load once:** execute finds each aggregate
once. If it needs fresh state, the attempt has failed and runs again from the start.

**Plan:** what to write. Built with `Plan.save(aggregate, repository, event)`,
`Plan.delete(aggregate, repository, event)`, `Plan.emit(event)`, and `.andSave(...)`,
`.andDelete(...)`, `.andEmit(...)` to add further aggregates and events. Every plan carries at
least one event; the first is the use case's result. Events are written in step order.

### 3a. The load phase and the record-level check (amendment 2026-10-18)

`authorize` sees the command and the context only; a rule that depends on the stored record ("a
requester may comment only on a ticket they opened") used to live inside `execute` as a rejection,
away from the other access checks. A use case that works on one record can say so:

```java
UseCase.named("add-comment", AddComment.class, TicketCommented.class)
    .validate(...)
    .authorize(requires(Permissions.COMMENT))          // permission-level, no record yet
    .load((ctx, cmd) -> tickets.find(db, ctx.client(), new TicketId(cmd.ticketId())), TICKET_NOT_FOUND)
    .authorizeLoaded((ctx, cmd, ticket) -> ...)        // record-level, optional
    .execute((ctx, cmd, ticket) -> ...);               // gets the record that was checked
```

Order inside one attempt: validate, authorize, **load**, **authorizeLoaded**, execute. Rules:

1. `load` runs once per attempt, after `authorize`, so a caller who is refused at the permission
   level never causes a read. A conflict re-runs the attempt from step 1, so the record is loaded
   and checked again (the retry reads the other writer's change).
2. `load` returns `Optional<L>`. Empty ends the run with the `whenMissing` error given to `load`
   (conventionally `NOT_FOUND`); `authorizeLoaded` and `execute` do not run. The repository's `find`
   already filters by client, so another client's record is missing.
3. `authorizeLoaded` returns an error or empty, like `authorize`; its error is the result (kind
   `AUTHORIZATION` by convention) and `execute` does not run. It is optional. The check chooses its
   error: a rule that must not reveal that the record exists answers with `whenMissing`'s error
   instead of an authorization error.
4. A throwing `load` is `INTERNAL` with code `LOAD`, a throwing `authorizeLoaded` is `INTERNAL` with
   code `AUTHORIZE`, as for the other phases: the cause is logged, never put in the message.
5. A use case with a load phase still needs `authorize` (or `Authorize.PUBLIC`); the load phase is
   additional, never a replacement. Defining one without fails as in section 3.
6. The loaded value is a plain value of the application's type; the runner does not look at it. The
   plan still has to hold aggregates of the context's client (section 4, step 3).

## 4. Running a use case

`UseCases.run(useCase, context, command)` returns `UseCaseResult<E>`: `Committed(E event)` or
`Failed(UseCaseError)`. It never throws for an expected outcome. One attempt:

1. validate, then authorize; the first error is the result.
2. execute (with a load phase: load, then the record-level check, then execute; section 3a); `Rejected` is the result.
3. Check every aggregate in the plan belongs to `context.client()`. A mismatch fails as
   `INTERNAL` with code `CROSS_CLIENT_WRITE` and is logged as a bug.
4. Begin a transaction (READ COMMITTED) on the default database instance.
5. Apply the plan's writes in order, then write each event to the outbox, then one audit row.
6. Commit.

Outcome of a failed write:

| Failure | Result |
|---|---|
| stale aggregate (row count 0) | conflict: apply the policy |
| SQLSTATE `40001` or `40P01` (serialization failure, deadlock) | conflict: apply the policy |
| `23505` | `CONFLICT` / `UNIQUE_VIOLATION`, not retried |
| any other exception from a phase, a repository or the database | `INTERNAL`, code naming the step (`VALIDATE`, `AUTHORIZE`, `LOAD`, `EXECUTE`, `BEGIN`, `PERSIST`, `OUTBOX`, `AUDIT`, `COMMIT`); the cause is logged, never put in the error message |
| a plan with another client's aggregate | `INTERNAL` / `CROSS_CLIENT_WRITE` (step 3), not retried |
| the thread is interrupted while waiting between attempts | `INTERNAL` / `INTERRUPTED`, the interrupt flag is restored |

Every failed attempt rolls back fully. **Conflict policy:** `fail` returns `CONFLICT` with code
`STALE_AGGREGATE`. `retry(n, backoff)` runs the whole use case again from step 1, up to `n`
attempts in total, waiting `backoff` (with jitter) between them; when attempts run out, the result
is `CONFLICT` / `STALE_AGGREGATE`. `n` is 2 to 10.

Application code never begins, commits or rolls back a transaction, and never writes outside a
plan in a use case.

## 4a. Running inside the caller's transaction (amendment 2026-10-18, friction F13)

`UseCases.run(useCase, context, command, Tx joined)` runs the same attempts on the caller's
connection instead of opening its own, so a use case composes with work that owns the transaction
(`DispatchDelivery.once`, which hands its work an open connection).

1. `joined` is `Tx.of(connection)` for a connection in a transaction (auto-commit off); anything
   else is an `IllegalArgumentException` (a programming error, not an outcome).
2. Steps 1 to 3 of section 4 are unchanged. Steps 4 to 6 become: take a savepoint, apply the plan's
   writes, the events and the audit row on the caller's connection, release the savepoint. **The
   runner never begins, commits or rolls back the caller's transaction**; `Committed` means "applied
   in your transaction", visible to others when the caller commits and gone if the caller rolls back.
3. A stale aggregate, a serialization failure, a deadlock, a unique violation or any other failed
   step rolls the attempt back **to the savepoint**: the caller's earlier work in the transaction
   survives and the transaction stays usable. The result and the conflict policy are those of
   section 4. A retry runs the whole attempt again on the same transaction.
4. The isolation level is the caller's. Retrying a conflict assumes READ COMMITTED (what `once`
   uses): under a stronger level the retry would read the same snapshot and conflict again.
5. What does **not** join: reads a use case makes through its repository's `find(db, ...)` use the
   database's own connections, so they do not see the caller's uncommitted writes; and anything that
   is not the database. An HTTP call, an email or a message send is outside every transaction and is
   not made atomic by this: make such a call idempotent (the dispatch job id is the key).

## 5. Errors

`UseCaseError`: kind, code (`UPPER_SNAKE`), message (safe to show a caller), details (string
map). It implements core's `ServiceError` (MA item 8; http contract section 1), which is what the
HTTP layer maps, so `framework-http` does not depend on this module. Kinds and their HTTP status,
the same as the SDKs:

| Kind | Status |
|---|---|
| `VALIDATION` | 400 |
| `AUTHORIZATION` | 403 |
| `NOT_FOUND` | 404 |
| `CONFLICT` | 409 |
| `BUSINESS_RULE` | 409 |
| `INTERNAL` | 500 |

## 6. Events

A domain event is a record implementing `DomainEvent`, carrying `EventMetadata` and its own data
fields. Metadata, as in the SDKs:

| Field | Rule |
|---|---|
| event id | a TSID, generated |
| spec version | `1.0` |
| type | `{application}:{domain}:{aggregate}:{action}` |
| source | `{application}:{domain}` |
| subject | `{domain}.{aggregate}.{id}` |
| time | from the injected `InstantSource` |
| correlation id, principal id | from the `Context` |
| causation id, execution id | from the `Context` when present (M2a added both to `Context`, see core.md section 3) |
| message group | `{domain}:{aggregate}:{id}` by default |

The data payload is the record's own fields as JSON (Jackson 3, records only).

## 7. The outbox

Events and audit rows are written to the `outbox_messages` table in the **same transaction** as
the aggregate. The table and the payloads are exactly those of the FlowCatalyst Java SDK
(`flowcatalyst-go/clients/java-sdk`: `migrations/postgresql/001_create_outbox_messages.sql`,
`JdbcOutboxDriver`, `CreateEventDto`, `CreateAuditLogDto`), so that in M3 the platform's outbox
processor reads them unchanged:

- the migration is the SDK's file, byte for byte, contributed by the framework's outbox module
  (module id `outbox`). The framework's migration runner needs the name `V0001__...`, so the file
  is stored as `V0001__create_outbox_messages.sql`; the content, and so the recorded checksum, is
  that of the SDK's `001_create_outbox_messages.sql`. An application that already ran the SDK's
  migration by other means has the table; it must not also run this module's migration;
- an event row has type `EVENT`, the event's message group, the `CreateEventDto` JSON payload,
  status `0`, and `client_id` set;
- the audit row has type `AUDIT_LOG` and the `CreateAuditLogDto` JSON: entity type and id from the
  first aggregate (or the event subject), operation = the use case name, principal, time,
  correlation id, client. Command data is included only when the command implements
  `AuditData`, which returns the map to record.
- `clientCode` in both payloads is the `ClientId` value. (In FlowCatalyst mode the client id is
  the platform client's identifier.)
- The SDK's event payload has no field for the event time, the principal or the execution id.
  The row carries the event id as `deduplicationId`, and the principal and execution id as
  `contextData` entries (`principalId`, `executionId`) when present; the event time is not sent
  (the platform stamps it on ingest; `created_at` of the row is the write time).
- The SDK's table has `client_id VARCHAR(26)`, but a `ClientId` (a platform client identifier,
  `VARCHAR(100)` in the platform) may be up to 100 characters. The outbox module's second
  migration, `V0002__widen_outbox_client_id.sql`, widens the column to `VARCHAR(100)`; the first
  stays byte-identical to the SDK file. The platform's outbox processor does not read `client_id`.

Nothing in M2 reads the outbox; delivering it is the platform's job (M3).

## 7a. Dispatch-job rows (MA item 4, 2026-10-10)

A plan may also carry **dispatch jobs**: `plan.andDispatch(DispatchJob)`. They are written to
`outbox_messages` in the same transaction as the aggregates, events and audit row (after the events,
before the audit row), and the platform's outbox processor delivers them to
`POST /api/dispatch-jobs/batch`. A plan still needs at least one event; dispatch jobs never replace
it. Only the runner writes them.

Facts this section is built on (platform Go source, read 2026-10-10):

- The processor claims rows with `status = 0` ordered by `message_group, created_at`, maps the
  `type` column to an endpoint by exact string (`DISPATCH_JOB` → `/api/dispatch-jobs/batch`;
  `internal/common/outbox.go`), and sends **each row's `payload` text unchanged** as one element of
  `{"items":[...]}` (`internal/outbox/http_dispatcher.go`). It never reads `client_id`, `headers` or
  `payload_size`; `message_group` is used only for per-group FIFO and is not copied into the body.
  Results are positional. A whole-batch `400` is terminal (`BAD_REQUEST`) and blocks a grouped row's
  group.
- The batch item (`internal/platform/shared/sdk/dispatch_jobs_batch.go`, `BatchItem`) has exactly:
  `id, externalId, kind, code, source, subject, targetUrl, payload, payloadContentType, dataOnly,
  eventId, correlationId, clientId, subscriptionId, serviceAccountId, dispatchPoolId, messageGroup,
  mode, sequence, timeoutSeconds, maxRetries, metadata, queue, descriptor`. Unknown keys are ignored
  silently; `metadata` is an **array** of `{"key","value"}` (an object fails the whole batch with
  `400 INVALID_JSON`, which is what the SDKs' object-shaped `metadata` does today). Platform
  defaults: `kind` `EVENT`, `payloadContentType` `application/json`, `dataOnly` false, `mode`
  `NEXT_ON_ERROR`, `sequence` 0 → 99, `timeoutSeconds` 0 → 30, `maxRetries` 0 → 3 (so an explicit 0
  cannot be sent for those three). `targetUrl` must be an absolute `http(s)` URL with a host and no
  user info (the platform's SSRF guard may refuse more, such as loopback addresses). `source` and
  `payload` are `NOT NULL` columns although the item fields are optional, so leaving them out fails
  the insert.
- Column limits (`msg_dispatch_jobs`): ids (`id`, `eventId`, `clientId`, `subscriptionId`,
  `serviceAccountId`, `dispatchPoolId`) `VARCHAR(17)`; `code`, `source`, `subject`, `externalId`,
  `correlationId`, `messageGroup`, `queue`, `descriptor` `VARCHAR(255)`; `payloadContentType`
  `VARCHAR(100)`.
- `clientId` is stored as given (a platform client **id**, at most 17 characters); the batch endpoint
  does not resolve a client code the way the events batch resolves `clientCode`.
- The insert is `ON CONFLICT (id, created_at) DO NOTHING` with `created_at` set at ingest, so a
  redelivered batch can create a second job row; the receiver's at-least-once rule covers it.

The row:

| Column | Value |
|---|---|
| `id` | the job's id (below) |
| `type` | `DISPATCH_JOB` |
| `message_group` | the job's message group, or null |
| `payload` | the batch item JSON below |
| `status` | `0` |
| `client_id` | the context's `ClientId` (as for event rows; the processor does not read it) |
| `payload_size`, `headers`, `created_at`, `updated_at` | as for event rows (`headers` null) |

The payload is the batch item. Written always: `id`, `kind`, `code`, `source`, `targetUrl`,
`payload`, `payloadContentType`, `dataOnly`, `correlationId` (the context's). Written only when set:
`subject`, `externalId`, `eventId`, `clientId`, `subscriptionId`, `serviceAccountId`,
`dispatchPoolId`, `messageGroup`, `mode`, `sequence`, `timeoutSeconds`, `maxRetries`, `metadata`
(array form, insertion order, only when not empty), `queue`, `descriptor`. **Never written**, because
the batch endpoint ignores them: `headers`, `retryStrategy`, `scheduledFor`, `expiresAt`,
`idempotencyKey`, `connectionId`, `causationId`.

`DispatchJob` (a record, built with `DispatchJob.builder(code, targetUrl)`) is validated when built
(`IllegalArgumentException`), so a row the platform would refuse terminally is never written:

- `code`: `{application}:{rest}`, the application segment `[a-z][a-z0-9-]*`, at most 255 characters
  (the platform confines an application-scoped caller by this first segment).
- `targetUrl`: absolute `http` or `https` URI with a host and no user info.
- `source`: default the code's application segment; 1 to 255 characters.
- payload: `payload(text, contentType)` or `jsonPayload(value)` (the framework's Jackson 3 mapper,
  content type `application/json`); default the empty JSON object `{}` (the column is `NOT NULL`).
- `kind`: `EVENT` or `TASK`, default **`TASK`** (ruling taken: a job the application creates itself
  is a task; the platform's own default, `EVENT`, describes jobs fanned out from events).
  `dataOnly` default false (the platform envelope, which the framework's receiver understands).
- `id`: optional, `[0-9A-Za-z_-]{1,17}`; when absent the writer generates a 13-character TSID. The
  id is in the payload, so a batch the processor sends again after a lost response creates a job
  with the **same** id, and the receiver's `DispatchDelivery.once` (keyed on the job id) runs the
  work once.
- platform ids (`eventId`, `subscriptionId`, `serviceAccountId`, `dispatchPoolId`,
  `platformClientId` → `clientId`): `[0-9A-Za-z_-]{1,17}`. `platformClientId` is the platform's id,
  never derived from the `Context`: the framework's `ClientId` is a client identifier (up to 100
  characters) that the platform would store unresolved, and that may not fit the column. Without it
  the job has no client, and with no pool named the platform uses `platform-DEFAULT-POOL`.
- `messageGroup`, `externalId`, `subject`, `descriptor`: 1 to 255 characters. `mode`: `IMMEDIATE`,
  `NEXT_ON_ERROR`, `BLOCK_ON_ERROR`. `queue`: `DEFAULT` or `HIGH_PRIORITY`. `sequence`,
  `timeoutSeconds`, `maxRetries`: at least 1 when set. `metadata`: keys not blank, at most 100.

`Sink` gained `writeDispatchJobs(tx, context, jobs)`, a default method that throws; a `Sink` that does
not support dispatch jobs fails the attempt at step `OUTBOX` when a plan carries one.

### Platform work found

- The dispatch-jobs batch should accept `clientCode` and resolve it, as the events batch does; until
  then a framework application cannot attribute a job to a client by its identifier.
- The Java, TypeScript and Laravel SDKs write `metadata` as an object, which the batch endpoint
  rejects with `400` for the whole batch, and write fields the endpoint ignores (`headers`,
  `retryStrategy`, `scheduledFor`, `expiresAt`, `idempotencyKey`, `connectionId`), and their default
  `maxRetries` is 5 against the platform's 3. Reported; the framework does not copy them.

## 8. Ids

`Ids` (core) generates TSIDs: 13 Crockford base-32 characters, time-ordered, optionally with a
prefix (`ord_0ABC...`). Monotonic within one `Ids` instance (the framework provides one per application): when the clock
does not advance or goes backwards, the generator continues from the last value instead of
repeating or going back; more than 4096 ids in one millisecond borrow the next one.

## 9. Reads

Outside use cases, screens and lists read through plain typed queries on the database instance;
they do not load aggregates. M2a provides a small JDBC helper (prepare, bind typed values, map
rows to records); M2b replaces hand-written mapping with generated code.

## Conformance vectors (2026-10-18)

`conformance/usecase/scenarios.json` (54 scenarios, written by `UseCaseScenariosConformanceTest` against
PostgreSQL): the phase order and which phases run (section 3a), conflicts under `fail` and `retry`,
SQLSTATE `40001`, `40P01` and `23505`, every step code (`VALIDATE` through `COMMIT`, `BEGIN` included),
`CROSS_CLIENT_WRITE`, `INTERRUPTED`, and the joined runs of section 4a (commit and rollback by the caller,
the savepoint, a retry on the caller's transaction, the auto-commit argument error). The sink's events stand for
the outbox rows (`outbox/outbox.json` covers the rows themselves). Not in the contract and not in the vector:
the joined run names its savepoint-release step `SAVEPOINT`, and a repository that affects more than one row is
`INTERNAL`/`PERSIST`. The codes `PERMISSION_REQUIRED` and `NOT_AUTHORIZED` do not exist in the Java framework
(`Authorize` is an application check; the application picks the error), so the vector has no such case.
