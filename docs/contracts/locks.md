# Contract: locks

Status: M1. Drivers: `database`, `redis`, `memory` (owner, 2026-10-08).

A lock gives one holder at a time the right to do some work, for a bounded time (the lease).
Holders can lose a lock without knowing it (a pause, a network split, an expired lease), so every
lease carries a **fencing token**: a number that grows with each acquisition. Whatever the
protected work writes to should reject a write carrying a lower token than one it has already
seen. The lock alone cannot make that guarantee; the token is what lets the resource make it.

## 1. Keys

`LockKey` is a record of a scope and a name.

| Scope | Constructor | Stored form |
|---|---|---|
| service | `LockKey.service("nightly-export")` | `svc:nightly-export` |
| client | `LockKey.client(clientId, "invoice-run")` | `client:<clientId>:invoice-run` |

Names match `[A-Za-z0-9._:-]{1,200}`; anything else is `IllegalArgumentException`. A client-scoped
key cannot equal any service-scoped key, and keys of different clients never collide: a
`ClientId` never contains `:` (core contract section 3), so the first `:` after `client:` always
ends the client id.

## 2. Operations

`LockStore` (one per named instance; registry `Locks`, see core contract section 2):

| Operation | Result |
|---|---|
| `tryAcquire(key, lease)` | `Acquired(Lease)` or `Busy` |
| `acquire(key, lease, maxWait)` | as `tryAcquire`, retrying until acquired or `maxWait` passes; throws `InterruptedException` |
| `refresh(lease, newLease)` | `Refreshed(Lease)` (a new value with the new deadline, same tokens) or `Lost` |
| `release(lease)` | `Released` or `NotHeld` |
| `withLock(key, lease, maxWait, action)` | see section 4 |

Results are sealed types; callers handle them with an exhaustive `switch`.

`Lease` is a record: key, owner token (an opaque string of at least 128 random bits, unique per
acquisition), fencing token (`long`), and a **local deadline**. The local deadline is the
holder's monotonic clock reading taken *before* the acquire or refresh request was sent, plus the
lease length. It is therefore never later than the store's own expiry. `Lease.remaining()` and
`Lease.expiredLocally()` read it.

Arguments: lease length from 100 ms to 24 h inclusive, `maxWait` from zero to 24 h; outside that
is `IllegalArgumentException`. A `Lease` from one store instance passed to another is
`IllegalArgumentException`.

Infrastructure failure (store unreachable, query error, timeout) throws
`LockStoreUnavailableException` (unchecked), with the cause attached. Not being able to get the
lock is never an exception.

## 3. What every driver guarantees

1. **Mutual exclusion.** At any instant of the store's clock, at most one unexpired lease exists
   per key.
2. **Expiry on the store's clock.** A lease expires when the store's clock passes its expiry:
   database time for `database`, the server's TTL for `redis`, the injected monotonic clock for
   `memory`. Holders' clocks are never compared with each other.
3. **Fencing tokens increase.** For one key, every successful acquisition returns a token
   strictly greater than every token returned before for that key by that store, including after
   release and after expiry. Tokens are not consecutive. For `redis` this holds as long as the
   server keeps its data (see section 5).
4. **Owner-checked refresh and release.** `refresh` and `release` act only when the owner token
   and fencing token match the current lease **and** it has not expired on the store's clock.
   Otherwise they return `Lost` / `NotHeld` and change nothing. A lease that expired is lost even
   if nobody else took the key since.
5. **Not reentrant.** Acquiring a key you already hold returns `Busy`.
6. **Acquire after expiry or release succeeds** at once, with a new fencing token.
7. **Release is immediate.** After `Released`, the next `tryAcquire` of the key may succeed.

`acquire` with a wait is implemented once, in the capability, on top of `tryAcquire`: it retries
with backoff starting at 10 ms, doubling to at most 250 ms, with jitter, until acquired or the wait
is used up, then returns `Busy`. An interrupt stops it with `InterruptedException`, including an
interrupt that arrives during a driver call and surfaces there as a store failure (JDBC reports
it as an SQL error): when the thread's interrupt flag is set, that failure is reported as
`InterruptedException`, not `LockStoreUnavailableException`.

## 4. `withLock`

```java
WithLockResult<T> withLock(LockKey key, Duration lease, Duration maxWait, LockedAction<T> action)
    throws InterruptedException
```

`LockedAction<T>` is `T run(Lease lease) throws Exception`; the action receives the lease so it
can pass the fencing token on to what it writes.

1. Acquire with `acquire(key, lease, maxWait)`. If `Busy`, return `NotAcquired`; the action does
   not run.
2. Run the action on the calling thread. Meanwhile a virtual thread refreshes the lease every
   `lease / 3`, with `newLease = lease`.
3. If a refresh returns `Lost`, or keeps failing with `LockStoreUnavailableException` until the
   lease's local deadline has passed, the lock is lost: the refresher interrupts the action's
   thread and stops.
4. When the action ends, stop the refresher and, if the lock was not lost, release it. A release
   failure is logged and does not change the result.
5. Result, a sealed type:
   - `Completed(value)`: the action returned and the lock was held throughout.
   - `NotAcquired`.
   - `Lost`: the lock was lost while the action ran. Its `value()` returns an `Optional` that is
     present if the action still returned normally with a non-null value. The caller must treat
     the work as having run without exclusion.
6. An exception from the action is rethrown after step 4: unchecked exceptions unchanged, checked
   ones wrapped in `LockedActionException` (unchecked) with the original as cause. An
   `InterruptedException` from the action caused by a loss is not rethrown; it produces `Lost`.

## 5. Drivers

### `memory`

Locks only within one process: for a single instance, local development and tests. Two
processes using it do not exclude each other. Expiry uses an injected monotonic clock
(`LongSupplier` of nanoseconds; default `System::nanoTime`), so tests can move time without
sleeping. Fencing tokens come from one counter per store instance.

Config: none.

### `database`

PostgreSQL, on a database instance (core contract section 2).

Config: `database` (instance name, default the default database instance).

Schema, contributed as the module's migration `V0001__create_lock_table.sql`:

```sql
CREATE SEQUENCE fw_lock_fence;
CREATE TABLE fw_lock (
    lock_key      text        PRIMARY KEY,
    owner_token   text,
    fencing_token bigint      NOT NULL,
    expires_at    timestamptz NOT NULL
);
```

Statements, each in autocommit, all using `clock_timestamp()` (not `now()`, which is the start of
the transaction):

```sql
-- acquire: zero rows returned means Busy
INSERT INTO fw_lock (lock_key, owner_token, fencing_token, expires_at)
VALUES (?, ?, nextval('fw_lock_fence'), clock_timestamp() + ? * interval '1 millisecond')
ON CONFLICT (lock_key) DO UPDATE
   SET owner_token = EXCLUDED.owner_token,
       fencing_token = EXCLUDED.fencing_token,
       expires_at = EXCLUDED.expires_at
 WHERE fw_lock.expires_at <= clock_timestamp()
RETURNING fencing_token;

-- refresh: one row updated means Refreshed
UPDATE fw_lock SET expires_at = clock_timestamp() + ? * interval '1 millisecond'
 WHERE lock_key = ? AND owner_token = ? AND fencing_token = ? AND expires_at > clock_timestamp();

-- release: one row updated means Released
UPDATE fw_lock SET owner_token = NULL, expires_at = clock_timestamp()
 WHERE lock_key = ? AND owner_token = ? AND fencing_token = ? AND expires_at > clock_timestamp();
```

Tokens come from one sequence for all keys, which satisfies guarantee 3 and means idle rows could
be deleted later without breaking it. No purge job in M1. If the configured instance is not the
default one, the driver checks at boot that `fw_lock` exists and fails start-up if not (database
contract section 2).

### `redis`

One standalone Redis or Valkey server (database contract section 3). Not Redlock: if the server
loses data (a failover to a replica that missed writes, a restart without persistence), a lock
can be held twice and fencing tokens can repeat. The contract states this; applications that need
more use the database driver.

Config: `connection` (Redis instance name, default the default instance), `prefix` (default
`fw:lock:`).

Keys: the lock at `<prefix><stored key>`, holding `<owner token>:<fencing token>` with a
millisecond TTL; one counter at `<prefix>__fence`, never expiring. All three operations are Lua
scripts run with `EVALSHA` (loaded at boot, reloaded on `NOSCRIPT`):

- acquire: if the lock key exists return nil; else `INCR` the counter, `SET` the lock key with
  `PX lease`, return the counter value.
- refresh: if the lock key's value equals `<owner>:<fence>`, `PEXPIRE` it and return 1; else 0.
- release: if the value matches, `DEL` and return 1; else 0.

## 6. The contract test suite

Published as the `framework-locks` test-jar: an abstract JUnit class each driver's test extends,
supplying a store, a way to let time pass (move the clock for `memory`; real time with short
leases for `database` and `redis`), and a test-only hook that expires a key's current lease at
once in the store itself (move the clock; set `expires_at` in the past; `DEL` the key), so loss
can be tested without waiting. It checks every guarantee in section 3 and the `withLock` rules in
section 4, at least:

- one of 50 virtual threads racing `tryAcquire` on a free key gets `Acquired`; the rest `Busy`;
- 20 acquire/release cycles on one key give strictly increasing tokens; so do cycles that end
  by expiry instead of release;
- refresh extends the lease; refresh and release after expiry return `Lost` / `NotHeld`, even
  when nobody took the key; a stale lease cannot release a newer holder's lock;
- holding a key and acquiring it again returns `Busy`;
- `acquire` with a wait succeeds once the holder releases, and returns `Busy` when the wait runs
  out;
- service and client keys with the same name do not collide; nor do two clients;
- `withLock` keeps a lock beyond its lease length by refreshing it; reports `Lost` and interrupts
  the action when the lease is taken from it; releases after an exception and rethrows it;
- argument limits throw `IllegalArgumentException`.
