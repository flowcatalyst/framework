package idempotency_test

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/idempotency"
)

func TestMain(m *testing.M) {
	fwtest.Main(m, fwtest.Template(func(ctx context.Context, url string) error {
		db, err := database.Open(ctx, "main", url)
		if err != nil {
			return err
		}
		defer db.Close()
		return database.ApplyMigrations(ctx, db, []fw.MigrationSet{
			{Module: idempotency.ModuleID, Files: idempotency.Migrations()},
		}, slog.New(slog.DiscardHandler))
	}))
}

// headerResolver takes the caller from X-Test-Client and X-Test-Principal; a request without a
// principal has a context without one.
type headerResolver struct{}

func (headerResolver) Resolve(r *http.Request) (fw.Context, error) {
	cx := fw.ContextFor(fw.MustClientID(r.Header.Get("X-Test-Client")))
	if p := r.Header.Get("X-Test-Principal"); p != "" {
		cx = cx.WithPrincipal(fw.MustPrincipalID(p))
	}
	return cx, nil
}

type site struct {
	base  string
	clock *fwtest.Clock
	db    *database.DB
	runs  atomic.Int32
	// hold, when set, is waited for by the handler before it answers (concurrency tests)
	hold    chan struct{}
	entered chan struct{}
	once    sync.Once
}

// startSite runs a server whose POST /orders (and /other) handler counts its runs and answers 201
// with the run number; a body {"fail":true} is answered 400, {"conflict":true} 409 (written by the
// handler), {"panic":true} panics.
func startSite(t *testing.T, opts ...idempotency.Option) *site {
	t.Helper()
	s := &site{clock: fwtest.NewClock(time.Date(2026, 11, 7, 12, 0, 0, 0, time.UTC))}
	pg := fwtest.NewDatabase(t)
	app := fw.New("idempotency-test", fw.WithEnvironment(map[string]string{"APP_ENV": "test", "HTTP_HOST": "127.0.0.1", "HTTP_PORT": "0"}),
		fw.WithConfigDir(t.TempDir()), fw.WithOutput(io.Discard, io.Discard), fw.WithLogger(slog.New(slog.DiscardHandler)), fw.WithClock(s.clock))
	web := httpfw.Declare(app)
	declared := idempotency.Declare(app)
	var srv *httpfw.Server
	run, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		db, err := database.Open(ctx, "main", pg.URL)
		if err != nil {
			return nil, err
		}
		b.OnShutdown("db", func(context.Context) error { db.Close(); return nil })
		s.db = db
		keys := idempotency.New(b, declared, db, opts...)
		rt := httpfw.NewRoutes("test")
		handler := func(w http.ResponseWriter, r *http.Request) error {
			body, err := httpfw.DecodeJSON[map[string]any](r)
			if err != nil {
				return err
			}
			n := s.runs.Add(1)
			if s.entered != nil {
				s.once.Do(func() { close(s.entered) })
			}
			if s.hold != nil {
				<-s.hold
			}
			if body["panic"] == true {
				panic("the handler failed")
			}
			if body["fail"] == true {
				return httpfw.Validation("refused")
			}
			if body["conflict"] == true { // a refusal written by the handler itself, not returned
				w.WriteHeader(http.StatusConflict)
				return nil
			}
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Location", fmt.Sprintf("/orders/%d", n))
			w.WriteHeader(http.StatusCreated)
			_, err = fmt.Fprintf(w, `{"run":%d,"path":%q}`, n, r.URL.Path)
			return err
		}
		rt.Handle("POST /orders", handler, httpfw.RequiresContext(), httpfw.With(keys.Middleware))
		rt.Handle("POST /other", handler, httpfw.RequiresContext(), httpfw.With(keys.Middleware))
		rt.Handle("POST /public", handler, httpfw.With(keys.Middleware))
		srv, err = httpfw.NewServer(b, web, []*httpfw.Routes{rt}, httpfw.WithResolver(headerResolver{}))
		return srv, err
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = run.Shutdown() })
	if srv == nil {
		t.Fatal("no server was built")
	}
	s.base = fmt.Sprintf("http://127.0.0.1:%d", srv.Port())
	return s
}

type answer struct {
	status int
	header http.Header
	body   string
}

func (a answer) code(t *testing.T) string {
	t.Helper()
	var env struct {
		Error string `json:"error"`
	}
	_ = json.Unmarshal([]byte(a.body), &env)
	return env.Error
}

// post sends a JSON body as principal of client, with key when it is not "".
func (s *site) post(t *testing.T, path, client, principal, key, body string) answer {
	t.Helper()
	req, err := http.NewRequestWithContext(t.Context(), http.MethodPost, s.base+path, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Test-Client", client)
	if principal != "" {
		req.Header.Set("X-Test-Principal", principal)
	}
	if key != "" {
		req.Header.Set(idempotency.Header, key)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return answer{res.StatusCode, res.Header, string(b)}
}

func TestARepeatedKeyGetsTheFirstAnswerAndTheHandlerRunsOnce(t *testing.T) {
	t.Parallel()
	s := startSite(t)
	first := s.post(t, "/orders", "acme", "usr_1", "k-1", `{"n":1}`)
	again := s.post(t, "/orders", "acme", "usr_1", "k-1", `{"n":1}`)
	if first.status != 201 || first.body != `{"run":1,"path":"/orders"}` || first.header.Get(idempotency.ReplayedHeader) != "" {
		t.Fatalf("first: %d %s %v", first.status, first.body, first.header)
	}
	if again.status != 201 || again.body != first.body || again.header.Get("Content-Type") != "application/json" ||
		again.header.Get(idempotency.ReplayedHeader) != "true" {
		t.Fatalf("again: %d %s %v", again.status, again.body, again.header)
	}
	if n := s.runs.Load(); n != 1 {
		t.Fatalf("the handler ran %d times", n)
	}
	// without the header a request runs as it is
	if r := s.post(t, "/orders", "acme", "usr_1", "", `{"n":1}`); r.status != 201 || r.body != `{"run":2,"path":"/orders"}` {
		t.Errorf("no key: %d %s", r.status, r.body)
	}
}

func TestTwoConcurrentRequestsWithOneKeyRunTheHandlerOnceAndGetOneAnswer(t *testing.T) {
	t.Parallel()
	s := startSite(t)
	s.hold, s.entered = make(chan struct{}), make(chan struct{})
	answers := make(chan answer, 2)
	go func() { answers <- s.post(t, "/orders", "acme", "usr_1", "k-c", `{"n":1}`) }()
	<-s.entered // the first holds the key and runs
	go func() { answers <- s.post(t, "/orders", "acme", "usr_1", "k-c", `{"n":1}`) }()
	time.Sleep(300 * time.Millisecond) // the second arrives while the first still runs
	close(s.hold)
	a, b := <-answers, <-answers
	if a.status != 201 || b.status != 201 || a.body != b.body {
		t.Fatalf("answers: %d %s / %d %s", a.status, a.body, b.status, b.body)
	}
	if replayed := a.header.Get(idempotency.ReplayedHeader) + b.header.Get(idempotency.ReplayedHeader); replayed != "true" {
		t.Errorf("exactly one answer is a replay, got %q", replayed)
	}
	if n := s.runs.Load(); n != 1 {
		t.Fatalf("the handler ran %d times", n)
	}
}

func TestARepeatThatWaitsLongerThanItsWaitIsToldToRetry(t *testing.T) {
	t.Parallel()
	s := startSite(t, idempotency.Wait(100*time.Millisecond))
	s.hold, s.entered = make(chan struct{}), make(chan struct{})
	first := make(chan answer, 1)
	go func() { first <- s.post(t, "/orders", "acme", "usr_1", "k-w", `{}`) }()
	<-s.entered
	r := s.post(t, "/orders", "acme", "usr_1", "k-w", `{}`)
	close(s.hold)
	if r.status != 409 || r.code(t) != "IDEMPOTENCY_IN_PROGRESS" {
		t.Errorf("while the first runs: %d %s", r.status, r.body)
	}
	if a := <-first; a.status != 201 {
		t.Errorf("first: %d %s", a.status, a.body)
	}
}

func TestAKeyUsedForAnotherRequestIsRefused(t *testing.T) {
	t.Parallel()
	s := startSite(t)
	s.post(t, "/orders", "acme", "usr_1", "k-r", `{"n":1}`)
	for _, c := range []struct{ path, body string }{
		{"/orders", `{"n":2}`}, // another body
		{"/other", `{"n":1}`},  // another path
		{"/orders?x=1", `{"n":1}`},
	} {
		if r := s.post(t, c.path, "acme", "usr_1", "k-r", c.body); r.status != 422 || r.code(t) != "IDEMPOTENCY_KEY_REUSED" {
			t.Errorf("%s %s: %d %s", c.path, c.body, r.status, r.body)
		}
	}
	if n := s.runs.Load(); n != 1 {
		t.Errorf("the handler ran %d times", n)
	}
}

func TestAFailedOrPanickedRequestFreesItsKey(t *testing.T) {
	t.Parallel()
	s := startSite(t)
	if r := s.post(t, "/orders", "acme", "usr_1", "k-f", `{"fail":true}`); r.status != 400 {
		t.Fatalf("failing: %d %s", r.status, r.body)
	}
	// the corrected request under the same key runs, and is then kept
	if r := s.post(t, "/orders", "acme", "usr_1", "k-f", `{"n":1}`); r.status != 201 || r.header.Get(idempotency.ReplayedHeader) != "" {
		t.Fatalf("corrected: %d %s", r.status, r.body)
	}
	if r := s.post(t, "/orders", "acme", "usr_1", "k-f", `{"n":1}`); r.header.Get(idempotency.ReplayedHeader) != "true" {
		t.Fatalf("repeat of the corrected: %d %s", r.status, r.body)
	}
	if r := s.post(t, "/orders", "acme", "usr_1", "k-w", `{"conflict":true}`); r.status != 409 {
		t.Fatalf("refused by the handler: %d %s", r.status, r.body)
	}
	if r := s.post(t, "/orders", "acme", "usr_1", "k-w", `{"n":1}`); r.status != 201 {
		t.Fatalf("after a refusal written by the handler the key is free: %d %s", r.status, r.body)
	}
	if r := s.post(t, "/orders", "acme", "usr_1", "k-p", `{"panic":true}`); r.status != 500 {
		t.Fatalf("panicking: %d %s", r.status, r.body)
	}
	if r := s.post(t, "/orders", "acme", "usr_1", "k-p", `{"n":1}`); r.status != 201 {
		t.Fatalf("after a panic the key is free: %d %s", r.status, r.body)
	}
}

func TestKeysBelongToOneClientAndOneCaller(t *testing.T) {
	t.Parallel()
	s := startSite(t)
	s.post(t, "/orders", "acme", "usr_1", "k-s", `{}`)
	for _, c := range []struct{ client, principal string }{{"acme", "usr_2"}, {"globex", "usr_1"}} {
		if r := s.post(t, "/orders", c.client, c.principal, "k-s", `{}`); r.status != 201 || r.header.Get(idempotency.ReplayedHeader) != "" {
			t.Errorf("%s/%s: %d %s %v", c.client, c.principal, r.status, r.body, r.header)
		}
	}
	if n := s.runs.Load(); n != 3 {
		t.Errorf("the handler ran %d times, want 3", n)
	}
}

func TestAKeyExpiresAfterTheRetentionAndAnAbandonedOneIsTakenOver(t *testing.T) {
	t.Parallel()
	s := startSite(t, idempotency.Retention(time.Hour), idempotency.InFlightTimeout(time.Minute), idempotency.Wait(200*time.Millisecond))
	s.post(t, "/orders", "acme", "usr_1", "k-e", `{}`)
	s.clock.Advance(59 * time.Minute)
	if r := s.post(t, "/orders", "acme", "usr_1", "k-e", `{}`); r.header.Get(idempotency.ReplayedHeader) != "true" {
		t.Fatalf("within the retention: %d %s", r.status, r.body)
	}
	s.clock.Advance(2 * time.Minute)
	if r := s.post(t, "/orders", "acme", "usr_1", "k-e", `{}`); r.status != 201 || r.header.Get(idempotency.ReplayedHeader) != "" {
		t.Fatalf("after the retention the request runs again: %d %s %v", r.status, r.body, r.header)
	}
	// a reservation whose request never finished (the process stopped) is taken over after the timeout
	// (the same request's hash, taken from the row of k-e)
	_, err := s.db.Pool().Exec(t.Context(), `INSERT INTO fw_idempotency_keys
		(client_id, principal_id, idempotency_key, request_hash, token, created_at)
		SELECT 'acme', 'usr_1', 'k-a', request_hash, 'gone', $1 FROM fw_idempotency_keys WHERE idempotency_key = 'k-e'`,
		s.clock.Now().Add(-30*time.Second))
	if err != nil {
		t.Fatal(err)
	}
	if r := s.post(t, "/orders", "acme", "usr_1", "k-a", `{}`); r.status != 409 {
		t.Fatalf("a reservation still in flight: %d %s", r.status, r.body)
	}
	s.clock.Advance(time.Minute)
	if r := s.post(t, "/orders", "acme", "usr_1", "k-a", `{}`); r.status != 201 {
		t.Fatalf("an abandoned reservation: %d %s", r.status, r.body)
	}
	// pruning removes what has expired
	s.clock.Advance(2 * time.Hour)
	removed, err := idempotency.Prune(t.Context(), s.db, s.clock.Now().Add(-time.Hour))
	if err != nil || removed != 2 {
		t.Fatalf("prune: %d %v", removed, err)
	}
}

func TestAnInvalidKeyAndAKeyWithoutACallerAreRefused(t *testing.T) {
	t.Parallel()
	s := startSite(t)
	for _, key := range []string{strings.Repeat("k", 201), "two words", "café"} {
		if r := s.post(t, "/orders", "acme", "usr_1", key, `{}`); r.status != 400 || r.code(t) != "IDEMPOTENCY_KEY_INVALID" {
			t.Errorf("%q: %d %s", key, r.status, r.body)
		}
	}
	if n := s.runs.Load(); n != 0 {
		t.Errorf("the handler ran %d times", n)
	}
	// a context without a principal, and a route without a context, cannot scope a key: the
	// request is refused rather than run unprotected
	if r := s.post(t, "/orders", "acme", "", "k-x", `{}`); r.status != 400 || r.code(t) != "IDEMPOTENCY_KEY_UNSUPPORTED" {
		t.Errorf("no principal: %d %s", r.status, r.body)
	}
	if r := s.post(t, "/public", "acme", "usr_1", "k-x", `{}`); r.status != 500 {
		t.Errorf("a route without a context: %d %s", r.status, r.body)
	}
}
