# fulfil-go: moving live updates onto framework push

Status 2026-11-09. For the agent working on fulfil-go. fulfil-go built its own live updates
(`internal/streams`, `internal/flightboard`; its `docs/live-updates.md`) and asked for them to move
into the framework. The framework now has that module: [push](contracts/push.md), guide
[Go: push to clients](guide/go/push.md). This note maps one onto the other. Nothing here changes
fulfil-go by itself; the move is that app's work, screen by screen.

## What maps to what

| fulfil-go today | framework push |
|---|---|
| `sync_events` written in the change's transaction, `NOTIFY` at commit | `usecase.Push(topic, type, data)` in the use case's plan (or `pushes.Publish(ctx, tx, cx, p)` in a transaction you own): a row in `fw_push_outbox`, sent only if the transaction commits |
| Readers stop at `pg_snapshot_xmin`, order by `(txid, id)`, event id `txid-id` | One relay at a time (lock `svc:push-relay`) moves committed outbox rows into `fw_push_log` and numbers them in commit order; ids are plain integers (strings on the wire) |
| `streams.Hub` (LISTEN + 1 s poll), `streams.Serve` | The module's hub and routes: `POST {base}/push/subscriptions`, then `GET {base}/push/stream?st=…` |
| Channel `store:{client}:{ref}` | Topic `fulfil:store:{ref}`; the push's client keeps tenants apart (contract section 5) |
| Channel `flightboard:{client}` | Topic `fulfil:flightboard` |
| `ForStoresOf(client)` (the office follows every store) | No wildcard subscriptions: publish to `fulfil:stores` as well as `fulfil:store:{ref}` (two `usecase.Push` steps) |
| Per-channel check in the stream handler | Rules for what a permission decides; tickets for the rest (below) |
| `streams.LatestCursor` in a read endpoint | `pushes.Cursor()`, called **before** the state is read |
| `@inhance/api-client` `createSseClient` | `PushStream` (field-react-native for the phones, field-js for the web; one shared core) |
| Payload = the new state of the row | Allowed: the contract's default is a signal, but a topic may carry state when it is fine for everyone on the topic and fits `push.max-data-bytes` (16 KiB default, up to 64 KiB) |
| Retention of `sync_events` | `push.retention`, 10 min to 24 h (default 24 h); a client away longer gets `reset` |

## Why the ordering differs, and what that costs

Both designs fix the same bug (ids allocated before commit). fulfil-go's horizon rule needs no
relay, but every reader waits for the oldest running transaction, so one long transaction (a
migration, a report, a stuck session) holds back every update until it ends. The relay numbers rows
only once committed, so a long transaction delays only its own pushes. The relay adds a hop: woken
by `NOTIFY`, it moves a row within milliseconds; after the relay's instance dies, pushes wait for
the lock's lease (`push.relay.lease`, 5 s). Load-test figures: `docs/push-load-test-results.md`.

## Publishing

- In a use case: `usecase.Push("fulfil:store:"+ref, "pick.updated", pick)` and, for the office,
  `usecase.Push("fulfil:stores", "pick.updated", pick)`. Pass `pushes.RunnerOption()` to
  `usecase.NewRunner` so oversize data fails the run (as a bug) instead of the relay.
- **The push's client is the Context's client.** A push written in the platform client's context
  has no client and goes to **every** client's streams on that topic. The flightboard projector runs
  in a background loop: it must publish each row with `fw.ContextFor(<the fulfilment's client>)`
  in the projector's transaction (`pushes.Publish`), never with a system or platform context.
- Keep the projector (dirty marks, `SKIP LOCKED`, late-at instants, the shared vector). Only its
  last step changes: `pushes.Publish` instead of the `sync_events` insert. A projector helper may
  come to the framework later; it is not part of this.

## Authorization

- **Office (people with permissions):** rules, declared once at start-up:
  `pd.Topic("fulfil:flightboard", push.Requires("fulfil:flightboard:view"))`,
  `pd.Topic("fulfil:stores", push.Requires("fulfil:picking:view"))`,
  `pd.Topic("fulfil:store:{id}", push.Requires("fulfil:picking:view"))` (use the app's real
  permission codes).
- **Pickers (field workers, PIN + device key):** a picker may hear their own store only, which no
  rule can express. Add an endpoint (for example `POST /api/picking/push-ticket`) that checks the
  worker's store as the stream handler does today and answers
  `pushes.TicketWith(cx, push.TicketOptions{ProofKey: <the worker's DPoP key thumbprint>}, "fulfil:store:"+ref)`.
  The thumbprint is `auth.FieldPrincipal.KeyThumbprint`; a ticket bound to it is useless on another
  device. Tickets live two minutes; the reader fetches new ones at every subscribe (`getTickets`).
- A per-worker topic `fulfil:field-worker:{principal}:jobs` needs no ticket (the rule
  `pd.Topic("fulfil:field-worker:{principal}:jobs")` matches only the worker's own id); use it if
  work is ever assigned to a person rather than a store.
- Field logins revoked: wire `flowcatalyst.OnFieldRevocation(... pushes.EndStreams(ctx, flowcatalyst.PushHolder(ev)))`
  so a revoked phone's stream ends at once (guide, "Streams, backpressure and ends").

## Snapshot with a cursor

A read endpoint that a screen follows calls `cursor := pushes.Cursor()` **first**, then reads the
state, and returns both (`pushCursor` in the JSON). The client starts its stream with that cursor
(`lastEventId`). Every change not in the snapshot then arrives as a push; a push for a change the
snapshot already has may arrive too, and applying a whole row again is harmless (as today). This
replaces `LatestCursor` and the `refresh()` on every reconnect: after a drop the stream replays what
was missed, so reload only on `reset`.

## Clients

```ts
import { PushStream } from "@flowcatalyst/field-react-native"; // the release after v0.1.0

const stream = new PushStream({
  baseUrl: env.apiBase,                     // the base the push routes are mounted under
  topics: [`fulfil:store:${store}`],
  fetch: signedFetch,                        // DPoP; FieldClient's fetch is expo/fetch, which streams
  getTickets: async () => [await api.picking.pushTicket(store)],
  lastEventId: snapshot.pushCursor,
});
stream.on(`fulfil:store:${store}`, ({ data }) => { if (data.pick) show(upsert(current.current, data.pick)); });
stream.addListener("reset", () => void refresh());   // away too long: reload (new cursor)
stream.addListener("state", (state) => setLive(state));
stream.start();
// AppState 'active': stream.stop(); stream.start() resumes from stream.lastEventId
```

- **Phones:** field-react-native's `PushStream` has no DOM dependency (Hermes): listeners are
  `addListener(event, fn) → { remove() }`, as on `FieldClient`. React Native's own `fetch` cannot
  stream a body; FieldClient is built on `expo/fetch`, which can, so `signedFetch` works for the
  stream too.
- **Web (FlightboardPage):** field-js's `PushStream` is the same reader as an `EventTarget`
  (`addEventListener("reset", …)`, `detail` on each event), with
  `fetch: globalThis.fetch.bind(globalThis)` for the cookie session.
- Ids are strings and opaque; the reader drops a push at or below the last id it delivered.

## Cutover

- Enable the module: `push.Declare(app)` (migration set `push`: `fw_push_outbox`, `fw_push_log`),
  `Build(ctx, b, db, lk, push.Authenticate(push.AuthModule(am)))`, mount `pushes.Routes()`, and set
  the secrets `push.stream.secret` (32+ characters, the same on every instance) and
  `push.ticket.key` (`<kid>:<base64url 32-byte Ed25519 seed>`). Postgres only; no Redis needed.
- Move one screen at a time: publish to both `sync_events` and push until its clients are on
  `PushStream`, then stop writing that channel. Drop `sync_events` and `internal/streams` last.
- A client still holding an old `txid-id` cursor gets a `reset` (reason `unknown-id`) and
  reloads, so nothing is missed at the switch.
