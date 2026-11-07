// Package idempotency makes a write safe to send twice: a request with an Idempotency-Key header runs
// once per client, caller and key, and a repeat gets the first answer again (status, Content-Type and
// body, with Idempotency-Replayed: true). An offline queue that replays what it could not confirm
// cannot then run a claim or a payment twice.
//
// The keys live in the application's database (table fw_idempotency_keys, the migration set of
// module [ModuleID]); [New] registers an hourly loop that prunes the expired ones. Use it as route
// middleware on routes that resolve a context:
//
//	d := idempotency.Declare(app)                      // in main, after the database module
//	keys := idempotency.New(b, d, db)                  // in build
//	rt.HandleContext("POST /api/picks/{id}/claim", claim, httpfw.With(keys.Middleware))
//	rt.Bind(httpfw.Handle(PlaceOrder, svc.place, httpfw.With(keys.Middleware)))
package idempotency

import (
	"bytes"
	"context"
	crand "crypto/rand"
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/background"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// ModuleID is the module of the key table's migration set.
const ModuleID = "http-idempotency"

// Header is the request header that carries the key.
const Header = "Idempotency-Key"

// ReplayedHeader marks an answer that is a repeat of the first one.
const ReplayedHeader = "Idempotency-Replayed"

// MaxKeyLength is the longest key, in characters.
const MaxKeyLength = 200

// Defaults of the options.
const (
	// DefaultRetention is how long a key and its answer are kept.
	DefaultRetention = 24 * time.Hour
	// DefaultWait is how long a repeat waits for the first request with its key to finish.
	DefaultWait = 10 * time.Second
	// DefaultInFlightTimeout is how long a reservation whose request has not finished holds its key
	// (the process that ran it may have stopped); after it another request takes the key over.
	DefaultInFlightTimeout = 5 * time.Minute
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

// Migrations returns the migration set (table fw_idempotency_keys).
func Migrations() fs.FS {
	sub, err := fs.Sub(migrationFiles, "migrations")
	if err != nil {
		panic(err) // embedded above
	}
	return sub
}

// Declared is the proof that the migration set is part of the application.
type Declared struct{}

// Declare adds the module's migration set to the application (applied to the default database
// instance). Declare it after the database module, before Start.
func Declare(app *fw.App) *Declared {
	app.Module(ModuleID).Migrations(Migrations())
	return &Declared{}
}

// Option configures [New].
type Option func(*options)

type options struct {
	retention, wait, inFlight time.Duration
}

// Retention sets how long a key and its answer are kept (default [DefaultRetention]); after it a
// repeat runs again.
func Retention(d time.Duration) Option { return func(o *options) { o.retention = d } }

// Wait sets how long a repeat that arrives while the first request still runs waits for its answer
// (default [DefaultWait]); after it the repeat is answered 409 IDEMPOTENCY_IN_PROGRESS.
func Wait(d time.Duration) Option { return func(o *options) { o.wait = d } }

// InFlightTimeout sets how long an unfinished reservation holds its key (default
// [DefaultInFlightTimeout]).
func InFlightTimeout(d time.Duration) Option { return func(o *options) { o.inFlight = d } }

// Keys is the middleware over the key table. Safe for concurrent use.
type Keys struct {
	db    *database.DB
	clock fw.Clock
	log   *slog.Logger
	opts  options
}

// New returns the middleware over db (the database the migration set is applied to) and registers
// the loop "http-idempotency-prune", which deletes the expired keys every hour on every replica
// (the delete is idempotent).
func New(b *fw.Boot, _ *Declared, db *database.DB, opts ...Option) *Keys {
	o := options{retention: DefaultRetention, wait: DefaultWait, inFlight: DefaultInFlightTimeout}
	for _, f := range opts {
		f(&o)
	}
	k := &Keys{db: db, clock: b.Clock(), log: b.Logger(ModuleID), opts: o}
	b.Loop("http-idempotency-prune", background.Options{Interval: time.Hour}, func(ctx context.Context, _ background.Tick) error {
		_, err := Prune(ctx, db, k.clock.Now().Add(-o.retention))
		return err
	})
	return k
}

// Prune deletes the keys created before before and returns how many.
func Prune(ctx context.Context, db *database.DB, before time.Time) (int64, error) {
	tag, err := db.Pool().Exec(ctx, `DELETE FROM fw_idempotency_keys WHERE created_at < $1`, before)
	if err != nil {
		return 0, fmt.Errorf("idempotency: prune: %w", err)
	}
	return tag.RowsAffected(), nil
}

// scope is one key of one caller.
type scope struct{ client, principal, key string }

// Middleware runs a request with an Idempotency-Key once per client, caller and key:
//
//   - the first takes the key; when it succeeds (2xx) its status, Content-Type and body are kept,
//     and when it fails (an error, a status of 300 or more, a panic) the key is freed, so a retry
//     does the work. A streamed answer (one the handler flushed before it returned) is not kept
//     either: the key is freed, so a repeat runs again;
//   - a repeat with the same method, path, query and body gets the kept answer, with
//     Idempotency-Replayed: true; a repeat that arrives while the first runs waits for it (Wait),
//     then gets its answer, or 409 IDEMPOTENCY_IN_PROGRESS when the wait runs out;
//   - another request under a key already used is 422 IDEMPOTENCY_KEY_REUSED;
//   - a key that is not 1 to 200 printable ASCII characters is 400 IDEMPOTENCY_KEY_INVALID, and a
//     key on a request whose context has no principal is 400 IDEMPOTENCY_KEY_UNSUPPORTED (it cannot
//     be scoped to a caller, so the request is not run unprotected).
//
// A request without the header runs as it is. The body is read within the route's body limit. It is
// route middleware for routes that resolve a context: on a route without one it fails (500).
func (k *Keys) Middleware(next httpfw.HandlerFunc) httpfw.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) error {
		key, present := r.Header[http.CanonicalHeaderKey(Header)]
		if !present {
			return next(w, r)
		}
		if len(key) != 1 || !validKey(key[0]) {
			return httpfw.NewError(http.StatusBadRequest, "IDEMPOTENCY_KEY_INVALID",
				fmt.Sprintf("an Idempotency-Key is 1 to %d printable ASCII characters, sent once", MaxKeyLength))
		}
		cx, ok := httpfw.ContextIfAny(r)
		if !ok {
			return errors.New("idempotency: the middleware is on route " + httpfw.RoutePattern(r) + ", which resolves no context")
		}
		principal, ok := cx.Principal()
		if !ok {
			return httpfw.NewError(http.StatusBadRequest, "IDEMPOTENCY_KEY_UNSUPPORTED",
				"an Idempotency-Key needs an authenticated caller")
		}
		body, err := httpfw.ReadBody(r)
		if err != nil {
			return err
		}
		r.Body = io.NopCloser(bytes.NewReader(body))
		sc := scope{client: cx.Client().String(), principal: principal.String(), key: key[0]}
		return k.run(w, r, next, sc, requestHash(r, body))
	}
}

func validKey(key string) bool {
	if key == "" || len(key) > MaxKeyLength {
		return false
	}
	for i := 0; i < len(key); i++ {
		if key[i] < 0x21 || key[i] > 0x7e {
			return false
		}
	}
	return true
}

// requestHash identifies the request a key was used for: method, path, query and body.
func requestHash(r *http.Request, body []byte) string {
	sum := sha256.New()
	_, _ = io.WriteString(sum, r.Method+" "+r.URL.EscapedPath()+"?"+r.URL.RawQuery+"\n")
	_, _ = sum.Write(body)
	return hex.EncodeToString(sum.Sum(nil))
}

// kept is a key's row.
type kept struct {
	hash        string
	status      *int32
	contentType *string
	body        []byte
}

func (k *Keys) run(w http.ResponseWriter, r *http.Request, next httpfw.HandlerFunc, sc scope, hash string) error {
	ctx := r.Context()
	deadline := time.NewTimer(k.opts.wait)
	defer deadline.Stop()
	poll := 20 * time.Millisecond
	for {
		token, reserved, err := k.reserve(ctx, sc, hash)
		if err != nil {
			return err
		}
		if reserved {
			return k.first(w, r, next, sc, token)
		}
		row, found, err := k.find(ctx, sc)
		if err != nil {
			return err
		}
		switch {
		case !found:
			continue // freed (or expired) since: take it
		case row.hash != hash:
			return httpfw.NewError(http.StatusUnprocessableEntity, "IDEMPOTENCY_KEY_REUSED",
				"this Idempotency-Key was used for another request")
		case row.status != nil:
			return replay(w, row)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-deadline.C:
			return httpfw.NewError(http.StatusConflict, "IDEMPOTENCY_IN_PROGRESS",
				"the first request with this Idempotency-Key is still running; retry shortly")
		case <-time.After(poll):
		}
		poll = min(2*poll, 250*time.Millisecond)
	}
}

// first runs the request that holds the key and keeps its answer, or frees the key.
func (k *Keys) first(w http.ResponseWriter, r *http.Request, next httpfw.HandlerFunc, sc scope, token string) error {
	rec := &recorder{ResponseWriter: w, status: http.StatusOK}
	completed := false
	defer func() {
		if completed {
			return
		}
		// an error, a refusal or a panic: free the key, so a retry does the work
		if err := k.release(context.WithoutCancel(r.Context()), sc, token); err != nil {
			k.log.Warn("an Idempotency-Key could not be freed; it is freed when its reservation times out",
				slog.String("error", err.Error()))
		}
	}()
	if err := next(rec, r); err != nil || rec.status >= http.StatusMultipleChoices || rec.streamed {
		return err
	}
	if err := k.complete(context.WithoutCancel(r.Context()), sc, token, rec); err != nil {
		return err
	}
	completed = true
	return nil
}

func replay(w http.ResponseWriter, row kept) error {
	if row.contentType != nil && *row.contentType != "" {
		w.Header().Set("Content-Type", *row.contentType)
	}
	w.Header().Set(ReplayedHeader, "true")
	w.WriteHeader(int(*row.status))
	_, err := w.Write(row.body)
	return err
}

// reserve takes the key for this request: a new row, or the takeover of an expired key or of an
// unfinished reservation older than the in-flight timeout. It returns the reservation's token.
func (k *Keys) reserve(ctx context.Context, sc scope, hash string) (string, bool, error) {
	var raw [16]byte
	_, _ = crand.Read(raw[:])
	token := hex.EncodeToString(raw[:])
	now := k.clock.Now()
	tag, err := k.db.Pool().Exec(ctx, `
INSERT INTO fw_idempotency_keys (client_id, principal_id, idempotency_key, request_hash, token, created_at)
VALUES ($1, $2, $3, $4, $5, $6)
ON CONFLICT (client_id, principal_id, idempotency_key) DO UPDATE
   SET request_hash = EXCLUDED.request_hash, token = EXCLUDED.token, status = NULL, content_type = NULL,
       response_body = NULL, created_at = EXCLUDED.created_at, completed_at = NULL
 WHERE fw_idempotency_keys.created_at < $7
    OR (fw_idempotency_keys.status IS NULL AND fw_idempotency_keys.created_at < $8)`,
		sc.client, sc.principal, sc.key, hash, token, now, now.Add(-k.opts.retention), now.Add(-k.opts.inFlight))
	if err != nil {
		return "", false, fmt.Errorf("idempotency: reserve: %w", err)
	}
	return token, tag.RowsAffected() == 1, nil
}

// find reads a key's row (an expired one was taken over by reserve just before).
func (k *Keys) find(ctx context.Context, sc scope) (kept, bool, error) {
	var row kept
	err := k.db.Pool().QueryRow(ctx, `
SELECT request_hash, status, content_type, response_body FROM fw_idempotency_keys
 WHERE client_id = $1 AND principal_id = $2 AND idempotency_key = $3`,
		sc.client, sc.principal, sc.key).
		Scan(&row.hash, &row.status, &row.contentType, &row.body)
	if errors.Is(err, pgx.ErrNoRows) {
		return kept{}, false, nil
	}
	if err != nil {
		return kept{}, false, fmt.Errorf("idempotency: read: %w", err)
	}
	return row, true, nil
}

func (k *Keys) complete(ctx context.Context, sc scope, token string, rec *recorder) error {
	_, err := k.db.Pool().Exec(ctx, `
UPDATE fw_idempotency_keys SET status = $5, content_type = $6, response_body = $7, completed_at = $8
 WHERE client_id = $1 AND principal_id = $2 AND idempotency_key = $3 AND token = $4`,
		sc.client, sc.principal, sc.key, token, rec.status, rec.Header().Get("Content-Type"), rec.body.Bytes(), k.clock.Now())
	if err != nil {
		return fmt.Errorf("idempotency: keep the answer: %w", err)
	}
	return nil
}

func (k *Keys) release(ctx context.Context, sc scope, token string) error {
	_, err := k.db.Pool().Exec(ctx, `
DELETE FROM fw_idempotency_keys
 WHERE client_id = $1 AND principal_id = $2 AND idempotency_key = $3 AND token = $4 AND status IS NULL`,
		sc.client, sc.principal, sc.key, token)
	return err
}

// recorder keeps the answer while writing it. A flush marks the answer streamed.
type recorder struct {
	http.ResponseWriter
	status   int
	wrote    bool
	streamed bool
	body     bytes.Buffer
}

func (r *recorder) WriteHeader(status int) {
	if !r.wrote {
		r.status, r.wrote = status, true
	}
	r.ResponseWriter.WriteHeader(status)
}

func (r *recorder) Write(b []byte) (int, error) {
	r.wrote = true
	r.body.Write(b)
	return r.ResponseWriter.Write(b)
}

// FlushError is what http.ResponseController.Flush calls: the answer is streamed.
func (r *recorder) FlushError() error {
	r.streamed = true
	return http.NewResponseController(r.ResponseWriter).Flush()
}

// Flush is the http.Flusher form of FlushError.
func (r *recorder) Flush() { _ = r.FlushError() }

// Unwrap lets http.ResponseController reach the real writer.
func (r *recorder) Unwrap() http.ResponseWriter { return r.ResponseWriter }
