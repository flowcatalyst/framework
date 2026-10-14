# Contract: database connections, migrations and Redis connections

Status: M1. The write path (unit of work, repositories, optimistic locking) is M2 and has its
own contract.

## 1. Database instances

*Amended 2026-10-14 ([config and code](config-and-code.md), sections 3.2 and 3.3): `pool.*`, `migrate`, Redis `timeout` and
`pool.max`, and `driver` are no longer configuration. They are `DatabaseModule.builder().pool(..)`
and `.migrate(..)`, `RedisModule.builder().pool(..)`, with the same defaults; an instance section
holds `url`, `username` and `password` (Redis: `url`, `password`).*

PostgreSQL only in M1. Named instances, configured like any capability:

```yaml
database:
  default: main
  instances:
    main:
      url: jdbc:postgresql://localhost:5432/app
      username: app
      password: ""          # a secret: give it in secrets.local.yaml or DATABASE_INSTANCES_MAIN_PASSWORD
      pool: { max: 10, min-idle: 1, connect-timeout: 5s }
      migrate: true
```

- Each instance is a HikariCP pool over the PostgreSQL JDBC driver. `password` is read as a
  `Secret`.
- `Databases` (the registry) gives `get(name)` and `defaultInstance()`; each instance exposes its
  `javax.sql.DataSource` and a `ping()` used as its health check (`database:<name>`).
- Start-up opens one connection per instance and fails, naming the instance and the cause, if it
  cannot. Pools are closed at shutdown.

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
