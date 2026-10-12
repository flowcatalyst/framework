<!-- types: PgServer, TestDatabase, RedisServer -->
# The Rust framework

A second implementation of the [contracts](../reference/contracts.md), in Rust, in the `rust/`
workspace of this repository. Applications built on either implementation are meant to be
interchangeable to the FlowCatalyst platform: the same outbox rows, ids, config semantics and
framework tables. The design is in [`docs/rust/`](../../rust/design.md); the plan and its state in
[Rust milestones](../../rust/milestones.md).

**State:** milestone R0 (the workspace) is built; R1 (foundation and locks) is in progress:
[application and config](core.md) and [database](database.md) are built. Nothing is published; crate names are the
design's working names (`fw-*`). The Java pages of this guide describe the Java framework only.

## The workspace

```
rust/
  Cargo.toml            workspace: members, shared package fields, lint table, dependency versions
  rust-toolchain.toml   1.98.1, the platform's pin
  deny.toml             licences (permissive only), advisories, crates.io only
  clippy.toml           tests may unwrap, expect and panic
  .cargo/config.toml    SQLX_OFFLINE=true
  crates/fw-core        lifecycle, modules, config, Context and ids, health, background loops, metrics
  crates/fw-db          PostgreSQL instances, the migration runner, Db and Tx
  crates/fw-test        PostgreSQL 18 and Redis for tests, a fresh database per test
  conformance/          the conformance runner: checks the shared vectors in ../conformance/
```

The shared vectors live at the repository root in [`conformance/`](../../../conformance/README.md),
beside `ui-kit/`. The Java tests write them; the Rust tests only compare. The build rules (edition,
MSRV, features, lints, supply chain) are the [Rust build contract](../../contracts/rust-build.md).

## Building and testing

From `rust/`:

```
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-features
cargo deny check
```

Tests that need PostgreSQL or Redis get them from `fw-test`. One server of each per test binary,
and a fresh database per test (`PgServer::fresh_database`, which returns a `TestDatabase` with a
`postgres://` URL and the same database as a `jdbc:` URL), so tests run in parallel:

- With `FW_TEST_DATABASE_URL` (a `postgres://user[:password]@host:port[/db]` URL of a role that may
  `CREATE DATABASE`) and `FW_TEST_REDIS_URL` set, those servers are used. CI sets them to its
  service containers. Use a throwaway server: test databases are never dropped.
- Otherwise Testcontainers starts `postgres:18-alpine` and `redis:8-alpine` once per test binary
  (Docker must be running) and removes them when the binary exits.

`RedisServer::unique_prefix` gives each test its own key prefix on the shared Redis.

## The conformance vectors

Run the Java side to (re)write them, from the repository root:

```
./mvnw -q verify -Dconformance.update=true
```

then review `git diff conformance/`. The Java helper is `Conformance` (and `JsonVectors`) in the
`framework-core` test-jar. The Rust side is `cargo test -p fw-conformance`.
