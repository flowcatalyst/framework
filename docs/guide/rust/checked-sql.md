<!-- types: OrderRepository, OrderSummary, OrderStatus, CustomerName -->
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
