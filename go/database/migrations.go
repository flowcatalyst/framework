package database

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io/fs"
	"log/slog"
	"regexp"
	"slices"
	"strconv"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/flowcatalyst/framework/go/fw"
)

// The migration runner (database contract section 2), the Java runner's rules and history rows:
// files V<version>__<description>.sql, one set per module applied in module order, the history
// table fw_schema_history (module, version, description, checksum = SHA-256 of the file bytes in
// hex, applied_at), one transaction per file with its history row, a PostgreSQL advisory lock for
// the whole run, and every inconsistency a start-up failure. A database migrated by the Java
// runner is accepted, and this runner writes the same rows (conformance/migrations/).

// AdvisoryLockKey is the advisory lock the runner holds while it runs; the Java runner uses the
// same key, so a Java and a Go instance starting together apply each file once.
const AdvisoryLockKey int64 = 0x46575F4D49475241

// CreateHistorySQL creates the history table, as the Java runner does.
const CreateHistorySQL = `CREATE TABLE IF NOT EXISTS fw_schema_history (
    module      text        NOT NULL,
    version     int         NOT NULL,
    description text        NOT NULL,
    checksum    text        NOT NULL,
    applied_at  timestamptz NOT NULL,
    PRIMARY KEY (module, version)
)`

var migrationFile = regexp.MustCompile(`^V(\d+)__(.+)\.sql$`)

type migrationFileInfo struct {
	module, description, fileName, checksum, sql string
	version                                      int
}

type appliedRow struct {
	description, checksum string
}

// Migrate applies the application's declared migration sets ([fw.Declarer.Migrations]) to the
// default instance, when there is one and its migrate setting is true. Problems are returned as
// a *fw.StartupError.
func Migrate(ctx context.Context, b *fw.Boot, dbs *Databases) error {
	sets := b.MigrationSets()
	target, ok := dbs.Default()
	if len(sets) == 0 || !ok || !target.migrate {
		return nil
	}
	return ApplyMigrations(ctx, target, sets, b.Logger(ModuleID))
}

// ApplyMigrations applies migration sets, in the order given, to one database. It is the
// runner Migrate uses; tools and tests call it directly.
func ApplyMigrations(ctx context.Context, db *DB, sets []fw.MigrationSet, log *slog.Logger) error {
	var problems []fw.Problem
	files := make(map[string][]migrationFileInfo)
	var order []string
	for _, set := range sets {
		if !slices.Contains(order, set.Module) {
			order = append(order, set.Module)
		}
		read, p := readSet(set)
		problems = append(problems, p...)
		files[set.Module] = append(files[set.Module], read...)
	}
	if len(problems) > 0 {
		return &fw.StartupError{Problems: problems}
	}

	conn, err := db.pool.Acquire(ctx)
	if err != nil {
		return runFailed(db, err)
	}
	defer conn.Release()
	if _, err := conn.Exec(ctx, "SELECT pg_advisory_lock($1)", AdvisoryLockKey); err != nil {
		return runFailed(db, err)
	}
	defer func() {
		if _, err := conn.Exec(context.WithoutCancel(ctx), "SELECT pg_advisory_unlock($1)", AdvisoryLockKey); err != nil {
			log.Warn("could not release the migration advisory lock", slog.String("error", err.Error()))
		}
	}()
	return migrate(ctx, conn, db, order, files, log)
}

func runFailed(db *DB, err error) error {
	return &fw.StartupError{Problems: []fw.Problem{{Key: "database:" + db.name, Message: "migrations failed: " + err.Error()}}}
}

func migrate(ctx context.Context, conn *pgxpool.Conn, db *DB, order []string, files map[string][]migrationFileInfo, log *slog.Logger) error {
	if _, err := conn.Exec(ctx, CreateHistorySQL); err != nil {
		return runFailed(db, err)
	}
	applied, err := readHistory(ctx, conn)
	if err != nil {
		return runFailed(db, err)
	}
	var problems []fw.Problem
	var pending []migrationFileInfo
	for _, module := range order {
		moduleFiles := files[module]
		problems = append(problems, duplicates(module, moduleFiles)...)
		done := applied[module]
		byVersion := make(map[int]migrationFileInfo)
		for _, f := range moduleFiles {
			if _, seen := byVersion[f.version]; !seen {
				byVersion[f.version] = f
			}
		}
		highest := 0
		doneVersions := make([]int, 0, len(done))
		for v := range done {
			doneVersions = append(doneVersions, v)
			highest = max(highest, v)
		}
		slices.Sort(doneVersions)
		for _, v := range doneVersions {
			row := done[v]
			f, ok := byVersion[v]
			switch {
			case !ok:
				problems = append(problems, fw.Problem{
					Module: module, Key: "V" + strconv.Itoa(v),
					Message: fmt.Sprintf("version %d (%s) was applied but its file is gone", v, row.description),
				})
			case f.checksum != row.checksum:
				problems = append(problems, fw.Problem{
					Module: module, Key: f.fileName,
					Message: fmt.Sprintf("changed after it was applied (applied checksum %s..., file checksum %s...); "+
						"applied migrations must never be edited, add a new version", prefix12(row.checksum), prefix12(f.checksum)),
				})
			}
		}
		versions := make([]int, 0, len(byVersion))
		for v := range byVersion {
			versions = append(versions, v)
		}
		slices.Sort(versions)
		for _, v := range versions {
			f := byVersion[v]
			if _, ok := done[v]; ok {
				continue
			}
			if v < highest {
				problems = append(problems, fw.Problem{
					Module: module, Key: f.fileName,
					Message: fmt.Sprintf("was never applied, but a higher version (%d) already was; versions must not be added below the applied ones", highest),
				})
				continue
			}
			pending = append(pending, f)
		}
	}
	if len(problems) > 0 {
		return &fw.StartupError{Problems: problems}
	}
	for _, f := range pending {
		if err := apply(ctx, conn, f, log); err != nil {
			return err
		}
	}
	return nil
}

func apply(ctx context.Context, conn *pgxpool.Conn, f migrationFileInfo, log *slog.Logger) error {
	log.Info("applying migration", slog.String("file", f.fileName), slog.String("migration_module", f.module))
	err := pgx.BeginFunc(ctx, conn, func(tx pgx.Tx) error {
		// The simple protocol runs a file of several statements as one request, without arguments.
		if _, err := tx.Conn().PgConn().Exec(ctx, f.sql).ReadAll(); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, "INSERT INTO fw_schema_history (module, version, description, checksum, applied_at) "+
			"VALUES ($1, $2, $3, $4, now())", f.module, f.version, f.description, f.checksum)
		return err
	})
	if err != nil {
		return &fw.StartupError{Problems: []fw.Problem{{
			Module: f.module, Key: f.fileName,
			Message: "migration failed and was rolled back: " + err.Error(),
		}}}
	}
	return nil
}

func readHistory(ctx context.Context, conn *pgxpool.Conn) (map[string]map[int]appliedRow, error) {
	rows, err := conn.Query(ctx, "SELECT module, version, description, checksum FROM fw_schema_history")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make(map[string]map[int]appliedRow)
	for rows.Next() {
		var module, description, checksum string
		var version int
		if err := rows.Scan(&module, &version, &description, &checksum); err != nil {
			return nil, err
		}
		if out[module] == nil {
			out[module] = make(map[int]appliedRow)
		}
		out[module][version] = appliedRow{description: description, checksum: checksum}
	}
	return out, rows.Err()
}

func duplicates(module string, files []migrationFileInfo) []fw.Problem {
	var out []fw.Problem
	seen := make(map[int]string)
	reported := make(map[int]bool)
	for _, f := range files {
		other, dup := seen[f.version]
		if !dup {
			seen[f.version] = f.fileName
			continue
		}
		if !reported[f.version] {
			reported[f.version] = true
			out = append(out, fw.Problem{
				Module: module, Key: f.fileName,
				Message: fmt.Sprintf("has the same version (%d) as %s", f.version, other),
			})
		}
	}
	return out
}

func readSet(set fw.MigrationSet) ([]migrationFileInfo, []fw.Problem) {
	if set.Files == nil {
		return nil, []fw.Problem{{Module: set.Module, Message: "migration set has no files"}}
	}
	entries, err := fs.ReadDir(set.Files, ".")
	if err != nil {
		return nil, []fw.Problem{{Module: set.Module, Message: "cannot read migration set: " + err.Error()}}
	}
	var out []migrationFileInfo
	var problems []fw.Problem
	found := false
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".sql") {
			continue
		}
		found = true
		m := migrationFile.FindStringSubmatch(e.Name())
		if m == nil {
			problems = append(problems, fw.Problem{Module: set.Module, Key: e.Name(), Message: "does not match V<version>__<description>.sql"})
			continue
		}
		version, err := strconv.ParseInt(m[1], 10, 32)
		if err != nil || version < 1 {
			problems = append(problems, fw.Problem{Module: set.Module, Key: e.Name(), Message: "version must be a positive integer"})
			continue
		}
		b, err := fs.ReadFile(set.Files, e.Name())
		if err != nil {
			problems = append(problems, fw.Problem{Module: set.Module, Key: e.Name(), Message: "cannot read: " + err.Error()})
			continue
		}
		sum := sha256.Sum256(b)
		out = append(out, migrationFileInfo{
			module: set.Module, version: int(version), description: strings.ReplaceAll(m[2], "_", " "),
			fileName: e.Name(), checksum: hex.EncodeToString(sum[:]), sql: string(b),
		})
	}
	if !found && len(problems) == 0 {
		problems = append(problems, fw.Problem{Module: set.Module, Message: "migration location not found or empty"})
	}
	return out, problems
}

func prefix12(s string) string {
	if len(s) < 12 {
		return s
	}
	return s[:12]
}
