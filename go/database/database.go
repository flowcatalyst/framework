// Package database is the framework's PostgreSQL access (database-and-redis contract sections 1
// and 2): named instances on pgxpool, a start-up ping and a health check per instance, the
// migration runner on fw_schema_history (shared with the Java framework, so a Go service can take
// over a Java service's database), and the dynamic-query [Filter].
//
//	db := database.Declare(app)                // in main, before Launch
//	dbs, err := database.Connect(ctx, b, db)   // in build: pools, pings, health, closed at shutdown
//	err = database.Migrate(ctx, b, dbs)        // the declared migration sets, on the default instance
package database

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"slices"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/internal/dbtx"
)

// ModuleID is the database module's id.
const ModuleID = "database"

// Driver is the only driver of the database section: PostgreSQL through pgxpool. Instances may
// leave it out.
const Driver = "postgresql"

// The keys of a database instance (database.instances.<name>.<key>).
var (
	keyURL = config.String("url",
		"jdbc:postgresql://host:port/db or postgres://... (the jdbc: prefix is dropped)").Required()
	keyUsername = config.String("username", "User name; wins over one in the URL.")
	keyPassword = config.SecretValue("password", "Password; wins over one in the URL.")
	keyPoolMax  = config.Int("pool.max", "Most connections in the pool.").WithDefault(10)
	keyMinIdle  = config.Int("pool.min-idle", "Idle connections the pool keeps.").WithDefault(1)
	keyTimeout  = config.Duration("pool.connect-timeout",
		"Time to open a connection (at least 250ms).").WithDefault(5 * time.Second)
	keyMigrate = config.Bool("migrate",
		"Whether the migration sets are applied to this instance when it is the default one.").WithDefault(true)
)

// Declared is the database module's declaration, from [Declare].
type Declared struct {
	instances fw.InstancesHandle
}

// Declare declares the database module: the instance section "database" with its keys.
func Declare(app *fw.App) *Declared {
	d := app.Module(ModuleID)
	return &Declared{instances: d.Instances("database", "Database", Driver, config.Driver{
		Name: Driver,
		Keys: []config.Decl{keyURL, keyUsername, keyPassword, keyPoolMax, keyMinIdle, keyTimeout, keyMigrate},
	})}
}

// DB is one database instance: a pgxpool pool and its settings.
type DB struct {
	name    string
	pool    *pgxpool.Pool
	migrate bool
}

// Tx is a transaction the framework began (the use-case runner, a receiver's once). Inside a
// plan step, application code uses its Conn (dbq.New(tx.Conn())); it never begins, commits or
// rolls back.
type Tx = dbtx.Tx

// Name returns the instance's name.
func (db *DB) Name() string { return db.name }

// Pool returns the pool, for reads and sqlc's generated code (dbq.New(db.Pool())).
func (db *DB) Pool() *pgxpool.Pool { return db.pool }

// Migrates reports the instance's migrate setting.
func (db *DB) Migrates() bool { return db.migrate }

// Ping checks that the database answers.
func (db *DB) Ping(ctx context.Context) error {
	_, err := db.pool.Exec(ctx, "SELECT 1")
	return err
}

// TableExists reports whether a table (or other relation), optionally schema-qualified, exists.
func (db *DB) TableExists(ctx context.Context, qualifiedName string) (bool, error) {
	var ok bool
	err := db.pool.QueryRow(ctx, "SELECT to_regclass($1) IS NOT NULL", qualifiedName).Scan(&ok)
	return ok, err
}

// RequireTable fails unless a table exists: for a capability that needs a table on an instance
// other than the default one, where migrations do not run (database contract section 2).
// needer describes who needs it, for the message.
func (db *DB) RequireTable(ctx context.Context, qualifiedName, needer string) error {
	ok, err := db.TableExists(ctx, qualifiedName)
	if err != nil {
		return fmt.Errorf("cannot check for table '%s' in database instance '%s': %w", qualifiedName, db.name, err)
	}
	if !ok {
		return fmt.Errorf("%s needs table '%s' in database instance '%s', but it does not exist; migrations do not run on this "+
			"instance, so create the table yourself or use the default database instance", needer, qualifiedName, db.name)
	}
	return nil
}

// Databases are the configured instances.
type Databases struct {
	names  []string
	def    string
	byName map[string]*DB
}

// Get returns the named instance, or an error naming the known instances.
func (d *Databases) Get(name string) (*DB, error) {
	if db, ok := d.byName[name]; ok {
		return db, nil
	}
	known := "(none)"
	if len(d.names) > 0 {
		known = strings.Join(d.names, ", ")
	}
	return nil, fmt.Errorf("no Database instance named '%s'; known instances: %s", name, known)
}

// Default returns the default instance, and false when no instance is configured.
func (d *Databases) Default() (*DB, bool) {
	db, ok := d.byName[d.def]
	if !ok || db == nil {
		return nil, false
	}
	return db, true
}

// Names returns the instances' names in configuration order.
func (d *Databases) Names() []string { return slices.Clone(d.names) }

// Connect opens a pool per configured instance and pings it; an instance that does not answer
// fails the start, naming it. Each instance gets the critical health check database:<name>, and
// its pool is closed at shutdown.
func Connect(ctx context.Context, b *fw.Boot, d *Declared) (*Databases, error) {
	insts := b.Instances(d.instances)
	out := &Databases{names: slices.Clone(insts.Names), def: insts.Default, byName: make(map[string]*DB)}
	for _, name := range insts.Names {
		in := insts.ByName[name]
		cfg, err := poolConfig(in)
		if err != nil {
			return nil, fmt.Errorf("database instance '%s': %w", name, err)
		}
		pool, err := pgxpool.NewWithConfig(ctx, cfg)
		if err != nil {
			return nil, fmt.Errorf("database instance '%s': %w", name, err)
		}
		db := &DB{name: name, pool: pool, migrate: keyMigrate.Get(in)}
		b.OnShutdown("database:"+name, func(context.Context) error {
			pool.Close()
			return nil
		})
		if err := db.Ping(ctx); err != nil {
			return nil, fmt.Errorf("cannot connect to database instance '%s': %w", name, err)
		}
		b.Health("database:"+name, fw.Critical, db.Ping)
		out.byName[name] = db
	}
	return out, nil
}

// Open opens one instance from a URL outside an application (tools, tests): a pool with the
// default settings, pinged.
func Open(ctx context.Context, name, rawURL string) (*DB, error) {
	cfg, err := parseURL(rawURL)
	if err != nil {
		return nil, err
	}
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, err
	}
	db := &DB{name: name, pool: pool, migrate: true}
	if err := db.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("cannot connect to database instance '%s': %w", name, err)
	}
	return db, nil
}

// Close closes the pool (an application's pools are closed at shutdown; this is for [Open]).
func (db *DB) Close() { db.pool.Close() }

func poolConfig(in config.Instance) (*pgxpool.Config, error) {
	cfg, err := parseURL(keyURL.Get(in))
	if err != nil {
		return nil, err
	}
	if user, ok := keyUsername.Lookup(in); ok && user != "" {
		cfg.ConnConfig.User = user
	}
	if pw, ok := keyPassword.Lookup(in); ok && pw.Reveal() != "" {
		cfg.ConnConfig.Password = pw.Reveal()
	}
	maxConns, minIdle := keyPoolMax.Get(in), keyMinIdle.Get(in)
	if maxConns < 1 {
		return nil, errors.New("pool.max must be at least 1")
	}
	if minIdle < 0 || minIdle > maxConns {
		return nil, errors.New("pool.min-idle must be 0 to pool.max")
	}
	cfg.MaxConns = int32(maxConns)    //nolint:gosec // checked by the int setting (32 bits)
	cfg.MinIdleConns = int32(minIdle) //nolint:gosec // as above
	cfg.ConnConfig.ConnectTimeout = max(250*time.Millisecond, keyTimeout.Get(in))
	return cfg, nil
}

// parseURL accepts jdbc:postgresql://... (the prefix is dropped, as the Rust driver does),
// postgres://... and postgresql://...; not pgx's key=value form, which the Java framework cannot
// read either.
func parseURL(raw string) (*pgxpool.Config, error) {
	s := strings.TrimPrefix(raw, "jdbc:")
	u, err := url.Parse(s)
	if err != nil || (u.Scheme != "postgres" && u.Scheme != "postgresql") || u.Host == "" {
		return nil, errors.New("url must be jdbc:postgresql://host[:port]/db, postgres://host/db or postgresql://host/db")
	}
	cfg, err := pgxpool.ParseConfig(s)
	if err != nil {
		return nil, fmt.Errorf("url does not parse: %w", err)
	}
	return cfg, nil
}
