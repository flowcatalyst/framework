# Contract: checked SQL (generated typed queries)

Status: M2b. "SQL you can read, checked at build time" (framework-plumbing.md section 7). The
approach is that of sqlc and of sqlx's offline mode: queries are written as SQL in `.sql` files;
a generator checks each one against the real migrated schema and writes typed Java code, which is
committed. Ordinary builds need no database. A check mode fails when the committed code is stale.

## 1. Query files

Files under `src/main/sql/`, one per repository or read model, e.g.
`src/main/sql/com/acme/orders/OrderQueries.sql`. The path below `src/main/sql` gives the Java
package; the file name gives the generated class (`OrderQueries`).

```sql
-- name: findOrder :one
-- param: id com.acme.orders.OrderId
SELECT id AS "id: com.acme.orders.OrderId", client_id, version, status, placed_at
  FROM orders
 WHERE id = :id AND client_id = :client_id;

-- name: linesOf :many
SELECT order_id, line_no, sku, quantity FROM order_lines
 WHERE order_id = :order_id ORDER BY line_no;

-- name: updateOrder :exec
UPDATE orders SET status = :status, version = :new_version
 WHERE id = :id AND client_id = :client_id AND version = :version;
```

- A query starts at a `-- name: <javaName> <kind>` line and runs to the next one. `javaName` is a
  lower-camel Java identifier, unique in the file.
- Kinds: `:one` (returns `Optional<Row>`; more than one row at run time throws
  `IllegalStateException`), `:many` (`List<Row>`), `:exec` (returns the affected row count as
  `int`).
- Parameters are `:snake_name`; the Java parameter is its lower-camel form. A name used twice is
  one parameter. `::` (a PostgreSQL cast) is not a parameter. Parameters become `?` in the
  executed text; the generated code holds that text as a constant.

## 2. Types

From the database's description of each statement (parameter and result types), the
PostgreSQL types map to:

| PostgreSQL | Java |
|---|---|
| `text`, `varchar`, `char` | `String` |
| `int2`, `int4` | `int` |
| `int8` | `long` |
| `bool` | `boolean` |
| `numeric` | `BigDecimal` |
| `float4`, `float8` | `float`, `double` |
| `timestamptz` | `Instant` |
| `timestamp` | `LocalDateTime` |
| `date` | `LocalDate` |
| `uuid` | `UUID` |
| `bytea` | `byte[]` |
| `json`, `jsonb` | `String` (the JSON text) |
| one-dimensional arrays of the above | `List<T>` |

Any other type fails generation, naming the query and the column.

**Nullability of results:** a column read directly from a table column declared `NOT NULL` is
non-null; every other column (expressions, aggregates) is nullable and its Java type is boxed and
`@Nullable`. The database's description cannot tell that a column of an outer-joined table may be
null, so in a query whose text contains `LEFT`, `RIGHT` or `FULL` `JOIN`, every column is
nullable unless overridden. Overrides in the column alias, as in sqlx:
`AS "total!"` declares non-null (only when the query guarantees it: `COALESCE`, `count(*)`,
`RETURNING` of a `NOT NULL` column), `AS "note?"` declares nullable. A `!` override is checked at
run time: a null read there throws `IllegalStateException` naming the query and column.

**Nullability of parameters:** non-null by default. `-- param: <name> ?` declares one nullable.

**Typed values:** `AS "col: com.acme.X"` and `-- param: <name> com.acme.X` map a column or
parameter to an application type. `X` is a record with exactly one component, named `value`, of
the Java type the column maps to (a typed id such as `record OrderId(String value)`); generated
code calls `new X(...)` and `x.value()`. An enum is written `enum com.acme.X` and stored as its
`name()` in a text column; generated code calls `X.valueOf(...)` and `x.name()`. The generator
never loads application classes: `javac` checks the generated calls when the project compiles.
Overrides combine: `AS "col?: com.acme.X"`.

## 3. Generated code

For `OrderQueries.sql`, one `final class OrderQueries` in the matching package, containing:

- a `record` per `:one` / `:many` query for its row (`FindOrderRow`), components in select order,
  named in lower camel from the column alias;
- one static method per query taking a `java.sql.Connection` first (a use case's `Tx` and a
  `Database` both give one), then its parameters in order of first appearance;
- the SQL text as it is executed, as a `static final String`, with a comment giving the file and
  line it came from.

Generated sources are written to `src/main/java-generated/` (added as a source root), carry a
`// Generated from <file>. Do not edit.` header, and are committed. Output is deterministic: same
input, same bytes.

## 4. The generator

A Maven plugin, `framework-sql-maven-plugin`, with two goals:

- `generate`: starts a throwaway PostgreSQL (Testcontainers `postgres:18-alpine`, or the empty
  database at `FW_SQL_DATABASE_URL` when set), applies the project's migration sets with the
  framework's own migration runner, describes every query (prepare without executing), and writes
  the sources.
- `check`: does the same into a temporary directory and fails if any generated file differs from
  the committed one, or a committed generated file has no source; it lists the differing files.
  Bound to `verify` only when the project enables it (CI does).

Configuration: the migration sets to apply, in order, as `moduleId` + classpath location
(framework modules' sets are on the project's dependency classpath), and the source and output
directories (defaults as above).

Generation errors name the file and line: SQL syntax or unknown column (the database's message),
or an unsupported type.

## 5. Out of scope

Dynamic queries (optional filters, variable `IN` lists) stay hand-written with the M2a JDBC helper.
Multi-statement queries, `COPY`, and MySQL are out of scope.
