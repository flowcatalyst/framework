# Contract: background work and metrics (MA item 3)

Status: built 2026-10-10. Both live in `framework-core` (no new dependency); the lock gate in
`framework-locks`; the Prometheus endpoint in `framework-http` (handler) and `framework-http-jetty`
(mounting). Java names are given where the shape matters.

## 1. Background loops

A **loop** is named work that runs again and again on its own virtual thread, owned by the
application's lifecycle. `Background` is a service the core module provides (like `Config`, every
application has one).

```java
app.get(Background.class)
    .loop("invoice-sweeper", LoopOptions.every(Duration.ofSeconds(30)), tick -> sweep(tick));
```

- **Registration:** `loop(name, options, task)` in `boot` or `started` (or later). Names match
  `[a-z][a-z0-9-]*`, at most 100 characters, unique (`IllegalArgumentException` otherwise).
  Registering after the application began closing is `IllegalStateException`.
- **Start:** loops registered before start-up finished begin **after every module's `started`
  hook** ran, so a loop never sees a half-started application. Registered later, a loop begins at
  once. A command run by `launch` never starts loops (no `boot`).
- **Schedule:** wait `initialDelay` (default zero), run, then wait `interval` after the run ended
  (fixed delay; runs never overlap), and so on. `interval` is 10 ms to 24 h. Waiting uses real time
  and ends early when the loop is stopped.
- **The task** is `void run(LoopTick tick) throws Exception`. `LoopTick` gives `loop()` (the name),
  `run()` (1, 2, ...), `fencingToken()` (`OptionalLong`, present when a lock gate admitted the run)
  and `stopping()` (true once shutdown began; long tasks should check it and return).
- **Failures:** an exception from a run is logged at ERROR with the loop name and run number, and
  the loop continues with its next run. An `Error` is logged and ends the loop (its health check
  reports it). The interrupt flag is cleared between runs.
- **Gate (optional):** `LoopOptions.gatedBy(LoopGate)`. Before each run the gate decides:
  `Ran` (the task ran, with the fencing token if any), `Skipped(reason)` (not run this time, for
  example another replica holds the lock) or `Lost` (the task ran but lost exclusion while running).
  `framework-locks` provides `LockGate.of(LockStore, LockKey, lease)` and
  `LockGate.service(Locks, name, lease)` (default instance, `LockKey.service(name)`): each run takes
  the lock with `withLock(key, lease, 0)`, so a held lock skips the run instead of waiting, and the
  lease is refreshed while the task runs (locks contract section 4). A lock store that is down is a
  failed run.
- **Stop:** `App.close()` stops every loop **first**, before any module's `shutdown` hook, so a
  loop never runs against a service that has been shut down. All loops are signalled together;
  waiting loops end at once; a running task gets until `app.shutdown.drain` to return, then its
  thread is interrupted, logged at WARN as an overrun and left behind (as for shutdown hooks).
- **Health:** each loop adds the check `background:<name>`, **informational** by default
  (`LoopOptions.critical()` makes it critical; `app.health.*` overrides apply). It is `Down` when the
  last `downAfterFailures` runs (default 3, 1 to 1000) all failed (`Lost` counts as a failure,
  `Skipped` does not), when the loop ended on an `Error`, or when `stuckAfter` is set and the current
  run has lasted longer. The reason names the count and the exception **class** only, never its
  message (health is served without authentication).
- **Metrics** (section 3): `fw_background_runs_total{loop,outcome}` with outcome `success`,
  `failure`, `skipped`, `lost`; `fw_background_run_duration_seconds{loop}` (gated-out runs are not
  timed).

## 2. Metrics

`Metrics` is a service the core module provides. Instruments are **families** with fixed label
names; `labels(values...)` returns the series to record on (cache it on hot paths).

| Instrument | Create | Record |
|---|---|---|
| counter | `metrics.counter(name, help, labelNames...)` | `inc()`, `inc(amount)` (amount ≥ 0) |
| gauge | `metrics.gauge(name, help, labelNames...)` | `set(v)`, `inc(d)`, `dec(d)` |
| gauge (callback) | `metrics.gauge(name, help, DoubleSupplier)` | read when exported |
| timer | `metrics.timer(name, help, labelNames...)` or with explicit bucket bounds | `record(Duration)`, `recordNanos(long)`, `time(Callable)` |

A family with no labels records directly on itself (`counter.inc()`).

**Drivers**, chosen by `app.metrics.driver` (`memory` default, or `noop`):

- `memory`: in-process, lock-free adders; `snapshot()` returns every series.
- `noop`: every instrument does nothing; `snapshot()` is empty. Validation still runs, so a name
  that is wrong fails in tests with either driver.

A real exporter (OpenTelemetry, Micrometer) is a later driver; `Metrics` is an interface for that.

### Naming and label rules (checked when an instrument is created; `IllegalArgumentException`)

- Names: `[a-z][a-z0-9_]*`, at most 200 characters. Counters end in `_total`. Timers end in
  `_seconds` and record seconds. Gauges end in neither. Use a prefix per component; the framework's
  own metrics start with `fw_`, which applications must not use.
- Help: not blank, at most 500 characters.
- Label names: `[a-z][a-z0-9_]*`, not starting with `__`, not `le` or `quantile`, unique within the
  family, at most 10.
- Creating an instrument again with the same name, type, help and label names (and buckets) returns
  the same family; with anything different it is `IllegalArgumentException`.
- **Label values are runtime input and never make a recording throw**: values longer than 200
  characters are cut to 200; `null` becomes the empty string. Only a wrong *number* of values
  throws (a programming error).
- **Cardinality is bounded:** a family holds at most `app.metrics.max-series` series (default
  1000, 1 to 100 000). A recording that would create one more is dropped, counted in
  `fw_metrics_dropped_series_total{metric}` and logged at WARN once per family. Never use ids,
  paths, user input or exception messages as label values.
- Timer buckets: strictly increasing, positive, finite, at most 50; default `0.005, 0.01, 0.025,
  0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10` seconds. A negative duration records zero.

### Built-in metrics

| Name | Type | Labels | From |
|---|---|---|---|
| `fw_http_server_requests_total` | counter | `method`, `route`, `status` | every HTTP request |
| `fw_http_server_request_duration_seconds` | timer | `method`, `route` | every HTTP request |
| `fw_usecase_runs_total` | counter | `usecase`, `outcome` | every `UseCases.run` |
| `fw_usecase_duration_seconds` | timer | `usecase` | every `UseCases.run` |
| `fw_background_runs_total` | counter | `loop`, `outcome` | section 1 |
| `fw_background_run_duration_seconds` | timer | `loop` | section 1 |
| `fw_metrics_dropped_series_total` | counter | `metric` | the cardinality bound |

`route` is the matched route **pattern** (`/orders/{id}`), or `<unmatched>` when no route matched,
so request paths never become label values. `status` is the numeric status. Use-case `outcome` is
`committed` or the failure's kind in lower case (`validation`, `authorization`, `not_found`,
`conflict`, `business_rule`, `internal`).

## 3. The Prometheus endpoint

- `PrometheusText.write(MetricsSnapshot)` (core) renders the text exposition format 0.0.4: per
  family `# HELP` (backslash and newline escaped) and `# TYPE` (`counter`, `gauge`, `histogram`),
  then one line per series; label values escape backslash, double quote and newline; timers as
  histograms (`_bucket` with cumulative counts and `le`, including `+Inf`, then `_sum`, `_count`).
  Families sorted by name, series by label values, so the output is stable. Numbers: integral values
  without a fraction, others as Java's shortest round-trip form, `+Inf`, `-Inf`, `NaN`.
- `MetricsEndpoint.handler(metrics, bearerToken)` (framework-http) answers `200` with content type
  `text/plain; version=0.0.4; charset=utf-8`. With a bearer token configured, a request without
  `Authorization: Bearer <token>` (compared as SHA-256 digests, constant time) is `401
  UNAUTHENTICATED` with the standard envelope.
- `JettyHttpModule` mounts it when `http.metrics.enabled` is true (default **false**: metrics reveal
  routes and volumes, so exposing them is a decision). Settings: `http.metrics.path` (default
  `/metrics`, literal segments), `http.metrics.bearer-token` (secret, optional). No context is
  required. Start-up warns when it is enabled without a bearer token, and when the driver is `noop`.

## 3a. As built (2026-10-10)

- `Metrics` and `Background` are provided by the core module (`app.get(...)` always works; a second
  provider fails start-up as usual; `AppBuilder.override(Metrics.class, ...)` works in tests).
  `Health` is now a seeded framework service (it cannot be provided or overridden) so `Background`
  can add its checks.
- `Metrics.readable()` says whether `snapshot()` returns what was recorded (false for `noop`); the
  Jetty module uses it for its start-up warning. A callback gauge can be registered once per name; a
  second registration, or a plain gauge of the same name, is `IllegalArgumentException`. A callback
  that throws is left out of the snapshot (WARN once).
- `Routes.compile(resolver, clock, metrics)` and `TestClient.of(routes, resolver, metrics)` are the
  overloads that record request metrics; the old ones record into a no-op registry. The status label
  of a request that ended in an uncaught exception is `500`.
- `UseCases` has a constructor taking `Metrics` and a `MonotonicClock`; `UseCaseModule` uses it. The
  duration covers every attempt of a run, retries and their waits included.
- `fw_metrics_dropped_series_total` exists from start (without series) in the `memory` driver.
- `LockGate.service` throws `IllegalStateException` when no lock instance is configured (from
  `Locks.defaultInstance()`).

## 4. Tests

Background: start after `started`, fixed delay without overlap, initial delay, failure keeps the
loop, consecutive-failure health and recovery, `Error` ends the loop, stuck run, stop before module
hooks, stop within the drain with an interrupt for a task that ignores the stop, `stopping()`,
registration rules, gate outcomes (ran / skipped / lost) and the lock gate end to end on the memory
driver with two "replicas". Metrics: every naming rule, idempotent re-creation, label truncation,
the cardinality bound and its counter, each instrument, histogram buckets, the noop driver, the exact
exposition bytes and escaping, the endpoint with and without the token, built-in HTTP, use-case and
background metrics.
