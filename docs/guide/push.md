<!-- types: PushModule, Pushes, Push, PushOutbox, InvalidPushException, Rule, Holder, Log, Entry, OutboxRow, Follower, PostgresLog, RedisPushLog, RedisPushLogs, LogException, Credentials, PushAuthenticator, PushAuth, TicketOptions, Denial, PushHolders, Plan, UseCases, FieldPrincipal, AuthContextResolver, HttpServer, Tx -->
# Push to clients

Module `framework-push` implements the [push contract](../contracts/push.md) (P2a): small messages
to browsers, apps and field devices listening over server-sent events. A push is written in the same
transaction as the change it announces, so it is sent only if that transaction commits; one relay
at a time moves committed pushes, in order, into a log every instance follows; each instance's hub
fans them out to its streams, replays what a reconnecting client missed and sends a `reset` to one
that was away too long. Two log drivers: `postgres` (A1, `PostgresLog`, the default, in this module) and
`redis` (A2, `RedisPushLog`, in `framework-push-redis`: [the Redis driver](#the-redis-driver-a2)).
The Go framework has the same module ([Go: push to clients](go/push.md)); a Java host and a Go host
on one database share the tables, the notification channels and the relay lock. Snippets come from
the module's tests (`framework-push/src/test`); the vectors in `conformance/push/` pass.

## Setting up

List the module after the database, locks, use case, HTTP and (for `PushAuth.authModule()`) auth
modules. It declares the settings `push.*` (contract section 10), a claim on the `push` component,
and the migration set `push` (`fw_push_outbox` and `fw_push_log`, applied to the default database
instance). The relay needs a lock instance for `svc:push-relay`. The rules are declared on the
builder, from `TestNode`:

```java
PushModule.Builder builder =
    PushModule.builder().application(new ApplicationCode("test")).authenticate(lookup -> HEADER_AUTH);
b.topic("test:order:{id}", "test:order:view");
b.topic("test:user:{principal}");
b.topic("test:client:{client}:news", "test:news:view");
b.publicTopic("test:public");
```

and with the auth module, from `AuthModuleTest`:

```java
b.authenticate(PushAuth.authModule());
b.topic("test:field-worker:{principal}:jobs");
```

`push.stream.secret` (at least 32 characters, the same on every instance) and `push.ticket.key` are
required secrets. `push.ticket.key` is `<kid>:<base64url of a 32-byte Ed25519 seed>`, comma
separated, the signing key first and the previous key second during a rotation. Every range of the
contract is checked when the module is built and reported together as a start-up failure, as is a
rule with neither `{principal}` nor a required permission, a topic outside the application's code
and a bad base path (`basePath("/api")` serves `/api/push/subscriptions`).

The module registers the loops `push-relay` (gated; health check `background:push-relay`) and
`push-retention` (every minute: deletes log rows older than `push.retention`, never the newest), the
informational health check `push:log`, the hub (started with the application once it follows the
log, stopped at shutdown) and the three routes. The hub holds one pooled connection for as long as it
runs (it listens for new log rows) and the relay one more while it holds the lock: keep `pool.max` of
the default database comfortably above that.

The relay's lock has the lease `push.relay.lease` (default 5 s, 1 s to 60 s), refreshed every third of
it. A relay that stops cleanly releases the lock, so another instance takes over at once (a rolling
deploy); after a crash the pushes committed meanwhile wait for the lease, then arrive. A relay run
that is under way when the loop stops or the lock is lost is let finish (bounded by 10 s), not
interrupted.

Before the server starts, the hub reads the newest `push.recent` pushes of the log into memory in one
read (`Log.newest`), so the clients of an instance that was just restarted resume from memory like
those of one that stayed up. The heartbeat of an idle stream is `http.sse.heartbeat`; the server's
drain ends every stream at once through `HttpServer.beforeDrain`.

## Publishing

In a use case, `plan.andPush(Push.of(topic, type, data))` adds a push written in the attempt's
transaction right after the steps added so far, for the context's client (the platform client's push
is stored with a NULL client and goes to every stream on the topic). It is not an event: it is not
audited and is never the result. From `PlanPushTest`:

```java
Plan<Tick> plan = Plan.emit(new Tick(events.metadata(c, TICKED, new TickId(cmd.id()))));
return plan.andPush(Push.of("test:order:" + cmd.id(), "order.updated", Map.of("orderId", cmd.id(), "version", cmd.version())));
```

`Push.of` takes a map (keys sorted), a record or a Jackson tree and stores compact JSON;
`Push.ofJson` takes JSON text, kept as written apart from the whitespace outside strings. An invalid
topic, type or data throws `InvalidPushException` (an `IllegalArgumentException`; `reason()` is one
of the codes of `conformance/push/topics.json`). Thrown while a use case is decided, or for data over
`push.max-data-bytes` (which the module gives the runner, `UseCases.pushMaxDataBytes`), it fails the
run as `INTERNAL` / `INVALID_PUSH`, logged as a bug, before anything is written; a failure writing
the row is `INTERNAL` / `PUSH`.

Outside a use case, `Pushes.publish(tx, context, push)` writes the row and a notification in a
transaction you own, and `Pushes.publishAlone(context, push)` in one of its own. From `DeliveryTest`:

```java
try (Connection tx = n.dataSource.getConnection()) {
  tx.setAutoCommit(false);
  n.pushes.publish(io.flowcatalyst.framework.usecase.Tx.of(tx), Context.of(ACME), Push.of("test:order:1", "order.updated", Map.of("v", "rolled back")));
  tx.rollback();
}
```

Say what changed, not the state: the client reads the state through the API, with its normal
access checks.

## Subscribing and listening

`POST {base}/push/subscriptions` takes `{"topics": [...], "tickets": [...]}` and answers the stream
token, its expiry and the granted and denied topics. A topic is granted by a rule for the request's
context or by a ticket. `GET {base}/push/stream?st=<token>` is the stream: the `retry` field, a
`subscribed` event, then the pushes missed since `Last-Event-ID` (the header or `lastEventId`) or a
`reset`, then the live pushes. A `Last-Event-ID` that is present but not a non-negative integer gets
a `reset` with the reason `unknown-id`; an empty one is no cursor. `GET {base}/push/keys` is the
ticket keys' JWKS. The answers and the bytes are the contract's (sections 6 and 7) and
`conformance/push/wire.json`; the access log never records the query string, so the stream token is
not logged.

`PushAuth.authModule()` authenticates a subscribe with the auth module: a bearer token, a field token
with its DPoP proof, or the session cookie with its CSRF rule. A request with none of them is not
refused; it needs a valid ticket, which then names the stream's client. Bad credentials are `401`
even with good tickets. Any other scheme implements `PushAuthenticator` (a `Credentials` value: the
context, the credential's expiry, and for a field worker the DPoP key thumbprint, device and login
family).

### Snapshot with a cursor

A client that loads its state from a read endpoint and then listens can miss a change committed
between the two. Return `pushes.cursor()` with the state, and have the client open its first listen
with it as `Last-Event-ID` (field-js: the `lastEventId` option of `PushStream`). Take the cursor
BEFORE reading the state. It is the hub's in-memory last id, a decimal string (`"0"` for an empty
log), and costs no I/O; one that lags the log only replays more pushes. From `CursorTest`:

```java
String cursor = n.pushes.cursor(); // before the state read
n.publish(ACME, "test:order:1", "order.updated", Map.of("v", "B"));
```

## Rules and tickets

A rule (`PushModule.Builder.topic`, `Rule.of`) lets a context subscribe without a ticket:
`{principal}` must be the context's principal, `{client}` its client, any other `{name}` matches one
segment, and every required permission must be held. A rule with neither `{principal}` nor a
permission is refused at start-up; `publicTopic` declares a deliberately open one. Rules are data
(`Pushes.rules()`), so a gateway can apply them.

Anything a rule cannot express is a ticket, minted by the application's own endpoint after its own
access check (from `HttpTest`):

```java
String ticket = n.pushes.ticket(Context.of(ACME).withPrincipal(new PrincipalId("prn_9")), "test:order:{id}", "test:public");
String bound = n.pushes.ticketWith(Context.of(ACME), new Pushes.TicketOptions("jkt-1", null), "test:order:{id}");
```

A ticket is an EdDSA JWS, valid for two minutes, for the context's client. `TicketOptions.proofKey`
binds it to a device key: the subscribe must then be authenticated with a field token bound to that
key (`FieldPrincipal.keyThumbprint`, the token's `cnf.jkt`). `streamExpiry` caps the stream's life.

## Streams, backpressure and ends

Each stream has a queue of `push.stream.buffer` pushes; a stream that falls further behind is ended
without an event and resumes from its `Last-Event-ID`. A push is encoded once and the same bytes go
to every stream; a queue exists only while its stream is behind. Each instance keeps `push.recent`
pushes in memory and replays from there when it holds everything after the client's id, from the log
otherwise, one page of 1000 entries at a time, with streams resuming from the same id sharing each
page read. Over `push.max-streams` or `push.max-streams-per-principal` a subscribe, or a stream
opened with a token, is `429 PUSH_LIMIT`. At the token's expiry the stream sends `auth-expired` and
ends. A stream is one virtual thread parked on its queue: a client that went away is noticed at the
next write, at the latest at the heartbeat, and only then does its slot under the limits come free.

`Pushes.endStreams(Holder)` ends the matching streams on every instance with `auth-expired`
(`pg_notify('fw_push_end', ...)`, the same payload as the Go framework's). For field revocations,
`PushHolders.of` in `framework-flowcatalyst` maps each event (that module depends on `framework-push`
optionally):

```java
receivers.onFieldRevocation((ctx, event) -> pushes.endStreams(PushHolders.of(event)));
```

Ending streams when a session signs out is not built yet.

## The log driver

`Log` is what the relay, the hub and the HTTP layer use: `append` (in the relay's transaction for A1;
a driver outside the database appends at once and keeps `Entry.outboxId`, and the hub drops an outbox
id it has already seen), `readAfter`, `newest` (the newest entries, oldest first: the hub's memory at
start), `latest`, `trimmed` (the highest id deleted: a `Last-Event-ID` below it gets a `reset`),
`prune`, `follow` (wake-ups and the ends of streams), `end` and `ping`; failures are a `LogException`.
`PostgresLog` is A1: ids `max(id)+1` in the relay's transaction, `pg_notify('fw_push_log', <id>)`.
`PushModule.Builder.redis(provider)` gives the Redis driver, used when `push.driver` is `redis`
(ignored when it is `postgres`, as in Go); `Builder.log(provider)` gives a driver of your own, used
whatever `push.driver` says.

### The Redis driver (A2)

Add `framework-push-redis`, list `RedisModule` (the connection is configuration), set
`push.driver=redis` and give the builder the driver. `push.redis-instance` names the Redis instance
(the default instance when it is not set). The database still holds the outbox and the relay still
takes its lock there; only the log moves. From `RedisBackend` in the module's tests, the
configuration, the environment and the builder call of a node:

```java
return "redis:\n  instances:\n    default: { url: \"" + redis.url() + "\" }\n";
return Map.of("PUSH_DRIVER", "redis");
builder.redis(RedisPushLogs.redis(prefix));
```

`RedisPushLogs.redis()` has no prefix (production); `redis(prefix)` puts one before both keys, so
tests give each test its own. `new RedisPushLog(client, prefix)` builds the driver on a Jedis
`RedisClient` of your own. The driver is the Go framework's `RedisStreamLog` key for key and field
for field; a Java and a Go host can share one stream (the layout is asserted in
`RedisPushLogTest.appendWritesTheFieldsOfTheGoDriverInOrder`; a run against a Go host is not part of
the tests).

- **Keys.** The stream `<prefix>fw:push` (one entry per push with the fields `topic`, `type`,
  `client`, `data`, `outbox`, `logged_at` in that order; `logged_at` is microseconds since the epoch)
  and the pub/sub channel `<prefix>fw:push:end`, whose message is the `Holder`'s JSON. Redis 6.2 or
  Valkey is needed (exclusive range ids); one standalone server.
- **Ids.** The entry id `ms-seq` is sent as `ms * 2^20 + seq`: ids increase but are not contiguous and
  are far above the 1, 2, 3 of A1. A sequence of 2^20 or more is a `LogException`, not a wrong id
  (`RedisPushLogTest.aSequenceOf2To20IsRefused`).
- **Append** is one `MULTI`/`EXEC` of `XADD` in the relay's order, before the relay deletes the outbox
  rows. A crash between the two adds the rows again; each entry keeps its outbox id and the hub drops
  one it has already seen, live and in a replay (`RedisDriverTest.aDuplicateOutboxIdIsDeliveredOnce`).
  The replay remembers the last 10 000 outbox ids.
- **Follow** subscribes to the end channel on a virtual thread, calls `grown` and then blocks in
  `XREAD BLOCK`, one second at a time, so a stopped follower returns within about a second; `grown`
  is called after every wake-up. When a connection fails `follow` throws a `LogException`; the hub
  calls it again after a pause (one second, doubling to thirty) and polls every `push.relay.interval`
  meanwhile (`RedisDriverTest.theHubRecoversWhenRedisDropsItsConnections`). An end published while the
  subscription is down is lost. The follower holds two pooled connections (the subscription and the
  read): keep the instance's `pool.max` above that.
- **Retention.** `prune` deletes (`XDEL`, in batches of 1000) the entries whose id is below the Redis
  server's millisecond of the edge, never the newest; it does not use `XTRIM`, because only `XDEL`
  moves `max-deleted-entry-id`, which is what `trimmed` reports. An edge at or before the epoch
  deletes nothing (it would send the range `(0-0`, which Redis refuses). The ids are the server's
  clock, so keep it within seconds of the application's. `latest` is the stream's
  `last-generated-id`, which stays right when the stream is empty or trimmed.
- **Tests.** `DeliveryTest`, `HardeningTest`, `CursorTest` and the wire and replay vectors of
  `VectorsTest` are written against `LogBackend`; `framework-push` publishes its test classes and
  `framework-push-redis` runs them again as `RedisDeliveryTest` and the like, on a Redis container.

## Metrics

`fw_push_published_total`, `fw_push_relay_lag_seconds` (a timer), `fw_push_log_id`,
`fw_push_streams_open`, `fw_push_delivered_total`, `fw_push_streams_ended_total{reason}` (`client`,
`slow`, `expired`, `revoked`, `drain`), `fw_push_resets_total{reason}` and
`fw_push_replay_source_total{source}` (`memory`, `log`).

## What is not built

A reader in the shared UI packages (`@flowcatalyst/ui`; the platform's `@flowcatalyst/field-js` and
`@flowcatalyst/field-react-native` have one, `PushStream`), a sign-out hook, and the load test of
contract section 11 on the target (the Go module has a local run: [load test results](../push-load-test-results.md)).
