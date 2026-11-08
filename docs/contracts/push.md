# Contract: push to clients (server-sent events)

Status: **accepted 2026-11-08 (owner rulings in section 16); P1 (Go) in progress.** Drivers: `postgres` (A1) and
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
Go the plan fails as `INTERNAL` / `INVALID_PUSH`, logged as a bug).

## 2. Publishing

A push is published in a transaction, always:

- **In a use case** (write-path contract section 3): `plan.andPush(Push.of(topic, type, data))`
  (Go `usecase.Push(topic, type, data)`). The runner writes it in the attempt's transaction, in plan
  order, at step 5 (step code `PUSH`). It is not an event: it is not audited, is never the use
  case's result and does not count toward "every plan carries an event". A failed attempt writes no
  push.
- **Outside a use case:** `Pushes.publish(tx, context, push)` (Go `push.Publish(ctx, tx, cx, p)`)
  inside a transaction the caller owns, or `Pushes.publish(context, push)` in its own transaction.

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
| retention | rows older than `push.retention` deleted by a loop | `XADD ... MINID` at the retention edge |
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
  same reset with the reason `unknown-id`.
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
  from there when it can, from the log only when it must. After a deploy or a restart, thousands of
  clients resuming together are then served from memory, not the database.
- Limits, each refused with `429 PUSH_LIMIT` at subscribe:
  - `push.max-streams` per instance (default 50 000);
  - `push.max-streams-per-principal` (default 20).
- An instance that is draining (HTTP contract 13.1) ends its streams, as for any SSE stream, and
  answers no new subscribes. Its clients reconnect to another instance.

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

## 10. Configuration

| key | default | range |
|---|---|---|
| `push.driver` | `postgres` | `postgres`, `redis` |
| `push.redis-instance` | the default Redis instance | a configured instance |
| `push.retention` | 24h | 10m to 24h |
| `push.max-data-bytes` | 16384 | 1 to 65536 |
| `push.max-topics` | 100 | 1 to 1000 |
| `push.relay.batch` / `push.relay.interval` | 500 / 1s | 1–5000 / 50ms–10s |
| `push.stream.buffer` | 256 | 16 to 10 000 |
| `push.stream.max-lifetime` | 1h | 1m to 24h |
| `push.recent` | 10000 | 0 to 1 000 000 |
| `push.max-streams` / `push.max-streams-per-principal` | 50000 / 20 | |
| `push.stream.secret`, `push.ticket.key` | (secrets, required) | |

## 11. Metrics, health and the load test

**Metrics:**
- `fw_push_published_total`;
- `fw_push_relay_lag_seconds` (oldest outbox row's age);
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
