<!-- types: OrderRepository, OrderSummary, OrderStatus, CustomerName, PgTimestamptz, PgTimestamp, PgDate -->
# Rust: checked SQL

The Rust counterpart of the checked-SQL contract ([contract](../../contracts/checked-sql.md), Java
guide [Checked SQL](../checked-sql.md)) is **sqlx's compile-time macros** (`sqlx::query!`,
`query_as!`, `query_scalar!`) with offline data, not a port of the Java generator (design decision
9). The query descriptions are committed in `rust/.sqlx/`, and `rust/.cargo/config.toml` sets
`SQLX_OFFLINE=true`, so **an ordinary build needs no database**, and a renamed column, a wrong type or
a nullable column read into a non-`Option` field fails the build.

## Writing queries

Queries live next to their mapping, in Rust source (from `examples/orders-demo`):

```rust
let Some(row) = sqlx::query!(
    r#"SELECT client_id AS "client_id: ClientId", version, customer AS "customer: CustomerName",
              status AS "status: OrderStatus"
         FROM orders
        WHERE id = $1 AND client_id = $2"#,
    id as &OrderId,
    client as &ClientId,
)
.fetch_optional(db.pool())
.await?
```

- Typed values bind as `id as &OrderId` and read as `AS "col: Type"`; `Id<K>`, `ClientId` and the
  other id types implement sqlx's `Type`, `Encode` and `Decode` (the `sqlx` feature of `fw-core`,
  which `fw-db` enables), validating on decode.
- A text column holding an enum (`OrderStatus`) gets hand-written `Type`/`Encode`/`Decode` over
  `&str`; a newtype over one column (`CustomerName`) can derive `sqlx::Type` with
  `#[sqlx(transparent)]`.
- Nullability is read from the schema. **Every column of an outer join is nullable to sqlx**; say
  `!` where the query guarantees a value (the same rule as the Java generator):

```rust
r#"SELECT o.id AS "id!: OrderId", o.customer AS "customer!: CustomerName", ...
          count(l.position) AS "line_count!",
          coalesce(sum(l.quantity * l.unit_price_cents), 0)::bigint AS "total_cents!",
          min(l.sku) AS first_sku
     FROM orders o LEFT JOIN order_lines l ON l.order_id = o.id ..."#
```

- Dynamic queries stay hand-written with `sqlx::QueryBuilder` and `push_bind`, never `format!`
  with values.

## Time columns

The framework's time types are jiff's, and sqlx 0.8 maps PostgreSQL's time types only to `chrono`
or `time` (neither is a dependency). Rust's orphan rule keeps anyone but sqlx and jiff from
teaching sqlx about jiff, so `fw-db` has one small type per column type that carries the jiff value
across the database boundary, and an application's SQL names it where it binds or reads such a
column. Domain types, events and use cases hold the jiff types.

| PostgreSQL | jiff (domain, events) | In SQL (`fw_db`) | Java |
|---|---|---|---|
| `timestamptz` | `Timestamp` | `PgTimestamptz` | `Instant` |
| `timestamp` | `civil::DateTime` | `PgTimestamp` | `LocalDateTime` |
| `date` | `civil::Date` | `PgDate` | `LocalDate` |

Each is a tuple struct over its jiff value (`.0`, and `From` both ways), with sqlx's `Type`,
`Encode`, `Decode` and array support. A column read into a struct names the type in its alias, and
`query_as!` converts it into the field's jiff type with `Into`; a bound value is wrapped and cast
(from `fw-db`'s tests):

```rust
let applied = sqlx::query_as!(
    Applied,                                     // applied_at: Timestamp
    r#"SELECT module, version, applied_at AS "applied_at: PgTimestamptz"
         FROM fw_schema_history
        WHERE applied_at <= $1
        ORDER BY module, version"#,
    PgTimestamptz(cutoff) as PgTimestamptz,
)
.fetch_all(&pool)
.await
.unwrap();
```

`query!`'s anonymous record keeps the column type, and a nullable column is an `Option` of it:

```rust
let last =
    sqlx::query!(r#"SELECT max(applied_at) AS "last: PgTimestamptz" FROM fw_schema_history"#)
        .fetch_one(&pool)
        .await
        .unwrap()
        .last
        .map(Timestamp::from);
```

A time column without the override fails the build with sqlx's own message, "SQLx feature `time`
required for type TIMESTAMPTZ of column #1", and a bound time parameter without the cast the same
for its parameter; the fix is the override or the cast, not the feature. Outside the macros:
`.bind(PgTimestamptz(t))`, `row.try_get::<PgTimestamptz, _>(..)?.0`, and
`#[sqlx(try_from = "PgTimestamptz")]` on a `FromRow` field. The binary encoding is the one sqlx used
for `chrono`, so stored values do not change (a fraction finer than a microsecond is cut towards
2000-01-01); `infinity`, or a year after 9999, read back is an error naming the value. `time`,
`timetz` and `interval` are not mapped, as in the contract. The move from `chrono`:
[from chrono to jiff](from-chrono-to-jiff.md).

## Regenerating and checking

`fw-sql.toml` at the workspace root lists the migration sets to apply, in order. Then:

```
cargo run -p cargo-fw -- sql prepare     # rewrite .sqlx/ ; commit it
cargo run -p cargo-fw -- sql check       # fail, naming the problem, when .sqlx/ is stale
```

Both apply the sets with the framework's own migration runner to an empty PostgreSQL 18 (the
database at `FW_SQL_DATABASE_URL`, else a fresh database on the test server: `FW_TEST_DATABASE_URL`,
else Testcontainers), then run `cargo sqlx prepare --workspace` (with `--check`) against it. They need
`sqlx-cli` at the library's version: `cargo install sqlx-cli --version 0.8.6 --no-default-features
--features postgres,rustls`; a different `sqlx` version can change the `.sqlx/` hashes. CI runs the
check.
