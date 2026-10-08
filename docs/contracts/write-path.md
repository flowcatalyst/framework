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
| `find(db, client, id)` | no transaction (reads) | returns `Optional<A>`; filters by `client_id` |
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
| authorize | **yes** | resource-level access check; returns `Optional<UseCaseError>`. `Authorize.PUBLIC` declares an operation deliberately open. A use case built without one fails when it is defined. |
| execute | yes | loads what it needs (once), applies the rules, returns a `Decision`: `Plan` or `Rejected(UseCaseError)` |
| conflict policy | no | `ConflictPolicy.fail()` (default) or `ConflictPolicy.retry(maxAttempts, backoff)` |

Each phase receives the `Context` and the command. **Load once:** execute finds each aggregate
once. If it needs fresh state, the attempt has failed and runs again from the start.

**Plan:** what to write. Built with `Plan.save(aggregate, repository, event)`,
`Plan.delete(aggregate, repository, event)`, `Plan.emit(event)`, and `.and(...)` to add further
aggregates and events. Every plan carries at least one event; the first is the use case's result.

## 4. Running a use case

`UseCases.run(useCase, context, command)` returns `UseCaseResult<E>`: `Committed(E event)` or
`Failed(UseCaseError)`. It never throws for an expected outcome. One attempt:

1. validate, then authorize; the first error is the result.
2. execute; `Rejected` is the result.
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
| any other exception from a phase, a repository or the database | `INTERNAL`, code naming the step (`EXECUTE`, `PERSIST`, `OUTBOX`, `AUDIT`, `COMMIT`); the cause is logged, never put in the error message |

Every failed attempt rolls back fully. **Conflict policy:** `fail` returns `CONFLICT` with code
`STALE_AGGREGATE`. `retry(n, backoff)` runs the whole use case again from step 1, up to `n`
attempts in total, waiting `backoff` (with jitter) between them; when attempts run out, the result
is `CONFLICT` / `STALE_AGGREGATE`. `n` is 2 to 10.

Application code never begins, commits or rolls back a transaction, and never writes outside a
plan in a use case.

## 5. Errors

`UseCaseError`: kind, code (`UPPER_SNAKE`), message (safe to show a caller), details (string
map). Kinds and their HTTP status, the same as the SDKs:

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
| causation id, execution id | from the `Context` when present |
| message group | `{domain}:{aggregate}:{id}` by default |

The data payload is the record's own fields as JSON (Jackson 3, records only).

## 7. The outbox

Events and audit rows are written to the `outbox_messages` table in the **same transaction** as
the aggregate. The table and the payloads are exactly those of the FlowCatalyst Java SDK
(`flowcatalyst-go/clients/java-sdk`: `migrations/postgresql/001_create_outbox_messages.sql`,
`JdbcOutboxDriver`, `CreateEventDto`, `CreateAuditLogDto`), so that in M3 the platform's outbox
processor reads them unchanged:

- the migration is the SDK's, contributed by the framework's outbox module;
- an event row has type `EVENT`, the event's message group, the `CreateEventDto` JSON payload,
  status `0`, and `client_id` set;
- the audit row has type `AUDIT_LOG` and the `CreateAuditLogDto` JSON: entity type and id from the
  first aggregate (or the event subject), operation = the use case name, principal, time,
  correlation id, client. Command data is included only when the command implements
  `AuditData`, which returns the map to record.
- `clientCode` in both payloads is the `ClientId` value. (In FlowCatalyst mode the client id is
  the platform client's identifier.)

Nothing in M2 reads the outbox; delivering it is the platform's job (M3).

## 8. Ids

`Ids` (core) generates TSIDs: 13 Crockford base-32 characters, time-ordered, optionally with a
prefix (`ord_0ABC...`). Monotonic within a process: when the clock does not advance or goes
backwards, the generator continues from the last value instead of repeating or going back.

## 9. Reads

Outside use cases, screens and lists read through plain typed queries on the database instance;
they do not load aggregates. M2a provides a small JDBC helper (prepare, bind typed values, map
rows to records); M2b replaces hand-written mapping with generated code.
