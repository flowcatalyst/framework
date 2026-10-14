package sqlfw_test

import (
	"testing"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/sqlfw"
	appdb "github.com/flowcatalyst/framework/go/sqlfw/testdata/app/db"
	"github.com/flowcatalyst/framework/go/sqlfw/testdata/app/jet/public/table"
	"github.com/flowcatalyst/framework/go/sqlfw/testdata/app/types"

	. "github.com/go-jet/jet/v2/postgres"
)

type plain struct {
	query string
	args  []any
}

func (p plain) Sql() (string, []any) { return p.query, p.args }

type nameRow struct {
	ID   int64
	Name string
}

func openFixtureDB(t *testing.T) *database.DB {
	t.Helper()
	d := fwtest.NewDatabase(t)
	db, err := database.Open(t.Context(), "test", d.URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	// The generated sqlc code, with the generic-alias column type, writes the rows.
	q := appdb.New(db.Pool())
	for i, n := range []string{"a", "b", "c"} {
		note := "n" + n
		if err := q.InsertThing(t.Context(), appdb.InsertThingParams{ID: types.ThingID{V: int64(i + 1)}, Name: n, Note: &note}); err != nil {
			t.Fatal(err)
		}
	}
	return db
}

func TestQueryRunsAJetStatement(t *testing.T) {
	t.Parallel()
	db := openFixtureDB(t)
	stmt := SELECT(table.Things.ID, table.Things.Name).
		FROM(table.Things).
		WHERE(table.Things.ID.GT(Int(1))).
		ORDER_BY(table.Things.ID.DESC())
	got, err := sqlfw.Query(t.Context(), db, stmt, pgx.RowToStructByPos[nameRow])
	if err != nil {
		t.Fatal(err)
	}
	want := []nameRow{{3, "c"}, {2, "b"}}
	if len(got) != len(want) || got[0] != want[0] || got[1] != want[1] {
		t.Fatalf("got %v, want %v", got, want)
	}
}

func TestQueryRunsAPlainStatementAndReportsErrors(t *testing.T) {
	t.Parallel()
	db := openFixtureDB(t)
	got, err := sqlfw.Query(t.Context(), db, plain{"SELECT name FROM things WHERE id = $1", []any{int64(2)}}, pgx.RowTo[string])
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0] != "b" {
		t.Fatalf("got %v", got)
	}
	if _, err := sqlfw.Query(t.Context(), db, plain{"SELECT nope FROM things", nil}, pgx.RowTo[string]); err == nil {
		t.Fatal("a statement on an unknown column succeeded")
	}
	got, err = sqlfw.Query(t.Context(), db, plain{"SELECT name FROM things WHERE id = $1", []any{int64(99)}}, pgx.RowTo[string])
	if err != nil || len(got) != 0 {
		t.Fatalf("no rows: got %v, %v", got, err)
	}
}

func TestGeneratedSqlcCodeScansTheGenericAliasType(t *testing.T) {
	t.Parallel()
	db := openFixtureDB(t)
	row, err := appdb.New(db.Pool()).GetThing(t.Context(), types.ThingID{V: 2})
	if err != nil {
		t.Fatal(err)
	}
	if row.ID.V != 2 || row.Name != "b" || row.Note == nil || *row.Note != "nb" {
		t.Fatalf("got %+v", row)
	}
}
