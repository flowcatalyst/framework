<!-- types: DatabaseModule, DatabaseDeclared, Databases, Db, Tx, Migration, MigrationSet, MigrationFile, RedisModule, RedisDeclared, RedisConnections, RedisConnection -->
# Rust: database, migrations and Redis

`fw-db` is the Rust counterpart of `framework-database` ([contract](../../contracts/database-and-redis.md)):
named PostgreSQL instances on sqlx pools, a start-up ping and a health check per instance, and the
framework's own migration runner. The configuration is the Java one, so one file serves both:

```yaml
database:
  default: main
  instances:
    main:
      url: jdbc:postgresql://localhost:5432/app   # or postgres://...
      username: app
      password: ""                               # a secret
      pool: { max: 10, min-idle: 1, connect-timeout: 5s }
      migrate: true
```

A `jdbc:` prefix is stripped (pending ruling, [capabilities](../../rust/capabilities.md) section 2);
`pool.max` and `pool.min-idle` set the sqlx pool's maximum and minimum connections, and
`pool.connect-timeout` its acquire timeout (at least 250 ms).

## Using it

```rust
let database = app.module(DatabaseModule);
app.module(Things);                                  // contributes a migration set
let built = app.configure().await?.build(async |boot: &mut Boot| {
    let dbs = Databases::connect(boot, &database).await?;  // pings each, health `database:<name>`, closes at shutdown
    dbs.migrate(boot).await?;                              // every module's set, on the default instance
    let main = dbs.require_default("test")?;
    assert!(main.table_exists("things").await.unwrap());
    Ok(())
}).await?;
```

`Db` (cloneable) gives the pool for reads outside use cases, `ping`, `table_exists` and
`require_table` (for a module that needs a table on a non-default instance, where migrations do not
run). `Tx` is the use-case runner's transaction: only the runner begins one.

## Migrations

A module contributes a `MigrationSet` while declaring:

```rust
d.migrations(MigrationSet::new(
    ModuleId::of("things"),
    [MigrationFile::embedded("V1__things.sql", b"CREATE TABLE things (id int);")],
));
```

Crates embed their files with `include_bytes!`, so a binary carries its migrations. The runner is
the framework's own, **not** `sqlx::migrate!`: the history table `fw_schema_history` (module,
version, description, checksum = SHA-256 of the file bytes, applied_at), the advisory lock, the
per-module sets and every refusal (a changed or missing file, an unapplied version below the highest
applied one, two files with one version) are the Java runner's, so a database migrated by one
implementation is accepted by the other. The vectors in `conformance/migrations/` are the history
rows the Java runner writes for the framework's own sets; the Rust tests check that the Rust runner
writes the same rows and accepts a database that holds them.

Each file runs in its own transaction with its history row; a failing file is rolled back and
fails start-up naming the module, the file and the database error.

## Redis

`fw-redis` is the counterpart of `framework-redis`: one standalone Redis or Valkey server per named
instance, configured as in Java (`url`, `password` as a secret that wins over the URL's, `timeout`
default 2 s, applied to connecting and to each response). `RedisConnections::connect(boot, &declared)`
pings each instance (start-up fails naming it when it does not answer) and adds the health check
`redis:<name>`. Each `RedisConnection` wraps the `redis` crate's `ConnectionManager`
(multiplexed, reconnecting); `manager()` hands out a clone for commands and scripts.

```rust
let conns = RedisConnections::connect(boot, &redis).await?;
conns.default_connection().unwrap().ping().await.unwrap();
```
