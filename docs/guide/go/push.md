<!-- types: Pushes, Declared, Push, Rule, RuleOption, Option, Driver, Log, Entry, OutboxRow, Holder, Follower, PostgresLog, RedisStreamLog, Credentials, Authenticator, AuthenticatorFunc, TicketOptions, Denial, InvalidPushError, DeclareOption, MultiOption, RuleProvider, TicketKeyResolver, AppLimits, AppLimitProvider, ClientAuthenticator, ClientAuthenticatorFunc, Publication, GatewaySync, GatewayRule, PushOption, PlatformPush -->
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

The relay's lock has the lease `push.relay.lease` (default 5 s, 1 s to 60 s), refreshed every third
of it. A relay that stops cleanly releases the lock, so another instance's relay takes over at once
(a rolling deploy); after a crash the pushes committed meanwhile wait for the lease, then arrive. A
database that stalls for longer than the lease makes the relay lose the lock and take it again; set
the lease higher where that happens.
A relay run, or a read of the log by the hub, that is under way when the application stops is let
finish (each is bounded by 10 s) rather than cancelled: a query cancelled half-way makes pgx abandon
its connection, and closing the pool then waits for PostgreSQL to acknowledge the cancel.

Before anything is served, `Build` reads the newest `push.recent` pushes of the log into the hub's
memory in one read (`Log.Newest`), so the clients of an instance that was just started or restarted
resume from memory like those of one that stayed up.

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
`reset`, then the live pushes. A `Last-Event-ID` that is present but not a non-negative integer
(`abc`, `12-34`) gets a `reset` with the reason `unknown-id`, never a silent live start; an empty one
is no cursor. `GET {base}/push/keys` is the ticket keys' JWKS. The answers and the
bytes are the contract's (sections 6 and 7) and `conformance/push/wire.json`; the access log never
records the query string, so the stream token is not logged.

`AuthModule(m)` authenticates a subscribe with the auth module: a bearer token, a field token with its
DPoP proof, or the session cookie with its CSRF rule. A request with none of them is not refused; it
needs a valid ticket, which then names the stream's client. Bad credentials are `401` even with good
tickets. Any other scheme implements `Authenticator` (a `Credentials` value: the Context, the
credential's expiry, and for a field worker the DPoP key thumbprint, device and login family).

### Snapshot with a cursor

A client that loads its state from a read endpoint and then listens can miss a change committed
between the two. Return the push cursor with the state, and have the client open its first listen with
it as `Last-Event-ID` (field-js: the `lastEventId` option of `PushStream`). Take the cursor BEFORE
reading the state: every push at or below it was logged before, so its change is in a read that starts
after; any change the read lacks has a push above the cursor. A cursor that lags the log only replays
more pushes, so `Cursor` is the hub's in-memory last id and does no I/O. After a `reset` the client
reloads its state and takes a new cursor.

```go
func (a *API) getOrder(ctx context.Context, id string) (OrderView, error) {
	cursor := a.pushes.Cursor() // before the state
	order, err := a.orders.Find(ctx, id)
	if err != nil {
		return OrderView{}, err
	}
	return OrderView{Order: order, PushCursor: cursor}, nil // PushCursor `json:"pushCursor"`
}
```

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

## Multi-application hosts

A host that serves the topics of many applications, such as the platform's push gateway
([contract amendment 16](../../contracts/push.md)), builds the module with `MultiApplication`. Go
only. The rules, the ticket keys and the limits of each application come from providers that the
host may change while it runs; the module reads them for every subscribe and listen and keeps
nothing. From `go/push/multi_test.go`:

```go
func (w *world) multi(extra ...Option) nodeConfig {
	return nodeConfig{
		declare:     func(*Declared) {},
		declareOpts: []DeclareOption{OptionalTicketKey()},
		opts:        append([]Option{MultiApplication(w, w, AppLimitsFrom(w))}, extra...),
	}
}
```

`w` here is a `RuleProvider` (`Rules(application) []Rule`), a `TicketKeyResolver`
(`PublicKey(issuer, kid) (ed25519.PublicKey, bool)`) and an `AppLimitProvider`
(`AppLimits(application) AppLimits`). Each has a `...Func` adapter.

- **Rules.** A topic is granted only by the rules of its own application (its first segment). An
  application the provider knows nothing of has its topics denied (`TOPIC_FORBIDDEN`), never an error
  for the whole subscribe. The rules declared on `Declared` are a start-up problem in this mode.
- **Tickets.** A ticket is checked with the key the resolver gives for its `iss` and `kid` and may
  grant topics of its issuer's application only. Tickets of several issuers compose in one subscribe;
  a ticket for another client than the stream's grants nothing (`TICKET_INVALID`).
  `DeclareWith(app, OptionalTicketKey())` makes `push.ticket.key` optional for a host that mints
  nothing; `Ticket` then fails and `GET /push/keys` lists no key.
- **Limits.** `AppLimits{MaxStreams, MaxDataBytes}` per application, with `DefaultAppLimits` as the
  default (20 000 streams, push.max-data-bytes). A stream counts against every application of its
  granted topics. Over the limit a subscribe or a listen is `429 PUSH_LIMIT` with
  `"details":{"scope":"application","application":"beta"}`; the host-wide limits keep their body.
  `MaxDataBytes` replaces push.max-data-bytes for the application's pushes. Publish rate and the
  pause are the gateway's, at its ingest.

### The client member

A subscribe body may name the client it wants: `"client": "globex"`. A host whose credentials can act
for several clients gives `Authenticate` an authenticator that is also a `ClientAuthenticator`; the
module passes it the client named ("" when none):

```go
var multiClientAuth = ClientAuthenticatorFunc(func(r *http.Request, client string) (Credentials, bool, error) {
	list := strings.Split(r.Header.Get("X-Clients"), ",")
	if r.Header.Get("X-Clients") == "" {
		return Credentials{}, false, nil
	}
	switch {
	case client == "" && len(list) != 1:
		return Credentials{}, true, httpfw.Validation("the credential has several clients; name one in client")
	case client == "":
		client = list[0]
	case !slices.Contains(list, client):
		return Credentials{}, true, httpfw.Forbidden("CLIENT_FORBIDDEN", "the credential cannot use that client")
	}
	cx := fw.ContextFor(fw.MustClientID(client)).WithPrincipal(fw.MustPrincipalID("prn_1")).WithPermissions(fw.MustPermission("test:order:view"))
	return Credentials{Context: cx}, true, nil
})
```

For any authenticator the module then requires a named client to equal the Context's client
(`403 CLIENT_FORBIDDEN`); with `AuthModule` and no `client` nothing changes.

### Without the framework HTTP server

`Pushes.Handler()` serves the three routes under `BasePath` as an `http.Handler`, with the error
envelope, the body limit and an access log that never records the query. Mount it in the host's own
router without stripping the prefix, and call `Pushes.Drain()` when the host shuts down: the hub ends
every stream at once and a subscribe or listen is `503` (the module also drains when the application
shuts down). `httpfw.WithDrain` is the matching option for any dispatcher. A host that owns the tables
declares the module with `DeclareWith(app, ExternalMigrations())` and creates them byte for byte from
`go/push/migrations/V0001__create_push_tables.sql`. From the tests:

```go
n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{handler: true})
n.p.Drain()
```

### Publishing a batch

`PublishBatch(ctx, tx, items...)` and `PublishBatchAlone(ctx, items...)` write pushes, each with its
own client, in one statement: all or none. Every item is checked first.

```go
err := n.p.PublishBatchAlone(ctx,
	item(acme, "alpha:feed", "one", map[string]any{}),
	item(globex, "alpha:feed", "two", map[string]any{}),
	item(fw.PlatformClient, "beta:feed", "three", map[string]any{}),
	item(acme, "beta:feed", "four", map[string]any{}),
)
```

(`item` builds a `Publication{Context: fw.ContextFor(client), Push: p}`.)

## Streams, backpressure and ends

Each stream has a buffer of `push.stream.buffer` pushes; a stream that falls further behind is ended
without an event and resumes from its `Last-Event-ID`. The buffer holds pointers to the pushes, each
encoded once as its SSE event and shared by every stream it goes to, and it grows only as far as the
stream falls behind: an idle stream holds none. Each instance keeps `push.recent` pushes in
memory and replays from there when it holds everything after the client's id, from the log
otherwise. A replay from the log reads one page of 1000 entries at a time, whatever its length, and
streams resuming from the same id at the same moment share each page read. Over `push.max-streams`
or `push.max-streams-per-principal` a subscribe, or a stream opened with a token, is `429
PUSH_LIMIT`. At the token's expiry the stream sends `auth-expired` and ends. When the server's drain
begins (`httpfw.Draining`) the hub ends every open stream at once (reason `drain`), opens no more
(`503`) and stops reading the log; the clients reconnect to another instance after the `retry`, with
jitter.

A stream is served by two goroutines: the handler's, and net/http's background read of the
connection, which is how a client that goes away is noticed at once (its slot under the limits is
freed and nothing more is written to it). Serving it with one would mean hijacking the connection
and noticing a closed client only at the next write (up to the 25 s heartbeat), so it is not done.

`EndStreams(ctx, Holder{...})` ends the matching streams on every instance with `auth-expired`. For
field revocations, the `flowcatalyst` module maps each event:

```go
flowcatalyst.OnFieldRevocation(receivers, func(ctx context.Context, cx fw.Context, ev flowcatalyst.FieldRevocation) error {
	return pushes.EndStreams(ctx, flowcatalyst.PushHolder(ev))
})
```

(from the doc comment of `flowcatalyst.PushHolder`). Ending streams when a session signs out is not
built yet.

## Publishing through the platform gateway

With `push.driver=platform` (contract amendment 17) the application does not host push: its pushes go
to the platform's push gateway, which holds the log and serves the routes. Nothing changes in the
code that publishes (`usecase.Push`, `Publish`, `PublishAlone`, `PublishBatch`); `RunnerOption()`
tells the runner. The module then keeps no log, hub, relay or retention loop, serves no routes
(`Routes()` is empty, `Handler()` answers 404), needs no `push.stream.secret` (ignored if set), and
requires `push.ticket.key`: tickets are minted here with `Ticket` / `TicketWith` as before and the
gateway verifies them. A push is a `PUSH` row of the application's `outbox_messages`
(`conformance/outbox/outbox-push.json`), written in the use case's transaction (or `tx` of
`Publish`) together with a `pg_notify('fc_outbox_push')` so the platform's outbox processor delivers
it as soon as the transaction commits. `usecase.Push` takes the options `PushUngrouped()`,
`PushMessageGroup(g)`, `PushID(id)` and `PushClientCode(code)`; the message group is the topic by
default, so the pushes of one topic reach the gateway in order. The last two exist only in this row
and fail the run as `INVALID_PUSH` with a hosted driver.

The `flowcatalyst` module sends the gateway the topic rules and the public ticket keys
(`flowcatalyst.WithPushes(pushes)`), as the category `PUSH` of `sync` and of sync on boot:

```go
p, err := pd.Build(ctx, b, db, lk)
fc, err := flowcatalyst.Build(ctx, b, fcd, flowcatalyst.WithPushes(p))
```

**No cursor.** The gateway owns the log, so `Cursor()` returns `""` and `HasCursor()` is false: a
snapshot endpoint has nothing to hand the client as `Last-Event-ID`, the client's first listen is
live, and it reads its state once more when the stream reports `subscribed` to close the gap. A
cursor from the gateway is future platform work. `EndStreams` and `EndPrincipal` return
`ErrPlatformDriver`: the gateway ends the streams of a platform sign-out and of a revoked field
login. Java does not have this driver yet.

## The log driver

`Log` is what the relay, the hub and the HTTP layer use, so another driver plugs in without them
changing: `Append` (in the relay's transaction for A1; a driver outside the database appends at once
and keeps `Entry.OutboxID`, and the hub drops an outbox id it has already seen), `ReadAfter`,
`Newest` (the newest entries, oldest first: the hub's memory at start), `Latest`, `Trimmed` (the highest id deleted: a `Last-Event-ID` below it gets a `reset`), `Prune`,
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
`kill -9`, resumes after a restart served from the log, a slow shutdown) are in
[the load test results](../../push-load-test-results.md), with the same tests after the fixes: about
42 KiB per stream, pushes back about 5 s after a `kill -9`, resumes from memory, the framework's
part of a 50 000-stream shutdown under a second.
