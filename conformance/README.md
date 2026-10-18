# Conformance vectors

Inputs and expected outputs, as files, that the Java and the Rust implementations of the framework
both read ([Rust milestones](../docs/rust/milestones.md), section 3). They are what "the same rows,
the same ids, the same config semantics" means in practice.

## Who writes them

The **Java framework is the reference** (design decision 18). Each Java module has a vector test
that runs the Java code on every input of its files and compares the result with the committed
expected output; run with `-Dconformance.update=true` it writes the file instead:

```
./mvnw -q verify -Dconformance.update=true        # rewrite every vector
git diff conformance/                              # review, then commit
```

The Rust tests (`rust/conformance`) only compare. A Rust mismatch therefore means Rust is wrong,
or the vector exposes a Java bug, which is fixed in Java first.

To add a vector: add the input to the file (or a new file, with a Java test that fills it), run the
Java module's tests in update mode, review the output, commit both, then make the Rust test pass.

## Comparison rules

- JSON values (payloads, rows) are compared as parsed values: key order and whitespace are free,
  values exact. A JSON document carried inside a string (an event's `data`, an audit row's
  `operationData`) is parsed and compared the same way.
- Text files under `sql/` and `lua/` are compared as **exact bytes**: they are the statements and
  scripts both implementations send to the server. They have no trailing newline, because the
  statements do not.
- Generated values are pinned: vectors use fixed clocks and fixed ids. Where a value cannot be
  pinned (a random TSID the Java code generates internally), the vector says how it is checked.
- An allowed difference is recorded in the vector as `"rust": {...}` with a reason, so a
  difference is always deliberate and visible.

## Format of the newer vectors

Every vector file written from 2026-10-18 on (receivers, usecase, settings, ratelimit) opens with two
fields: `description` (the setup, the input format and how each `expect` is to be read) and `howToRun`
(what a runner in another language does with a case). Cases carry a `name`, the input, and an `expect`;
where Java's wording of an error is not part of the contract it is recorded as `messageJava` and is
not compared. A Java-only quirk the vector records is flagged in the case (`note`, `javaLiteralCase`).

## Areas

| Directory | Written by (Java test) | What |
|---|---|---|
| `tsid/` | `framework-core` `IdsConformanceTest` | TSID decoding, canonical encoding, prefixes, the millisecond layout |
| `config/` | `framework-core` `ConfigConformanceTest` | environment names, value parsing, whole scenarios (sources in order, every problem, collisions) |
| `sql/locks/` | `framework-locks-database` `LockConformanceTest` | the three lock statements, as the database driver sends them |
| `lua/locks/` | `framework-locks-redis` `LockScriptConformanceTest` | the three lock scripts, as the Redis driver loads them |
| `migrations/` | `LockConformanceTest`, `OutboxConformanceTest`, `OrdersConformanceTest` (orders-demo) | the `fw_schema_history` rows the Java runner writes for a migration set |
| `sql/outbox/` | `framework-usecase` `OutboxConformanceTest` | the outbox `INSERT` (JDBC `?` placeholders; Rust numbers them when the writer is built) |
| `outbox/` | `framework-usecase` `OutboxConformanceTest` | the rows the outbox writer stores for events, dispatch jobs and audit entries (every column, the payload text), and the dispatch-job builder's refusals |
| `receivers/context.json` | `framework-flowcatalyst` `ReceiverContextConformanceTest` | the `Context` a receiver hands its handler for a delivery and the application's token (service principal, kind, causation, execution, correlation), or the answer when it runs none (flowcatalyst contract section 4b) |
| `http/spa.json` | `framework-http` `SpaConformanceTest` | the SPA fallback (static-assets contract): a fixture build, routes and options, and per request the status, headers and body hash the framework answers in process; Go's `httpfw/spa` compares |
| `auth/login-failure.json` | `framework-auth` `LoginFailureNavigationTest` | a sign-in that cannot start (sessions contract 4.1.1): per request headers, a redirect to the failure page for a browser navigation or the JSON envelope; Go compares when its sessions are built (G5b) |
| `admin/navigation.json` | `framework-admin` `AdminConformanceTest` | `GET /api/admin/navigation` (admin contract 4.1): per held-permission set, the status (`200` or `403 FORBIDDEN`) and the section ids the caller may use, the wildcard rule included |
| `admin/config.json` | `framework-admin` `AdminConfigConformanceTest` | `GET /api/admin/config[/{key}]` (admin contract 10): declared settings and an environment in, per case the settings with state, source and value, a secret or credential-named value never shown, URLs without user info |
| `admin/platform-reads.json` | `framework-flowcatalyst` `PlatformAdminConformanceTest` | the event types and roles sections (admin contract 11): platform answers in, per case the confined, filtered, sorted, cursor-paged answer, `404` for another application's record, `502 PLATFORM_ERROR` for a refusing platform, `403` without calling the platform |
| `openapi/` | `framework-http` `OpenApiConformanceTest` (`fixture.json`), `framework-auth` `AuthOpenApiTest` (`auth.json`), orders-demo `OrdersOpenApiTest` (`orders-demo.json`) | OpenAPI documents (OpenAPI contract section 10), compared as **exact bytes**, not as parsed JSON: the fixture API covering every rule, the two session operations, and a sample application; the fixture API is described in [`openapi/README.md`](openapi/README.md) |
| `receivers/signature.json` | `framework-flowcatalyst` `SignatureConformanceTest` | the receiver gate (flowcatalyst contract section 4): per delivery (secret, bearer, require-signature, clock, ordered headers, body) accepted, or `401 UNAUTHENTICATED` with `details.reason`; plus the HMAC function alone |
| `receivers/dispatch.json` | `framework-flowcatalyst` `DispatchVectorConformanceTest` | `POST /fc/dispatch` for a registered handler outcome and a request: status, `FlowCatalyst-Outcome`, body, which handler ran and what it was handed |
| `receivers/scheduled-jobs.json` | `framework-flowcatalyst` `ScheduledJobsVectorConformanceTest` | `POST /fc/scheduled-jobs`: validation, the `202`, the firing the handler gets, the overlap lock (free, held, down, lost, none) and the completion calls the platform receives |
| `usecase/scenarios.json` | `framework-usecase` `UseCaseScenariosConformanceTest` | runner scenarios (write-path contract 3a, 4, 4a, 5) against PostgreSQL: phase trace, result (kind, code, details, status), events, audits, rows; conflicts, step codes, joined runs |
| `settings/client-settings.json`, `migrations/client-settings.json` | `framework-client-settings` `ClientSettingsConformanceTest` | per-client settings: key rules, scripted reads and writes against `fw_client_settings` with the stored rows, and the migration history row |
| `ratelimit/gcra.json` | `framework-http` `RateLimitConformanceTest` | GCRA decisions by the memory driver with a pinned clock, per-request policies, policy range checks, limiter names, the bounded table |
| `ratelimit/http.json` | `framework-http` `RateLimitConformanceTest` | the rate-limit middleware: the `429` envelope, `Retry-After` and `RateLimit-*` headers, failure modes, per-request policy failures, and the key resolvers (client address, `X-Forwarded-For`, header) |
