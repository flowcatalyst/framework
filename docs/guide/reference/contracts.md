<!-- types: Module, Context, LockStore, UseCases, Routes, FlowCatalystModule, CheckedSql, Database -->
# Contracts

The contracts in [`docs/contracts/`](../../contracts/) are the specification. Each capability is
specified there **before** it is built, and the code is a port of that text. This guide is the
how-to; when you need the exact rule, read the contract.

Contracts can lag the code. Differences found when this guide was last verified are listed at the
end of [STATUS](../STATUS.md#where-the-guide-and-the-contracts-can-differ).

| Contract | Covers | Guide page |
|---|---|---|
| [core](../../contracts/core.md) | modules and the application lifecycle, named instances and drivers, `Context`, config, commands | [Modules and lifecycle](../modules-and-lifecycle.md), [Config](../config.md), [Context](../context.md) |
| [config and code](../../contracts/config-and-code.md) | configuration holds service and connection settings and secrets; things with many instances (lock stores, secret stores, HTTP clients, rate limiters) and their policies are code, drivers chosen in code, removed keys fail start-up, claimed components (owner ruling 2026-10-14, clarified the same day; **breaking** for locks, secrets, HTTP clients) | [Config](../config.md) |
| [database and Redis](../../contracts/database-and-redis.md) | database instances, migrations, Redis connections | [Modules and lifecycle](../modules-and-lifecycle.md#the-database-module-and-migrations), [Config](../config.md) |
| [locks](../../contracts/locks.md) | keys, leases, fencing tokens, `withLock`, the three drivers, the contract test suite | [Locks](../locks.md) |
| [write path](../../contracts/write-path.md) | aggregates, repositories, use cases, optimistic locking, events, the outbox, ids | [The write path](../write-path.md) |
| [checked SQL](../../contracts/checked-sql.md) | query files, types, generated code, the Maven plugin | [Checked SQL](../checked-sql.md) |
| [HTTP](../../contracts/http.md) | routing, middleware, validation, errors, Jetty, Datastar | [HTTP](../http.md) |
| [static assets and the SPA](../../contracts/static-assets-and-spa.md) | serving one built single-page application as the router's fallback: API prefixes, missing assets, caching, ETags, security headers, build integration (designed 2026-10-14, not built) | none yet ([STATUS](../STATUS.md), row MS) |
| [OpenAPI](../../contracts/openapi.md) | typed operations declared in code, the emitted OpenAPI 3.1 document, the `openapi` command, the generated TypeScript client; shared with Go (designed 2026-10-14, not built) | none yet ([STATUS](../STATUS.md), row MO) |
| [background and metrics](../../contracts/background-and-metrics.md) | `Background` loops, the lock gate, `Metrics`, built-in metrics, the Prometheus endpoint | [Background and metrics](../background-and-metrics.md) |
| [rate limiting](../../contracts/rate-limiting.md) | GCRA limiters built in code, `memory` and `redis` drivers, the 429 middleware, key resolvers, per-request policies, abuse cases | [Rate limiting](../rate-limiting.md) |
| [sessions and login](../../contracts/sessions-and-login.md) | the application as its own backend-for-frontend: `/auth/*` endpoints, the OIDC relying party, bearer tokens, session cookies and drivers, CSRF, the `ContextResolver` | [Sessions and login](../sessions-and-login.md), [Migrating from flowcatalyst-token](../migrating-from-flowcatalyst-token.md) |
| [HTTP client](../../contracts/http-client.md) | the client library on Vert.x, retries, auth, the framework adapter | [HTTP client](../http-client.md) |
| [secrets](../../contracts/secrets.md) | per-client secret references, the `env` and `file` drivers, the threat model, the blocked platform driver | [Per-client secrets](../secrets.md) |
| [client settings](../../contracts/client-settings.md) | per-client settings: definitions in code, values in `fw_client_settings`, one aggregate per client, written by ordinary use cases (M5 first slice, 2026-10-18) | [Per-client settings](../client-settings.md) |
| [build and publishing](../../contracts/build.md) | `framework-bom`, what an application needs, GitHub Packages, the publish workflow | [Getting started](../getting-started.md) |
| [Rust build](../../contracts/rust-build.md) | the Rust workspace: toolchain, crates and features, lints, supply chain, CI | [The Rust framework](../rust/index.md) |
| [FlowCatalyst module](../../contracts/flowcatalyst.md) | the platform client, definition sync, outbox delivery stance, receivers | [The FlowCatalyst module](../flowcatalyst-module.md), [Receivers](../receivers.md) |

Design documents behind the contracts:

- [`framework-plumbing.md`](../../framework-plumbing.md): the design rules and owner rulings.
- [`implementation-plan.md`](../../implementation-plan.md): milestones and the order of work.
- [`laravel-capability-inventory.md`](../../laravel-capability-inventory.md): the capability
  inventory the design started from.
