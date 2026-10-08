# Implementation plan (2026-10-08)

Companion to [`framework-plumbing.md`](framework-plumbing.md), which holds the rules and the
owner rulings. Java 25 first (ruled 2026-10-08). Each capability is specified in
[`contracts/`](contracts/) before it is built; the code is a port of that text.

## Milestones

| # | Scope | Depends on | Status |
|---|---|---|---|
| M1 | **Foundation and locks:** modules and the application lifecycle, named instances and the driver registry, `Context`, config (declarations, layered local sources, secrets), database connections and migrations, Redis connections, the lock capability with memory, database and Redis drivers and a shared contract test suite | — | In progress |
| M2 | **Write path:** unit of work, repositories, optimistic locking with per-use-case conflict policy, events into a local outbox; read path with typed queries; build-time SQL checking for Java | M1 | Not started |
| M3 | **FlowCatalyst module:** identity and users, events through the platform outbox, dispatch and scheduled jobs; `Context` built from a request | M2, platform SDK | Not started |
| M4 | **HTTP and UI:** routing, the one middleware pipeline, typed request validation, the Datastar adapter; the trial business service | M3 | Not started |
| M5 | **Cache and storage;** the platform config driver; metering and messaging | M2; platform config and metering features | Not started |

Platform work the framework needs, to be built in the platform (Go first):

- optimistic locking on the records administrators edit;
- the config and secret store, keyed and access-controlled by client and
  `{application_code}:{sub_domain}` (framework-plumbing.md section 6);
- metering and outbound message tracking (M5).

## M1 in detail

### Repository layout

Maven multi-module build at the repository root, beside `docs/` and `ui-kit/`:

| Module | Contents |
|---|---|
| `framework-core` | `Module`, `AppBuilder`, `App`, typed service lookup, `Drivers` and named instances, `Context` and its id types, config (declarations, sources, `Secret`), health checks, shutdown |
| `framework-database` | named database instances (HikariCP, PostgreSQL JDBC), migrations contributed per module |
| `framework-redis` | named Redis connections, shared later by cache and rate limiting |
| `framework-locks` | the lock contract, the memory driver, `withLock`, and the contract test suite published as a test-jar |
| `framework-locks-database` | the database (PostgreSQL) lock driver and its migration |
| `framework-locks-redis` | the Redis lock driver |

Group id `io.flowcatalyst.framework`, package root `io.flowcatalyst.framework`. Both are
placeholders until naming is settled (open question 6). Licence Apache-2.0, as the platform.

### Java house rules

These hold in every module. They exist to give one way of doing each thing.

- **Values are records.** No Lombok, no mutable beans.
- **Expected outcomes are sealed result types** handled with an exhaustive `switch`. Exceptions
  are for programming errors and infrastructure failure, and each contract names the ones it
  throws.
- **Nullness is checked at build time.** Every package is `@NullMarked` (JSpecify) and the build
  runs Error Prone with NullAway as an error. `Optional` is for return values only, never fields
  or parameters.
- **No reflection, no annotation scanning, no classpath scanning** for wiring. Modules are listed
  in code.
- **Blocking code on virtual threads.** No reactive APIs in public contracts.
- **Time comes from an injected `InstantSource`** (and a monotonic nanosecond source where
  elapsed time matters), never from `Instant.now()` in library code, so the fakes can control it.
- **Logging through the SLF4J API only.** Library modules bring no logging backend.

### Tests

- JUnit 5 and AssertJ.
- Real PostgreSQL 18 and Redis through Testcontainers, one container per test class.
- Every driver of a capability runs the same contract suite from the capability's test-jar. A
  driver is done when it passes it.

### Order of work

1. Build skeleton: parent POM, Error Prone and NullAway, the modules empty, `mvn verify` green.
2. `framework-core`: modules and lifecycle, service lookup, drivers and named instances,
   `Context`, config, health, shutdown.
3. `framework-database`: instances and migrations.
4. `framework-redis`: connections.
5. `framework-locks`: contract types, memory driver, `withLock`, contract suite.
6. `framework-locks-database`, then `framework-locks-redis`.
7. An example application under `examples/locks-demo` that starts with all three lock instances
   configured, so the start-up path is exercised end to end.

One commit per step, each with `mvn verify` passing.
