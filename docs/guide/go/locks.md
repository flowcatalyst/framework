<!-- types: Key, Lease, Acquisition, Acquired, Busy, Store, Lock, Outcome, WithResult, Locks, DriverSpec, BuildOption, Factory, Gate, MemoryStore, Harness, Env, OffsetClock -->
# Go: locks

Package `locks` implements the [locks contract](../../contracts/locks.md): a key held by one holder
at a time for a lease, with a fencing token that grows with each acquisition. The drivers are
packages compiled only when imported: the memory driver in `locks`, `locks/dblock` (PostgreSQL) and
`locks/redislock` (Redis).

## Configuring

```yaml
locks:
  default: main
  instances:
    main: { driver: database }
    shared: { driver: redis, connection: default, prefix: "fw:lock:" }
    local: { driver: memory }
```

Each driver declares its keys, so a typo is a configuration problem at start-up. Declare and build
(from `examples/locks-demo`):

```go
locks: locks.Declare(app, locks.MemoryDriver(), dblock.Declare(app), redislock.Driver()),
```

```go
all, err := locks.Build(ctx, b, d.locks, dblock.With(dbs), redislock.With(clients))
```

`dblock.Declare` also contributes the lock table's migration set (module `locks-database`, the Java
file byte for byte). A database instance other than the default one is checked for `fw_lock` at
start-up.

## Using a lock

```go
res, err := locks.With(ctx, lock, key, 10*time.Second, 5*time.Second,
	func(_ context.Context, lease locks.Lease) (string, error) {
		return fmt.Sprintf("token %d", lease.Fence()), nil
	})
```

`With` acquires (retrying with backoff from 10 ms doubling to 250 ms, with jitter, until `maxWait`),
runs the action, refreshes the lease every `lease / 3`, and releases it afterwards. The action's
context is **cancelled when the lease is lost**; the result's `Outcome` is `Completed`,
`NotAcquired` or `Lost` (with the action's value when it still returned one). An error from the
action is returned beside the outcome; the context error caused by a loss is not. An action that
ignores its context keeps running, as a Java thread that ignores interrupts does.

`Lock.TryAcquire` and `Lock.Acquire` return an `Acquisition` (`Acquired` with the `Lease`, or
`Busy`); `Refresh` returns the refreshed lease and whether it was still held; `Release` whether it
was. Keys are `locks.ServiceKey(name)` (stored `svc:<name>`) and `locks.ClientKey(client, name)`
(`client:<id>:<name>`). Lease 100 ms to 24 h, wait 0 to 24 h; anything else is
`ErrInvalidArgument`; an infrastructure failure wraps `ErrStoreUnavailable`.

`locks.NewGate` / `locks.ServiceGate` gate a background loop: a run happens only on the holder of
the lock (no wait), with the fencing token in its tick.

## Shared with Java

The database statements are `conformance/sql/locks/*.sql` and the Redis scripts
`conformance/lua/locks/*.lua`, copied into the driver packages and checked byte for byte by the
conformance tests (`make -C go go-sync-shared` refreshes the copies). Placeholders are numbered once
when the package initialises. So a Java and a Go replica exclude each other on one database or one
Redis server.

## The contract suite

`lockstest.Run(t, harness)` is the contract's test suite, one subtest per case (50 racing
acquirers, increasing tokens over release and expiry cycles, refresh, loss after expiry, stale
release, not reentrant, waiting, key scopes, `With` refreshing, losing and releasing, argument
limits). Every driver's tests call it:

```go
lockstest.Run(t, lockstest.Harness{New: func(t *testing.T) lockstest.Env {
	clock := &lockstest.OffsetClock{}
	store := locks.NewMemoryStore(clock)
	return lockstest.Env{
		Lock:   locks.New(store, locks.WithClock(clock)),
		Expire: func(_ *testing.T, k locks.Key) { store.ExpireNow(k.Stored()) },
		Pass:   func(_ *testing.T, d time.Duration) { clock.Advance(d) },
	}
}})
```
