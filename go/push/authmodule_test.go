package push

import (
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"log/slog"
	"maps"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/auth/authtest"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/locks/dblock"
)

// authNode is a node whose subscribes authenticate with the real auth module: sessions, bearer
// tokens and field workers with DPoP.
type authNode struct {
	*node
	provider *authtest.Provider
	testing  *auth.Testing
	device   *authtest.FieldDevice
}

const authOrigin = "https://app.example"

func startAuthNode(t *testing.T) *authNode {
	t.Helper()
	clock := fwtest.NewClock(begin)
	provider := authtest.Start(clock)
	t.Cleanup(provider.Close)
	provider.Client("app-client", "", authOrigin+"/auth/callback")
	provider.FieldAudience("test")
	env := map[string]string{
		"APP_ENV": "test", "HTTP_HOST": "127.0.0.1", "HTTP_PORT": "0",
		"PUSH_STREAM_SECRET": testSecret, "PUSH_TICKET_KEY": testKeyCurrent, "PUSH_RELAY_INTERVAL": "50ms",
		"AUTH_ISSUER": provider.Issuer(), "AUTH_CLIENT_ID": "app-client", "AUTH_PUBLIC_BASE_URL": authOrigin,
		"AUTH_COOKIE_KEYS": "k1:" + base64.RawURLEncoding.EncodeToString([]byte(strings.Repeat("k", 32))),
	}
	app := fw.New("test", fw.WithEnvironment(maps.Clone(env)), fw.WithConfigDir(t.TempDir()), fw.WithClock(clock),
		fw.WithOutput(io.Discard, io.Discard), fw.WithLogger(slog.New(slog.DiscardHandler)))
	web := httpfw.Declare(app)
	ad := auth.Declare(app, web, auth.DeclareFieldWorkers())
	ld := locks.Declare(app)
	pd := Declare(app)
	pd.Topic("test:order:{id}", Requires("test:order:view"))
	pd.Topic("test:field-worker:{principal}:jobs")
	url := fwtest.NewDatabase(t).URL
	n := &authNode{node: &node{t: t, clock: clock}, provider: provider, device: authtest.NewFieldDevice(authtest.DeviceClock(clock))}
	var srv *httpfw.Server
	run, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		db, err := database.Open(ctx, "main", url)
		if err != nil {
			return nil, err
		}
		b.OnShutdown("db", func(context.Context) error { db.Close(); return nil })
		am, err := ad.Build(ctx, b, auth.SessionStore(auth.MemoryStoreFactory()), auth.Application(fw.MustApplicationCode("test")),
			auth.FieldWorkers(), auth.Permissions(auth.ByRole(map[string][]string{"test:viewer": {"test:order:view"}})))
		if err != nil {
			return nil, err
		}
		if n.testing, err = am.Testing(); err != nil {
			return nil, err
		}
		lk, err := locks.Build(ctx, b, ld, locks.WithStore("main", func(context.Context, *fw.Boot) (locks.Store, error) {
			return dblock.NewStore(db), nil
		}))
		if err != nil {
			return nil, err
		}
		p, err := pd.Build(ctx, b, db, lk, Authenticate(AuthModule(am)))
		if err != nil {
			return nil, err
		}
		n.p, n.db, n.metrics = p, db, b.Metrics()
		srv, err = httpfw.NewServer(b, web, []*httpfw.Routes{am.Routes(), p.Routes()}, httpfw.WithResolver(am.Resolver()))
		return srv, err
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = run.Shutdown() })
	n.run = run
	if srv == nil {
		t.Fatal("no server was built")
	}
	n.base = fmt.Sprintf("http://127.0.0.1:%d", srv.Port())
	return n
}

// field returns the headers of a field worker's subscribe (a field token and its DPoP proof).
func (n *authNode) field(device *authtest.FieldDevice, who auth.FieldPrincipal) []string {
	n.t.Helper()
	h, err := n.testing.FieldRequest(n.provider, device, "POST", "/push/subscriptions", who)
	if err != nil {
		n.t.Fatal(err)
	}
	return []string{"Authorization", h.Get("Authorization"), "DPoP", h.Get("DPoP")}
}

var worker = auth.FieldPrincipal{WorkerID: "fwu_W1", ClientIdentifier: "acme", SessionID: "fws_S1", DeviceID: "fwd_D1"}

func TestFieldWorkerSubscribesWithItsTokenAndDPoP(t *testing.T) {
	t.Parallel()
	n := startAuthNode(t)
	token, m := n.subscribe([]string{"test:field-worker:fwu_W1:jobs", "test:field-worker:fwu_W2:jobs"}, nil, n.field(n.device, worker)...)
	if strings.Join(toStrings(m["granted"]), " ") != "test:field-worker:fwu_W1:jobs" || denials(m["denied"]) != "test:field-worker:fwu_W2:jobs=TOPIC_FORBIDDEN" {
		t.Fatalf("answer %v", m)
	}
	if m["expiresAt"] != begin.Add(10*time.Minute).Format(time.RFC3339) {
		t.Errorf("expiresAt %v, want the field token's expiry", m["expiresAt"])
	}
	claims, _ := n.p.tokens.verify(token, n.clock.Now())
	if claims.Client != "acme" || claims.Principal != "fwu_W1" || claims.Device != "fwd_D1" || claims.Session != "fws_S1" {
		t.Errorf("stream claims %+v", claims)
	}
	c := n.listen(token, "")
	c.greeting()
	n.publish(acme, "test:field-worker:fwu_W1:jobs", "job.assigned", map[string]any{"jobId": "job_1"})
	if id := pushID(t, c.next()); id != 1 {
		t.Fatalf("push %d", id)
	}
	// the platform revokes the login family: the stream ends with auth-expired
	if err := n.p.EndStreams(t.Context(), Holder{Session: "fws_S1"}); err != nil {
		t.Fatal(err)
	}
	if rest := c.ended(10 * time.Second); len(rest) != 1 || rest[0] != authExpiredEvent() {
		t.Fatalf("frames %q", rest)
	}
}

func TestTicketBoundToTheDeviceKey(t *testing.T) {
	t.Parallel()
	n := startAuthNode(t)
	ticket, err := n.p.TicketWith(fw.ContextFor(acme), TicketOptions{ProofKey: n.device.JKT()}, "test:order:{id}")
	if err != nil {
		t.Fatal(err)
	}
	_, m := n.subscribe([]string{"test:order:1"}, []string{ticket}, n.field(n.device, worker)...)
	if strings.Join(toStrings(m["granted"]), " ") != "test:order:1" {
		t.Fatalf("the device that holds the key: %v", m)
	}
	another := authtest.NewFieldDevice(authtest.DeviceClock(n.clock))
	body := `{"topics":["test:order:1"],"tickets":["` + ticket + `"]}`
	a := n.call("POST", "/push/subscriptions", body, n.field(another, worker)...)
	if a.status != 403 || !strings.Contains(a.body, "NO_TOPICS") {
		t.Fatalf("another device: %d %s", a.status, a.body)
	}
	if a := n.call("POST", "/push/subscriptions", body); a.status != 401 {
		t.Errorf("no credentials, no proof: %d %s", a.status, a.body)
	}
}

func TestSessionAndBearerThroughTheAuthModule(t *testing.T) {
	t.Parallel()
	n := startAuthNode(t)
	cookie, err := n.testing.Cookie(t.Context(), auth.ClientUser("prn_1", "clt_1", "acme", "test:viewer"))
	if err != nil {
		t.Fatal(err)
	}
	_, m := n.subscribe([]string{"test:order:1"}, nil, "Cookie", cookie, "Sec-Fetch-Site", "same-origin")
	if strings.Join(toStrings(m["granted"]), " ") != "test:order:1" {
		t.Fatalf("session: %v", m)
	}
	if a := n.call("POST", "/push/subscriptions", `{"topics":["test:order:1"]}`, "Cookie", cookie, "Sec-Fetch-Site", "cross-site"); a.status != 403 {
		t.Errorf("a cross-site subscribe with the session: %d %s", a.status, a.body)
	}
	if a := n.call("POST", "/push/subscriptions", `{"topics":["test:order:1"]}`, "Authorization", "Bearer not-a-token"); a.status != 401 {
		t.Errorf("a bad bearer token: %d %s", a.status, a.body)
	}
	ticket, _ := n.p.Ticket(fw.ContextFor(acme), "test:order:{id}")
	if a := n.call("POST", "/push/subscriptions", `{"topics":["test:order:1"],"tickets":["`+ticket+`"]}`, "Authorization", "Bearer not-a-token"); a.status != 401 {
		t.Errorf("a bad bearer token with a good ticket: %d %s", a.status, a.body)
	}
	if a := n.call("POST", "/push/subscriptions", `{"topics":["test:order:1"],"tickets":["`+ticket+`"]}`); a.status != 200 {
		t.Errorf("a ticket alone: %d %s", a.status, a.body)
	}
}
