# Contract: push to clients (server-sent events)

Status: **accepted 2026-11-08 (owner rulings in section 16). P1a built in Go (2026-11-08): the core
module (`go/push`), the A1 `postgres` driver, `usecase.Push`, tickets, the HTTP routes, the
conformance vectors and the A1 contract tests; amendments from that build in section 17. A2
(`redis`) built in Go the same day (`RedisStreamLog`; the contract tests run on both drivers;
amendment 10 of section 17). First local load test the same day; its four findings fixed in Go
(amendment 11 of section 17, 2026-11-08). The snapshot cursor (9.1) and the reset for an invalid
`Last-Event-ID` (amendments 12 and 13 of section 17, 2026-11-09). P2a built in Java (2026-11-09):
module `framework-push` (the core, the A1 `postgres` driver, `Plan.andPush`, tickets, the routes,
the four conformance vector files, the contract test suite of section 14 on A1; amendment 14 of
section 17). The Java Redis driver (A2) built the same day: module `framework-push-redis`, the contract
tests of section 14 on both drivers (amendment 15 of section 17). The framework changes the A4 platform
push gateway needs, built in Go (2026-11-09, amendment 16 of section 17): many applications in one
host, per-application stream limits, the optional `client` member of the subscribe body, `Handler`,
`Drain`, `ExternalMigrations`, `PublishBatch`. Not yet: the SDK readers, the load test on the target,
the Java side of amendment 16 and of `push.driver=platform`. `push.driver=platform`, the publish sink
for applications that use the gateway, is built in Go (2026-11-09, amendment 17 of section 17).**
Drivers: `postgres` (A1) and
`redis` (A2), behind one contract. A platform push gateway (A4) is sketched in section 12 so that
nothing here rules it out.

An application pushes small messages to browsers, apps and field devices that are listening over
server-sent events. A push is not a domain event: it is whatever a screen needs to hear ("order 7
changed", "a job was assigned to you"). The transport is the framework's SSE stream (HTTP contract
section 13.5: `Last-Event-ID`, heartbeat, drain). This contract adds what a stream needs to be
robust with more than one instance: a push written in the same transaction as the change, one
ordered log every instance follows, replay after a reconnect, an answer for a client that was away
too long, topics with access rules, and backpressure.

**The guarantee.** A push is sent only if its transaction commits, and every committed push is
delivered to every stream subscribed to its topic, in log order, once, as long as the stream is
open or resumes within the retention window. A stream that resumes from further back gets a `reset`
event and reloads its state (section 7). The log is per host: ids from one host mean nothing to
another.

## 1. Topics and types

- **Topic:** `{application}:{segment}(:{segment})*`. The application segment is the application's
  code (`[a-z][a-z0-9-]*`, as permission codes); each further segment is `[0-9A-Za-z_.-]+`. At most
  255 characters, at least two segments. Examples: `fulfil:order:ord_0ABCDEFGH01`,
  `fulfil:store:12:dock`, `fulfil:user:prn_0ABC`. Every topic starts with its application's code,
  so topics from different applications never collide on a shared host (section 12).
- **Type:** `[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*`, at most 100 characters, for example
  `order.updated`. The types `reset`, `auth-expired`, `subscribed` and every type starting `fw.` are
  reserved (section 6).
- **Data:** a JSON object, at most `push.max-data-bytes` (default 16 KiB, at most 64 KiB) of UTF-8.
- **Signals, not state (default).** A push should say what changed (`{"orderId": "...",
  "version": 7}`); the client reads the state through the application's API, which applies its
  normal access checks and never shows a stale copy. A push may carry state when the data is fine
  for everyone allowed on the topic and fits the limit; that is the application's call, per topic.

Invalid topics, types or data are refused when the push is built (Java `IllegalArgumentException`;
Go the plan fails as `INTERNAL` / `INVALID_PUSH`, logged as a bug). The refusal codes (amended
2026-11-08, section 17): `TOPIC_INVALID`, `TYPE_INVALID`, `TYPE_RESERVED`, `DATA_INVALID` (not
JSON), `DATA_NOT_OBJECT`, `DATA_TOO_LARGE`. The data is stored as compact JSON (no whitespace), so
its `data` line on the wire is one line; the limit counts those bytes.

## 2. Publishing

A push is published in a transaction, always:

- **In a use case** (write-path contract section 3): `plan.andPush(Push.of(topic, type, data))`
  (Go `usecase.Push(topic, type, data)`). The runner writes it in the attempt's transaction, in plan
  order, at step 5 (step code `PUSH`). It is not an event: it is not audited, is never the use
  case's result and does not count toward "every plan carries an event". A failed attempt writes no
  push.
- **Outside a use case:** `Pushes.publish(tx, context, push)` (Go `pushes.Publish(ctx, tx, cx, p)`,
  a method of the built module, which knows `push.max-data-bytes` and the clock; `tx` is a `pgx.Tx`)
  inside a transaction the caller owns, or `Pushes.publish(context, push)` (Go
  `pushes.PublishAlone(ctx, cx, p)`) in its own transaction.

Each push records the `Context`'s client (section 5): the client is part of the push.

Writing a push inserts one row into `fw_push_outbox` and issues `pg_notify('fw_push_outbox', '')`.
Both are transactional: the row and the notification exist only if the transaction commits. The
outbox is always on the default database instance, whatever the driver.

```sql
-- module push, V0001__create_push_tables.sql
CREATE TABLE fw_push_outbox (
    id         BIGSERIAL PRIMARY KEY,
    topic      VARCHAR(255) NOT NULL,
    type       VARCHAR(100) NOT NULL,
    client     VARCHAR(100),             -- NULL: the platform client (section 5)
    data       TEXT NOT NULL,            -- the JSON object as written
    created_at TIMESTAMPTZ NOT NULL
);
```

## 3. The relay

One instance at a time, the **relay**, moves pushes from the outbox into the log. It is a background
loop (background contract section 1) gated by the lock `LockKey.service("push-relay")`, so the
fencing token protects it (locks contract section 3).

One run: take up to `push.relay.batch` (default 500) outbox rows in `id` order, append them to the
log in that order, delete them, commit (A1: one transaction; A2: append, then delete, section 4).
The relay runs when `fw_push_outbox` is notified and at least every `push.relay.interval` (default
1 s) in case a notification was missed.

The lock's lease is `push.relay.lease` (default 5 s), refreshed every third of it. A relay that
stops cleanly releases the lock, so another instance's relay takes over at once (a rolling deploy).
After a crash the lock is free when the lease ends: pushes committed meanwhile wait in the outbox
for up to the lease, then arrive, none lost.

**Why a relay.** Log ids must follow commit order: a client that has seen id 41 must never later
be owed an id 40. A sequence assigned when the row is inserted does not give that, because two
transactions can commit in the other order. The relay assigns log ids only to rows that are already
committed, one writer at a time, so the log has no gaps and never reorders. A row whose transaction
commits after the relay's read is simply picked up by a later run.

A push therefore reaches a stream about one relay run plus one hub wake-up after its commit (target
p99 under 250 ms, section 11).

## 4. The log and the drivers

The log holds, per push: the **id** (a positive increasing 64-bit integer, the SSE event id), the
topic, type, client, data, and the time it was logged. The driver decides where it lives
(`push.driver`):

| | A1 `postgres` (default) | A2 `redis` |
|---|---|---|
| log | table `fw_push_log` (below) on the default instance | the Redis stream `fw:push` on `push.redis-instance` |
| id | `max(id) + 1`, assigned by the relay inside its transaction; `PRIMARY KEY (id)` refuses a second relay | the Redis entry id `ms-seq`, sent as `ms * 2^20 + seq` (seq below 2^20) |
| instances woken by | `pg_notify('fw_push_log', <highest id>)` in the relay's transaction | `XREAD BLOCK` on the stream |
| retention | rows older than `push.retention` deleted by a loop | entries below the retention edge deleted by a loop with `XDEL` (not `XTRIM`: section 17.10) |
| relay step | insert into the log and delete from the outbox, one transaction | `XADD` each row, then delete the rows; a crash between the two can add a row twice, so each entry carries the outbox id and the hub drops an outbox id it has already seen |

```sql
CREATE TABLE fw_push_log (
    id         BIGINT PRIMARY KEY,
    topic      VARCHAR(255) NOT NULL,
    type       VARCHAR(100) NOT NULL,
    client     VARCHAR(100),
    data       TEXT NOT NULL,
    logged_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX fw_push_log_logged_at ON fw_push_log (logged_at);
```

`push.retention` defaults to 24 h and is configurable from 10 minutes up to 24 h, never more (owner, 2026-11-08). Each driver passes the same contract tests and
conformance vectors (section 13); application code and clients cannot tell them apart.

## 5. Clients (tenants) and who receives what

Every push has the client of the context that wrote it. A stream belongs to one client (section 6).
A stream receives a push when the push's topic is one it subscribed to **and**:

- the push's client is the stream's client, or
- the push's client is the platform client (written as NULL): it goes to every stream subscribed
  to the topic, whatever the stream's client.

A stream opened in the platform client's context (an anchor tier user or a platform service)
receives the topic's pushes of every client. The client check is made for every push, on every
stream, after the topic match. Even if two clients' topics have the same name, one never hears
the other.

## 6. Subscribing and the stream

A client opens a stream in two steps. Both steps are the same on every host (A1, A2, A4).

**6.1 Subscribe.** `POST {base}/push/subscriptions`, JSON:

```json
{ "topics": ["fulfil:order:ord_1", "fulfil:user:prn_0ABC"], "tickets": ["<ticket>", "..."] }
```

The request is authenticated the application's usual way (session cookie with its CSRF rule,
bearer token, or a field token with DPoP), or not at all when the tickets grant every topic. Each
topic is granted when a **rule** (6.3) allows it for the request's `Context`, or a **ticket** (6.4)
grants it. The answer is `200`:

```json
{
  "streamToken": "<opaque>",
  "expiresAt": "2026-11-08T13:00:00Z",
  "granted": ["fulfil:order:ord_1"],
  "denied": [{ "topic": "fulfil:user:prn_0ABC", "code": "TOPIC_FORBIDDEN" }]
}
```

Other answers:
- `400 VALIDATION`: the body is malformed, a topic is invalid, there are more than `push.max-topics`
  topics (default 100), or a ticket is malformed.
- `401`: authentication was given and failed.
- `403 NO_TOPICS`: nothing was granted.

A denied topic is reported, never an error for the others. The stream token names the granted
topics, the stream's client, the principal (when there is one) and the expiry. The expiry is the
earliest of the credential's expiry (session, access token), every ticket's `stream-exp`, and
`push.stream.max-lifetime` (default 1 h). The token is signed by the host with `push.stream.secret`
(HMAC-SHA256), which every instance of the host shares. It is a capability to listen, nothing else.

**6.2 Listen.** `GET {base}/push/stream?st=<streamToken>`, with `Last-Event-ID` (header, or
`lastEventId` in the query) when resuming. This works from a browser `EventSource`, which cannot set
headers. The query parameter `st` is redacted in access logs. Answers:
- `401 STREAM_TOKEN_INVALID` / `STREAM_TOKEN_EXPIRED`: subscribe again.
- `200 text/event-stream`, with the SSE stream of HTTP contract section 13.5, as follows:
  1. A `subscribed` event: `{"granted": [...], "latest": <highest log id now>}`, without an id.
  2. If `Last-Event-ID` is given and still inside retention, every push after it on the granted
     topics (subject to section 5), in id order. If it is older than the oldest id kept, a `reset`
     event instead (section 7). Without `Last-Event-ID`, nothing from the past: the client loaded its
     state before subscribing.
  3. The live pushes, each once, in id order.

Each push is sent as:

```
id: 1042
event: order.updated
data: {"topic":"fulfil:order:ord_1","data":{"orderId":"ord_1","version":7}}

```

The `data` line is one JSON object: `topic` and `data`. The vectors (section 13) pin these bytes.

**6.3 Rules (declared in code, kept as data).** An application declares, per topic pattern, who may
subscribe without a ticket:

```java
push.topic("fulfil:order:{id}").requires("fulfil:order:view");      // any order of the stream's client
push.topic("fulfil:user:{principal}");                               // only your own
push.topic("fulfil:store:{id}:dock").requires("fulfil:dock:view");
push.topic("fulfil:announcements").requires("fulfil:app:use");
```

- A pattern's segments are literals or placeholders.
- `{principal}` must equal the context's principal id.
- `{client}` must equal the context's client.
- Any other `{name}` matches one segment.
- `requires(permission)` needs the permission in the context, and may be given more than once
  (all needed).
- A rule without a principal placeholder or `requires` is refused when declared, so no topic is
  open to anyone by accident; `publicTopic(pattern)` declares a deliberately open one.
- Rules are data, not callbacks, so a gateway can apply them as well (section 12). Anything a rule
  cannot express (this user may see order 7 but not order 8) is a ticket.

**6.4 Tickets.** A ticket is minted by the application, in its own endpoint, after its own access
check. It grants topics to whoever holds it, so it is short-lived and only minted for an
authenticated caller. Format: JWS compact, `alg` `EdDSA` (Ed25519), header `kid`. Claims:

| claim | meaning |
|---|---|
| `iss` | the application code (the topics' first segment must equal it) |
| `aud` | `fw-push` |
| `client` | the stream's client (the platform client's value for platform-wide) |
| `topics` | patterns as in 6.3, placeholders matching any one segment, at most 100 |
| `sub` | optional: the principal; recorded in the stream token, for logs |
| `cnf` | optional: `{"jkt": "<thumbprint>"}`, then the subscribe request must carry a DPoP proof with that key |
| `iat`, `exp` | `exp` at most 5 minutes after `iat`: the time to subscribe with it |
| `stream-exp` | optional: the latest the stream may live (default `exp` + `push.stream.max-lifetime`) |

`Pushes.ticket(context, topics...)` (Go `push.Ticket(cx, topics...)`) mints one with the key
`push.ticket.key` (secret). The host serves its public keys at `GET {base}/push/keys` (JWKS), the
current and the previous key during a rotation. A ticket from another application, with a bad
signature, an unknown `kid`, an expired `exp` or a topic outside `iss` grants nothing (the topic is
denied with `TICKET_INVALID`). Tickets from several applications in one request are each checked
against their own issuer's keys; an application host knows only its own (section 12 for A4).

## 7. Reconnecting, resets and expiry

- **Resume:** a stream that drops is reopened with the same stream token and `Last-Event-ID`; the
  pushes in between are replayed (6.2 step 2). Clients reconnect after the `retry` (3 s) with
  jitter.
- **Reset:** when `Last-Event-ID` is older than the oldest id still kept, the host sends
  `event: reset` with `data: {"reason":"retention","latest":<id>}` and the id `latest`, then
  continues live. The client must reload its state from the API. A `Last-Event-ID` **higher** than
  the highest id the host has (the log was rebuilt, or the client talked to another host) gets the
  same reset with the reason `unknown-id`; so does a `Last-Event-ID` that is present but is not a
  non-negative integer (the host never starts such a stream live without telling the client). An
  empty value is no `Last-Event-ID`.
- **Expiry:** at the stream token's expiry the host sends `event: auth-expired`, data `{}`, and ends
  the stream. The client subscribes again (new token, new tickets if it used them) and resumes with
  its `Last-Event-ID`: nothing is lost. A stream token presented after its expiry is `401`.
- **Revocation:** a field login family revoked (sessions-and-login contract section 15.7) ends that
  principal's open streams with `auth-expired`. A session signed out does the same. Other credential
  changes take effect at the next subscribe, at most `push.stream.max-lifetime` later.

## 8. Backpressure and limits

- Each stream has a buffer of `push.stream.buffer` pushes (default 256). A stream that falls further
  behind is **ended** (no event; a slow client cannot get one). It resumes from its
  `Last-Event-ID` like any other drop. A slow client therefore never slows the others, and loses
  nothing within retention.
- Each instance keeps the most recent `push.recent` pushes (default 10 000) in memory and replays
  from there when it can, from the log only when it must. An instance reads the newest
  `push.recent` pushes of the log into that memory, in one read, before it serves (amended
  2026-11-08, section 17.11). After a deploy or a restart, thousands of clients resuming together
  are then served from memory, not the database, on the restarted instance as on one that stayed up;
  only a client away for longer than `push.recent` pushes reads the log, one page at a time.
- Limits, each refused with `429 PUSH_LIMIT` at subscribe:
  - `push.max-streams` per instance (default 50 000);
  - `push.max-streams-per-principal` (default 20).
- An instance that is draining (HTTP contract 13.1) ends its streams, all at once when the drain
  begins (not at each stream's next write or heartbeat), and answers no new subscribes or streams
  (`503`). Its clients reconnect to another instance after the `retry`, with jitter.

## 9. Clients (the SDKs)

The SDKs provide one reader per platform, all following sections 6 and 7:

- **TypeScript (web, Node) and field-js (React Native):**
  - subscribe with the caller's credentials (cookie, bearer, or DPoP through field-js) and
    tickets fetched from the application;
  - listen with `fetch` streaming (headers allowed) or `EventSource`, and keep the last id;
  - reconnect with jitter;
  - expose `onPush(topic, handler)`, `onReset()` and `onAuthExpired()` (resubscribe by default).
- **Server-rendered pages (Datastar):** a helper that subscribes and turns a push into a fragment
  reload.
- **Android (field-android):** the same reader in the foreground. In the background a device does
  not hold a stream: a mobile push notification (out of scope here) wakes it, and it resumes with
  its last id.

### 9.1 Snapshot with a cursor

A client loads its state through an ordinary read endpoint and then listens. A change committed
between the read and the listen would be missed. So the read endpoint also returns the push cursor
(a push log id, as a decimal string), and the client opens its first listen with it as
`Last-Event-ID`: it then receives every push after the cursor. Pushes are signals ("read X again"),
so a push for a change the snapshot already holds is harmless; a missed one is not.

- **The rule:** the host reads the cursor BEFORE it reads the state (before the read transaction
  takes its snapshot: in a REPEATABLE READ transaction before its first statement, in READ COMMITTED
  before the state query). Every push at or below the cursor was in the log when the cursor was
  read, and the relay logs a push only after the publishing transaction committed, so the change
  behind it is visible to a state read that starts afterwards. A change the snapshot lacks therefore
  has a push above the cursor.
- **A cursor that lags the log is correct** (more pushes are replayed), so a host may use its
  in-memory last id: no I/O. Go: `pushes.Cursor()`; `"0"` for an empty log, which replays everything
  after 0 (or resets with `retention` if the log has trimmed past it).
- The endpoint chooses the field name; `pushCursor` is recommended for JSON bodies.
- The client passes it as `Last-Event-ID` on its first listen (field-js: the `lastEventId` option of
  `PushStream`). After a `reset` it reloads its state, taking a new cursor with it.

## 10. Configuration

| key | default | range |
|---|---|---|
| `push.driver` | `postgres` | `postgres`, `redis`, `platform` (amendment 17) |
| `push.redis-instance` | the default Redis instance | a configured instance |
| `push.retention` | 24h | 10m to 24h |
| `push.max-data-bytes` | 16384 | 1 to 65536 |
| `push.max-topics` | 100 | 1 to 1000 |
| `push.relay.batch` / `push.relay.interval` | 500 / 1s | 1–5000 / 50ms–10s |
| `push.relay.lease` | 5s | 1s to 60s |
| `push.stream.buffer` | 256 | 16 to 10 000 |
| `push.stream.max-lifetime` | 1h | 1m to 24h |
| `push.recent` | 10000 | 0 to 1 000 000 |
| `push.max-streams` / `push.max-streams-per-principal` | 50000 / 20 | |
| `push.stream.secret`, `push.ticket.key` | (secrets, required) | `push.stream.secret` is not needed with `platform` (amendment 17) |

## 11. Metrics, health and the load test

**Metrics:**
- `fw_push_published_total`;
- `fw_push_relay_lag_seconds` (a timer: per relay run, the age of the oldest outbox row it moved;
  amended 2026-11-08, section 17);
- `fw_push_log_id` (gauge);
- `fw_push_streams_open`;
- `fw_push_delivered_total`;
- `fw_push_streams_ended_total{reason}`, with `client`, `slow`, `expired`, `revoked`, `drain`;
- `fw_push_resets_total{reason}`;
- `fw_push_replay_source_total{source}`, with `memory` or `log`.

**Health:** `background:push-relay` comes from the loop. Also `push:log`: the log or Redis is
reachable.

**Load test (P1, with the router benchmark rig).** It must reach these before the module is called
done, on one 8 GB Fargate task:
- **Idle connections:** 100 000 idle streams. Record memory per stream, and the open-file limit
  the task needs.
- **Fan-out:** 1 000 pushes/s spread over 10 000 topics. Delay from commit to client: p99 under
  250 ms.
- **Broadcast:** one push to 50 000 streams on one topic. Time until the last stream has it.
- **Restart storm:** restart one of two tasks under load. Record how long until every client has
  resumed with nothing lost, and the database load during it (reads from memory, not the log).

## 12. A4: a platform push gateway (sketch, not in P0 to P4)

The platform's specification of the gateway is `docs/push-gateway-spec.md` of the platform repository
(accepted 2026-11-09); it turns this sketch into a buildable plan, and its section 2.1 lists the
framework changes, built in Go as amendment 16 of section 17 (multi-application hosts, per-application
limits, the `client` member, hosting without the framework HTTP server, batch publishing), and
`push.driver=platform`, the publish sink for framework applications, as amendment 17 (Go; not Java).

The platform will host a push gateway, its own service (like the router), built on this module.
Applications that cannot hold streams themselves use it, Laravel and PHP-FPM in particular. So do
clients that listen to several applications on one stream (a field device), and operations that
want one view. It fits on top of this contract:

- **Publishing:** a third sink. The push is written to the application's outbox as a `PUSH` row,
  in the same transaction (every SDK writes outbox rows, Laravel included). The platform's outbox
  processor hands it to the gateway, whose relay logs it. Application code does not change:
  `andPush` is the same, only `push.driver` differs (`platform`).
- **Topics:** every topic starts with its application code (section 1), so they never collide. The
  gateway accepts pushes for an application only on that application's topics.
- **Rules** are synced to the platform like permissions (they are data, 6.3). The gateway applies
  them to its own authentication: platform users, portal users, field workers.
- **Tickets** are verified with each application's public keys, which the platform holds
  (registered or fetched from `/push/keys`). One subscribe may carry tickets of several
  applications (6.4).
- **Wire:** sections 6 and 7 unchanged; the SDK readers point at the gateway's base URL.
- **Isolation:** per-application limits on publish rate, data size and open streams, plus a pause.
  One application's burst or storm must not slow another's streams.
- **Console:**
  - topics and rules per application;
  - open streams per application and client;
  - relay lag and delay;
  - a recent pushes view per topic, gated like ingestion traces;
  - retention, a test send and the pause.
- **Scale:** gateway tasks at 25k to 50k streams each behind the ALB; Redis (A2) between them past a
  few tasks.

## 13. Conformance vectors

In `conformance/push/`, shared by Go and Java (and later Rust and the gateway):

- `topics.json`: valid and invalid topics, types and data sizes, and rule patterns with
  `(context, topic) → allowed | denied`.
- `tickets.json`: a fixed Ed25519 key pair, tickets (valid, expired, wrong `aud`, `kid`, `iss`,
  topic outside `iss`, `cnf` mismatch) and the granted and denied topics for each.
- `wire.json`: the exact bytes of a push event, `subscribed`, `reset` and `auth-expired`.
- `replay.json`: a log (ids, topics, clients) plus a subscription and a `Last-Event-ID`. Expected:
  the ids sent, or a `reset` with its reason, covering the client rule of section 5.

## 14. Tests (contract test suite, each driver)

- Pushes from a rolled-back transaction are never sent.
- Pushes from concurrent transactions committing out of id order arrive in log order with no gap
  and no reorder.
- Two hosts on one database: a push published on one reaches streams on the other.
- A relay killed mid-run: its replacement neither loses nor repeats a push. A2's crash between
  `XADD` and delete is deduplicated.
- Resume after a drop; a reset for an id older than retention and for an unknown id; expiry with
  `auth-expired`; a slow stream ended and resumed losslessly.
- The client filter of section 5, including the platform client.
- Each rule and ticket case of the vectors.
- Drain ends streams and refuses subscribes.

## 15. Not in this contract

- Client-to-server messages (WebSocket).
- Presence ("who is listening").
- Mobile push notifications.
- Ordering across hosts.
- Exactly-once beyond what the ids give: a client that resumes from an older id gets those pushes
  again, and drops ids it has already applied.

## 16. Owner rulings (2026-11-08)

1. **Tickets are EdDSA from the start**, so the A4 gateway verifies them without the application's secret.
2. **One subscribe mode:** subscribe, then listen. No direct `GET /push/stream?topic=...` with header
   authentication.
3. **Retention is configurable, 24 h at most** (`push.retention`, 10 minutes to 24 hours). One value
   per host; no per-topic retention.
4. **The first consumer (P4) is field workers' job updates**: a worker's device listens to its own
   topic (`{application}:field-worker:{principal}:jobs`, rule `{principal}`, principal kind
   `FIELD_WORKER`) through field-js, subscribing with its field token and DPoP.

## 17. Amendments from the Go build (P1a, 2026-11-08)

Smallest calls made where the text was silent or could not be built as written:

1. **Publishing (section 2).** Go `usecase.Push(topic, type, data)` is a plan step (passed to
   `NewPlan` like `Insert` or `Append`); a failure writing it is `INTERNAL` with step code `PUSH`. A
   plan whose only steps are pushes is valid (it is not "appends only"). The runner learns
   `push.max-data-bytes` from `usecase.WithPushMaxDataBytes` (`pushes.RunnerOption()`; default 16384).
   `fw_push_published_total` is counted by the relay as it logs (committed pushes only).
2. **The relay (section 3)** holds its lock (lease `push.relay.lease`, default 5 s, refreshed every
   third of it; 15 s fixed until amendment 11) for as long as it runs: one gated
   run listens on `fw_push_outbox` and drains the outbox until shutdown or the lock is lost; another
   instance retries every `push.relay.interval`. It takes rows with `SELECT ... ORDER BY id LIMIT n
   FOR UPDATE`, always from the head of the queue, never after a remembered id.
3. **Retention (section 4)** never deletes the newest log row, so A1's `max(id) + 1` never starts again
   at 1. **"Older than the oldest id kept" (section 7)** means: the log may have deleted a push after
   the client's `Last-Event-ID`. A driver reports the highest id deleted (`Trimmed`; A1: the oldest id
   kept minus one, its ids having no gaps; A2: the stream's `max-deleted-entry-id`), and the reset is
   sent when `Last-Event-ID` is below it. A `Last-Event-ID` equal to the oldest id kept minus one
   loses nothing and is not reset. Replaying from the log checks this after each page it reads; a
   replay from memory (everything after `Last-Event-ID` still held) is never a reset.
4. **Subscribe (6.1).** `topics` must hold 1 to `push.max-topics` entries (counted as sent);
   duplicates are dropped; `denied` is always present (an empty list). Without credentials the
   stream's client is the first valid ticket's; a request with neither credentials nor a valid ticket
   is `401`. A ticket whose `client` is not the stream's client grants nothing (`TICKET_INVALID`).
   The limits of section 8 are also checked when the stream opens (`429 PUSH_LIMIT` on the `GET`),
   because a stream token can be used more than once. A host that is draining answers `503
   UNAVAILABLE`.
5. **Rules (6.3).** A pattern's first segment is a literal and must be the application's code (a
   start-up problem otherwise); a placeholder name is `[a-z][A-Za-z0-9]*`. `{client}` alone does not
   make a rule safe: it needs `{principal}` or `requires`. A public rule allows a request without
   credentials.
6. **Tickets (6.4).** `push.ticket.key` is `<kid>:<base64url of a 32-byte Ed25519 seed>`, comma
   separated, the signing key first and the previous one second (at most two); `GET /push/keys` lists
   both. A minted ticket's `exp` is `iat` + 2 minutes. Also invalid: a header with `crit`, an `iat`
   more than a minute ahead of the host's clock, a `stream-exp` already past. "A DPoP proof with that
   key" (`cnf`) means the subscribe request is authenticated with a field token bound to that key
   (sessions contract section 15); there is no proof without a token. A ticket that names a requested
   topic but grants nothing makes its denial `TICKET_INVALID`; a malformed ticket (not a JWS compact
   with JSON header and claims) is `400`.
7. **Revocation (section 7).** Go `pushes.EndStreams(ctx, Holder{Principal, Device, Session})` ends
   the matching streams on every instance (A1: `pg_notify('fw_push_end', <holder>)`, which every hub
   follows; a hub serves streams only once it follows the log, so no such signal is missed), with
   `auth-expired`. `flowcatalyst.PushHolder(event)` turns a field revocation event into the holder;
   the application calls both from its `OnFieldRevocation` handler. A sign-out hook is not built yet.
8. **Configuration (section 10).** `push.stream.secret` has at least 32 characters;
   `push.max-streams` and `push.max-streams-per-principal` are at least 1.
9. **Metrics (section 11).** `fw_push_relay_lag_seconds` is a timer: the framework's registry
   refuses a gauge named `_seconds`. `push:log` is an informational health check (the database's
   own check is the critical one).
10. **A2 retention uses `XDEL`** (Go build, 2026-11-08). Redis moves a stream's `max-deleted-entry-id`
   on `XDEL` only, not on `XTRIM` or `XADD ... MINID` (checked on Redis 8.x), and `Trimmed` (amendment
   3) is that field. So `Prune` reads the entries below the edge and deletes them with `XDEL` in batches,
   never the newest. The edge is a Redis entry id, that is the Redis server's clock; the application's
   clock should agree with it to within seconds. `Latest` is `last-generated-id` and `Trimmed` is
   `max-deleted-entry-id`; ids are `ms * 2^20 + seq`, not contiguous. A1's and A2's `Log` interface is
   the same. The channel for `End` is `fw:push:end` (`PUBLISH`/`SUBSCRIBE`); both keys take an optional
   prefix.
11. **After the first load test** (Go, 2026-11-08; [results](../push-load-test-results.md), section
   "After the fixes"). Four findings fixed:
   - **Relay failover (sections 3 and 10).** The lease is `push.relay.lease`, default 5 s (1 s to
     60 s), refreshed every third of it; a cleanly stopping relay releases the lock (it did, once its
     run returned; a run is now let finish instead of being cancelled, see the drain). After a
     `kill -9` the pushes committed meanwhile arrived up to 12 to 31 s late; now about 5 s. A shorter
     lease is also lost sooner when the database stalls (the relay then takes the lock again).
   - **Memory after a restart (section 8).** The hub reads the newest `push.recent + 1` entries in
     one read before it serves (`Log.Newest`, a method added to the driver interface: A1 `ORDER BY id
     DESC LIMIT`, A2 `XREVRANGE`). It then holds every push after the oldest of them; when the log
     holds no more than `push.recent`, every push after the log's `Trimmed` (read after the entries,
     so a push pruned meanwhile is not counted as held, and a client below `Trimmed` still gets its
     reset from the log). A replay from the log holds one page (1000 entries) and the last 10 000
     outbox ids (an A2 duplicate is logged again within one relay batch, at most 5000), never the
     whole replay; streams resuming from the same id at the same time share each page read.
     Overlapping reads from different ids are not merged: with the memory warmed they are the
     clients away for longer than `push.recent` pushes, and a topic filter in the read would serve
     them better than merging.
   - **The drain (section 8).** When the server's drain begins, the hub ends every open stream in
     one pass (reason `drain`), opens no more (`503 UNAVAILABLE`) and stops reading the log (no
     stream is left to hear it). The slow shutdown was not the streams: shutdown cancelled the hub's
     read of the log (and could cancel a relay run) half-way, and pgx then abandons the connection
     after a cancel request to PostgreSQL that it waits up to 15 s to be acknowledged; closing the
     pool waited for it (`shutdown action overran` on `db`). A log read or relay run under way at
     shutdown, or when the lock is lost, is now let finish, bounded by 10 s; a relay run that
     overlaps the next relay's is safe (section 3: `FOR UPDATE`, A1's primary key, A2's outbox ids).
     On the test Mac, closing tens of thousands of loopback connections at once also stalls the
     kernel for seconds; that is the machine's, not the module's.
   - **Memory per stream (section 8).** The stream buffer holds pointers to the pushes, each encoded
     once as its SSE event and shared by every stream; it grows only as far as the stream falls
     behind (an idle stream holds none). `push.stream.buffer` still counts pushes and a full buffer
     still ends the stream. A stream keeps two goroutines (the handler and net/http's read of the
     connection, which notices a client that left at once); one would need a hijacked connection
     that notices a closed client only at its next write.
12. **Snapshot cursor (section 9.1, 2026-11-09).** Go `pushes.Cursor()` returns the hub's highest
    dispatched id as a decimal string ("0" for an empty log), with no context and no error. It is
    read before the state; a lagging cursor is safe. `Last-Event-ID: 0` works as any other id: it
    replays everything after 0, or resets with `retention` if the log has trimmed past it.
13. **Invalid `Last-Event-ID` (section 7, 2026-11-09).** A `Last-Event-ID` (header or `lastEventId`
    query) that is present but not a non-negative integer (`12-34`, `abc`, `-1`) is answered with
    `reset` (reason `unknown-id`, id `latest`) after `subscribed`, then the stream continues live.
    Before, it was treated as absent and the client silently missed changes. An empty or blank value
    is still no cursor. `conformance/push/replay.json` has the case.

14. **The Java build (P2a, 2026-11-09).** `framework-push` implements sections 1 to 11 and the amendments
    above; a Java host and a Go host on one database share the tables `fw_push_outbox` and
    `fw_push_log`, the channels `fw_push_outbox`, `fw_push_log` and `fw_push_end` (the same payloads),
    the relay lock `svc:push-relay` and the migration history key `push`. Stream tokens are valid on
    the hosts that share `push.stream.secret` only, as before. What the Java build settled:
    - **Layout.** One module, `framework-push`, holds the core and the A1 driver: the outbox is always
      on the default database and the relay is database-bound, so there is no database-free core to
      split off as `framework-locks` / `framework-locks-database` are. The `Log` interface is public,
      so a Redis driver can be its own module (`framework-push-redis`, like `framework-locks-redis`);
      `PushModule.Builder.redis(provider)` plugs the Redis one in, and `push.driver` `redis` without it
      is a start-up problem (amendment 15 replaced the first form of this, `Builder.log`). The value `Push` and the writing of the outbox row (`PushOutbox`) are in
      `framework-usecase`, which the runner needs and which `framework-push` depends on, as Go's
      `internal/pushrow` is shared by `usecase` and `push`.
    - **Publishing (section 2).** `plan.andPush(push)` adds a push after the steps added so far (and
      after the appends of the last of them), written by the runner in the attempt's transaction
      (step code `PUSH`); a plan always has an event step, so the Go case "a plan of pushes only" does
      not arise. `Push.of(topic, type, data)` (a map, a record or a Jackson tree; map keys sorted) and
      `Push.ofJson(...)` refuse a bad topic, type or data with an `InvalidPushException` (an
      `IllegalArgumentException` with the reason code of `topics.json`); thrown while a use case is
      decided it fails the run as `INTERNAL` / `INVALID_PUSH` (not `EXECUTE`), logged as a bug, as does
      a push over `push.max-data-bytes`. The module tells the runner the limit when it boots
      (`UseCases.pushMaxDataBytes`; default 16384). `Pushes.publish(tx, context, push)` takes the
      framework's `Tx`; over the limit it throws `InvalidPushException` `DATA_TOO_LARGE`.
    - **Relay (section 3, amendments 2 and 11).** The loop `push-relay` is gated by the lock
      `svc:push-relay` with the lease `push.relay.lease`; its task holds the lock for as long as it
      runs, listening on `fw_push_outbox` (a connection of its own) and polling every
      `push.relay.interval`. The lock's loss interrupts the loop's thread; a run is made on a thread
      of its own that nothing interrupts and is waited for up to 10 s, so a stop or a lost lock never
      leaves a run half-way.
    - **The hub.** Streams run on the HTTP server's virtual threads, one each, parked on a queue; the
      hub keeps two virtual threads (the reader of the log and the driver's follower, whose pooled
      connection it holds for as long as it runs). It warms its memory when the application boots,
      before the server is started, and the application's start waits up to 10 s for the follower.
      The server's drain reaches the hub through `HttpServer.beforeDrain`, so every stream ends at
      once. **Difference from Go:** a client that went away is noticed at the stream's next write,
      at the latest at the heartbeat (`http.sse.heartbeat`, 25 s by default, which the push stream
      uses), not at once; its slot under the limits of section 8 is freed then.
    - **Authentication (6.1).** `PushAuth.authModule()` authenticates a subscribe through the auth
      module as Go's `AuthModule` does. The build added `AuthContextResolver.carriesCredentials` and
      `AuthContextResolver.authenticated` and `FieldPrincipal.keyThumbprint` (the token's
      `cnf.jkt`), the parallels of Go's `Module.CarriesCredentials`, `auth.Current` and
      `FieldPrincipal.KeyThumbprint`.
    - **Revocation (amendment 7).** `PushHolders.of(FieldRevocation)` in `framework-flowcatalyst` is
      Go's `flowcatalyst.PushHolder` (the module depends on `framework-push` optionally);
      `Pushes.endStreams(Holder)` is `EndStreams`.
    - **Configuration (section 10).** The ranges are checked when the module is built and reported
      together as a start-up failure; topic rules are declared on the module's builder (`topic`,
      `publicTopic`), and an open rule is a start-up problem as in Go.
15. **The Java Redis driver (A2, 2026-11-09).** `framework-push-redis` (`RedisPushLog`, `RedisPushLogs`)
    is the Go `RedisStreamLog` on Jedis: the same keys (`<prefix>fw:push`, `<prefix>fw:push:end`), the
    same fields in the same order, the same payload on the end channel, the same id mapping
    (`ms * 2^20 + seq`, a sequence of 2^20 or more is an error), `Append` as one `MULTI`/`EXEC` of
    `XADD`, `ReadAfter` by exclusive `XRANGE`, `Newest` by `XREVRANGE`, `Latest` the stream's
    `last-generated-id`, `Trimmed` its `max-deleted-entry-id`, `Prune` by `XDEL` in batches of 1000
    that never deletes the newest and sends nothing for an edge at or before the epoch, and `Follow`
    by `XREAD BLOCK` (one second) plus a subscription to the end channel. The contract tests of
    section 14 and the wire and replay vectors run on both drivers. What differs from Go, and why:
    - **Selection.** Go's `RedisClients(clients, prefix)` option becomes
      `PushModule.Builder.redis(RedisPushLogs.redis(prefix))`: the provider reads `push.redis-instance`
      (the default Redis instance when unset) from the framework's `RedisConnections`, so `RedisModule`
      is listed too. `push.driver=redis` without the builder call is a start-up problem naming it;
      with `push.driver=postgres` the call is ignored, as in Go. `Builder.log(provider)` stays for a
      driver of your own and wins over both settings. This is amendment 14's "layout" rule, refined:
      `framework-push` cannot depend on the driver module (no scanning, no reflection for wiring).
    - **Failure.** `Follow` throws `LogException` where Go returns an error; `LogException` gained a
      constructor with a message only (a stored entry that cannot be read has no cause).
    - **Connections.** Jedis has no cancellable blocked read, so the subscription runs on a virtual
      thread of its own and `follow` unsubscribes and joins it when it returns; a follower holds two
      pooled connections (the subscription and the `XREAD`).
    - **Tests.** The Java contract suites take a `LogBackend`; `framework-push` publishes its test
      classes (a `test-jar`) and `framework-push-redis` extends them. The replay vector's host starts
      its clock at the epoch for every driver, because A2's vector ids are milliseconds near 0 and a
      retention edge of "clock less retention" would otherwise prune them while the test loads the log
      (the same race Go fixed).
16. **Multi-application hosts (A4, Go, 2026-11-09).** The framework side of the platform gateway
    (`docs/push-gateway-spec.md` of the platform repository, sections 2.1, 4, 9 and 10), in Go only for
    now (the gateway is Go; the Java module has none of it). A host that serves one application
    behaves exactly as before: every item below is opt-in, and the error bodies and codes of the
    existing limits and denials are unchanged.
    - **Many applications in one host (6.3, 6.4, 1).** `push.MultiApplication(rules, keys, opts...)`
      is a `Build` option. `rules` is a `RuleProvider` (`Rules(application) []Rule`), `keys` a
      `TicketKeyResolver` (`PublicKey(issuer, kid) (ed25519.PublicKey, bool)`). Both are read for every
      subscribe and listen, so the host may change what they answer at any time (the gateway reloads on
      sync); the module caches nothing. A topic is granted by a rule of its own application only: the
      application is the topic's first segment, and the rules are `rules.Rules(application)`, those
      of another application's pattern ignored. A ticket is verified with the key the resolver gives
      for its `iss` and `kid` (an `iss` that is not an application code, an unknown issuer or kid
      grants nothing), and grants topics of its issuer's application only (a ticket of `alpha` naming
      a `beta` topic grants nothing: `topic-outside-iss`). Tickets of several issuers compose in one
      subscribe. A ticket naming another client than the stream's grants nothing, as 17.4 says: the
      stream's client is the credentials' or, without credentials, that of the first valid ticket (or
      the client the body names, below), whatever the issuers. An application the providers do not
      know has its topics denied `TOPIC_FORBIDDEN` (or `TICKET_INVALID` when a ticket that fails
      names the topic), never an error for the whole subscribe. In this mode the rules declared on
      `Declared` (`Topic`, `PublicTopic`) are a start-up problem: the providers are the only source.
      `push.ticket.key` is used only to mint tickets (`Ticket` mints for the host's `Application`) and
      to serve `GET {base}/push/keys`; `DeclareWith(app, OptionalTicketKey())` makes it optional for a
      host that mints nothing, and the endpoint then answers `{"keys":[]}` and `Ticket` fails. A
      single-application host still needs the key, and `Build` reports its absence.
    - **Limits per application (8).** `AppLimits{MaxStreams, MaxDataBytes}` come from an
      `AppLimitProvider` (`AppLimitsFrom(p)`, read for every subscribe, listen and publish, so the
      gateway's console edits apply at once), with a default (`DefaultAppLimits(l)`; initially
      `DefaultAppMaxStreams` = 20 000 streams and push.max-data-bytes); a field that is zero or less
      takes the default. The hub counts the open streams of each application on the instance; a stream
      whose granted topics span several applications counts against each of them. A subscribe or a
      listen that would take an application over its `MaxStreams` is `429 PUSH_LIMIT`, checked with
      the existing limits (after them), with the scope and the application in the envelope's
      `details`: `{"error":"PUSH_LIMIT","message":"...","details":{"scope":"application","application":"beta"}}`.
      The existing limits keep their body without `details`. `MaxDataBytes` replaces
      push.max-data-bytes for the topics of the application (at most 65536) when a push is
      published: `Publish`, `PublishAlone` and `PublishBatch` accept a push for any application in
      this mode and refuse data over its application's limit with `DATA_TOO_LARGE`. Left to the
      gateway: publish rate and the pause (it limits at ingest, before it writes), refusing pushes for
      an application it does not know, and the `usecase.Push` runner, whose limit stays the host's
      push.max-data-bytes.
    - **The `client` member (6.1).** The subscribe body takes an optional `client`
      (`[A-Za-z0-9._-]{1,100}`, else `400 VALIDATION`): `{"topics":[...],"tickets":[...],"client":"..."}`.
      An authenticator that can act for several clients implements `ClientAuthenticator`
      (`AuthenticateClient(r, client)`, `client` being "" when the body names none), which the
      module calls instead of `Authenticate`; it answers `400 VALIDATION` for a credential with
      several clients and none named, and `403 CLIENT_FORBIDDEN` for one that cannot use the client
      named. The module checks once more, for every authenticator, that a client named equals the
      Context's client, else `403 CLIENT_FORBIDDEN`; an `Authenticator` that is not a
      `ClientAuthenticator` (`AuthModule`) behaves as before when `client` is absent. Without
      credentials the stream's client is the first valid ticket's that names the client given; if
      none does, `401`. A platform-wide stream is a `client` of the platform client's value
      (`_platform`) that the authenticator permits.
    - **Hosting without the framework server.** `Pushes.Handler(opts ...httpfw.Option)` serves the
      three routes under `BasePath` as an `http.Handler` (mount it without stripping the prefix),
      through the framework's dispatcher: the error envelope, the correlation id, the body limit and
      an access log that never records the query (so the stream token is not logged). `httpfw.WithDrain`
      (new, public) gives a dispatcher the drain signal that `httpfw.Draining` returns.
      `Pushes.Drain()` begins the module's drain for a host without an httpfw server: the hub ends
      every stream at once (reason `drain`) and a subscribe or listen is `503 UNAVAILABLE` (8, 17.11).
      It is idempotent, and the module also calls it when the application shuts down.
      `DeclareWith(app, ExternalMigrations())` leaves the module's migration set out: the host
      creates `fw_push_outbox` and `fw_push_log` itself, byte for byte as
      `go/push/migrations/V0001__create_push_tables.sql`, and keeps them identical to it when the
      module changes.
    - **Batch publishing (2).** `Pushes.PublishBatch(ctx, tx, items ...Publication)` and
      `PublishBatchAlone(ctx, items...)` write pushes, each a `Publication{Context, Push}` with its
      own client, with one statement in the caller's transaction (or one of its own): all of the rows
      and the one notification exist when it commits, or none. Every item is checked before anything
      is written (a zero Context or Push, data over its limit), and the error names the item's
      position. The rows get outbox ids in the order given, so the log keeps it.
    - **Release.** The core module `github.com/flowcatalyst/framework/go` is versioned by the tag
      `go/vX.Y.Z` (`make -C go release-go BUMP=0.1.0` for the first); the platform imports it from the
      first such tag.
17. **`push.driver=platform` (A4, Go, 2026-11-09).** The publish sink for an application that uses
    the platform's push gateway (`docs/push-gateway-spec.md` of the platform repository, sections 2.1
    item 4, 6.1, 6.4 and 8) instead of hosting push itself. Application code does not change
    (`usecase.Push`, `Pushes.Publish`); the application moves between the two by configuration.
    - **Setting.** `push.driver` accepts `platform` next to `postgres` and `redis`. With it the module
      keeps no log, hub, relay or retention loop and no `push:log` health check, serves **no routes**
      (`Routes()` is empty, `Handler()` answers 404, `Drain()` does nothing), and needs no
      `push.stream.secret` (a value that is set is ignored, so a deployment can switch by changing one
      key; `Authenticate` and `RedisLog` options are ignored with a warning, `BasePath` has no effect).
      `push.ticket.key` is **required** (tickets are still minted here and verified by the gateway),
      the lock instances are not used, the default database instance is. `platform` with
      `MultiApplication` is a start-up problem (a gateway does not publish through another).
    - **The row.** `usecase.Push` (any use case), `Pushes.Publish`, `PublishAlone`, `PublishBatch` and
      `PublishBatchAlone` write one `PUSH` row per push to the application's `outbox_messages`, in the
      caller's transaction (the run's transaction for `usecase.Push`, in plan order; the caller's `tx`
      for `Publish` and `PublishBatch`; its own for the `...Alone` forms), with the columns of the
      shared `insert.sql` and the statement `WITH woken AS (SELECT pg_notify('fc_outbox_push', ''))
      INSERT ... SELECT ... FROM woken`, so the platform's outbox processor wakes when the transaction
      commits and not before. Row: `id` a TSID (or the push's own id), `type` `PUSH`, `message_group`
      the **topic** by default, `status` 0, `client_id` the Context's client, `payload_size` the
      payload's bytes, `headers` null. Payload, compact, members in this order: `topic`, `type`,
      `data` (the JSON object itself), `clientCode` (the Context's client, absent for the platform
      client, like a dispatch job), `pushId` (= the row id). It is the vector
      `conformance/outbox/outbox-push.json`, which the Go outbox conformance test writes and compares
      byte for byte, with its `invalidPushes` (the codes of section 1, and `PUSH_ID_INVALID`,
      `CLIENT_INVALID`).
    - **Options.** `usecase.Push(topic, type, data, opts...)` takes `PushUngrouped()` (no message
      group: the processor sends such rows concurrently, no order), `PushMessageGroup(g)`,
      `PushID(id)` (the row id and `pushId`, `[0-9A-Za-z_-]{1,26}`) and `PushClientCode(code)` (a
      client other than the Context's, by identifier). The first two are ordering hints the hosted
      drivers ignore (they log in commit order). `PushID` and `PushClientCode` exist only in this row:
      with a hosted driver a push that carries them fails the run as `INTERNAL` / `INVALID_PUSH`
      (reason `OPTION_UNSUPPORTED`) rather than lose them silently. The runner learns the driver from
      `Pushes.RunnerOption()` (`usecase.WithPlatformPushes`); `usecase.NewRunner(b, outbox, db,
      pushes.RunnerOption())` is unchanged. The data limit is `push.max-data-bytes`, as before.
    - **Ordering.** The guarantee "log order = commit order" is the gateway's (spec 6.5): the pushes
      of one message group (default one topic) of this application arrive in outbox order.
    - **Sync.** The `flowcatalyst` module (`flowcatalyst.WithPushes(pushes)`) sends the gateway the
      rules declared on `push.Declared` and the JWKS of `push.ticket.key` with
      `POST /api/applications/{app}/push/sync`, body `{"rules": [{"pattern", "requires", "public"}],
      "keys": <JWKS>}` (`Pushes.GatewaySync()`), a declarative full replace, as a category `PUSH` after
      the definitions: by the `sync` command (with `--dry-run` it lists the patterns and sends
      nothing) and, when `flowcatalyst.sync.on-boot` is set, at start-up (a failure is logged, never
      fatal). Without `WithPushes`, or with another driver, the module does nothing for push. With the
      module disabled the rules are not synced (warned at start). The private seeds never leave the
      application.
    - **No local cursor.** The gateway owns the log, so `Pushes.Cursor()` returns `""` (and
      `HasCursor()` is false) instead of a number that means nothing to the gateway. A snapshot
      endpoint that returns `""` gives the client nothing to send as `Last-Event-ID`; its first listen
      is then live, and the client closes the gap between its state read and the listen by reading the
      state once more when its stream reports `subscribed` (section 9.1's reasoning, applied by the
      client). A cursor read from the gateway is a platform API that does not exist yet.
    - **Streams.** `Pushes.EndStreams` and `EndPrincipal` return `ErrPlatformDriver`: the gateway ends
      the streams of a platform sign-out and of a revoked field login itself (spec section 5). An
      application's own ticket-only sessions cannot be ended from here yet.
    - **Moving between hosting and the gateway.** The clients' `baseUrl` changes and they reload once;
      their last ids mean nothing on the other host (`reset` with `unknown-id`, spec 6.4).
    - **Java** has none of this (`framework-push`, `Plan.andPush`, the `flowcatalyst` sync): it would
      need the same `platform` driver value, a `PUSH` row writer in `framework-usecase` for
      `Plan.andPush` and `Pushes.publish`, `Pushes` without hub or routes, and the push category in
      the `flowcatalyst` module's sync.
