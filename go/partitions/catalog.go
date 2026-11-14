package partitions

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
)

// querier is what a pool and a transaction share.
type querier interface {
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

func quoteIdent(parts ...string) string { return pgx.Identifier(parts).Sanitize() }

type child struct{ schema, name string }

// children lists the partitions attached to the parent, read from the catalog.
func (tg *target) children(ctx context.Context, q querier) ([]child, error) {
	rows, err := q.Query(ctx, `SELECT n.nspname, c.relname
FROM pg_inherits i
JOIN pg_class c ON c.oid = i.inhrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE i.inhparent = $1::regclass`, tg.ident())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []child
	for rows.Next() {
		var c child
		if err := rows.Scan(&c.schema, &c.name); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// ours returns the attached partitions that carry this framework's names for the table, with the
// interval start each name gives; partitions of another shape or schema are not ours.
func (tg *target) ours(kids []child) (map[string]time.Time, []child) {
	starts := map[string]time.Time{}
	var strays []child
	for _, c := range kids {
		if c.schema != tg.schema {
			strays = append(strays, c)
			continue
		}
		start, ok := parsePartitionName(tg.rel, tg.Interval, c.name)
		if !ok {
			strays = append(strays, c)
			continue
		}
		starts[c.name] = start
	}
	return starts, strays
}

// validate resolves the table's schema and name and checks its shape against the declaration. The
// messages say what DDL is expected.
func (tg *target) validate(ctx context.Context) []string {
	pool := tg.db.Pool()
	schemaIn, rel, _ := splitName(tg.Name)
	var schema, relname, relkind string
	err := pool.QueryRow(ctx, `SELECT n.nspname, c.relname, c.relkind::text
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.oid = to_regclass($1)`, quoteIdent(append(optional(schemaIn), rel)...)).Scan(&schema, &relname, &relkind)
	if errors.Is(err, pgx.ErrNoRows) {
		return []string{fmt.Sprintf("does not exist in database instance '%s'; create it in a migration, expected: %s", tg.db.Name(), tg.expectedDDL())}
	}
	if err != nil {
		return []string{"cannot read the catalog: " + err.Error()}
	}
	tg.schema, tg.rel, tg.qualified = schema, relname, schema+"."+relname
	if relkind != "p" {
		return []string{fmt.Sprintf("is not a partitioned table (relkind %q); partition it from the start, expected: %s. "+
			"Converting an existing table is not supported yet", relkind, tg.expectedDDL())}
	}

	var (
		kind, attname, collation, colCollation, keydef string
		natts, attnum                                  int
		isTimestamptz, isText, isDate                  bool
	)
	err = pool.QueryRow(ctx, `SELECT pt.partstrat::text, pt.partnatts::int, pt.partattrs[0]::int,
       coalesce(a.attname::text, ''),
       coalesce(a.atttypid = 'timestamptz'::regtype, false),
       coalesce(a.atttypid IN ('text'::regtype, 'varchar'::regtype), false),
       coalesce(a.atttypid = 'date'::regtype, false),
       coalesce(co.collname::text, ''),
       coalesce(cc.collname::text, ''),
       pg_get_partkeydef(pt.partrelid)
FROM pg_partitioned_table pt
LEFT JOIN pg_attribute a ON a.attrelid = pt.partrelid AND a.attnum = pt.partattrs[0]
LEFT JOIN pg_collation co ON co.oid = pt.partcollation[0]
LEFT JOIN pg_collation cc ON cc.oid = a.attcollation
WHERE pt.partrelid = $1::regclass`, tg.ident()).Scan(&kind, &natts, &attnum, &attname, &isTimestamptz, &isText, &isDate, &collation, &colCollation, &keydef)
	if err != nil {
		return []string{"cannot read its partition key: " + err.Error()}
	}
	var out []string
	if kind != "r" || natts != 1 || attnum == 0 || attname != tg.By.column {
		return []string{fmt.Sprintf("is partitioned by %s, expected PARTITION BY RANGE (%s); expected: %s", keydef, tg.By.column, tg.expectedDDL())}
	}
	switch tg.By.kind {
	case kindID:
		if !isText {
			out = append(out, fmt.Sprintf("key column %q must be text or varchar to hold ids, expected: %s", tg.By.column, tg.expectedDDL()))
		}
		if collation != "C" && collation != "POSIX" {
			out = append(out, fmt.Sprintf("key column %q has collation %q, but range bounds on ids compare in byte order: declare it COLLATE \"C\" "+
				"(the column definition `%s text COLLATE \"C\" PRIMARY KEY`); expected: %s", tg.By.column, collation, tg.By.column, tg.expectedDDL()))
		}
		if collation == "C" || collation == "POSIX" {
			if colCollation != "C" && colCollation != "POSIX" {
				out = append(out, fmt.Sprintf("the partition key is in collation \"C\" but the column %q has collation %q, and PostgreSQL does not prune "+
					"`WHERE %s = $1` unless the column itself is in the key's collation: declare the column `%s text COLLATE \"C\"`; expected: %s",
					tg.By.column, colCollation, tg.By.column, tg.By.column, tg.expectedDDL()))
			}
		}
	case kindColumn:
		switch {
		case isDate:
			out = append(out, fmt.Sprintf("key column %q is a date column: declare the table with partitions.ByDateColumn (ByColumn is for timestamptz); expected: %s",
				tg.By.column, tg.expectedDDL()))
		case !isTimestamptz:
			out = append(out, fmt.Sprintf("key column %q must be timestamptz, expected: %s", tg.By.column, tg.expectedDDL()))
		}
	case kindDate:
		switch {
		case isTimestamptz:
			out = append(out, fmt.Sprintf("key column %q is a timestamptz column: declare the table with partitions.ByColumn (ByDateColumn is for date); expected: %s",
				tg.By.column, tg.expectedDDL()))
		case !isDate:
			out = append(out, fmt.Sprintf("key column %q must be date (a timestamp without time zone is refused too), expected: %s", tg.By.column, tg.expectedDDL()))
		}
	}
	var defaults int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
WHERE i.inhparent = $1::regclass AND pg_get_expr(c.relpartbound, c.oid) = 'DEFAULT'`, tg.ident()).Scan(&defaults); err != nil {
		return append(out, "cannot read its partitions: "+err.Error())
	}
	if defaults > 0 {
		out = append(out, "has a DEFAULT partition, which blocks creating later partitions whose rows it already holds; "+
			"detach and drop it (this framework fails loudly instead of collecting stray rows)")
	}
	if tg.retentionArchives() {
		var ok bool
		if err := pool.QueryRow(ctx, "SELECT to_regclass('fw_partition_archives') IS NOT NULL").Scan(&ok); err != nil || !ok {
			out = append(out, fmt.Sprintf("archives to an object store, so table fw_partition_archives must exist in database instance '%s': "+
				"run database.Migrate before partitions.Build (migrations run on the default instance only; create the table by hand elsewhere)", tg.db.Name()))
		}
	}
	return out
}

func (tg *target) retentionArchives() bool { return tg.Retention.kind == retArchive }

func optional(s string) []string {
	if s == "" {
		return nil
	}
	return []string{s}
}

// expectedDDL is the shape of table the declaration needs, for the start-up messages.
func (tg *target) expectedDDL() string {
	switch tg.By.kind {
	case kindID:
		return fmt.Sprintf(`CREATE TABLE %s (%s text COLLATE "C" PRIMARY KEY, ...) PARTITION BY RANGE (%s)`, tg.Name, tg.By.column, tg.By.column)
	case kindColumn:
		return fmt.Sprintf(`CREATE TABLE %s (id ..., %s timestamptz NOT NULL, ..., PRIMARY KEY (id, %s)) PARTITION BY RANGE (%s)`,
			tg.Name, tg.By.column, tg.By.column, tg.By.column)
	case kindDate:
		return fmt.Sprintf(`CREATE TABLE %s (id ..., %s date NOT NULL, ..., PRIMARY KEY (id, %s)) PARTITION BY RANGE (%s)`,
			tg.Name, tg.By.column, tg.By.column, tg.By.column)
	}
	return ""
}
