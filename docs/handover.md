# Handover (2026-10-08, saved before a restart)

Resume from here. Delete this file once the work it describes is picked up.

## Framework (this repository)

- Done and independently verified: M1 (foundation, locks), M2a (write path, optimistic
  locking, outbox), M2b (checked SQL). Full `./mvnw -q verify` green: 424 tests, 11 modules.
  HEAD `5ab192e`. Nothing pushed.
- **M3, HTTP: built** (contract `contracts/http.md`; commits `445693a`, `a99c30a`, `034ef24`;
  verified 2026-10-08: `./mvnw -q verify` green, 577 tests). Deviations from the contract to
  fold into it or rule on: `Datastar.stream(Request, Events)` (callback form) because the body
  is written after the handler returns; `JettyHttpModule` must be listed after every
  route-registering module (open: a core post-boot hook?); `Routes.forModule(id)` tags registrants;
  router backtracks (most-literal-at-first-difference among fitting routes); no total-read timeout
  (idle only); `data: namespace` line added per the Datastar ADR (summariser-read, re-check against
  source); access log omitting query strings is untested; `ContextResolver` presence is checked via
  `app.buildOrder()` (open: optional-service API in core). Core gained `App.shutdownDrain()` and
  `StartupException`-from-boot flattening. Nothing pushed.
- **Next: M4.**
- **M4, FlowCatalyst module:** an SDK survey was done (findings summarised in
  `implementation-plan.md`). Key facts: the Java SDK has no login, token validation or job
  receivers; interactive-login access tokens are `token_use=identity` (build the session from the
  id_token); dispatch webhooks are signed by the connection's service account, scheduled-job
  firings by the application's; the outbox processor ignores `headers` and `client_id`.
- **Owner ruling 2026-10-08 (platform work, Go, not started):** give the `application-service`
  role the three batch-ingest permissions (`platform:messaging:batch:events-write`,
  `platform:messaging:batch:dispatch-jobs-write`, `platform:admin:batch:audit-logs-write`), and
  make the batch endpoints refuse items for other applications' types, so each app delivers its
  own outbox with its own credentials.

## How the work has been run

Main session writes the contract in `docs/contracts/` and a task spec; a Sonnet agent implements
it; the main session reruns `./mvnw -q verify`, counts tests from the surefire reports, reads the
critical code, and fixes contract problems the agent reports. Agent rules that mattered: build in
the foreground as its own command and read the exit code before committing (never chain a commit
after a build); explicit-path staging; one commit per step; trailer
`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; never touch Docker containers it did
not start; never loosen an assertion to pass a flake without explaining why.

## Elsewhere (not this repository)

- **flowcatalyst-go:** the `exhaustive` linter change and its ten switch fixes are committed
  (`d9422eb`). Check whether the gofumpt issue in
  `internal/platform/shared/auth/application_scope_test.go:92` still fails `make lint`.
  Also open there: the user-sync security fix (mirror Java), the PHP app's service account
  lacking user-create rights, and the Go claims struct in `pkg/fcsdk/auth/claims.go` still
  reading `scope` as the tier (found by the survey, unverified).
- **flowcatalyst-rust:** branch `idiomatic-rust` at `9ff5f0c3` (phase 5, typed errors) verified;
  recommended frozen after this phase. The scratch worktree used for baseline tests lived in a
  temporary directory and is gone after the restart: run
  `git -C ../flowcatalyst-rust worktree prune`. Full test runs from that repo's own `target/`
  hit a macOS trust-store failure in 10 S3/SQS tests (the same binary passes when copied
  elsewhere).
- The three fc-dev instances (ports 18080 / 28080 / 38080) stop with the restart; the one on
  38080 was causing a Testcontainers port clash.
