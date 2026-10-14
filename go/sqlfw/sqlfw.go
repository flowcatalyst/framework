// Package sqlfw is the framework's checked-SQL tooling: the "fw sql" command and the runner for
// jet-built statements.
//
// Static queries are sqlc's: queries in .sql files, typed generated code, committed. The
// framework adds what sqlc cannot know. An application's migration sets (the framework's own,
// outbox and locks, then its own) are written to one schema directory in apply order so sqlc sees
// every table, and a check runs the real migrations on a PostgreSQL 18 with the framework's own
// runner and then lets sqlc prepare every query against it. Dynamic list queries are built with
// go-jet; its generated table code is produced and checked the same way, and [Query] runs a built
// statement on pgx.
//
// Only the application knows its migration sets, so the application wires the command in a small
// program of its own:
//
//	func main() {
//		os.Exit(sqlfw.Main(context.Background(), os.Args[1:], sqlfw.Config{
//			Sets: append(framework sets..., app.Sets()...),
//			Jet:  &sqlfw.JetConfig{Dir: "internal/jet"},
//		}))
//	}
//
// and runs "go run ./cmd/fwsql generate" and "go run ./cmd/fwsql check". This package does not
// import go-jet; package sqlfw/jetgen does, and Main uses it.
package sqlfw

import (
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/sqlfw/jetgen"
)

// Config configures [Main].
type Config struct {
	// Sets are the migration sets in apply order: the framework's sets (outbox, locks-database)
	// first, then the application's.
	Sets []fw.MigrationSet
	// Dir is the application module's directory; sqlc runs there and the other paths are
	// relative to it. Default ".".
	Dir string
	// SchemaDir receives every set's files, for sqlc's schema setting. Default ".fw/schema".
	SchemaDir string
	// Sqlc is the sqlc configuration file. Default "sqlc.yaml".
	Sqlc string
	// SqlcDisabled skips every sqlc step (an application that only uses jet).
	SqlcDisabled bool
	// Jet configures go-jet generation; nil skips it.
	Jet *JetConfig
	// DatabaseURL, when set, is the empty database to migrate instead of $FW_SQL_DATABASE_URL (it
	// takes precedence over the variable); tests that share one server use it.
	DatabaseURL string
	// Stdout and Stderr default to the process's.
	Stdout, Stderr io.Writer
}

// JetConfig configures the generation of go-jet's table, view and enum code.
type JetConfig struct {
	// Dir is the output directory; the code lands in Dir/<Schema>/{table,view,enum}. Relative to
	// [Config.Dir].
	Dir string
	// Schema is the database schema to generate. Default "public".
	Schema string
}

// EnvDatabaseURL names the variable that points the tooling at an existing, empty PostgreSQL
// database instead of an embedded one. It is also what Main sets for sqlc, whose configuration
// reads it (database: uri: ${FW_SQL_DATABASE_URL}).
const EnvDatabaseURL = "FW_SQL_DATABASE_URL"

// SqlcVersion is the sqlc release the framework is tested with.
const SqlcVersion = "v1.31.1"

const usage = `usage: fwsql <command>

  schema    write every migration set to the schema directory (what sqlc.yaml's schema: reads)
  generate  schema, then sqlc generate, then the jet code
  check     fail when the schema directory, the sqlc code or the jet code is stale, or sqlc vet
            (db-prepare) fails against a freshly migrated PostgreSQL 18
  jet       generate the jet code from the migrated database
`

type tool struct {
	cfg        Config
	out, err   io.Writer
	dir        string
	schemaDir  string
	sqlcConfig string
	jetDir     string
	jetSchema  string
	sqlcBin    string
}

// Main runs one "fw sql" command (args[0]: schema, generate, check or jet) and returns the
// process exit code: 0 on success, 1 when a step failed or a check found problems (each listed
// one per line on Stderr), 2 on a usage error.
func Main(ctx context.Context, args []string, cfg Config) int {
	t := &tool{cfg: cfg, out: cfg.Stdout, err: cfg.Stderr}
	if t.out == nil {
		t.out = os.Stdout
	}
	if t.err == nil {
		t.err = os.Stderr
	}
	t.dir = orDefault(cfg.Dir, ".")
	t.schemaDir = orDefault(cfg.SchemaDir, ".fw/schema")
	t.sqlcConfig = orDefault(cfg.Sqlc, "sqlc.yaml")
	if cfg.Jet != nil {
		t.jetDir = cfg.Jet.Dir
		t.jetSchema = orDefault(cfg.Jet.Schema, "public")
	}
	if len(args) == 0 {
		_, _ = io.WriteString(t.err, usage)
		return 2
	}
	var problems []string
	var err error
	switch args[0] {
	case "schema":
		err = t.writeSchema()
	case "generate":
		err = t.generate(ctx)
	case "jet":
		err = t.jet(ctx)
	case "check":
		problems, err = t.check(ctx)
	default:
		fmt.Fprintf(t.err, "fwsql: unknown command %q\n%s", args[0], usage)
		return 2
	}
	if err != nil {
		fmt.Fprintf(t.err, "fwsql %s: %v\n", args[0], err)
		return 1
	}
	if len(problems) > 0 {
		for _, p := range problems {
			fmt.Fprintln(t.err, p)
		}
		fmt.Fprintf(t.err, "fwsql check: %d problem(s); run the generate command and commit the result\n", len(problems))
		return 1
	}
	return 0
}

func orDefault(s, def string) string {
	if s == "" {
		return def
	}
	return s
}

// path resolves p against the application directory.
func (t *tool) path(p string) string {
	if filepath.IsAbs(p) {
		return p
	}
	return filepath.Join(t.dir, p)
}

func (t *tool) sqlcEnabled() bool { return !t.cfg.SqlcDisabled }

func (t *tool) generate(ctx context.Context) error {
	if t.sqlcEnabled() {
		if err := t.requireSqlc(ctx); err != nil {
			return err
		}
	}
	if err := t.writeSchema(); err != nil {
		return err
	}
	if t.sqlcEnabled() {
		if out, err := t.runSqlc(ctx, nil, "generate"); err != nil {
			return fmt.Errorf("sqlc generate: %w\n%s", err, out)
		}
		fmt.Fprintln(t.out, "sqlc generate: ok")
	}
	if t.cfg.Jet != nil {
		return t.jet(ctx)
	}
	return nil
}

func (t *tool) jet(ctx context.Context) error {
	if t.cfg.Jet == nil {
		return fmt.Errorf("no Jet configuration (Config.Jet is nil)")
	}
	return t.withDatabase(ctx, func(dsn string) error {
		if err := jetgen.Generate(ctx, dsn, t.jetSchema, t.path(t.jetDir), nil); err != nil {
			return err
		}
		fmt.Fprintf(t.out, "jet: wrote %s\n", filepath.ToSlash(filepath.Join(t.jetDir, t.jetSchema)))
		return nil
	})
}

func (t *tool) check(ctx context.Context) ([]string, error) {
	if t.sqlcEnabled() {
		if err := t.requireSqlc(ctx); err != nil {
			return nil, err
		}
	}
	expected, err := t.expectedSchema()
	if err != nil {
		return nil, err
	}
	problems := compareTree(expected, t.path(t.schemaDir), t.schemaDir, "schema directory is stale")
	err = t.withDatabase(ctx, func(dsn string) error {
		if t.sqlcEnabled() {
			env := []string{EnvDatabaseURL + "=" + dsn}
			for _, sub := range []string{"vet", "diff"} {
				if out, err := t.runSqlc(ctx, env, sub); err != nil {
					problems = append(problems, sqlcProblems(sub, out, err)...)
				}
			}
		}
		if t.cfg.Jet != nil {
			ps, err := t.checkJet(ctx, dsn)
			if err != nil {
				return err
			}
			problems = append(problems, ps...)
		}
		return nil
	})
	return problems, err
}

func (t *tool) checkJet(ctx context.Context, dsn string) ([]string, error) {
	tmp, err := os.MkdirTemp("", "fwsql-jet-*")
	if err != nil {
		return nil, err
	}
	defer func() { _ = os.RemoveAll(tmp) }()
	if err := jetgen.Generate(ctx, dsn, t.jetSchema, tmp, nil); err != nil {
		return nil, err
	}
	want, err := readTree(filepath.Join(tmp, t.jetSchema))
	if err != nil {
		return nil, err
	}
	shown := filepath.Join(t.jetDir, t.jetSchema)
	return compareTree(want, t.path(shown), shown, "jet code is stale"), nil
}

// sqlcProblems turns a failed sqlc run into one problem per output line.
func sqlcProblems(sub, out string, err error) []string {
	var ps []string
	for line := range strings.SplitSeq(out, "\n") {
		if line = strings.TrimRight(line, "\r"); strings.TrimSpace(line) != "" {
			ps = append(ps, fmt.Sprintf("sqlc %s: %s", sub, line))
		}
	}
	if len(ps) == 0 {
		ps = append(ps, fmt.Sprintf("sqlc %s: %v", sub, err))
	}
	return ps
}
