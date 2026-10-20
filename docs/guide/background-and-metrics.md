<!-- types: Background, LoopOptions, LoopTask, LoopTick, LoopGate, LockGate, Metrics, Counter, Gauge, Timer, MetricsSnapshot, PrometheusText, MetricsEndpoint -->
# Background work and metrics

Two things every service needs and the framework now owns: **loops** that run work again and again
(a sweeper, a retry pass, a cache refresh) and **metrics** (counters, gauges, timers, a Prometheus
endpoint). Both are in `framework-core` and always available: `app.get(Background.class)` and
`app.get(Metrics.class)`. The specification is
[contracts/background-and-metrics.md](../contracts/background-and-metrics.md).

## Loops

Register a loop in `boot` (or later). From `BackgroundTest`:

```java
app.get(Background.class)
    .loop(
        "flaky",
        LoopOptions.every(FAST).downAfterFailures(3),
        tick -> {
          if (fail.get()) {
            throw new IllegalStateException("secret detail: password=hunter2");
          }
        });
```

- **Names** match `[a-z][a-z0-9-]*` (at most 100 characters) and are unique.
- **When it runs:** loops registered during start-up begin only after **every** module's `started`
  hook, so a loop never sees a half-started application; one registered later begins at once. A
  command run through `launch` never starts loops.
- **Schedule:** `LoopOptions.every(interval)` is a fixed delay: the next run starts `interval` after
  the previous one *ended*, so runs never overlap. `.initialDelay(d)` waits before the first run.
  Intervals are 10 ms to 24 h.
- **The task** gets a `LoopTick`: `loop()`, `run()` (1, 2, ...), `fencingToken()` (present when a
  lock gate admitted the run) and `stopping()`, which turns true when shutdown begins; a long run
  should check it and return.
- **A failed run** (an exception) is logged at ERROR and counted; the loop carries on. An `Error`
  (out of memory, stack overflow) ends the loop.
- **Shutdown:** `App.close()` stops loops **first**, before any module's `shutdown` hook. A waiting
  loop ends at once; a running task gets `app.shutdown.drain`, then its thread is interrupted and
  left behind with a WARN.

### Health

Each loop adds the check `background:<name>`. It is `Down` when the last `downAfterFailures` runs
(default 3) all failed, when the loop ended on an `Error`, or, with `.stuckAfter(d)`, while one run
lasts longer than `d`. The reason names the exception **class** only, never its message, because
health is served without authentication. The check is **informational** by default (reported, never
makes the application not ready); `.critical()` makes it decide readiness, and
`app.health.critical` / `app.health.informational` override either way
([health checks](modules-and-lifecycle.md#critical-and-informational-checks)).

### One replica at a time: the lock gate

With several replicas, each runs every loop. To have only one do each run, gate the loop with a
lock (`framework-locks`). From `LockGateTest`:

```java
bg.loop(
    name,
    LoopOptions.every(Duration.ofMillis(10))
        .gatedBy(LockGate.service(app.get(Locks.class), "sweeper", Duration.ofSeconds(5))),
    tick -> {
      assertThat(tick.fencingToken()).isPresent();
      // ... the work
    });
```

Each run takes the lock with a wait of **zero**: if another replica holds it, this run is skipped
(not delayed) and counted as `skipped`. While the run lasts the lease is refreshed (every third of
it). If the lock is lost mid-run, the task's thread is interrupted and the run counts as `lost`
(a failure for health): it ran without exclusion. Pass `tick.fencingToken()` to whatever the run
writes so a write from a run that lost its lock can be refused ([Locks](locks.md)). A lock store
that is down is a failed run. `LockGate.of(store, key, lease)` takes any store and key.

A loop that works per client does the fan-out inside its tick with `PerClient.each(base, clients, task)` ([context](context.md#work-for-one-client-after-another)).

A gate is just `LoopGate`: `enter(loop, work)` returns `Ran`, `Skipped(reason)` or `Lost`.

## Metrics

```java
Counter c = metrics.counter("app_orders_total", "Orders placed.", "kind");
c.labels("web").inc();

Timer t = metrics.timer("app_job_seconds", "Job time.", List.of(0.1, 1.0), "job");
t.labels("export").record(Duration.ofMillis(50));

metrics.gauge("app_pool_size", "Pool size.", depth::get);   // read when exported
```

(from `MetricsTest`). An instrument is a **family** with fixed label names; `labels(values...)`
returns the series to record on, which you may keep for hot paths. A family without labels records
directly (`counter.inc()`, `gauge.set(v)`, `timer.record(d)`). A timer's series also has
`time(callable)`, which records even when the work throws.

A label value that will never be recorded again (a closed client, a finished tenant) is dropped
with `counter.removeSeries("label", "value")` (also on `Timer`), which returns how many series it
removed and frees their share of `app.metrics.max-series`. A series handle kept from before is
detached: ask `labels(...)` again to start a new series. `ServiceClients.unregister` uses it.

### Rules

Checked when the instrument is created (an `IllegalArgumentException` means a bug in your code):

- names `[a-z][a-z0-9_]*`, at most 200 characters; counters end in **`_total`**, timers in
  **`_seconds`** (they record seconds), gauges in neither. Prefix with your component (`orders_`);
  `fw_` is the framework's.
- help text 1 to 500 characters; at most 10 label names, `[a-z][a-z0-9_]*`, not `le`, `quantile` or
  starting with `__`;
- creating an instrument again with the same definition returns the same family; a different help,
  type, labels or buckets for the same name is refused.

At run time recording **never throws** for a label *value*: `null` becomes empty and values longer
than 200 characters are cut. Only a wrong *number* of values throws.

**Cardinality is bounded.** A family holds at most `app.metrics.max-series` series (default 1000).
Past that, recordings for new label combinations are dropped, counted in
`fw_metrics_dropped_series_total{metric="..."}`, and logged once. So never use ids, request paths,
user input or exception messages as label values.

### Drivers

| `app.metrics.driver` | Behaviour |
|---|---|
| `memory` (default) | in-process; `metrics.snapshot()` and the Prometheus endpoint read it |
| `noop` | records nothing; names are still checked |

`app.metrics.max-series` is 1 to 100 000. In tests, `AppBuilder.override(Metrics.class,
Metrics.memory(...))` gives you a registry to inspect ([Testing](testing.md#replacing-a-service)).

### Built-in metrics

| Name | Type | Labels |
|---|---|---|
| `fw_http_server_requests_total` | counter | `method`, `route`, `status` |
| `fw_http_server_request_duration_seconds` | timer | `method`, `route` |
| `fw_usecase_runs_total` | counter | `usecase`, `outcome` (`committed` or the failure kind: `validation`, `authorization`, `not_found`, `conflict`, `business_rule`, `internal`) |
| `fw_usecase_duration_seconds` | timer | `usecase` (all attempts) |
| `fw_background_runs_total` | counter | `loop`, `outcome` (`success`, `failure`, `skipped`, `lost`) |
| `fw_background_run_duration_seconds` | timer | `loop` (skipped runs are not timed) |
| `fw_metrics_dropped_series_total` | counter | `metric` |

`route` is the matched route **pattern** (`/orders/{id}`), or `<unmatched>`, so a scan of random
paths cannot create series.

### The Prometheus endpoint

Off by default; metrics reveal routes and volumes, so exposing them is your decision:

```yaml
http:
  metrics:
    enabled: true
    path: /internal/metrics        # default /metrics; literal segments only
    # bearer-token: from the environment, HTTP_METRICS_BEARER_TOKEN
```

With `bearer-token` set, scrapes need `Authorization: Bearer <token>` (compared in constant time);
otherwise `401`. Start-up warns when the endpoint is enabled without a token, or with the `noop`
driver. The body is the Prometheus text format 0.0.4 (`PrometheusText.write(snapshot)`); timers
appear as histograms (`_bucket`, `_sum`, `_count`). Without the Jetty module, register
`MetricsEndpoint.handler(metrics, token)` on a route yourself. From the Jetty module's test:

```
fw_http_server_requests_total{method="GET",route="/ping",status="200"} 1
```

## Testing

`Metrics.memory(maxSeries, clock)` is a plain registry you can create in a unit test and read with
`snapshot()`; `TestClient.of(routes, resolver, metrics)` records the request metrics into it. For
loops, start a real `App` and wait for a condition (the loop runs on real time); see
`BackgroundTest` and `LockGateTest`.
