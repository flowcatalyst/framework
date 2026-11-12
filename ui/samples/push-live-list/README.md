# Push live list (sample)

A minimal Vue page that keeps a list current over push: `providePushHub` makes the page's one stream,
`useLiveList` follows topics and applies each tick by version. It runs against `go/examples/push-demo`
(its routes, topics and payloads are described at the top of `src/demo.ts`). The reader is
`@flowcatalyst/push`, the Vue layer is `@flowcatalyst/ui/push`; see
[`../../../docs/guide/push.md`](../../../docs/guide/push.md), "Reading pushes in a Vue app".

What the page shows:

- `useLiveList` with a custom `toChange` (the demo's pushes are `demo.tick`, not `row.*`): a row per
  topic, `seq` as its version, so an older tick never replaces a newer one.
- `status` and `lastAppliedAt`, the hub's `error` and `denied`.
- `apply`: the Hide button removes a row at once with the row's version; the next tick for that topic has a
  higher version, so the row comes back.
- A change of the topic count loads again and resubscribes with the last id kept.

The demo keeps no state to read, so the page's `load` returns no rows and no push cursor and the stream
opens live. An endpoint of your own returns its rows and `pushCursor` (read BEFORE the rows, contract
section 9.1), and `load` passes both on.

## Run it

1. Start the demo with its embedded PostgreSQL, from the repository root (`loadtest.sh` builds `pgdb`
   and `push-demo` into `$PUSHLOAD_DIR`, default `${TMPDIR:-/tmp}/push-load`, and generates the stream
   secret and ticket key there; `loadtest.sh stop` stops both):

   ```sh
   go/examples/push-demo/loadtest.sh build
   go/examples/push-demo/loadtest.sh start
   ```

   The demo listens on 127.0.0.1:18100 (and the following ports).
2. From `ui/`: `pnpm install`, then `pnpm --filter @flowcatalyst/push-live-list-sample dev`
   (Vite on http://localhost:5177, proxying `/api` to the demo; `PUSH_DEMO_BACKEND` overrides
   `http://127.0.0.1:18100`).
3. Press "Publish a tick to each": each row's version and time update, and the status reads `live`.

`pnpm build` builds the page into `dist/`; `pnpm typecheck` checks it. Neither needs the demo.
