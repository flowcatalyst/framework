# Handover (2026-10-10)

The plan and status live in `implementation-plan.md` and `guide/STATUS.md`; contracts in
`contracts/`; developer docs in `guide/` (keep them current: see `../CLAUDE.md`). This note is
rewritten at hand-off and can lag the code in between.

- Done: M1, M2a, M2b, M3, core hooks (`started`, `find`, `Command`/`launch`), M4a (client, sync
  command, `outbox` backlog health check), M4b (dispatch and scheduled-job receivers, per-receiver
  secrets, `DispatchDelivery.once` with retention, lock opt-out, `flowcatalyst.receivers.enabled`),
  a start-up check for two config keys that read one environment variable.
- Review by the first application (2026-10-09): 28 findings on the guide, all handled: pages fixed,
  gaps turned into MA items 7 to 9 in the plan, three small code changes (env-variable collision
  check, receivers 404 / `enabled`, anchor check in `verify.sh`).
- MA built 2026-10-10, all nine items, each its own commit with contract, tests and guide:
  (7) informational health checks `9d73eb2`; (8) `framework-http` on core only, `ServiceError`,
  Enforcer rule `9cde933`; (2) `AppBuilder.override`, test mode = `APP_ENV=test` `21704e4`; (1)
  `framework-bom`, GitHub Packages config and `.github/workflows/publish.yml` `60fe77a` (nothing
  published); (4) `DISPATCH_JOB` outbox rows `1b6510e`; (3) `Background` + `Metrics` `661080f`; (5)
  `SecretResolver` (env, file; platform driver blocked on the platform) `91a1cc6`; (6)
  `framework-httpclient` on Vert.x + adapter `ea72549`; (9) rate limiting `3e1a771`.
- Owner decisions waiting (each recorded in its contract under "Rulings pending owner"): publishing
  the Java SDK and the group id before the first tag (`contracts/build.md`); rate-limit store
  failure default ALLOW, IPv6 /64, the memory overflow bucket (`contracts/rate-limiting.md`);
  service-scoped secrets and world-readable files (`contracts/secrets.md`).
- Platform work found: dispatch-jobs batch should resolve `clientCode`; the SDKs' object-shaped
  dispatch-job `metadata` fails the whole batch (`contracts/write-path.md` 7a); no secret-resolution
  endpoint, and the platform-config read returns SECRET values unmasked to any anchor-tier caller,
  application service accounts included (`contracts/secrets.md` section 5).
- Next: contracts for M-UI (JTE) and M4c (sessions and OIDC login; the HTTP client is ready for
  it), then the `/admin` area. Platform work for scoped event/dispatch-job reads is listed in
  `contracts/flowcatalyst.md` section 5.
- Rulings: the platform delivers the outbox; handler failure/timeout in a receiver is a
  transient error; a receiver without handlers answers 404 (reverses "routes stay and refuse");
  sessions have cookie/database/redis drivers; Opus for security-sensitive slices, Sonnet for
  wiring.
- Rust framework: designed 2026-10-11, nothing built. `rust/design.md` (principles, crate layout,
  the ordered owner decisions), `rust/capabilities.md`, `rust/orders-showcase.md`,
  `rust/milestones.md`. Findings: a Rust SDK exists after all, `flowcatalyst-rust/crates/fc-sdk`
  (Apache-2.0, unpublished), but its outbox DDL and dispatch-job shape diverge from the Java SDK and
  the batch endpoint; recommendation is a new `flowcatalyst-sdk` in `flowcatalyst-go/clients/rust-sdk`
  seeded from it. `contracts/flowcatalyst.md` 4a's "Unix seconds must be ASCII digits" should say
  the millisecond ISO-8601 form is accepted too (the code does).
- TypeScript framework: designed 2026-10-13, nothing built. Owner: TypeScript becomes the default
  for new business applications (Java stays for existing ones; Rust paused after R2), pending
  decision 0 (TypeScript or Go), which the approved T0 spike informs. `typescript/design.md`
  (principles, scriptc readiness, Bun now with Node green, the TypeBox/domain schema split, routes as
  values on an own trie, framework-written middleware and auth, worker-ready rules and the
  `RequestDispatcher` port, Nuxt UI without Nuxt, the dependency policy, 29 ordered owner decisions
  0 to 28), `typescript/capabilities.md`, `typescript/orders-showcase.md`, `typescript/spike.md` (the
  runnable T0 plan, TypeScript slice against a Go slice), `typescript/milestones.md` (T0 to T9 with
  T4a auth, T4b workers, T4c worker-pool dispatcher). Contract amendments proposed: http routing
  syntax and wildcard, opt-in CORS, static assets; an M4c auth contract written once for all three.
- UI kit (`ui-kit/`) is now being dogfooded by the Go platform's templ port (`/ui` in
  `flowcatalyst-go`). Since 2026-10-09 the kit gained: Props tables and 124+ fixtures with golden
  HTML (the conformance authority), specs for row actions, flash stack, form dialog, autocomplete,
  confirm dialog and server-loaded drawer, a 90-icon sprite built by `ui-kit/icons/build-sprite.sh`,
  an error contract for Datastar actions (handled failures answer 200 plus patches; mutations send
  `retryMaxCount: 0`), a phone-width shell and server-rendered theme cookies. Defects and gaps are
  in `ui-kit/PORTING-NOTES.md`. JTE (`framework-ui`, M-UI) has not started; it reuses the same
  fixtures. Plan: `ui-dogfood-plan.md`.
- Rust and TypeScript are **parked** (owner, 2026-10-13): no further work on either until resumed.
- Go framework: designed 2026-10-13, nothing built (owner decision: Go alongside Java; a part of a
  business application will later be built on both and compared). `go/gap-analysis.md` (fcsdk
  covers 2 of the 41 built Java capabilities, 14 partly, 25 not at all; none of the partly ones
  passes the shared vectors as is; the platform's templ UI kit is the big reuse), `go/design.md`
  (stdlib-first one-way rules, explicit build function and typed handles, three Go modules under
  `go/`, sqlc plus go-jet/`Filter`, embedded PostgreSQL tests, 27 ordered owner decisions),
  `go/capabilities.md`, `go/orders-showcase.md`, `go/milestones.md` (G0 to G7). Findings: the Java
  dispatch receiver does not follow the 2026-10-12 receiver-principal ruling (no principal, no
  causation); fcsdk is MPL-2.0, has no module of its own and writes a snake_case outbox payload;
  the Java tests still have to write the HTTP, Datastar, metrics, rate-limit, secrets and receiver
  vectors the other implementations need.

