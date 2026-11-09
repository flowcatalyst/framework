package sqlfw_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/sqlfw"
)

const fixture = "testdata/app"

func fixtureSets(dir string) []fw.MigrationSet {
	return []fw.MigrationSet{{Module: "fixture", Files: os.DirFS(filepath.Join(dir, "migrations"))}}
}

func TestMain(m *testing.M) {
	fwtest.Main(m, fwtest.Template(func(ctx context.Context, url string) error {
		db, err := database.Open(ctx, "template", url)
		if err != nil {
			return err
		}
		defer db.Close()
		return database.ApplyMigrations(ctx, db, fixtureSets(fixture), slog.New(slog.NewTextHandler(io.Discard, nil)))
	}))
}

type result struct {
	code           int
	stdout, stderr string
}

// emptyDatabase creates an empty database on the shared test server (the checks that are not about
// the embedded server use it instead of paying for a server each) and returns its URL.
func emptyDatabase(t *testing.T) string {
	t.Helper()
	ctx := t.Context()
	base := fwtest.NewDatabase(t)
	conn, err := pgx.Connect(ctx, base.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(ctx)
	name := base.Name + "_empty"
	if _, err := conn.Exec(ctx, "CREATE DATABASE "+name); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		c, err := pgx.Connect(context.WithoutCancel(ctx), base.URL)
		if err == nil {
			_, _ = c.Exec(context.WithoutCancel(ctx), "DROP DATABASE "+name+" WITH (FORCE)")
			_ = c.Close(context.WithoutCancel(ctx))
		}
	})
	return strings.Replace(base.URL, "/"+base.Name, "/"+name, 1)
}

func run(t *testing.T, dir string, sets []fw.MigrationSet, args ...string) result {
	t.Helper()
	return runOn(t, dir, sets, emptyDatabase(t), args...)
}

// runEmbedded runs on the embedded PostgreSQL the command starts itself.
func runEmbedded(t *testing.T, dir string, sets []fw.MigrationSet, args ...string) result {
	t.Helper()
	return runOn(t, dir, sets, "", args...)
}

func runOn(t *testing.T, dir string, sets []fw.MigrationSet, dbURL string, args ...string) result {
	t.Helper()
	var out, errb bytes.Buffer
	code := sqlfw.Main(t.Context(), args, sqlfw.Config{
		Sets:        sets,
		DatabaseURL: dbURL,
		Dir:         dir,
		Jet:         &sqlfw.JetConfig{Dir: "jet"},
		Stdout:      &out,
		Stderr:      &errb,
	})
	return result{code, out.String(), errb.String()}
}

// copyFixture copies the committed fixture application to a temporary directory.
func copyFixture(t *testing.T) string {
	t.Helper()
	dst := t.TempDir()
	copyTree(t, fixture, dst)
	return dst
}

func copyTree(t *testing.T, src, dst string) {
	t.Helper()
	err := filepath.WalkDir(src, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(src, p)
		target := filepath.Join(dst, rel)
		if d.IsDir() {
			return os.MkdirAll(target, 0o755)
		}
		b, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		return os.WriteFile(target, b, 0o644)
	})
	if err != nil {
		t.Fatal(err)
	}
}

// tree reads every file under dir into a map keyed by slash path.
func tree(t *testing.T, dir string) map[string]string {
	t.Helper()
	out := map[string]string{}
	err := filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		b, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(dir, p)
		out[filepath.ToSlash(rel)] = string(b)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func mustContain(t *testing.T, what, s string, wants ...string) {
	t.Helper()
	for _, w := range wants {
		if !strings.Contains(s, w) {
			t.Errorf("%s does not mention %q:\n%s", what, w, s)
		}
	}
}

func edit(t *testing.T, path, old, replacement string) {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(b), old) {
		t.Fatalf("%s: %q not found", path, old)
	}
	if err := os.WriteFile(path, []byte(strings.Replace(string(b), old, replacement, 1)), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestCheckPassesOnTheCommittedFixture(t *testing.T) {
	t.Parallel()
	r := runEmbedded(t, fixture, fixtureSets(fixture), "check")
	if r.code != 0 || r.stderr != "" {
		t.Fatalf("check on the committed fixture: exit %d\nstderr:\n%s\nstdout:\n%s", r.code, r.stderr, r.stdout)
	}
}

func TestCheckFailsNamingTheFileWhenGeneratedSqlcCodeIsStale(t *testing.T) {
	t.Parallel()
	dir := copyFixture(t)
	edit(t, filepath.Join(dir, "queries/things.sql"), "SELECT id, name, note FROM things ORDER BY id;", "SELECT id, name, note, created_at FROM things ORDER BY id;")
	r := run(t, dir, fixtureSets(dir), "check")
	if r.code != 1 {
		t.Fatalf("exit %d, want 1\n%s", r.code, r.stderr)
	}
	mustContain(t, "stderr", r.stderr, "db/things.sql.go")
}

func TestCheckFailsWhenAQueryNamesAnUnknownColumn(t *testing.T) {
	t.Parallel()
	dir := copyFixture(t)
	edit(t, filepath.Join(dir, "queries/things.sql"), "SELECT id, name, note FROM things ORDER BY id;", "SELECT id, nme, note FROM things ORDER BY id;")
	r := run(t, dir, fixtureSets(dir), "check")
	if r.code != 1 {
		t.Fatalf("exit %d, want 1\n%s", r.code, r.stderr)
	}
	mustContain(t, "stderr", r.stderr, "nme", "queries/things.sql")
	t.Logf("stderr:\n%s", r.stderr)
}

func TestCheckFailsWhenOnlyTheRealDatabaseRejectsAQuery(t *testing.T) {
	t.Parallel()
	dir := copyFixture(t)
	// sqlc's static analysis accepts lower(bigint); PostgreSQL's prepare does not.
	f := filepath.Join(dir, "queries/things.sql")
	if err := os.WriteFile(f, append(mustRead(t, f), []byte("\n-- name: Bad :many\nSELECT lower(id) AS l FROM things;\n")...), 0o644); err != nil {
		t.Fatal(err)
	}
	r := run(t, dir, fixtureSets(dir), "check")
	if r.code != 1 {
		t.Fatalf("exit %d, want 1\n%s", r.code, r.stderr)
	}
	mustContain(t, "stderr", r.stderr, "sqlc/db-prepare", "function lower(bigint) does not exist")
}

func TestCheckFailsWhenTheSchemaDirIsStale(t *testing.T) {
	t.Parallel()
	dir := copyFixture(t)
	// A migration the schema dir does not have, one whose bytes changed, one that is no longer a migration.
	if err := os.WriteFile(filepath.Join(dir, "migrations/V3__more.sql"), []byte("ALTER TABLE things ADD COLUMN extra text;\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	edit(t, filepath.Join(dir, ".fw/schema/00_fixture__V000001__create_things.sql"), "bigint", "int8")
	if err := os.WriteFile(filepath.Join(dir, ".fw/schema/00_fixture__V000009__gone.sql"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	r := run(t, dir, fixtureSets(dir), "check")
	if r.code != 1 {
		t.Fatalf("exit %d, want 1\n%s", r.code, r.stderr)
	}
	mustContain(t, "stderr", r.stderr,
		".fw/schema/00_fixture__V000003__more.sql",
		".fw/schema/00_fixture__V000001__create_things.sql",
		".fw/schema/00_fixture__V000009__gone.sql")
}

func TestCheckFailsWhenTheJetCodeIsStale(t *testing.T) {
	t.Parallel()
	dir := copyFixture(t)
	table := filepath.Join(dir, "jet/public/table/things.go")
	if err := os.WriteFile(table, append([]byte("// edited\n"), mustRead(t, table)...), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(dir, "jet/public/table/thing_tags.go")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "jet/public/table/extra.go"), []byte("package table\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	r := run(t, dir, fixtureSets(dir), "check")
	if r.code != 1 {
		t.Fatalf("exit %d, want 1\n%s", r.code, r.stderr)
	}
	mustContain(t, "stderr", r.stderr, "jet/public/table/things.go", "jet/public/table/thing_tags.go", "jet/public/table/extra.go")
}

func mustRead(t *testing.T, p string) []byte {
	t.Helper()
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestGenerateReproducesTheCommittedFixture(t *testing.T) {
	t.Parallel()
	dir := copyFixture(t)
	for _, d := range []string{".fw", "db", "jet"} {
		if err := os.RemoveAll(filepath.Join(dir, d)); err != nil {
			t.Fatal(err)
		}
	}
	r := runEmbedded(t, dir, fixtureSets(dir), "generate")
	if r.code != 0 {
		t.Fatalf("generate: exit %d\n%s", r.code, r.stderr)
	}
	for _, d := range []string{".fw", "db", "jet"} {
		got, want := tree(t, filepath.Join(dir, d)), tree(t, filepath.Join(fixture, d))
		if len(want) == 0 {
			t.Fatalf("committed %s is empty", d)
		}
		for name, w := range want {
			if g, ok := got[name]; !ok || g != w {
				t.Errorf("%s/%s: generate did not reproduce the committed file (present: %v)", d, name, ok)
			}
		}
		for name := range got {
			if _, ok := want[name]; !ok {
				t.Errorf("%s/%s: generated but not committed", d, name)
			}
		}
	}
}

func TestSchemaWritesEverySetInApplyOrderAndRemovesStaleFiles(t *testing.T) {
	t.Parallel()
	mk := func(files map[string]string) fs.FS {
		dir := t.TempDir()
		for n, c := range files {
			if err := os.WriteFile(filepath.Join(dir, n), []byte(c), 0o644); err != nil {
				t.Fatal(err)
			}
		}
		return os.DirFS(dir)
	}
	sets := []fw.MigrationSet{
		{Module: "outbox", Files: mk(map[string]string{"V1__a.sql": "CREATE TABLE a (id int);\n", "V10__b.sql": "ALTER TABLE a ADD y int;\n", "V2__c.sql": "ALTER TABLE a ADD x int;\n"})},
		{Module: "app", Files: mk(map[string]string{"V1__d.sql": "ALTER TABLE a ADD z int;\n"})},
	}
	dir := t.TempDir()
	stale := filepath.Join(dir, ".fw/schema/00_old/V1__gone.sql")
	if err := os.MkdirAll(filepath.Dir(stale), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(stale, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	for range 2 { // idempotent
		if r := runOn(t, dir, sets, "", "schema"); r.code != 0 {
			t.Fatalf("schema: exit %d\n%s", r.code, r.stderr)
		}
	}
	got := tree(t, filepath.Join(dir, ".fw/schema"))
	want := map[string]string{
		"00_outbox__V000001__a.sql": "CREATE TABLE a (id int);\n",
		"00_outbox__V000002__c.sql": "ALTER TABLE a ADD x int;\n",
		"00_outbox__V000010__b.sql": "ALTER TABLE a ADD y int;\n",
		"01_app__V000001__d.sql":    "ALTER TABLE a ADD z int;\n",
	}
	if len(got) != len(want) {
		t.Errorf("schema dir: got %v, want %v", got, want)
	}
	for n, w := range want {
		if got[n] != w {
			t.Errorf("%s: got %q, want %q", n, got[n], w)
		}
	}
	if _, err := os.Stat(filepath.Dir(stale)); err == nil {
		t.Errorf("the stale directory %s was not removed", filepath.Dir(stale))
	}
}

func TestMissingSqlcFailsWithTheInstallInstruction(t *testing.T) {
	dir := copyFixture(t)
	t.Setenv("PATH", t.TempDir())
	r := runOn(t, dir, fixtureSets(dir), "", "check")
	if r.code != 1 {
		t.Fatalf("exit %d, want 1", r.code)
	}
	mustContain(t, "stderr", r.stderr, "go install github.com/sqlc-dev/sqlc/cmd/sqlc@v1.31.1")
}

func TestUnknownSubcommandIsAUsageError(t *testing.T) {
	t.Parallel()
	for _, args := range [][]string{{}, {"nonsense"}} {
		r := runOn(t, fixture, fixtureSets(fixture), "", args...)
		if r.code != 2 {
			t.Errorf("%v: exit %d, want 2", args, r.code)
		}
		mustContain(t, "stderr", r.stderr, "schema", "generate", "check", "jet")
	}
}

func TestDatabaseURLOnTheDevelopmentPortIsRefused(t *testing.T) {
	dir := copyFixture(t)
	t.Setenv("FW_SQL_DATABASE_URL", "postgres://postgres:postgres@127.0.0.1:15432/postgres")
	r := runOn(t, dir, fixtureSets(dir), "", "check")
	if r.code != 1 {
		t.Fatalf("exit %d, want 1", r.code)
	}
	mustContain(t, "stderr", r.stderr, "15432")
}

func TestDatabaseURLMustPointAtAnEmptyDatabase(t *testing.T) {
	dir := copyFixture(t)
	db := fwtest.NewDatabase(t) // cloned from the migrated template: not empty
	t.Setenv("FW_SQL_DATABASE_URL", db.URL)
	r := runOn(t, dir, fixtureSets(dir), "", "check")
	if r.code != 1 {
		t.Fatalf("exit %d, want 1\n%s", r.code, r.stderr)
	}
	mustContain(t, "stderr", r.stderr, "not empty")

	// An empty database on the same server works, and the check passes there.
	ctx := t.Context()
	conn, err := pgx.Connect(ctx, db.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(ctx)
	// named after the test's database, so a repeated run (-count) on the shared server never meets
	// one left behind; dropped on a connection of its own (conn is closed before cleanups run)
	name := db.Name + "_empty"
	if _, err := conn.Exec(ctx, "CREATE DATABASE "+name); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		ctx := context.WithoutCancel(ctx)
		if c, err := pgx.Connect(ctx, db.URL); err == nil {
			_, _ = c.Exec(ctx, "DROP DATABASE "+name+" WITH (FORCE)")
			_ = c.Close(ctx)
		}
	})
	empty := strings.Replace(db.URL, "/"+db.Name, "/"+name, 1)
	t.Setenv("FW_SQL_DATABASE_URL", empty)
	r = runOn(t, dir, fixtureSets(dir), "", "check")
	if r.code != 0 {
		t.Fatalf("check on an empty database: exit %d\n%s", r.code, r.stderr)
	}
}

// A package's migrations are read from their directory by path, so the fwsql command needs none of
// the application's code: the first generate of a new package needs no stub of its queries.
func TestGenerateReadsAMigrationDirectoryByPathRelativeToTheApplication(t *testing.T) {
	t.Parallel()
	dir := copyFixture(t)
	for _, d := range []string{".fw", "db", "jet"} {
		if err := os.RemoveAll(filepath.Join(dir, d)); err != nil {
			t.Fatal(err)
		}
	}
	r := run(t, dir, []fw.MigrationSet{sqlfw.MigrationDir("fixture", "migrations")}, "generate")
	if r.code != 0 {
		t.Fatalf("generate: exit %d\n%s", r.code, r.stderr)
	}
	for _, f := range []string{"db/things.sql.go", ".fw/schema/00_fixture__V000002__create_tags.sql"} {
		if got, want := mustRead(t, filepath.Join(dir, f)), mustRead(t, filepath.Join(fixture, f)); !bytes.Equal(got, want) {
			t.Errorf("%s was not generated as committed", f)
		}
	}
	r = run(t, dir, []fw.MigrationSet{sqlfw.MigrationDir("fixture", "no-such-dir")}, "schema")
	if r.code != 1 {
		t.Fatalf("a missing directory: exit %d", r.code)
	}
	mustContain(t, "stderr", r.stderr, `migration set "fixture"`, "no-such-dir")
}

// The fixture's fwsql command builds while the fixture application cannot (it uses its generated
// queries, which are not there yet): the command depends on no application code.
func TestTheFwsqlCommandBuildsWithoutTheGeneratedCode(t *testing.T) {
	t.Parallel()
	abs, err := filepath.Abs(fixture)
	if err != nil {
		t.Fatal(err)
	}
	tmp := t.TempDir()
	usesDB := filepath.Join(tmp, "uses_db.go")
	if err := os.WriteFile(usesDB, []byte("package app\n\nimport _ \"github.com/flowcatalyst/framework/go/sqlfw/testdata/app/db\"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	replace := map[string]string{filepath.Join(abs, "uses_db.go"): usesDB}
	generated, _ := filepath.Glob(filepath.Join(abs, "db", "*.go"))
	for _, f := range generated {
		replace[f] = "" // not generated yet
	}
	overlay := filepath.Join(tmp, "overlay.json")
	b, err := json.Marshal(map[string]any{"Replace": replace})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(overlay, b, 0o644); err != nil {
		t.Fatal(err)
	}
	build := func(pkg string) error {
		out, err := exec.CommandContext(t.Context(), "go", "build", "-overlay", overlay, "-o", os.DevNull, pkg).CombinedOutput()
		if err != nil {
			return fmt.Errorf("%w\n%s", err, out)
		}
		return nil
	}
	if err := build("./testdata/app"); err == nil {
		t.Fatal("the overlay did not take the generated code away (the application still builds)")
	}
	if err := build("./testdata/app/cmd/fwsql"); err != nil {
		t.Fatalf("the fwsql command needs the application's code: %v", err)
	}
}
