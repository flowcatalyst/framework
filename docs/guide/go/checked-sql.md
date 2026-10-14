<!-- types: Config, JetConfig, Statement, Filter, ReadModel, ListQuery -->
# Go: checked SQL and dynamic queries

The [checked-SQL contract](../../contracts/checked-sql.md)'s promise (SQL you can read, checked
against the real schema, generated code committed, a check that fails when it is stale) is kept
with sqlc for static queries; dynamic list queries are built with go-jet or written with
`database.Filter` ([design](../../go/design.md) section 5).

## Static queries: sqlc, checked by the framework

Queries live in `.sql` files with sqlc's `-- name: X :one` headers and `@name` parameters
(`go/examples/orders-demo/sql/orders.sql`). `sqlc.yaml` follows the platform's style (pgx/v5,
pointers for nullable types, db tags) and names typed ids per column; a type alias of a generic
id works as the override type:

```yaml
          - column: orders.id
            go_type: { import: "github.com/flowcatalyst/framework/go/examples/orders-demo/domain", type: OrderID }
```

Only the application knows its migration sets, so it wires the framework's tool in a few lines
(`go/examples/orders-demo/tools/fwsql/main.go`):

```go
	os.Exit(sqlfw.Main(context.Background(), os.Args[1:], sqlfw.Config{
		Sets: []fw.MigrationSet{
			{Module: usecase.OutboxModuleID, Files: usecase.OutboxMigrations()},
			{Module: orders.ModuleID, Files: orders.Migrations()},
		},
		Jet: &sqlfw.JetConfig{Dir: "internal/jet"},
	}))
```

- `go run ./tools/fwsql schema` writes every set, in apply order, flat into `.fw/schema`
  (`00_outbox__V000001__create_outbox_messages.sql`; versions are zero-padded because sqlc reads
  the directory in lexical order and only one level deep), which `sqlc.yaml`'s `schema:` names;
- `generate` writes the schema, runs `sqlc generate` (static, no database) and generates the jet
  table code from a PostgreSQL 18 migrated by the framework's own runner;
- `check` (CI, `make -C go sql-check`) fails, one line per problem, when the schema directory, the
  sqlc code (`sqlc diff`) or the jet code is stale, or when a query does not prepare against the
  migrated database (`sqlc vet` with the `sqlc/db-prepare` rule; sqlc's own analysis already
  catches an unknown column, prepare catches what it misses, such as `lower(bigint)`). The database
  is an embedded PostgreSQL 18, or the empty one at `FW_SQL_DATABASE_URL`. `sqlc` v1.31.1 must be on
  `PATH`.

**sqlc's nullability, answered (design section 8).** Columns of an outer-joined table are
nullable to sqlc (pointers), even those declared `NOT NULL`. But a column with a type override
keeps its non-pointer type even when it comes from the outer-joined table (a NULL then fails to
scan), and an expression with a cast is typed non-null although it can be NULL
(`min(l.sku)::text` over an order without lines). sqlc is less strict than the Java contract here,
where every column of an outer-joined query is nullable unless overridden. Write such columns so
they cannot be NULL (`coalesce(min(l.sku), '')::text`), as the demo's `Summaries` query does, or
cast an overridden column to text and parse it in the repository.

## Dynamic queries: go-jet or Filter

The orders read model writes the same list query both ways and one test runs every filter
combination against both. go-jet builds the statement from the generated table code (a renamed
column fails to compile); pgx runs and scans it through `sqlfw.Query`, never jet's own mapper:

```go
	stmt := pg.SELECT(o.ID.AS("id"), o.Customer.AS("customer"), o.Status.AS("status")).
		FROM(o).
		WHERE(cond).
		ORDER_BY(o.ID.DESC()).
		LIMIT(int64(q.Size)).
		OFFSET(int64(q.offset()))
	return sqlfw.Query(ctx, r.DB, stmt, pgx.RowToStructByName[OrderRow])
```

`database.Filter` needs no dependency; its column names are strings, so the test is what checks
them:

```go
	var f database.Filter
	f.Eq("client_id", cx.Client().String())
	if q.Status != nil {
		f.Eq("status", *q.Status)
	}
	if q.Search != "" {
		f.Clause("customer LIKE $%d", "%"+escapeLike(q.Search)+"%")
	}
```

Values are always bound in both. go-jet's runtime package imports `lib/pq` (pure Go); no package
the framework or the demo compiles imports the MySQL driver or `go-sqlite3`, which appear only in
the module graph.
