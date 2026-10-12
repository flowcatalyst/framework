<!-- types: LocksModule, LocksDeclared, Locks, LocksBuilder, Lock, LockKey, LockName, Lease, LeaseLength, MaxWait, Acquire, Refresh, Release, WithLock, LockStore, LockStoreError, MemoryLockStore, PostgresLockStore, RedisLockStore, LockGate, LockHarness -->
# Rust: locks

`fw-locks` is the Rust counterpart of `framework-locks`, `framework-locks-database` and
`framework-locks-redis` ([contract](../../contracts/locks.md), Java guide [Locks](../locks.md)):
leases with fencing tokens, `with_lock`, and three drivers. The drivers are Cargo features of the
one crate: `memory` always, `database` with the feature `postgres`, `redis` with the feature
`redis`. Naming a driver whose feature is not compiled in fails start-up and says which feature to
enable.

The configuration is the Java one (`database` is the driver name, so one file serves both):

```yaml
locks:
  default: main
  instances:
    main:   { driver: database }                          # database: optional, the default instance
    shared: { driver: redis, connection: default, prefix: "fw:lock:" }
    local:  { driver: memory }
```

## Building the instances

`LocksModule` declares the section (and, with `postgres`, the migration set of `fw_lock`); the build
function gives `Locks::builder` the connections the drivers in use need (from `examples/locks-demo`):

```rust
let dbs = Databases::connect(boot, &declared.database).await?;
dbs.migrate(boot).await?;
let redis = RedisConnections::connect(boot, &declared.redis).await?;
let locks = Locks::builder(boot, &declared.locks).databases(&dbs).redis(&redis).build().await?;
```

A driver used in configuration whose connections were not given, a `database` or `connection`
naming an instance that is not configured, and a missing `fw_lock` table on a non-default database
instance are start-up problems, all reported together.

## Using a lock

```rust
const EXPORT: LockName = LockName::of("nightly-export");          // checked when the crate compiles
let lease = LeaseLength::new(Duration::from_secs(10))?;           // 100 ms to 24 h
match lock.with_lock(&LockKey::service(&EXPORT), lease, MaxWait::ZERO, async |l| l.fencing_token()).await? {
    WithLock::Completed(token) => println!("ran with token {token}"),
    WithLock::NotAcquired => println!("another replica is exporting"),
    WithLock::Lost(_) => println!("ran without exclusion"),
}
```

`LockKey::service(name)` and `LockKey::client(&client, name)` store `svc:<name>` and
`client:<client>:<name>`, as in Java. `try_acquire`, `acquire` (backoff 10 ms doubling to 250 ms
with jitter), `refresh` and `release` return the contract's outcomes as enums (`Acquire`, `Refresh`,
`Release`); a store failure is a `LockStoreError`, never an outcome.

`with_lock` refreshes the lease every third of its length. When the lock is lost (a refresh says
`Lost`, or refreshes fail until the local deadline), `lease.lost()` is cancelled, the action is
polled once more, and otherwise its future is **dropped**: the work stops at its next `.await`
(design decision 15). An action that watches `lease.lost()` can return at a safe point, giving
`Lost(Some(value))`. The action's own errors are its value (`T` is usually a `Result`); the lock is
released after it returns.

## The lock gate

`LockGate` gates a background loop: each run takes the lock with no wait, so a held lock skips the
run, and the run's `LoopTick` carries the fencing token:

```rust
let gate = LockGate::service(&locks, &HEARTBEAT, lease)?;
boot.background().spawn_loop("heartbeat", LoopOptions::every(Duration::from_secs(1)).gated_by(Arc::new(gate)), move |tick| { .. })?;
```

## Shared with the Java framework

The `database` driver sends the Java statements byte for byte (`conformance/sql/locks/`; JDBC's
`?` placeholders numbered `$1`, `$2`, ... as the PostgreSQL JDBC driver numbers them), on the same
table and sequence, created by the same migration file under the same module id (`locks-database`).
The `redis` driver loads the Java Lua scripts byte for byte (`conformance/lua/locks/`, so both load
the same SHA-1), with the same key format, value `<owner>:<fence>` and counter key. A Java and a
Rust replica can therefore share one lock table or one Redis server.

## Testing a driver

With the feature `testing`, `fw_locks::testing::suite!` expands to one test per contract case (the
Java `LockStoreContract`): implement `LockHarness` (the lock under test, a foreign lock, how time
passes) and invoke it:

```rust
fw_locks::testing::suite!(harness(), #[tokio::test(start_paused = true)]);   // memory, paused clock
```

The memory, database and Redis drivers all pass it in this repository.
