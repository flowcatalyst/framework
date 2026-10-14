// Package jetgen runs go-jet's code generator as a library, for sqlfw. Only the table, view and
// enum code is generated: the framework uses jet as a statement builder and scans with pgx, so
// jet's model structs are skipped.
//
// The generator reads the schema through database/sql with the lib/pq driver. That dependency
// stays in this package; the runtime package github.com/go-jet/jet/v2/postgres, which generated
// code imports, does not need it.
package jetgen

import (
	"context"
	"database/sql"
	"fmt"
	"io"
	"os"
	"sync"

	"github.com/go-jet/jet/v2/generator/metadata"
	jetpostgres "github.com/go-jet/jet/v2/generator/postgres"
	"github.com/go-jet/jet/v2/generator/template"
	"github.com/go-jet/jet/v2/postgres"
	_ "github.com/lib/pq" // the driver of the generator's database/sql connection
)

// stdoutMu serialises the redirection of os.Stdout, which jet's generator writes progress to.
var stdoutMu sync.Mutex

// Generate writes the jet table, view and enum code of schema in the database at dsn (a
// postgres:// URL) to dir/<schema>/{table,view,enum}. Whatever is in dir/<schema> is removed
// first. The generator's progress output goes to log (nil discards it).
func Generate(ctx context.Context, dsn, schema, dir string, log io.Writer) error {
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		return fmt.Errorf("jet: open database: %w", err)
	}
	defer func() { _ = db.Close() }()
	if err := db.PingContext(ctx); err != nil {
		return fmt.Errorf("jet: connect: %w", err)
	}
	tpl := template.Default(postgres.Dialect).UseSchema(func(md metadata.Schema) template.Schema {
		return template.DefaultSchema(md).UseModel(template.DefaultModel().ShouldSkip(true))
	})
	return captureStdout(log, func() error {
		if err := jetpostgres.GenerateDB(db, schema, dir, tpl); err != nil {
			return fmt.Errorf("jet: %w", err)
		}
		return nil
	})
}

// captureStdout runs f with os.Stdout redirected to w (discarded when nil).
func captureStdout(w io.Writer, f func() error) error {
	if w == nil {
		w = io.Discard
	}
	stdoutMu.Lock()
	defer stdoutMu.Unlock()
	r, pw, err := os.Pipe()
	if err != nil {
		return err
	}
	saved := os.Stdout
	os.Stdout = pw
	done := make(chan struct{})
	go func() {
		_, _ = io.Copy(w, r)
		close(done)
	}()
	ferr := f()
	os.Stdout = saved
	_ = pw.Close()
	<-done
	_ = r.Close()
	return ferr
}
