<!-- types: CheckedSql, QueryParser, JavaGenerator, TypeMapper, GenerateMojo, CheckMojo, AddSourceMojo, Migration, MigrationSet, Migrations, Layout -->
# Checked SQL

You write queries as SQL in `.sql` files. A generator checks each one **against the real, migrated
schema** and writes typed Java: a method per query, a record per result row. The generated code is
committed, so ordinary builds need no database; a `check` goal fails the build when the committed
code is stale. It is the approach of sqlc and of sqlx's offline mode.

You get: a misspelled column or a wrong type is a build error, parameters and results are typed,
and the SQL you read is the SQL that runs.

## The workflow

1. Put queries in `src/main/sql/<package path>/<ClassName>.sql`. The path below `src/main/sql` is
   the Java package, the file name the generated class.
2. Run the generator (Docker running, or an empty database given by `FW_SQL_DATABASE_URL`).
3. Commit `src/main/java-generated/`.
4. `./mvnw verify` runs `check` and fails, naming the files, if the committed code is stale.

## The plugin

Add it to your `pom.xml` (this is from `examples/orders-demo/pom.xml`; the version is your
`framework.version`):

```xml
<plugin>
  <groupId>io.flowcatalyst.framework</groupId>
  <artifactId>framework-sql-maven-plugin</artifactId>
  <version>${framework.version}</version>
  <configuration>
    <migrations>
      <migration>
        <moduleId>outbox</moduleId>
        <location>db/migration/outbox</location>
      </migration>
      <migration>
        <moduleId>orders</moduleId>
        <location>db/migration/orders</location>
      </migration>
    </migrations>
  </configuration>
  <executions>
    <execution>
      <id>add-generated-source</id>
      <goals>
        <goal>add-source</goal>
      </goals>
    </execution>
    <execution>
      <id>check-sql</id>
      <goals>
        <goal>check</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

`<migrations>` lists the migration sets to apply to the scratch database, in order, as the
application applies them: each is a module id plus a classpath location. The framework modules'
sets are on your dependency classpath; your own `src/main/resources` is searched too.

| Goal | Phase | What it does |
|---|---|---|
| `generate` | `generate-sources` | starts a throwaway PostgreSQL (Testcontainers `postgres:18-alpine`), or uses the empty database at `FW_SQL_DATABASE_URL`; applies the migration sets with the framework's own runner; describes every query without executing it; writes the sources; removes committed generated files (recognised by their header) whose query file is gone |
| `check` | `verify` | does the same in memory, writes nothing, and fails if any generated file differs from the committed one, is missing, or has no source. Lists the files |
| `add-source` | `generate-sources` | adds `src/main/java-generated` to the compile source roots. A build that only compiles the committed code needs this |

**`check` needs Docker (or a database) too.** As configured above it is bound to `verify`, so a
plain `./mvnw verify` starts a container. `-Dfw.sql.skip=true` skips a goal, but it skips
*all three*, including `add-source`: the committed generated sources are then no longer on the
compile path and the build will not compile. To make `check` optional, bind it in a profile
instead of in the main `<executions>` and leave `add-source` bound always.

Parameters (all optional): `fw.sql.sqlDirectory`, `fw.sql.outputDirectory`, `fw.sql.databaseUrl`,
`fw.sql.databaseUser`, `fw.sql.databasePassword`, `fw.sql.image` (default `postgres:18-alpine`),
`fw.sql.skip`. The environment variables `FW_SQL_DATABASE_URL`, `FW_SQL_DATABASE_USER` (default
`postgres`) and `FW_SQL_DATABASE_PASSWORD` are used when the properties are not set. The database
must be **empty**.

### Regenerating

```
./mvnw io.flowcatalyst.framework:framework-sql-maven-plugin:generate
```

or bind `generate` in a profile, as the example does:

```xml
<profile>
  <id>generate-sql</id>
  <build>
    <plugins>
      <plugin>
        <groupId>io.flowcatalyst.framework</groupId>
        <artifactId>framework-sql-maven-plugin</artifactId>
        <executions>
          <execution>
            <id>generate-sql</id>
            <goals>
              <goal>generate</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</profile>
```

```
./mvnw -Pgenerate-sql -DskipTests package -pl examples/orders-demo -am
```

(For an application of your own the `-pl ... -am` part is not needed.) Commit the result.

## A query file

From `examples/orders-demo/src/main/sql/.../OrderSql.sql`:

```sql
-- name: findHeader :one
-- The order's root row, for its client only.
-- param: id io.flowcatalyst.framework.examples.ordersdemo.OrderId
-- param: client_id io.flowcatalyst.framework.context.ClientId
-- column: status enum io.flowcatalyst.framework.examples.ordersdemo.OrderStatus
SELECT version, customer, status
  FROM orders
 WHERE id = :id AND client_id = :client_id;

-- name: updateOrder :exec
-- The optimistic-locking write: the row count says whether the version still matched.
-- param: id io.flowcatalyst.framework.examples.ordersdemo.OrderId
-- param: client_id io.flowcatalyst.framework.context.ClientId
-- param: status enum io.flowcatalyst.framework.examples.ordersdemo.OrderStatus
UPDATE orders
   SET version = :new_version, customer = :customer, status = :status
 WHERE id = :id AND client_id = :client_id AND version = :version;
```

- A query starts at a `-- name: <javaName> <kind>` line and runs to the next. `javaName` is a
  lower-camel identifier, unique in the file.
- **Kinds:** `:one` returns `Optional<Row>` (more than one row at run time throws
  `IllegalStateException`), `:many` returns `List<Row>`, `:exec` returns the affected row count as
  `int`.
- **Parameters** are `:snake_name`; the Java parameter is its lower-camel form (`:new_version`
  becomes `newVersion`), in order of first appearance. A name used twice is one parameter. `::`
  (a PostgreSQL cast), and `:name` inside a string literal, quoted identifier, dollar-quoted string
  or comment, are not parameters. A literal `?` is an error (write the jsonb `?` operator as
  `jsonb_exists()`), as is a second statement; one trailing `;` is dropped.
- Comment lines between the `-- name:` line and the SQL are the generated method's Javadoc, except
  `-- param:` and `-- column:` lines, which are declarations.

## Types

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

Any other type fails generation, naming the query and column.

### Nullability

- A result column read directly from a table column declared `NOT NULL` is non-null. Every other
  column (expressions, aggregates) is nullable: its type is boxed and `@Nullable`.
- The database cannot say that an outer-joined table's column may be null, so in a query whose text
  contains `LEFT`, `RIGHT` or `FULL JOIN`, **every column is nullable** unless overridden.
- Override with `AS "total!"` (non-null; only when the query guarantees it: `COALESCE`, `count(*)`,
  `RETURNING` a `NOT NULL` column) or `AS "note?"` (nullable). A `!` is checked at run time: a null
  there throws `IllegalStateException` naming the query and column.
- Parameters are non-null by default; `-- param: <name> ?` declares one nullable.

### Typed values and enums

Map a column or parameter to your own type:

- `-- param: id com.acme.OrderId` or `AS "id: com.acme.OrderId"`. `X` must be a record with exactly
  one component named `value`, of the Java type the column maps to (`record OrderId(String value)`).
  The generated code calls `new X(...)` and `x.value()`.
- `enum com.acme.OrderStatus` stores the enum as its `name()` in a text column; the generated code
  calls `valueOf` and `name()`.
- Overrides combine: `AS "col?: com.acme.X"`. Typed values do not apply to arrays.

PostgreSQL cuts an identifier at 63 bytes and a fully qualified type name often exceeds that. So
every alias override can instead be a `-- column:` line: `-- column: status ! enum com.acme.OrderStatus`
(`-- column: <name> [!|?] [enum] [<type>]`). Mix the two across columns, but override a column in
one place only. The generator refuses an alias of 63 bytes or more that contains a `:` and names
the `-- column:` line as the fix.

The generator never loads your classes; `javac` checks the generated calls when the project
compiles.

## The generated code

For `OrderSql.sql`, one `public final class OrderSql` in the matching package, with:

- a `record` per `:one` and `:many` query for its row (`FindHeaderRow`), components in select order;
- one static method per query taking a `java.sql.Connection` first (a use case's `Tx` and a
  `Database` both give one), then the parameters;
- the SQL text as it is executed, `public static final String FIND_HEADER_SQL`, with a comment
  giving the file and line;
- the query's comment lines as Javadoc.

```java
try (Connection c = db.dataSource().getConnection()) {
  Optional<FindHeaderRow> header = OrderSql.findHeader(c, id, client);
  ...
}
```

Output is deterministic: same input, same bytes. Do not edit it; it carries a
`// Generated from <file>. Do not edit.` header.

## Out of scope

Dynamic queries (optional filters, variable `IN` lists) stay hand-written with the
[`Jdbc` helper](write-path.md#7-reading). Multi-statement queries, `COPY` and MySQL are out of scope.

Errors name the file and the line of the query's `-- name:` line: SQL syntax or an unknown column
(the database's message), an unsupported type, a parameter used with two types, or a wrong
override. All queries are described first and then every failure is reported, one line each.

Specification: [contracts/checked-sql.md](../contracts/checked-sql.md).
