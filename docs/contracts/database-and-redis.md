# Contract: database connections, migrations and Redis connections

Status: M1. The write path (unit of work, repositories, optimistic locking) is M2 and has its
own contract.

## 1. Database instances

*Amended 2026-10-14 ([config and code](config-and-code.md), sections 3.2, 3.3 and 11): instances are
connections and stay configuration with every key below, except `driver`, which is removed (a
leftover one fails start-up). The pool keys and Redis `timeout` are now range-checked at start-up
(`pool.max` 1 to 1000, `pool.min-idle` 0 to `pool.max`, `pool.connect-timeout` 250 ms to 5 min,
`pool.login-timeout` 1 s to 5 min in whole seconds (added 2026-10-17, section 1.1),
Redis `timeout` more than 0 and at most 5 min); a connect timeout under 250 ms is no longer raised
silently.*

PostgreSQL only in M1. Named instances, configured like any capability:

```yaml
database:
  default: main
  instances:
    main:
      url: jdbc:postgresql://localhost:5432/app
      username: app
      password: ""          # a secret: give it in secrets.local.yaml or DATABASE_INSTANCES_MAIN_PASSWORD
      pool: { max: 10, min-idle: 1, connect-timeout: 5s, login-timeout: 10s }
      migrate: true
```

- Each instance is a HikariCP pool over the PostgreSQL JDBC driver. `password` is read as a
  `Secret`.
- `Databases` (the registry) gives `get(name)` and `defaultInstance()`; each instance exposes its
  `javax.sql.DataSource` and a `ping()` used as its health check (`database:<name>`).
- Start-up opens one connection per instance and fails, naming the instance and the cause, if it
  cannot. Pools are closed at shutdown.

### 1.1 Login timeout (added 2026-10-17)

`pool.connect-timeout` is how long a *caller* waits for a connection from the pool. It cannot stop
the attempt the pool already started: a server that accepts the TCP connection and never answers
(a wedged container, a proxy with nothing behind it) leaves that attempt, and the pool's close
after a failed start, waiting on the driver. `pool.login-timeout` is the driver's own limit for one
attempt (TCP connect, TLS, authentication), passed to the PostgreSQL JDBC driver as `loginTimeout`.

- Default `10s`, range 1 s to 5 min, **whole seconds** (the driver counts seconds); checked at
  start-up like the other pool keys (`1500ms` is refused, not rounded). Environment variable
  `DATABASE_INSTANCES_<NAME>_POOL_LOGIN_TIMEOUT`. A service/connection setting, so configuration
  (config-and-code contract, section 3.2).
- Why 10 s: twice the default `connect-timeout` (5 s), so on a normal failure the caller's timeout
  speaks first and the login timeout only bounds what is left behind; it equals the PostgreSQL
  driver's own default TCP connect limit. The 250 ms floor of `connect-timeout` is HikariCP's; the
  driver cannot express less than 1 s, hence the different floor.
- A start-up that fails on a timeout says so: `cannot connect to database instance '<name>': ...
  (limits: pool.connect-timeout ..., pool.login-timeout ...; the database is not answering, ...)`.
- Java only so far; the Go and Rust database modules do not read the key yet.

## 2. Migrations

- Plain SQL files, forward only, named `V<version>__<description>.sql` with a positive integer
  version (`V0001__create_lock_table.sql`).
- Every module may contribute one migration set during `register`: its module id plus a
  classpath location. The application's own module contributes its set the same way. Sets are
  applied in module registration order; within a set, by version.
- Migrations apply to the **default** database instance, and only when its `migrate` is true
  (default true).
- History table `fw_schema_history`: `module text, version int, description text, checksum text
  (SHA-256 of the file bytes, hex), applied_at timestamptz`, primary key `(module, version)`.
- Each file runs in its own transaction with its history row. A file that fails rolls back and
  fails start-up, naming the module, the file and the database error.
- The run holds a PostgreSQL advisory lock for its whole duration, so instances starting together
  apply each file once.
- Fails start-up: an applied file whose checksum changed; an applied version whose file is gone;
  a file with a version below the highest applied version of its set that was never applied;
  two files with one version.
- A module whose code needs a table on a non-default instance must check the table exists at boot
  and fail start-up with a message saying migrations do not run there.

## 3. Redis connections

```yaml
redis:
  default: default
  instances:
    default: { url: "redis://localhost:6379/0", timeout: 2s }
```

- One standalone Redis or Valkey server per instance. Redis Cluster and Sentinel are out of scope
  in M1.
- `url` may carry user and password; a separate `password` key is read as a `Secret` and wins.
- `RedisConnections` gives `get(name)` and `defaultInstance()`; each exposes a client the lock and
  later the cache drivers use, and a `ping()` health check (`redis:<name>`).
- Start-up pings each instance and fails, naming it, if it cannot.
- The client library is blocking (Jedis), used from virtual threads. Its pool is closed at
  shutdown.
