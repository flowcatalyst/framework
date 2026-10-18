# Go framework: vectors the Java tests should write (G7.1)

Companion to [`milestones.md`](milestones.md) section 3 and [`scoping-2026-10-18.md`](scoping-2026-10-18.md)
section 3. Written 2026-10-18; the owner accepted decision 5 (Java tests may write the missing vectors
first). **Java authors these** (a Java test in `-Dconformance.update=true` mode, per
[`conformance/README.md`](../../conformance/README.md)); Go reads them afterwards and a mismatch is a Go
bug or a Java bug reported and fixed in Java first. Nothing here is built on the Go side until the file
exists. Go-side consumption: an `internal/conformance/*_test.go` per file, like `outbox_test.go`.

## What exists, and what Go reads today

Every vector already in `conformance/` is read by Go and passes (tsid, config, lock SQL and Lua,
migrations, outbox rows and INSERT, spa, openapi x5, receivers/context, auth/login-failure, admin x5), as
`make -C go test` shows (`internal/conformance`, `httpfw`, `auth`, `admin`). **G7.1 therefore has no
Go-side consumption to build yet.** The cases below are all new files.

Common form for every new file: pinned clocks and ids, JSON compared as parsed values, a case has a
`name`, an `input` and an `expect`; an allowed Go difference is recorded as `"go": {...}` with a reason.
Priority 1 to 4 follows the scoping page: the cheap, high-value ones first.

## Priority 1: `receivers/signature.json` (flowcatalyst contract section 4, `WebhookSignature`)

Input: secret, optional bearer token, `requireSignature`, a pinned `now`, the delivery headers
(`X-FlowCatalyst-Signature`, `X-FlowCatalyst-Timestamp`, `Authorization`), the raw body. Expect: accepted,
or `401 UNAUTHENTICATED` (envelope). Cases:

1. valid: hex HMAC-SHA256 of `timestamp + rawBody` (include the existing vectors `a7d62a...` and `0278cf...`).
2. timestamp 300 s old: accepted; 301 s old: refused. 60 s in the future: accepted; 61 s: refused.
3. timestamp over 64 characters; non-numeric timestamp; empty timestamp.
4. signature not 64 characters; upper-case hex (state the expected answer); non-hex of length 64.
5. wrong secret; empty secret (fails closed, even with a matching empty-key MAC); body altered by one byte.
6. header sent twice: signature, timestamp, `Authorization` (each refused).
7. bearer gate set: matching, wrong, missing, wrong scheme; AND-ed with a valid signature.
8. `requireSignature=false`: unsigned accepted, signed-but-wrong still refused (state which).
9. `requireSignature=true`, no headers at all: refused.

## Priority 2: `receivers/dispatch.json` and `receivers/scheduled-jobs.json` (responses, section 4)

Input: a registered handler outcome, the request; expect: status, the `FlowCatalyst-Outcome` header,
the body as parsed JSON. Cases for dispatch: `Ack` -> `200 {"ack":true}`; `Defer(n)` -> `200
{"ack":false,"delaySeconds":n}` and the default 30; `Reject(reason)` -> `422` with `FlowCatalyst-Outcome:
reject`; handler throws -> `503 UNAVAILABLE` with no handler internals in the message; handler exceeds
the dispatch timeout -> 503; handler returns null; no handler for the type -> `422` reject
"no handler for <type>"; most-specific pattern wins (`a:b:*` vs `a:*`); `dataOnly` raw body; malformed
envelope (missing `id`, bad JSON, wrong content type); `X-FlowCatalyst-Client` forms (`id:code`, only id,
absent, malformed); not ready. Scheduled jobs: the camelCase body with every optional field absent and
present; `tracksCompletion` true/false and what the completion call carries (body, truncation limit,
status); `concurrent` false with the overlap lock held, free, lost, store down (state the status for each);
timeout; an invalid body (field named in the error).

## Priority 3: `usecase/scenarios.json` (write-path sections 3a, 4, 4a, 5; runner behaviour)

One scenario = a use case description (which phases fail, how, the conflict policy), a prior database
state, a command, a context, and the expected result and rows. Expect: `Committed` or `Failed` with
**kind, code, details, HTTP status**; and the row counts and ids of `outbox_messages` and the aggregate
table afterwards. Cases:

1. validate refuses (`VALIDATION`); validate throws -> `INTERNAL` / `VALIDATE`.
2. authorize refuses (`AUTHORIZATION`, `PERMISSION_REQUIRED`); authorize throws -> `AUTHORIZE`; zero grant
   -> `NOT_AUTHORIZED`.
3. **load phase**: order validate, authorize, load, authorizeLoaded, execute (record the call order);
   permission-refused caller causes no load; load empty -> the `whenMissing` error, no later phase;
   another client's record is missing; authorizeLoaded refuses (AUTHORIZATION, execute not run);
   authorizeLoaded answers `whenMissing` (hides existence); load throws -> `INTERNAL` / `LOAD`;
   authorizeLoaded throws -> `INTERNAL` / `AUTHORIZE`; the cause never appears in the message.
4. conflict: stale aggregate (row count 0) with `fail` -> `CONFLICT` / `STALE_AGGREGATE`; with `retry(3, ...)`
   succeeds on attempt 2 and **re-runs load and authorizeLoaded** (count the calls); attempts exhausted ->
   `STALE_AGGREGATE`; SQLSTATE `40001` and `40P01` retried; `23505` -> `CONFLICT` / `UNIQUE_VIOLATION`, not
   retried.
5. step codes for a failing repository/database: `BEGIN`, `PERSIST`, `OUTBOX`, `AUDIT`, `COMMIT`; rollback
   leaves no rows.
6. plan holds another client's aggregate -> `INTERNAL` / `CROSS_CLIENT_WRITE`, not retried.
7. interrupted while waiting between attempts -> `INTERNAL` / `INTERRUPTED`.
8. **join** (`run(..., Tx joined)`): committed on the caller's connection, rows invisible to a second
   connection until the caller commits and gone if the caller rolls back; a failed attempt rolls back **to
   the savepoint** and the caller's earlier insert survives and the transaction is usable; a retry on the
   same transaction; a connection not in a transaction (auto-commit on) is an argument error, not an outcome;
   unique violation inside a join leaves the caller's transaction usable.
9. details map (Go writes details sorted by key; give the vector an order-insensitive form or record the
   difference).

## Priority 4: `settings/client-settings.json` and its migration history (client-settings contract)

1. `migrations/client-settings.json`: the `fw_schema_history` rows for module `client-settings`
   (as `locks-database.json` does).
2. Storage: after writing settings for a client, the row (`client_id`, `version`, `settings` JSONB as parsed
   JSON); a second client's row untouched; `without` removes the key; version increments by one per write.
3. Reading: no row -> default; row without the key -> default; a value that does not decode -> the error
   naming key and client (record the Java message text).
4. Keys: accepted `a`, `tickets.sla`, `a.b_c-d`, a 100-character key; refused empty, uppercase, leading
   digit, 101 characters, `a:b`, space.
5. Rules: the rule message surfaces in `with` (`IllegalArgumentException` text); the SLA example from the
   trial (`urgent <= high` ordering) with its exact messages and codes (`POLICY_NOT_ORDERED`,
   `MINUTES_INVALID`).
6. JSON bytes: the stored text for a record, an enum, a string, a number and a list value, since Go writes
   JSON with `encoding/json/v2` (key order, number formats, escaping of `<`, `>`, `&`).
7. `ClientSettingsId` equals the client's code; two first writes at once -> `UNIQUE_VIOLATION`; two writers
   on one row -> `STALE_AGGREGATE`.

## Priority 5: `ratelimit/gcra.json` (+ the 429 response)

Input: policy `(limit, period, burst)`, a list of `(timeNanos, key, cost)`, optional per-request policy;
expect per step `allowed`/`limited`, `remaining`, `resetAfter`, `retryAfter` in nanoseconds. Include: a
fresh key; burst exhaustion; refill after exactly `T`; cost greater than 1; the TAT cap with a changing
per-request policy (section 3a); the range checks (limit 0, 1 000 001; period 0 ns, over a day; `period /
limit` under 1 microsecond; window overflow) as refused policies; name rules (`[a-z][a-z0-9_-]*`, 63).
The 429 envelope: body, `Retry-After` (rounded how), the `RateLimit-*` headers if any, `ALLOW`/`DENY`
modes. Key resolvers: `ForwardedFor(n)` with 0, 1, 2 proxies and a spoofed left-most entry, IPv6 and
malformed entries (Go's `netip` is stricter than Java's; record each difference).

## Not wanted now

Metrics and health text (scoping decision 5), sessions beyond sign-in failure (token checks, cookie flags,
rotation, expiry: ask after the next Java sessions change), secrets. Config key-rules vector remains deferred
(G1).
