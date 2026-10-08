# Handover (2026-10-08, saved before a restart)

Resume from here. Delete this file once the work it describes is picked up.

## Framework (this repository)

- Done and independently verified: M1 (foundation, locks), M2a (write path, optimistic
  locking, outbox), M2b (checked SQL). Full `./mvnw -q verify` green: 424 tests, 11 modules.
  HEAD `5ab192e`. Nothing pushed.
- **Next: M3, HTTP** (see `implementation-plan.md`; order changed so HTTP precedes the
  FlowCatalyst module). Owner ruling 2026-10-08: **Jetty 12**. The M3 contract is not written
  yet. Points already decided for it:
  - Jetty 12 core handlers (no Servlet), handlers on virtual threads, hidden behind the
    framework's own routing and middleware contracts so the server can be swapped.
  - Error body is the platform's envelope: `{"error": "<CODE>", "message": "...", "details"?: {...}}`
    (flowcatalyst-go `internal/platform/shared/httperror/httperror.go`); status mapping as in
    `contracts/write-path.md` section 5.
  - Routes declared by modules at boot; method + `{param}` patterns; duplicates fail start-up;
    404 / 405 with `Allow`.
  - One middleware abstraction, shared later with jobs and commands.
  - Typed request validation without reflection or annotations (records decoded with Jackson 3,
    a small explicit validator API returning field errors → 400).
  - `X-Correlation-Id` read or generated, echoed, put in `Context`.
  - A `ContextResolver` hook for routes that need a `Context` (the real one comes with M4 login);
    start-up fails if such a route exists and no resolver is configured.
  - Health endpoints from `App.health()`; graceful shutdown within `app.shutdown.drain`.
  - Datastar adapter: SSE `datastar-patch-elements` / `datastar-patch-signals` per the Datastar
    1.0 SDK specification; reading signals from the request.
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

- **flowcatalyst-go:** uncommitted, awaiting the owner's go-ahead to commit:
  `.golangci.yml` (`exhaustive` with `default-signifies-exhaustive: false`) plus ten switch fixes
  in `runner.go`, `authservice.go`, `clientselection.go`, `principal/entity.go`, `httperror.go`,
  `handlers_dashboard.go`, `circuit_breaker.go`, `mediator.go`, `notification.go`,
  `pkg/fcsdk/usecase/error.go`. Build and affected tests pass. `make lint` still fails on one
  pre-existing gofumpt issue in `internal/platform/shared/auth/application_scope_test.go:92`.
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
