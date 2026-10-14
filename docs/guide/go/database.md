<!-- types: Declared, DB, Databases, Tx, Filter, Client, Clients, MigrationSet -->
# Go: database and Redis

Packages `database` and `redisfw` implement the
[database and Redis contract](../../contracts/database-and-redis.md) on pgx v5 (`pgxpool`) and
go-redis v9.

## Database instances

```yaml
database:
  default: main
  instances:
    main:
      url: jdbc:postgresql://localhost:5432/app
      username: app
      password: ""
      pool: { max: 10, min-idle: 1, connect-timeout: 5s }
      migrate: true
```

The driver is `postgresql` and may be left out. `url` accepts `jdbc:postgresql://` (the prefix is
dropped), `postgres://` and `postgresql://`; pgx's `key=value` form is refused, because the Java
framework cannot read it either (the G1 ruling the design asked for). `username` and `password` win
over credentials in the URL. `database.Connect(ctx, b, declared)` opens a pool per instance, pings
it (an instance that does not answer fails the start, naming it), adds the critical health check
`database:<name>` and closes the pool at shutdown. `DB.Pool()` is what sqlc's generated code takes;
`DB.RequireTable` is for a capability that needs a table on a non-default instance.

`database.Tx` is a transaction the framework began (the use-case runner; later a receiver's
`once`): application code uses `Conn()` inside a plan step and can never begin one, because the
constructor is internal to the framework.

## Migrations

A module contributes one set with `Declarer.Migrations(fs)`, usually an embedded directory
(`dblock.Migrations()` is the lock table's). `database.Migrate(ctx, b, dbs)` applies every set, in
module declaration order, to the default instance when its `migrate` is true. The runner is the
Java runner's, byte for byte where it shows:

- files `V<version>__<description>.sql`; history in `fw_schema_history` (module, version,
  description with `_` read as a space, SHA-256 of the file bytes in hex, `applied_at`);
- one transaction per file with its history row; a failing file rolls back and fails the start;
- the same PostgreSQL advisory lock (`0x46575F4D49475241`), so Java and Go instances starting
  together apply each file once;
- the same start-up failures and texts: a changed checksum, an applied version whose file is gone,
  a file below the highest applied version, two files with one version.

Checked against `conformance/migrations/locks-database.json`: the Go runner writes the Java rows,
and accepts a database the Java runner migrated. `database.ApplyMigrations` runs it outside an
application (tests, tools).

`database.Filter` accumulates positional conditions for dynamic list queries (copied from the
platform's `repocommon.Filter`); column names must be constants.

## Redis

```yaml
redis:
  default: default
  instances:
    default: { url: "redis://localhost:6379/0", timeout: 2s }
```

`redisfw.Connect` makes one go-redis client per instance (standalone servers only), pings it, adds
the critical check `redis:<name>` and closes it at shutdown. `url` may carry a user and password
(user info without `:` is the password, as in Java) and a database number; `password` wins;
`rediss://` uses TLS. `Client.Redis()` is the go-redis client.
