<!-- types: LockStore, Locks, LockKey, Lease, AcquireResult, RefreshResult, ReleaseResult, WithLockResult, LockedAction, LockStoreUnavailableException, LockedActionException, LocksModule, LockStoreFactory, LockStores, DatabaseLockStores, RedisLockStores, LocksDatabaseModule, MemoryLockDriver, LockStoreContract, AdjustableClock, RedisModule, DatabaseModule -->
# Locks

A lock gives one holder at a time the right to do some work, for a bounded time (the **lease**).
Holders can lose a lock without knowing it (a pause, a network split, an expired lease), so every
lease carries a **fencing token**: a number that grows with each acquisition. Whatever the
protected work writes to should reject a write carrying a lower token than one it has already
seen. The lock alone cannot make that guarantee; the token is what lets the resource make it.

## Setting up

Lock stores are named **in code**, each with the driver its factory chooses; there is no `locks`
configuration (an application can have many lock stores, so they are code,
[Config](config.md#what-configuration-holds)). The database and Redis instances they use are
configuration. All three drivers, as in `examples/locks-demo`:

```java
App.builder()
    // the database and Redis instances, pools included, are configuration
    .module(new DatabaseModule())
    .module(new RedisModule())
    // lock stores can be many, so they are code: one per driver, the driver chosen here
    .module(
        LocksModule.builder()
            .store("main", DatabaseLockStores.database())
            .store("shared", RedisLockStores.redis("default", "fw:lock:"))
            .store("local", LockStores.memory())
            .defaultStore("main")
            .build())
    // the fw_lock migration the database store needs
    .module(new LocksDatabaseModule());
```

| Factory | Driver | Needs |
|---|---|---|
| `LockStores.memory()` (`framework-locks`) | `memory`: one JVM only | nothing |
| `DatabaseLockStores.database()`, `database(instance)` (`framework-locks-database`) | `database` on the default (or a named) database instance | `DatabaseModule`, and `LocksDatabaseModule` for the `fw_lock` migration; on another instance the table must exist, start-up checks |
| `RedisLockStores.redis()`, `redis(connection)`, `redis(connection, prefix)` (`framework-locks-redis`) | `redis` on the default (or a named) connection, keys under `fw:lock:` (or your prefix: not blank, at most 100 characters) | `RedisModule` |

Store names match `[a-z][a-z0-9-]*`. With one store it is the default; with several,
`defaultStore(name)` must name one, or `build()` throws. A factory is a `LockStoreFactory`
(`create(name, lookup)`), so you can write your own, and it can read your own settings from the
lookup's `Config`. A factory that fails fails start-up naming the store. A leftover `locks` section
in a file fails start-up with the code to write instead.

`app.get(Locks.class)` is the registry: `get(name)`, `defaultInstance()`, `names()`.

## Taking a lock

The usual call is `withLock`. It acquires, runs your action while a virtual thread refreshes the
lease every third of its length, and releases:

```java
LockStore store = app.get(Locks.class).defaultInstance();
WithLockResult<String> result =
    store.withLock(
        LockKey.service("nightly-export"),
        Duration.ofSeconds(30),     // lease length
        Duration.ZERO,              // how long to wait for the lock
        lease -> "fence " + lease.fencingToken());
return switch (result) {
  case WithLockResult.Completed<String> c -> c.value();
  case WithLockResult.NotAcquired<String> n -> "someone else has it";
  case WithLockResult.Lost<String> l -> "lost the lock";
};
```

`LockedAction<T>` is `T run(Lease lease) throws Exception`. The action receives the lease so it can
pass `lease.fencingToken()` to what it writes. Results are sealed types; handle them with an
exhaustive `switch`.

| Result | Meaning |
|---|---|
| `Completed(value)` | the action returned and the lock was held throughout |
| `NotAcquired` | the lock was busy for the whole wait; the action did not run |
| `Lost` | the lock was lost while the action ran (a refresh found it taken or expired, or kept failing past the lease). The action's thread was interrupted. `value()` is an `Optional` that is present if the action still returned normally. **Treat the work as having run without exclusion.** |

An exception from the action is rethrown after the lock is released: unchecked ones unchanged,
checked ones wrapped in the unchecked `LockedActionException`.

### The lower-level operations

`LockStore` also has `tryAcquire(key, lease)` (`Acquired(Lease)` or `Busy`),
`acquire(key, lease, maxWait)` (retries with backoff, 10 ms doubling to 250 ms with jitter),
`refresh(lease, newLease)` (`Refreshed(Lease)` or `Lost`) and `release(lease)` (`Released` or
`NotHeld`). `Lease` has `key()`, `ownerToken()`, `fencingToken()`, `remaining()` and
`expiredLocally()`; its local deadline is computed from the holder's monotonic clock reading taken
*before* the request, so it is never later than the store's own expiry.

Lease lengths run from 100 ms to 24 h, `maxWait` from zero to 24 h; outside that is
`IllegalArgumentException`. Passing a `Lease` from one store to another is also
`IllegalArgumentException`.

### Keys

| Scope | Constructor | Stored form |
|---|---|---|
| service | `LockKey.service("nightly-export")` | `svc:nightly-export` |
| client | `LockKey.client(clientId, "invoice-run")` | `client:<clientId>:invoice-run` |

Names match `[A-Za-z0-9._:-]{1,200}`. A client-scoped key can never equal a service-scoped one, and
two clients never collide.

## Guarantees and the differences between drivers

Every driver guarantees: at most one unexpired lease per key on the **store's** clock; fencing
tokens that strictly increase per key (not consecutive); `refresh` and `release` act only for the
current owner and only before expiry; locks are **not reentrant** (acquiring a key you hold is
`Busy`); and release is immediate.

| Driver | Use it for | Caveat |
|---|---|---|
| `memory` | one process: development and tests | two processes do not exclude each other |
| `database` | the default choice in production; uses the default or a named PostgreSQL instance and its clock | needs the `fw_lock` table; on a non-default database instance the driver checks at boot that it exists and fails start-up otherwise (migrations do not run there) |
| `redis` | a single Redis or Valkey server | **not Redlock.** If the server loses data (a failover to a replica that missed writes, a restart without persistence), a lock can be held twice and fencing tokens can repeat. If you need more, use `database` |

Infrastructure failure (store unreachable, query error) throws the unchecked
`LockStoreUnavailableException`. Not being able to get the lock is never an exception.

## Testing

`MemoryLockDriver` takes a clock supplier, so a test can move time without sleeping. The
`framework-locks` test-jar publishes `LockStoreContract`, an abstract JUnit class every driver's
test extends, and `AdjustableClock` (a monotonic clock you can advance). Tests of *your* code can
configure a `memory` instance and use `builder.monotonicClock(...)`. See [Testing](testing.md).

## Not built

There is no purge job for idle `fw_lock` rows (tokens come from one sequence, so rows could be
deleted later without breaking the guarantees). Wrapper drivers (failover, metered) are not
implemented, though the driver factory receives the instances already built so they can be.

Specification: [contracts/locks.md](../contracts/locks.md).
