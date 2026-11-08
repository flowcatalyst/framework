<!-- types: Pushes, Declared, Push, Rule, RuleOption, Option, Driver, Log, Entry, OutboxRow, Holder, Follower, PostgresLog, RedisStreamLog, Credentials, Authenticator, AuthenticatorFunc, TicketOptions, Denial, InvalidPushError -->
# Go: push to clients

Package `push` implements the [push contract](../../contracts/push.md) (P1a): small messages to
browsers, apps and field devices listening over server-sent events. A push is written in the same
transaction as the change it announces, so it is sent only if that transaction commits; one relay
at a time moves committed pushes, in order, into a log every instance follows; each instance's hub
fans them out to its streams, replays what a reconnecting client missed and sends a `reset` to one
that was away too long. This build has both log drivers: `postgres` (A1, `PostgresLog`, the default) and `redis`
(A2, `RedisStreamLog`), selected by `push.driver`. Snippets come from the package tests
(`go/push/*_test.go`); the vectors in `conformance/push/` pass.

## Declaring and building

`push.Declare` declares the settings `push.*` (section 10 of the contract), a claim on the `push`
component and the migration set `push` (tables `fw_push_outbox` and `fw_push_log`, applied to the
default database instance). The topic rules are declared on the result. `Build` takes the default
database instance and the lock instances (the relay is gated by `svc:push-relay` on the default
one) and an authenticator for subscribe requests (from `go/push/authmodule_test.go`):

```go
pd := Declare(app)
pd.Topic("test:order:{id}", Requires("test:order:view"))
pd.Topic("test:field-worker:{principal}:jobs")
```

```go
p, err := pd.Build(ctx, b, db, lk, Authenticate(AuthModule(am)))
if err != nil {
	return nil, err
}
srv, err = httpfw.NewServer(b, web, []*httpfw.Routes{am.Routes(), p.Routes()}, httpfw.WithResolver(am.Resolver()))
```

`push.stream.secret` (at least 32 characters, the same on every instance) and `push.ticket.key` are
required secrets. `push.ticket.key` is `<kid>:<base64url of a 32-byte Ed25519 seed>`, comma
separated, the signing key first and the previous key second during a rotation.

Options: `Authenticate` (without it every subscribe needs tickets), `Application(code)` (default: the
application's name; the first segment of every topic and the issuer of its tickets), `BasePath("/api")`
for the routes, and, when `push.driver` is `redis`, `RedisClients(clients, prefix)` (the A2 driver on the Redis
instance `push.redis-instance`, the default instance when it is not set) or `RedisLog(...)` for a
driver of your own. `Build`
registers the loops `push-relay` (gated) and `push-retention` (every minute, deletes log rows older
than `push.retention` but never the newest), the informational health check `push:log`, and the hub,
which starts with the application once it follows the log and stops at shutdown.

## Publishing

In a use case, `usecase.Push(topic, type, data)` is a plan step, written in the attempt's
transaction in plan order, for the Context's client (the platform client's push has a NULL client and
goes to every stream on the topic). It is not an event. From `go/usecase/push_test.go`:

```go
return usecase.NewPlan(e.created(cx, t1),
	usecase.Push("test:thing:thg_push", "thing.creating", map[string]any{"z": 1, "a": "x"}),
	usecase.Insert(usecase.NewFresh(cx, t1), thingRepo{}),
	usecase.Push("test:thing:thg_push", "thing.created", map[string]any{"id": t1.id})), nil
```

Pass `pushes.RunnerOption()` to `usecase.NewRunner` so the runner refuses data over
`push.max-data-bytes`. An invalid topic, type or data fails the run as `INTERNAL` / `INVALID_PUSH`
(logged as a bug) before anything is written; a failure writing it is `INTERNAL` / `PUSH`.

Outside a use case, `push.New(topic, type, data)` checks a push (the error is an
`*InvalidPushError` with a reason code of `conformance/push/topics.json`), `Publish` writes it in a
transaction the caller owns (a `pgx.Tx`), and `PublishAlone` in one of its own:

```go
p, _ := New("test:order:1", "order.updated", map[string]any{"v": "rolled back"})
err := pgx.BeginTxFunc(t.Context(), n.db.Pool(), pgx.TxOptions{}, func(tx pgx.Tx) error {
	if err := n.p.Publish(t.Context(), tx, fw.ContextFor(acme), p); err != nil {
		return err
	}
	return errors.New("roll back")
})
```

Data is a JSON object: a map or struct (marshalled, map keys sorted) or JSON text as
`jsontext.Value` or `[]byte`; it is stored compact. Say what changed, not the state: the client reads
the state through the API, with its normal access checks.

## Subscribing and listening

`POST {base}/push/subscriptions` takes `{"topics": [...], "tickets": [...]}` and answers the stream
token, its expiry and the granted and denied topics. A topic is granted by a rule for the request's
Context or by a ticket. `GET {base}/push/stream?st=<token>` is the stream: the `retry` field, a
`subscribed` event, then the pushes missed since `Last-Event-ID` (the header or `lastEventId`) or a
`reset`, then the live pushes. `GET {base}/push/keys` is the ticket keys' JWKS. The answers and the
bytes are the contract's (sections 6 and 7) and `conformance/push/wire.json`; the access log never
records the query string, so the stream token is not logged.

`AuthModule(m)` authenticates a subscribe with the auth module: a bearer token, a field token with its
DPoP proof, or the session cookie with its CSRF rule. A request with none of them is not refused; it
needs a valid ticket, which then names the stream's client. Bad credentials are `401` even with good
tickets. Any other scheme implements `Authenticator` (a `Credentials` value: the Context, the
credential's expiry, and for a field worker the DPoP key thumbprint, device and login family).

## Rules and tickets

A rule (`Declared.Topic`, `NewRule`) lets a Context subscribe without a ticket: `{principal}` must be
the Context's principal, `{client}` its client, any other `{name}` matches one segment, and every
`Requires` permission must be held. A rule with neither `{principal}` nor `Requires` is refused at
start-up (`ErrOpenRule`); `PublicTopic` declares a deliberately open one. Rules are data
(`Pushes.Rules`), so a gateway can apply them.

Anything a rule cannot express is a ticket, minted by the application's own endpoint after its own
access check (from `go/push/http_test.go`):

```go
ticket, err := n.p.Ticket(fw.ContextFor(acme).WithPrincipal(fw.MustPrincipalID("prn_9")), "test:order:{id}", "test:public")
```

```go
bound, _ := n.p.TicketWith(fw.ContextFor(acme), TicketOptions{ProofKey: "jkt-1"}, "test:order:{id}")
```

A ticket is an EdDSA JWS, valid for two minutes, for the Context's client. `TicketOptions.ProofKey`
binds it to a device key: the subscribe must then be authenticated with a field token bound to that
key (`auth.FieldPrincipal.KeyThumbprint`). `StreamExpiry` caps the stream's life.

## Streams, backpressure and ends

Each stream has a buffer of `push.stream.buffer` pushes; a stream that falls further behind is ended
without an event and resumes from its `Last-Event-ID`. Each instance keeps `push.recent` pushes in
memory and replays from there when it holds everything after the client's id, from the log
otherwise. Over `push.max-streams` or `push.max-streams-per-principal` a subscribe, or a stream
opened with a token, is `429 PUSH_LIMIT`. At the token's expiry the stream sends `auth-expired` and
ends; at the drain it ends.

`EndStreams(ctx, Holder{...})` ends the matching streams on every instance with `auth-expired`. For
field revocations, the `flowcatalyst` module maps each event:

```go
flowcatalyst.OnFieldRevocation(receivers, func(ctx context.Context, cx fw.Context, ev flowcatalyst.FieldRevocation) error {
	return pushes.EndStreams(ctx, flowcatalyst.PushHolder(ev))
})
```

(from the doc comment of `flowcatalyst.PushHolder`). Ending streams when a session signs out is not
built yet.

## The log driver

`Log` is what the relay, the hub and the HTTP layer use, so another driver plugs in without them
changing: `Append` (in the relay's transaction for A1; a driver outside the database appends at once
and keeps `Entry.OutboxID`, and the hub drops an outbox id it has already seen), `ReadAfter`,
`Latest`, `Trimmed` (the highest id deleted: a `Last-Event-ID` below it gets a `reset`), `Prune`,
`Follow` (wake-ups and the ends of streams), `End` and `Ping`. `PostgresLog` is A1: ids `max(id)+1`
in the relay's transaction, `pg_notify('fw_push_log', <id>)` and `pg_notify('fw_push_end', <holder>)`.

### The Redis driver (A2)

Set `push.driver=redis` and give `Build` the Redis instances (`push.redis-instance` names one; the
default instance otherwise). The database still holds the outbox and the relay still takes its
lock there; only the log moves. From `go/push/harness_test.go`:

```go
clients, err := redisfw.Connect(ctx, b, rd)
if err != nil {
	return nil, err
}
opts = append(opts, RedisClients(clients, c.backend.redis.Prefix))
```

`NewRedisStreamLog(client, prefix)` builds the driver on a go-redis client of your own. The prefix
("" in production) goes before both keys, so tests give each test its own.

- **Keys.** The stream `<prefix>fw:push` (one entry per push with the fields `topic`, `type`,
  `client`, `data`, `outbox` and `logged_at`) and the pub/sub channel `<prefix>fw:push:end`.
  Redis 6.2 or Valkey is needed (exclusive range ids). One standalone server, as everywhere in the
  framework.
- **Ids.** The entry id `ms-seq` is sent as `ms * 2^20 + seq`, so ids increase but are not
  contiguous, and are far above the 1, 2, 3 of A1. A sequence of 2^20 or more (a million entries in
  one millisecond) is an error, not a wrong id. Clients treat ids as opaque, as the contract says.
- **Append** is one `MULTI`/`EXEC` of `XADD`, in the relay's order, before the relay deletes the
  outbox rows. A crash between the two adds the rows again; the hub drops an outbox id it has seen,
  live and in a replay.
- **Follow** blocks in `XREAD BLOCK` (one second at a time, so a stopped follower returns within a
  second) and calls `Grown` once when it starts and after every wake-up; it subscribes to the end
  channel, and `End` is a `PUBLISH` every instance receives, this one included. When the connection
  fails `Follow` returns the error; the hub calls it again after a pause (one second, doubling to
  thirty) and polls the log every `push.relay.interval` meanwhile, so the application stays ready
  (`push:log` is informational) and pushes are late by at most the interval. An end published while
  the subscription is down is lost, as with `LISTEN`/`NOTIFY`.
- **Retention.** `Prune` deletes the entries whose id is below the Redis server's clock at the
  retention edge, always keeping the newest, with `XDEL` in batches of 1000. It does not use `XTRIM`:
  Redis moves `max-deleted-entry-id`, which is what `Trimmed` reports, on `XDEL` only. The ids are
  the server's clock, so keep it within seconds of the application's.
- **Latest** is the stream's `last-generated-id`, which stays right when the stream is empty or
  trimmed; **Trimmed** is `max-deleted-entry-id` (0 when nothing was deleted).

## Metrics

`fw_push_published_total`, `fw_push_relay_lag_seconds` (a timer), `fw_push_log_id`,
`fw_push_streams_open`, `fw_push_delivered_total`, `fw_push_streams_ended_total{reason}` (`client`,
`slow`, `expired`, `revoked`, `drain`), `fw_push_resets_total{reason}` and
`fw_push_replay_source_total{source}` (`memory`, `log`).

## Load test

`go/examples/push-demo` is a small server with the push module (public topic `pushdemo:t:{id}`, a
ticket endpoint, a publish endpoint, several listening ports, `/debug/stats` and pprof) and a load
generator (`loadgen`: idle, fan-out, broadcast and restart modes, delay histogram, loss check, with
`Last-Event-ID` resumes); `loadtest.sh` starts and stops the processes. The first local run, its
method, the commands and the findings (about 68 KiB per stream, a 15 s relay-lock delay after a
`kill -9`, resumes after a restart served from the log) are in
[the load test results](../../push-load-test-results.md).
