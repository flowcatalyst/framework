package push

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json/v2"
	"fmt"
	"io"
	"log/slog"
	"maps"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/locks/dblock"
	"github.com/flowcatalyst/framework/go/metrics"
	"github.com/flowcatalyst/framework/go/redisfw"
)

func TestMain(m *testing.M) {
	fwtest.Main(m, fwtest.Template(func(ctx context.Context, url string) error {
		db, err := database.Open(ctx, "main", url)
		if err != nil {
			return err
		}
		defer db.Close()
		return database.ApplyMigrations(ctx, db, []fw.MigrationSet{
			{Module: dblock.ModuleID, Files: dblock.Migrations()},
			{Module: ModuleID, Files: Migrations()},
		}, slog.New(slog.DiscardHandler))
	}))
}

// The TEST keys of conformance/push/tickets.json: never use them for anything real.
const (
	testKeyCurrent  = "test-2026-a:5BgJptHbkz4uIeRqgLz3Ws_38Ovwi1uCpSdyW45yMdc"
	testKeyPrevious = "test-2025-z:aMAmPCO41Sx6WwDvG_MWCgxTdygpi14ynrtSTxTfFrM"
	testKeyStranger = "test-other:akpj827cS1gLGxwkstCQeVk6M-4kmfcyq47WcTCGpr0"
	testSecret      = "CMY325HtlvFwAGl8Fh0YFzJiXVcxbQO905RbLlbkbiZlcknycLNMYxgoAXXKt2b"
)

// begin is the tests' clock start, on a whole second.
var begin = time.Date(2026, 11, 8, 12, 0, 0, 0, time.UTC)

// headerAuth is the tests' authenticator: X-Client (with X-Principal, X-Permissions, X-Expires in
// unix seconds, X-Proof-Key) is a credential; X-Fail is a bad one; neither is none.
var headerAuth = AuthenticatorFunc(func(r *http.Request) (Credentials, bool, error) {
	if r.Header.Get("X-Fail") != "" {
		return Credentials{}, true, httpfw.Unauthenticated("bad credentials")
	}
	client := r.Header.Get("X-Client")
	if client == "" {
		return Credentials{}, false, nil
	}
	cx := fw.ContextFor(fw.MustClientID(client))
	if p := r.Header.Get("X-Principal"); p != "" {
		cx = cx.WithPrincipal(fw.MustPrincipalID(p))
	}
	var perms []fw.Permission
	for p := range strings.SplitSeq(r.Header.Get("X-Permissions"), ",") {
		if p != "" {
			perms = append(perms, fw.MustPermission(p))
		}
	}
	cx = cx.WithPermissions(perms...)
	c := Credentials{Context: cx, ProofKey: r.Header.Get("X-Proof-Key")}
	if e := r.Header.Get("X-Expires"); e != "" {
		n, _ := strconv.ParseInt(e, 10, 64)
		c.ExpiresAt = time.Unix(n, 0)
	}
	return c, true, nil
})

type nodeConfig struct {
	env     map[string]string
	clock   *fwtest.Clock
	declare func(d *Declared)
	logs    io.Writer
	// backend is the log driver and its servers; nil is A1 on the database the node is given.
	backend *backend
	// declareOpts and opts are given to DeclareWith and Build (the multi-application tests).
	declareOpts []DeclareOption
	opts        []Option
	// handler serves the routes through Pushes.Handler on an httptest server, with no httpfw server.
	handler bool
}

// The log drivers the contract tests run against.
const (
	driverPostgres = "postgres"
	driverRedis    = "redis"
)

// backend is where a test's nodes keep the log: the database every node shares, and for the redis
// driver the Redis server and the key prefix of the test.
type backend struct {
	driver string
	dbURL  string
	redis  fwtest.Redis
}

// eachDriver runs f once per log driver, as parallel subtests on a database of their own.
func eachDriver(t *testing.T, f func(t *testing.T, b backend)) {
	t.Helper()
	for _, driver := range []string{driverPostgres, driverRedis} {
		t.Run(driver, func(t *testing.T) {
			t.Parallel()
			b := backend{driver: driver, dbURL: fwtest.NewDatabase(t).URL}
			if driver == driverRedis {
				b.redis = fwtest.NewRedis(t)
			}
			f(t, b)
		})
	}
}

// start starts a node of the application on the backend.
func (b backend) start(t *testing.T, c nodeConfig) *node {
	t.Helper()
	c.backend = &b
	return startNode(t, b.dbURL, c)
}

// node is one instance of the application: its own hub, relay loop and HTTP server, on a database
// it may share with other nodes.
type node struct {
	t       *testing.T
	p       *Pushes
	run     *fw.Running
	base    string
	clock   *fwtest.Clock
	db      *database.DB
	metrics *metrics.Registry
	// migrationModules are the modules that declared a migration set.
	migrationModules []string
}

// defaultRules are the rules every test node declares unless the test sets its own.
func defaultRules(d *Declared) {
	d.Topic("test:order:{id}", Requires("test:order:view"))
	d.Topic("test:user:{principal}")
	d.Topic("test:client:{client}:news", Requires("test:news:view"))
	d.PublicTopic("test:public")
}

func startNode(t *testing.T, url string, c nodeConfig) *node {
	t.Helper()
	n, err := tryNode(t, url, c)
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	return n
}

func tryNode(t *testing.T, url string, c nodeConfig) (*node, error) {
	t.Helper()
	env := map[string]string{
		"APP_ENV": "test", "HTTP_HOST": "127.0.0.1", "HTTP_PORT": "0",
		"PUSH_STREAM_SECRET": testSecret, "PUSH_TICKET_KEY": testKeyCurrent + "," + testKeyPrevious,
		"PUSH_RELAY_INTERVAL": "50ms",
	}
	configDir := t.TempDir()
	if c.backend != nil && c.backend.driver == driverRedis {
		env["PUSH_DRIVER"] = driverRedis
		env["REDIS_INSTANCES_DEFAULT_URL"] = c.backend.redis.URL
		yaml := "redis:\n  instances:\n    default: { url: placeholder }\n"
		if err := os.WriteFile(filepath.Join(configDir, "application.yaml"), []byte(yaml), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	maps.Copy(env, c.env)
	clock := c.clock
	if clock == nil {
		clock = fwtest.NewClock(begin)
	}
	logs := c.logs
	if logs == nil {
		logs = io.Discard
	}
	app := fw.New("test", fw.WithEnvironment(env), fw.WithConfigDir(configDir), fw.WithClock(clock),
		fw.WithOutput(io.Discard, io.Discard), fw.WithLogger(slog.New(slog.NewTextHandler(logs, nil))))
	web := httpfw.Declare(app)
	ld := locks.Declare(app)
	pd := DeclareWith(app, c.declareOpts...)
	var rd *redisfw.Declared
	if c.backend != nil && c.backend.driver == driverRedis {
		rd = redisfw.Declare(app)
	}
	if c.declare != nil {
		c.declare(pd)
	} else {
		defaultRules(pd)
	}
	n := &node{t: t, clock: clock}
	var srv *httpfw.Server
	run, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		db, err := database.Open(ctx, "main", url)
		if err != nil {
			return nil, err
		}
		b.OnShutdown("db", func(context.Context) error { db.Close(); return nil })
		lk, err := locks.Build(ctx, b, ld, locks.WithStore("main", func(context.Context, *fw.Boot) (locks.Store, error) {
			return dblock.NewStore(db), nil
		}))
		if err != nil {
			return nil, err
		}
		opts := []Option{Authenticate(headerAuth)}
		opts = append(opts, c.opts...)
		if rd != nil {
			clients, err := redisfw.Connect(ctx, b, rd)
			if err != nil {
				return nil, err
			}
			opts = append(opts, RedisClients(clients, c.backend.redis.Prefix))
		}
		p, err := pd.Build(ctx, b, db, lk, opts...)
		if err != nil {
			return nil, err
		}
		n.p, n.db, n.metrics = p, db, b.Metrics()
		for _, set := range b.MigrationSets() {
			n.migrationModules = append(n.migrationModules, set.Module)
		}
		if c.handler {
			h, err := p.Handler()
			if err != nil {
				return nil, err
			}
			ts := httptest.NewServer(h)
			t.Cleanup(ts.Close)
			n.base = ts.URL
			return fw.ServeFunc(func(ctx context.Context) error { <-ctx.Done(); return nil }), nil
		}
		srv, err = httpfw.NewServer(b, web, []*httpfw.Routes{p.Routes()})
		return srv, err
	})
	if err != nil {
		return nil, err
	}
	n.run = run
	t.Cleanup(func() { _ = run.Shutdown() })
	if c.handler {
		return n, nil
	}
	if srv == nil {
		t.Fatal("no server was built")
	}
	n.base = fmt.Sprintf("http://127.0.0.1:%d", srv.Port())
	return n, nil
}

// publish writes a push for client in its own transaction.
func (n *node) publish(client fw.ClientID, topic, typ string, data any) {
	n.t.Helper()
	p, err := New(topic, typ, data)
	if err != nil {
		n.t.Fatal(err)
	}
	if err := n.p.PublishAlone(n.t.Context(), fw.ContextFor(client), p); err != nil {
		n.t.Fatal(err)
	}
}

// answer is an HTTP answer.
type answer struct {
	status int
	header http.Header
	body   string
}

func (a answer) json(t *testing.T) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal([]byte(a.body), &m); err != nil {
		t.Fatalf("not JSON: %s", a.body)
	}
	return m
}

func (a answer) code() string {
	var m map[string]any
	_ = json.Unmarshal([]byte(a.body), &m)
	s, _ := m["error"].(string)
	return s
}

// call sends a request; headers are name, value pairs.
func (n *node) call(method, path, body string, headers ...string) answer {
	n.t.Helper()
	req, err := http.NewRequestWithContext(n.t.Context(), method, n.base+path, strings.NewReader(body))
	if err != nil {
		n.t.Fatal(err)
	}
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	for i := 0; i+1 < len(headers); i += 2 {
		req.Header.Set(headers[i], headers[i+1])
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		n.t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return answer{status: res.StatusCode, header: res.Header, body: string(b)}
}

// subscribe subscribes and returns the stream token; it fails the test unless the answer is 200.
func (n *node) subscribe(topics []string, tickets []string, headers ...string) (string, map[string]any) {
	n.t.Helper()
	body, _ := json.Marshal(map[string]any{"topics": topics, "tickets": nonNil(tickets)})
	a := n.call("POST", "/push/subscriptions", string(body), headers...)
	if a.status != 200 {
		n.t.Fatalf("subscribe %v: %d %s", topics, a.status, a.body)
	}
	m := a.json(n.t)
	return m["streamToken"].(string), m
}

func nonNil(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}

// client is an open stream, read frame by frame.
type client struct {
	t      *testing.T
	res    *http.Response
	frames chan string
	cancel context.CancelFunc
	once   sync.Once
}

// listen opens GET /push/stream with the token; last, when not empty, is the Last-Event-ID.
func (n *node) listen(token, last string) *client {
	n.t.Helper()
	c, status := n.tryListen(token, last)
	if c == nil {
		n.t.Fatalf("listen: %d", status)
	}
	return c
}

func (n *node) tryListen(token, last string) (*client, int) {
	n.t.Helper()
	ctx, cancel := context.WithCancel(n.t.Context())
	req, err := http.NewRequestWithContext(ctx, "GET", n.base+"/push/stream?st="+token, nil)
	if err != nil {
		n.t.Fatal(err)
	}
	if last != "" {
		req.Header.Set("Last-Event-ID", last)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		cancel()
		n.t.Fatal(err)
	}
	if res.StatusCode != 200 {
		res.Body.Close()
		cancel()
		return nil, res.StatusCode
	}
	c := &client{t: n.t, res: res, frames: make(chan string, 1024), cancel: cancel}
	go func() {
		defer close(c.frames)
		br := bufio.NewReader(res.Body)
		var frame bytes.Buffer
		for {
			line, err := br.ReadString('\n')
			frame.WriteString(line)
			if err != nil {
				return
			}
			if line == "\n" {
				c.frames <- frame.String()
				frame.Reset()
			}
		}
	}()
	n.t.Cleanup(c.close)
	return c, 200
}

func (c *client) close() {
	c.once.Do(func() {
		c.cancel()
		_ = c.res.Body.Close()
	})
}

// next returns the next frame (an event block including its blank line), failing after 10s.
func (c *client) next() string {
	c.t.Helper()
	select {
	case f, ok := <-c.frames:
		if !ok {
			c.t.Fatal("the stream ended")
		}
		return f
	case <-time.After(10 * time.Second):
		c.t.Fatal("no frame within 10s")
	}
	return ""
}

// ended waits for the end of the stream; frames before it are returned.
func (c *client) ended(within time.Duration) []string {
	c.t.Helper()
	var rest []string
	timer := time.After(within)
	for {
		select {
		case f, ok := <-c.frames:
			if !ok {
				return rest
			}
			rest = append(rest, f)
		case <-timer:
			c.t.Fatalf("the stream did not end within %v (frames %q)", within, rest)
		}
	}
}

// greeting reads the retry field and the subscribed event, and returns the latter.
func (c *client) greeting() string {
	c.t.Helper()
	if f := c.next(); f != "retry: 3000\n\n" {
		c.t.Fatalf("first frame %q, want the retry field", f)
	}
	return c.next()
}

// pushID reads a push event's id; it fails the test on another frame.
func pushID(t *testing.T, frame string) int64 {
	t.Helper()
	if !strings.HasPrefix(frame, "id: ") || strings.Contains(frame, "event: reset") {
		t.Fatalf("not a push: %q", frame)
	}
	id, err := strconv.ParseInt(strings.TrimPrefix(strings.SplitN(frame, "\n", 2)[0], "id: "), 10, 64)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

// ids reads n pushes and returns their ids.
func (c *client) ids(n int) []int64 {
	c.t.Helper()
	out := make([]int64, n)
	for i := range n {
		out[i] = pushID(c.t, c.next())
	}
	return out
}

// counter reads a counter series from a registry snapshot (0 when absent).
func counter(reg *metrics.Registry, name string, labels ...string) float64 {
	for _, f := range reg.Snapshot().Families {
		if f.Name != name {
			continue
		}
		for _, s := range f.Samples {
			if strings.Join(s.LabelValues, ",") == strings.Join(labels, ",") {
				return s.Value
			}
		}
	}
	return 0
}

// waitFor polls cond for up to 10s.
func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// settle waits until the log holds count entries and the hub has dispatched them, and returns their
// ids in order. The tests name pushes by their place in the log because A2's ids are not 1, 2, 3.
func (n *node) settle(count int) []int64 {
	n.t.Helper()
	var ids []int64
	waitFor(n.t, fmt.Sprintf("%d entries in the log", count), func() bool {
		entries, err := n.p.log.ReadAfter(n.t.Context(), 0, 10_000)
		if err != nil {
			n.t.Fatal(err)
		}
		ids = ids[:0]
		for _, e := range entries {
			ids = append(ids, e.ID)
		}
		return len(ids) >= count
	})
	if len(ids) != count {
		n.t.Fatalf("the log holds %d entries, want %d", len(ids), count)
	}
	waitFor(n.t, "the hub to follow", func() bool { return n.p.hub.lastID() == ids[count-1] })
	return ids
}

// deleteLogUpTo deletes the log's entries with an id up to id, as retention would.
func deleteLogUpTo(t *testing.T, log Log, db *database.DB, id int64) {
	t.Helper()
	switch l := log.(type) {
	case *PostgresLog:
		if _, err := db.Pool().Exec(t.Context(), "DELETE FROM fw_push_log WHERE id <= $1", id); err != nil {
			t.Fatal(err)
		}
	case *RedisStreamLog:
		msgs, err := l.client.XRange(t.Context(), l.stream, "-", intToID(id)).Result()
		if err != nil {
			t.Fatal(err)
		}
		for _, m := range msgs {
			if err := l.client.XDel(t.Context(), l.stream, m.ID).Err(); err != nil {
				t.Fatal(err)
			}
		}
	default:
		t.Fatalf("unknown log driver %T", log)
	}
}
