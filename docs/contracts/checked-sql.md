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
  one parameter (the generated code binds it at each place). `::` (a PostgreSQL cast) is not a
  parameter, and neither is a `:name` inside a string literal, a quoted identifier, a
  dollar-quoted string or a comment. Parameters become `?` in the executed text; the generated
  code holds that text as a constant. A literal `?` is an error (the jsonb `?` operator is
  written `jsonb_exists()`), as is a second statement; one trailing `;` is dropped.
- Between the `-- name:` line and the SQL, comment lines are the method's documentation, except
  the two declaration forms: `-- param: <name> [?] [enum] [<type>]` (section 2) and
  `-- column: <name> [!|?] [enum] [<type>]` (the override of a result column, written beside the
  query instead of in the alias; see section 2 for why both exist). Comments after the SQL has
  started are SQL comments and stay in the executed text.

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

PostgreSQL cuts an identifier at 63 bytes, and a fully qualified type name in an alias often
exceeds that. So every alias override can instead be written as a `-- column:` line
(`-- column: status ! enum com.acme.OrderStatus`); the two forms may be mixed across columns but a
column is overridden in one place only. The generator refuses an alias that is 63 bytes or longer
and contains a `:`, naming the `-- column:` line as the fix. Typed values do not apply to arrays;
a null element of an array fails at run time (`IllegalStateException`).

Nullability of a result column is read from the driver's result metadata, which looks the column
up in `pg_attribute` by table and column number (`attnotnull`, and a domain's `NOT NULL`); an
expression has no table column and is nullable.

## 3. Generated code

For `OrderQueries.sql`, one `final class OrderQueries` in the matching package, containing:

- a `record` per `:one` / `:many` query for its row (`FindOrderRow`), components in select order,
  named in lower camel from the column alias;
- one static method per query taking a `java.sql.Connection` first (a use case's `Tx` and a
  `Database` both give one), then its parameters in order of first appearance;
- the SQL text as it is executed, as a `public static final String` named after the method in
  upper snake case with `_SQL` (`FIND_ORDER_SQL`), with a comment giving the file and line it
  came from. The method's Javadoc is the query's comment lines.

Generated sources are written to `src/main/java-generated/` (added as a source root), carry a
`// Generated from <file>. Do not edit.` header, and are committed. Output is deterministic: same
input, same bytes.

## 4. The generator

A Maven plugin, `framework-sql-maven-plugin`, with three goals:

- `generate`: starts a throwaway PostgreSQL (Testcontainers `postgres:18-alpine`, or the empty
  database at `FW_SQL_DATABASE_URL` when set), applies the project's migration sets with the
  framework's own migration runner, describes every query (prepare without executing), and writes
  the sources.
  It also removes committed generated files (recognised by their header) whose query file is
  gone.
- `check`: does the same in memory, writes nothing, and fails if any generated file differs from
  the committed one, is missing, or a committed generated file has no source; it lists the
  files. Bound to `verify` by the project's pom; there is no plugin switch (a project that wants it off binds it in a profile). Do not skip it with `fw.sql.skip`, which also skips `add-source`.
- `add-source`: adds the generated-source directory to the compile source roots. A build that
  only compiles the committed code (no database, no `generate`) needs this; `generate` does it
  as well.

Configuration: the migration sets to apply, in order, as `moduleId` + classpath location
(framework modules' sets are on the project's dependency classpath; the project's own
`src/main/resources` is searched too), and the source and output directories (defaults as above).
A database to use instead of a container is given by `FW_SQL_DATABASE_URL`, with
`FW_SQL_DATABASE_USER` (default `postgres`) and `FW_SQL_DATABASE_PASSWORD` (or the plugin
parameters `databaseUrl`, `databaseUser`, `databasePassword`); it must be empty. The migration
runner is the framework's own (`Migrations.apply` in `framework-database`). All queries of the
build are described, then every failure is reported, one line each.

Generation errors name the file and the line of the query's `-- name:` line: SQL syntax or
unknown column (the database's message, and the line of the SQL it points at), an unsupported
type, a parameter used with two types, or a wrong override.

## 5. Out of scope

Dynamic queries (optional filters, variable `IN` lists) stay hand-written with the M2a JDBC helper.
Multi-statement queries, `COPY`, and MySQL are out of scope.

## 6. Using it

In the project's `pom.xml`:

```xml
<plugin>
  <groupId>io.flowcatalyst.framework</groupId>
  <artifactId>framework-sql-maven-plugin</artifactId>
  <version>${framework.version}</version>
  <configuration>
    <migrations>
      <migration><moduleId>outbox</moduleId><location>db/migration/outbox</location></migration>
      <migration><moduleId>orders</moduleId><location>db/migration/orders</location></migration>
    </migrations>
  </configuration>
  <executions>
    <execution><id>add-generated-source</id><goals><goal>add-source</goal></goals></execution>
    <execution><id>check-sql</id><goals><goal>check</goal></goals></execution>
  </executions>
</plugin>
```

Put queries in `src/main/sql/<package path>/<ClassName>.sql`. Regenerate with Docker running (or
`FW_SQL_DATABASE_URL` set to an empty database):

```
./mvnw io.flowcatalyst.framework:framework-sql-maven-plugin:generate
```

and commit `src/main/java-generated/`. `./mvnw verify` then runs `check` and fails, naming the
files, when the committed code is stale. A project that wants regeneration as a profile binds
`generate` in one (see `examples/orders-demo`, whose command is
`./mvnw -Pgenerate-sql -DskipTests package -pl examples/orders-demo -am`).
