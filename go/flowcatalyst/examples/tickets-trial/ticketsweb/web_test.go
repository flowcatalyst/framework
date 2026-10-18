package ticketsweb

import (
	"encoding/json/v2"
	"fmt"
	"io"
	"maps"
	"net"
	"net/http"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/auth/authtest"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
)

// site is the whole web application started on a real port, with the database session store and a test
// identity provider (the Java tests' TicketsWeb with AuthTesting).
type site struct {
	t        *testing.T
	base     string
	run      *fw.Running
	built    *Built
	provider *authtest.Provider
}

type siteOptions struct {
	env    map[string]string
	deps   Deps
	clock  fw.Clock
	tuning *Tuning
}

func startWeb(t *testing.T, o siteOptions) *site {
	t.Helper()
	port := reservePort(t)
	origin := fmt.Sprintf("http://127.0.0.1:%d", port)
	provider := authtest.Start(fw.SystemClock())
	t.Cleanup(provider.Close)
	provider.Client("tickets-web", "", origin+"/auth/callback")
	env := map[string]string{
		"APP_ENV":                          "test",
		"DATABASE_INSTANCES_MAIN_URL":      fwtest.NewDatabase(t).URL,
		"DATABASE_INSTANCES_MAIN_USERNAME": "postgres",
		"DATABASE_INSTANCES_MAIN_PASSWORD": "postgres",
		"HTTP_HOST":                        "127.0.0.1",
		"HTTP_PORT":                        fmt.Sprint(port),
		"AUTH_ISSUER":                      provider.Issuer(),
		"AUTH_CLIENT_ID":                   "tickets-web",
		"AUTH_PUBLIC_BASE_URL":             origin,
	}
	maps.Copy(env, o.env)
	opts := []fw.Option{fw.WithEnvironment(env), fw.WithConfigDir(ConfigDir), quiet()}
	if o.clock != nil {
		opts = append(opts, fw.WithClock(o.clock))
	}
	app := NewApp(opts...)
	s := &site{t: t, base: origin, provider: provider}
	deps := o.deps
	deps.Tuning = o.tuning
	deps.Served = func(b *Built) { s.built = b }
	run, err := app.Start(t.Context(), BuildWeb(Declare(app), deps))
	if err != nil {
		t.Fatal(err)
	}
	if s.built == nil {
		t.Fatal("no server was built")
	}
	s.run = run
	t.Cleanup(func() { _ = run.Shutdown() })
	return s
}

func reservePort(t *testing.T) int {
	t.Helper()
	l, err := (&net.ListenConfig{}).Listen(t.Context(), "tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = l.Close() }()
	addr, ok := l.Addr().(*net.TCPAddr)
	if !ok {
		t.Fatal("not a TCP address")
	}
	return addr.Port
}

// person signs a user of a client in through the real session store and returns the Cookie header.
func (s *site) person(client, who string, roles ...string) string {
	s.t.Helper()
	testing, err := s.built.Auth.Testing()
	if err != nil {
		s.t.Fatal(err)
	}
	c, err := testing.Cookie(s.t.Context(), auth.ClientUser(who, "clt_"+client, client, roles...))
	if err != nil {
		s.t.Fatal(err)
	}
	return c
}

func (s *site) requester(client, who string) string {
	return s.person(client, who, "tickets-trial:requester")
}

func (s *site) agent(client string) string {
	return s.person(client, "agent_"+client, "tickets-trial:agent")
}

func (s *site) lead(client string) string {
	return s.person(client, "lead_"+client, "tickets-trial:lead")
}

type answer struct {
	status int
	header http.Header
	body   string
}

func (a answer) json(t *testing.T) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal([]byte(a.body), &m); err != nil {
		t.Fatalf("not a JSON object: %s", a.body)
	}
	return m
}

func (s *site) call(method, path, body string, headers ...string) answer {
	s.t.Helper()
	var rd io.Reader
	if body != "" {
		rd = strings.NewReader(body)
	}
	req, err := http.NewRequestWithContext(s.t.Context(), method, s.base+path, rd)
	if err != nil {
		s.t.Fatal(err)
	}
	for i := 0; i+1 < len(headers); i += 2 {
		req.Header.Set(headers[i], headers[i+1])
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		s.t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return answer{res.StatusCode, res.Header, string(b)}
}

func (s *site) get(path, cookie string) answer {
	s.t.Helper()
	return s.call("GET", path, "", "Cookie", cookie, "Accept", "application/json", "Sec-Fetch-Mode", "cors")
}

func (s *site) send(method, path, cookie, body string) answer {
	s.t.Helper()
	return s.call(method, path, body, "Cookie", cookie, "Sec-Fetch-Site", "same-origin", "Content-Type", "application/json")
}

func (s *site) post(path, cookie, body string) answer {
	s.t.Helper()
	return s.send("POST", path, cookie, body)
}

func (s *site) put(path, cookie, body string) answer {
	s.t.Helper()
	return s.send("PUT", path, cookie, body)
}

// open opens a ticket as the person and returns its id.
func (s *site) open(cookie, subject, priority string) string {
	s.t.Helper()
	a := s.post("/api/tickets", cookie, `{"subject":"`+subject+`","description":"d","priority":"`+priority+`"}`)
	if a.status != 201 {
		s.t.Fatalf("open: %d %s", a.status, a.body)
	}
	return a.json(s.t)["id"].(string) //nolint:forcetypeassert // a ticket view has a string id
}
