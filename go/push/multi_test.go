package push

import (
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json/v2"
	"errors"
	"fmt"
	"net/http"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// Multi-application hosts, the client member, Handler, Drain, PublishBatch (push contract 17.16).

var globex = fw.MustClientID("globex")

// world is the providers of a multi-application host, changed by the test while the host runs.
type world struct {
	mu     sync.Mutex
	rules  map[string][]Rule
	keys   map[string]map[string]ed25519.PublicKey
	limits map[string]AppLimits
}

func newWorld() *world {
	return &world{rules: map[string][]Rule{}, keys: map[string]map[string]ed25519.PublicKey{}, limits: map[string]AppLimits{}}
}

func (w *world) Rules(app string) []Rule {
	w.mu.Lock()
	defer w.mu.Unlock()
	return slices.Clone(w.rules[app])
}

func (w *world) PublicKey(iss, kid string) (ed25519.PublicKey, bool) {
	w.mu.Lock()
	defer w.mu.Unlock()
	k, ok := w.keys[iss][kid]
	return k, ok
}

func (w *world) AppLimits(app string) AppLimits {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.limits[app]
}

func (w *world) setRules(app string, rules ...Rule) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.rules[app] = rules
}

func (w *world) setKey(app, kid string, pub ed25519.PublicKey) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.keys[app] == nil {
		w.keys[app] = map[string]ed25519.PublicKey{}
	}
	if pub == nil {
		delete(w.keys[app], kid)
		return
	}
	w.keys[app][kid] = pub
}

func (w *world) setLimits(app string, l AppLimits) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.limits[app] = l
}

// addApp registers an application's ticket key (a seed of the one byte) and returns its signer.
func (w *world) addApp(t *testing.T, app, kid string, seed byte) *ticketKeys {
	t.Helper()
	k, err := parseTicketKeys(kid + ":" + base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{seed}, ed25519.SeedSize)))
	if err != nil {
		t.Fatal(err)
	}
	w.setKey(app, kid, k.keys[0].public)
	return k
}

// mint signs a ticket of issuer iss for client, as the application would.
func mint(t *testing.T, k *ticketKeys, iss, client string, topics ...string) string {
	t.Helper()
	s, err := k.sign(ticketClaims{
		Iss: iss, Aud: TicketAudience, Client: client, Topics: topics,
		Iat: begin.Unix(), Exp: begin.Add(TicketTTL).Unix(),
	})
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func mustRule(t *testing.T, pattern string, opts ...RuleOption) Rule {
	t.Helper()
	r, err := NewRule(pattern, opts...)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func mustPublic(t *testing.T, pattern string) Rule {
	t.Helper()
	r, err := NewPublicRule(pattern)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

// multi is the nodeConfig of a multi-application host on the world.
func (w *world) multi(extra ...Option) nodeConfig {
	return nodeConfig{
		declare:     func(*Declared) {},
		declareOpts: []DeclareOption{OptionalTicketKey()},
		opts:        append([]Option{MultiApplication(w, w, AppLimitsFrom(w))}, extra...),
	}
}

// call posts a subscribe body.
func (n *node) post(body map[string]any, headers ...string) answer {
	n.t.Helper()
	b, _ := json.Marshal(body)
	return n.call("POST", "/push/subscriptions", string(b), headers...)
}

func (n *node) streamClaims(token string) streamClaims {
	n.t.Helper()
	c, code := n.p.tokens.verify(token, n.clock.Now())
	if code != "" {
		n.t.Fatalf("stream token: %s", code)
	}
	return c
}

var alphaUser = []string{"X-Client", "acme", "X-Principal", "prn_1", "X-Permissions", "alpha:order:view"}

func TestMultiAppRulesPerApplication(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, b backend) {
		w := newWorld()
		w.setRules("alpha", mustRule(t, "alpha:order:{id}", Requires("alpha:order:view")))
		w.setRules("beta", mustRule(t, "beta:user:{principal}"), mustPublic(t, "beta:public"))
		n := b.start(t, w.multi())

		a := n.post(map[string]any{"topics": []string{
			"alpha:order:1", "beta:user:prn_1", "beta:user:prn_2", "beta:order:1", "alpha:user:prn_1", "gamma:thing:1", "beta:public",
		}}, alphaUser...)
		if a.status != 200 {
			t.Fatalf("%d %s", a.status, a.body)
		}
		m := a.json(t)
		if g := strings.Join(toStrings(m["granted"]), " "); g != "alpha:order:1 beta:user:prn_1 beta:public" {
			t.Errorf("granted %s", g)
		}
		// a rule of one application never grants a topic of another; an unknown application is
		// denied, not an error for the others
		if d := denials(m["denied"]); d != "beta:user:prn_2=TOPIC_FORBIDDEN beta:order:1=TOPIC_FORBIDDEN alpha:user:prn_1=TOPIC_FORBIDDEN gamma:thing:1=TOPIC_FORBIDDEN" {
			t.Errorf("denied %s", d)
		}

		token := m["streamToken"].(string)
		c := n.listen(token, "")
		c.greeting()
		n.publish(acme, "alpha:order:1", "order.updated", map[string]any{"v": 1})
		n.publish(acme, "beta:user:prn_1", "user.updated", map[string]any{"v": 2})
		n.publish(acme, "gamma:thing:1", "thing.updated", map[string]any{"v": 3}) // any application publishes
		ids := c.ids(2)
		if ids[0] >= ids[1] {
			t.Errorf("ids %v", ids)
		}

		// the providers change while the host runs: the next subscribe sees it
		w.setRules("gamma", mustPublic(t, "gamma:thing:{id}"))
		w.setRules("alpha")
		a = n.post(map[string]any{"topics": []string{"alpha:order:1", "gamma:thing:1"}}, alphaUser...)
		if a.status != 200 {
			t.Fatalf("%d %s", a.status, a.body)
		}
		m = a.json(t)
		if g := strings.Join(toStrings(m["granted"]), " "); g != "gamma:thing:1" {
			t.Errorf("after the change, granted %s", g)
		}
		if d := denials(m["denied"]); d != "alpha:order:1=TOPIC_FORBIDDEN" {
			t.Errorf("after the change, denied %s", d)
		}
	})
}

func TestMultiAppHostRulesAndProvidersAreChecked(t *testing.T) {
	t.Parallel()
	w := newWorld()
	url := fwtest.NewDatabase(t).URL
	cfg := w.multi()
	cfg.declare = func(d *Declared) { d.Topic("test:order:{id}", Requires("test:order:view")) }
	if _, err := tryNode(t, url, cfg); err == nil || !strings.Contains(err.Error(), "RuleProvider") {
		t.Errorf("rules declared on a multi-application host: %v", err)
	}
	cfg = w.multi(MultiApplication(nil, nil))
	if _, err := tryNode(t, url, cfg); err == nil || !strings.Contains(err.Error(), "needs a RuleProvider") {
		t.Errorf("a multi-application host without providers: %v", err)
	}
}

func TestMultiAppTickets(t *testing.T) {
	t.Parallel()
	w := newWorld()
	alpha := w.addApp(t, "alpha", "a1", 1)
	beta := w.addApp(t, "beta", "b1", 2)
	n := startNode(t, fwtest.NewDatabase(t).URL, w.multi())

	tA := mint(t, alpha, "alpha", "acme", "alpha:order:{id}")
	tB := mint(t, beta, "beta", "acme", "beta:chat:{room}")
	body := func(tickets ...string) map[string]any {
		return map[string]any{"topics": []string{"alpha:order:7", "beta:chat:x"}, "tickets": tickets}
	}
	check := func(name string, a answer, granted, denied string) {
		t.Helper()
		if a.status != 200 {
			t.Errorf("%s: %d %s", name, a.status, a.body)
			return
		}
		m := a.json(t)
		if g := strings.Join(toStrings(m["granted"]), " "); g != granted {
			t.Errorf("%s: granted %q, want %q", name, g, granted)
		}
		if d := denials(m["denied"]); d != denied {
			t.Errorf("%s: denied %q, want %q", name, d, denied)
		}
	}

	// tickets from two issuers compose in one subscribe, with no credentials
	a := n.post(body(tA, tB))
	check("two issuers", a, "alpha:order:7 beta:chat:x", "")
	if c := n.streamClaims(a.json(t)["streamToken"].(string)); c.Client != "acme" {
		t.Errorf("stream client %q", c.Client)
	}

	// alpha's ticket naming beta's topic: the whole ticket grants nothing, beta's topic is denied
	cross := mint(t, alpha, "alpha", "acme", "beta:chat:{room}")
	check("a ticket for another application's topic", n.post(body(tA, cross)), "alpha:order:7", "beta:chat:x=TICKET_INVALID")
	alone := n.post(map[string]any{"topics": []string{"beta:chat:x"}, "tickets": []string{cross, tA}})
	if alone.status != 403 || alone.code() != "NO_TOPICS" {
		t.Errorf("only a cross ticket for the topic: %d %s", alone.status, alone.body)
	}

	// alpha's issuer with beta's key, an unknown issuer, an unknown kid: each grants nothing
	forged := mint(t, beta, "alpha", "acme", "alpha:order:{id}")
	check("beta's key for alpha", n.post(body(forged, tB)), "beta:chat:x", "alpha:order:7=TICKET_INVALID")
	gamma := w.addApp(t, "gamma", "g1", 3)
	w.setKey("gamma", "g1", nil) // gamma signs, but the host knows no key of it
	check("an unknown issuer", n.post(body(mint(t, gamma, "gamma", "acme", "alpha:order:{id}"), tB)), "beta:chat:x", "alpha:order:7=TICKET_INVALID")
	unknownKid := w.addApp(t, "alpha", "a9", 9)
	w.setKey("alpha", "a9", nil)
	check("an unknown kid", n.post(body(mint(t, unknownKid, "alpha", "acme", "alpha:order:{id}"), tB)), "beta:chat:x", "alpha:order:7=TICKET_INVALID")

	// one client for the stream: the first valid ticket names it, a ticket for another client
	// grants nothing
	tBglobex := mint(t, beta, "beta", "globex", "beta:chat:{room}")
	a = n.post(body(tA, tBglobex))
	check("tickets of two clients", a, "alpha:order:7", "beta:chat:x=TICKET_INVALID")
	if c := n.streamClaims(a.json(t)["streamToken"].(string)); c.Client != "acme" {
		t.Errorf("stream client %q", c.Client)
	}
	a = n.post(body(tBglobex, tA))
	check("tickets of two clients, the other first", a, "beta:chat:x", "alpha:order:7=TICKET_INVALID")
	if c := n.streamClaims(a.json(t)["streamToken"].(string)); c.Client != "globex" {
		t.Errorf("stream client %q", c.Client)
	}
	// credentials fix the client: acme's credentials, a ticket for globex
	creds := []string{"X-Client", "acme", "X-Principal", "prn_1"}
	check("credentials of one client, a ticket of another", n.post(body(tA, tBglobex), creds...), "alpha:order:7", "beta:chat:x=TICKET_INVALID")

	// the body names the client: the matching ticket's, else 401
	a = n.post(map[string]any{"topics": []string{"alpha:order:7", "beta:chat:x"}, "tickets": []string{tA, tBglobex}, "client": "globex"})
	check("client named, tickets only", a, "beta:chat:x", "alpha:order:7=TICKET_INVALID")
	if a := n.post(map[string]any{"topics": []string{"alpha:order:7"}, "tickets": []string{tA}, "client": "initech"}); a.status != 401 {
		t.Errorf("client named that no ticket names: %d %s", a.status, a.body)
	}

	// the resolver changes while the host runs: a rotated-out key stops granting at once
	w.setKey("alpha", "a1", nil)
	check("the key was removed", n.post(body(tA, tB)), "beta:chat:x", "alpha:order:7=TICKET_INVALID")
	w.setKey("alpha", "a1", alpha.keys[0].public)
	check("the key is back", n.post(body(tA, tB)), "alpha:order:7 beta:chat:x", "")
}

func TestMultiAppStreamLimitsPerApplication(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, b backend) {
		w := newWorld()
		w.setRules("alpha", mustRule(t, "alpha:user:{principal}"))
		w.setRules("beta", mustRule(t, "beta:user:{principal}"))
		w.setLimits("alpha", AppLimits{MaxStreams: 2})
		w.setLimits("beta", AppLimits{MaxStreams: 1})
		n := b.start(t, w.multi())
		h := []string{"X-Client", "acme", "X-Principal", "prn_1"}
		limitOf := func(a answer) string {
			t.Helper()
			if a.status != 429 || a.code() != "PUSH_LIMIT" {
				return a.body
			}
			d, _ := a.json(t)["details"].(map[string]any)
			s, _ := d["scope"].(string)
			app, _ := d["application"].(string)
			return s + ":" + app
		}

		spare, _ := n.subscribe([]string{"alpha:user:prn_1"}, nil, h...) // a token, listened to later
		both, _ := n.subscribe([]string{"alpha:user:prn_1", "beta:user:prn_1"}, nil, h...)
		c1 := n.listen(both, "")
		c1.greeting()

		// the stream spans alpha and beta, so it counts against both
		if got := limitOf(n.post(map[string]any{"topics": []string{"beta:user:prn_1"}}, h...)); got != "application:beta" {
			t.Errorf("beta is full: %s", got)
		}
		alphaOnly, _ := n.subscribe([]string{"alpha:user:prn_1"}, nil, h...)
		c2 := n.listen(alphaOnly, "")
		c2.greeting()
		if got := limitOf(n.post(map[string]any{"topics": []string{"alpha:user:prn_1"}}, h...)); got != "application:alpha" {
			t.Errorf("alpha is full: %s", got)
		}
		// a stream spanning both is refused for the one that is full
		if got := limitOf(n.post(map[string]any{"topics": []string{"alpha:user:prn_1", "beta:user:prn_1"}}, h...)); got != "application:alpha" {
			t.Errorf("spanning both: %s", got)
		}
		// the limit is also checked when the stream opens: a token from before the limit was reached
		if c, status := n.tryListen(spare, ""); c != nil || status != 429 {
			t.Errorf("listen over alpha's limit: %d", status)
		}

		// closing the spanning stream frees both applications
		c1.close()
		waitFor(t, "the spanning stream to close", func() bool { return n.p.hub.openCount() == 1 })
		if a := n.post(map[string]any{"topics": []string{"beta:user:prn_1"}}, h...); a.status != 200 {
			t.Errorf("beta after the close: %d %s", a.status, a.body)
		}
		betaOnly, _ := n.subscribe([]string{"beta:user:prn_1"}, nil, h...)
		n.listen(betaOnly, "").greeting()
		if got := limitOf(n.post(map[string]any{"topics": []string{"beta:user:prn_1"}}, h...)); got != "application:beta" {
			t.Errorf("beta is full again: %s", got)
		}
		// the provider's limit is read for every subscribe: raising it admits at once
		w.setLimits("beta", AppLimits{MaxStreams: 2})
		if a := n.post(map[string]any{"topics": []string{"beta:user:prn_1"}}, h...); a.status != 200 {
			t.Errorf("beta after the raise: %d %s", a.status, a.body)
		}
		// the default applies to an application the provider has no limit for
		m := &multiApp{rules: w, keys: w, limits: w, defaults: AppLimits{MaxStreams: DefaultAppMaxStreams}}
		if l := m.limitsOf("unlisted").MaxStreams; l != DefaultAppMaxStreams {
			t.Errorf("default %d", l)
		}
	})
}

func TestHostStreamLimitBodyIsUnchanged(t *testing.T) {
	t.Parallel()
	w := newWorld()
	w.setRules("alpha", mustRule(t, "alpha:user:{principal}"))
	cfg := w.multi()
	cfg.env = map[string]string{"PUSH_MAX_STREAMS_PER_PRINCIPAL": "1"}
	n := startNode(t, fwtest.NewDatabase(t).URL, cfg)
	h := []string{"X-Client", "acme", "X-Principal", "prn_1"}
	token, _ := n.subscribe([]string{"alpha:user:prn_1"}, nil, h...)
	n.listen(token, "").greeting()
	a := n.post(map[string]any{"topics": []string{"alpha:user:prn_1"}}, h...)
	if a.status != 429 || a.code() != "PUSH_LIMIT" || strings.Contains(a.body, "details") {
		t.Errorf("%d %s", a.status, a.body)
	}
}

// multiClientAuth is an authenticator whose credential (X-Clients) can act for several clients.
var multiClientAuth = ClientAuthenticatorFunc(func(r *http.Request, client string) (Credentials, bool, error) {
	list := strings.Split(r.Header.Get("X-Clients"), ",")
	if r.Header.Get("X-Clients") == "" {
		return Credentials{}, false, nil
	}
	switch {
	case client == "" && len(list) != 1:
		return Credentials{}, true, httpfw.Validation("the credential has several clients; name one in client")
	case client == "":
		client = list[0]
	case !slices.Contains(list, client):
		return Credentials{}, true, httpfw.Forbidden("CLIENT_FORBIDDEN", "the credential cannot use that client")
	}
	cx := fw.ContextFor(fw.MustClientID(client)).WithPrincipal(fw.MustPrincipalID("prn_1")).WithPermissions(fw.MustPermission("test:order:view"))
	return Credentials{Context: cx}, true, nil
})

func TestSubscribeClientMember(t *testing.T) {
	t.Parallel()
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{})
	topic := []string{"test:order:1"}
	cases := []struct {
		name   string
		body   map[string]any
		status int
		code   string
	}{
		{"absent", map[string]any{"topics": topic}, 200, ""},
		{"equal to the context's client", map[string]any{"topics": topic, "client": "acme"}, 200, ""},
		{"another client", map[string]any{"topics": topic, "client": "globex"}, 403, "CLIENT_FORBIDDEN"},
		{"the platform client", map[string]any{"topics": topic, "client": "_platform"}, 403, "CLIENT_FORBIDDEN"},
		{"not a client", map[string]any{"topics": topic, "client": "a b"}, 400, "VALIDATION"},
		{"empty", map[string]any{"topics": topic, "client": ""}, 400, "VALIDATION"},
	}
	for _, c := range cases {
		a := n.post(c.body, user...)
		if a.status != c.status || a.code() != c.code {
			t.Errorf("%s: %d %s, want %d %s", c.name, a.status, a.body, c.status, c.code)
		}
	}
	// no credentials: the client named must be a valid ticket's
	acmeTicket, _ := n.p.Ticket(fw.ContextFor(acme), "test:order:{id}")
	if a := n.post(map[string]any{"topics": topic, "tickets": []string{acmeTicket}, "client": "acme"}); a.status != 200 {
		t.Errorf("tickets, client equal: %d %s", a.status, a.body)
	}
	if a := n.post(map[string]any{"topics": topic, "tickets": []string{acmeTicket}, "client": "globex"}); a.status != 401 {
		t.Errorf("tickets, another client: %d %s", a.status, a.body)
	}
}

func TestClientAuthenticatorReceivesTheClient(t *testing.T) {
	t.Parallel()
	cfg := nodeConfig{opts: []Option{Authenticate(multiClientAuth)}}
	n := startNode(t, fwtest.NewDatabase(t).URL, cfg)
	topic := []string{"test:order:1"}
	two := []string{"X-Clients", "acme,globex"}
	cases := []struct {
		name    string
		body    map[string]any
		headers []string
		status  int
		code    string
		client  string
	}{
		{"several clients, none named", map[string]any{"topics": topic}, two, 400, "VALIDATION", ""},
		{"several clients, one named", map[string]any{"topics": topic, "client": "globex"}, two, 200, "", "globex"},
		{"a client the credential cannot use", map[string]any{"topics": topic, "client": "initech"}, two, 403, "CLIENT_FORBIDDEN", ""},
		{"one client, none named", map[string]any{"topics": topic}, []string{"X-Clients", "acme"}, 200, "", "acme"},
	}
	for _, c := range cases {
		a := n.post(c.body, c.headers...)
		if a.status != c.status || a.code() != c.code {
			t.Errorf("%s: %d %s, want %d %s", c.name, a.status, a.body, c.status, c.code)
			continue
		}
		if c.client != "" {
			if got := n.streamClaims(a.json(t)["streamToken"].(string)).Client; got != c.client {
				t.Errorf("%s: stream client %q, want %q", c.name, got, c.client)
			}
		}
	}

	// an authenticator that ignores the client named does not get a stream for another client
	lying := ClientAuthenticatorFunc(func(*http.Request, string) (Credentials, bool, error) {
		cx := fw.ContextFor(acme).WithPrincipal(fw.MustPrincipalID("prn_1")).WithPermissions(fw.MustPermission("test:order:view"))
		return Credentials{Context: cx}, true, nil
	})
	n2 := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{opts: []Option{Authenticate(lying)}})
	if a := n2.post(map[string]any{"topics": topic, "client": "globex"}); a.status != 403 || a.code() != "CLIENT_FORBIDDEN" {
		t.Errorf("an authenticator that ignores the client: %d %s", a.status, a.body)
	}
}

// vectorHeaders turns a vector case's context into the tests' credential headers.
func vectorHeaders(c map[string]any) []string {
	m, ok := c["context"].(map[string]any)
	if !ok {
		return nil
	}
	h := []string{"X-Client", str(m["client"])}
	if p := str(m["principal"]); p != "" {
		h = append(h, "X-Principal", p)
	}
	if k := str(m["proofKey"]); k != "" {
		h = append(h, "X-Proof-Key", k)
	}
	return append(h, "X-Permissions", strings.Join(asList(m["permissions"]), ","))
}

// runTicketVectorOverHTTP posts every case of conformance/push/tickets.json to a host and compares
// the answer.
func runTicketVectorOverHTTP(t *testing.T, n *node, v map[string]any) {
	t.Helper()
	statuses := map[string]int{"VALIDATION": 400, "UNAUTHENTICATED": 401, "NO_TOPICS": 403}
	for _, raw := range v["cases"].([]any) {
		c := raw.(map[string]any)
		name := str(c["name"])
		want := c["expect"].(map[string]any)
		a := n.post(map[string]any{"topics": asList(c["topics"]), "tickets": nonNil(asList(c["tickets"]))}, vectorHeaders(c)...)
		if code := str(want["error"]); code != "" {
			if a.status != statuses[code] || a.code() != code {
				t.Errorf("%s: %d %s, want %s", name, a.status, a.body, code)
			}
			continue
		}
		if a.status != 200 {
			t.Errorf("%s: %d %s", name, a.status, a.body)
			continue
		}
		m := a.json(t)
		gotDenied := append([]any{}, m["denied"].([]any)...)
		claims := n.streamClaims(m["streamToken"].(string))
		principal := any(claims.Principal)
		if claims.Principal == "" {
			principal = nil
		}
		for k, got := range map[string]any{
			"granted": m["granted"], "denied": gotDenied, "streamClient": claims.Client, "principal": principal, "expiresAt": m["expiresAt"],
		} {
			if !sameJSONValue(got, want[k]) {
				t.Errorf("%s: %s %v, want %v", name, k, got, want[k])
			}
		}
	}
	if a := n.call("GET", "/push/keys", ""); a.status != 200 || !sameJSON(t, a.body, v["jwks"]) {
		t.Errorf("GET /push/keys: %d %s", a.status, a.body)
	}
}

func vectorNodeConfig(v map[string]any) nodeConfig {
	return nodeConfig{
		clock:   fwtest.NewClock(time.Unix(int64(v["now"].(float64)), 0).UTC()),
		declare: func(*Declared) {},
		handler: true,
		opts:    []Option{Application(fw.MustApplicationCode(str(v["application"])))},
	}
}

// TestHandlerServesTheTicketVector runs the tickets vector over Pushes.Handler (no httpfw server),
// on a single-application host and on a multi-application host that knows the one application.
func TestHandlerServesTheTicketVector(t *testing.T) {
	t.Parallel()
	v := readVector(t, "tickets.json")
	t.Run("single", func(t *testing.T) {
		t.Parallel()
		runTicketVectorOverHTTP(t, startNode(t, fwtest.NewDatabase(t).URL, vectorNodeConfig(v)), v)
	})
	t.Run("multi", func(t *testing.T) {
		t.Parallel()
		w := newWorld()
		keys := v["keys"].(map[string]any)
		for _, name := range []string{"current", "previous"} {
			k := keys[name].(map[string]any)
			set, err := parseTicketKeys(str(k["kid"]) + ":" + str(k["seed"]))
			if err != nil {
				t.Fatal(err)
			}
			w.setKey(str(v["application"]), str(k["kid"]), set.keys[0].public)
		}
		cfg := vectorNodeConfig(v)
		cfg.opts = append(cfg.opts, MultiApplication(w, w))
		runTicketVectorOverHTTP(t, startNode(t, fwtest.NewDatabase(t).URL, cfg), v)
	})
}

// TestHandlerServesTheWireAndReplayVectors runs the wire bytes and the replay vector over Handler.
func TestHandlerServesTheWireAndReplayVectors(t *testing.T) {
	t.Parallel()
	wire := readVector(t, "wire.json")
	replay := readVector(t, "replay.json")
	logs := &syncBuffer{}
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{handler: true, logs: logs})
	token, _ := n.subscribe([]string{"test:order:ord_1", "test:user:prn_1"}, nil, user...)
	c := n.listen(token, "")
	if f := c.next(); f != str(wire["streamStart"].(map[string]any)["bytes"]) {
		t.Errorf("start %q", f)
	}
	if f := c.next(); f != subscribedEvent([]string{"test:order:ord_1", "test:user:prn_1"}, 0) {
		t.Errorf("subscribed %q", f)
	}
	n.publish(acme, "test:order:ord_1", "order.updated", map[string]any{"orderId": "ord_1", "version": 7})
	if f, want := c.next(), "id: 1\nevent: order.updated\ndata: {\"topic\":\"test:order:ord_1\",\"data\":{\"orderId\":\"ord_1\",\"version\":7}}\n\n"; f != want {
		t.Errorf("push %q, want %q", f, want)
	}
	if h := c.res.Header; h.Get("Content-Type") != "text/event-stream" || h.Get("Cache-Control") != "no-cache" || h.Get("X-Accel-Buffering") != "no" {
		t.Errorf("headers %v", h)
	}
	c.close()
	// the stream token is in the query: the access log never holds it
	waitFor(t, "the access log line", func() bool { return strings.Contains(logs.String(), "route=/push/stream") })
	if _, sig, _ := strings.Cut(token, "."); strings.Contains(logs.String(), sig) || strings.Contains(logs.String(), "st=") {
		t.Errorf("the logs carry the stream token:\n%s", logs.String())
	}

	latest := int64(replay["latest"].(float64))
	for _, raw := range replay["cases"].([]any) {
		rc := raw.(map[string]any)
		t.Run(str(rc["name"]), func(t *testing.T) {
			t.Parallel()
			eachDriver(t, func(t *testing.T, b backend) {
				n := b.start(t, nodeConfig{handler: true, env: map[string]string{"PUSH_RECENT": "0"}, clock: fwtest.NewClock(time.Unix(0, 0).UTC())})
				loadLog(t, n, replay["log"].([]any), int64(replay["trimmed"].(float64)))
				waitFor(t, "the hub to follow the log", func() bool { return n.p.hub.lastID() == latest })
				lastText := ""
				switch l := rc["lastEventId"].(type) {
				case float64:
					lastText = fmt.Sprint(int64(l))
				case string:
					lastText = l
				}
				got := replayThrough(t, n, subscription{topics: asList(rc["topics"]), client: str(rc["client"])}, lastText)
				if !sameJSONValue(got, rc["expect"]) {
					t.Errorf("got %v, want %v", got, rc["expect"])
				}
			})
		})
	}
}

func TestDrainWithoutTheFrameworkServer(t *testing.T) {
	t.Parallel()
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{handler: true})
	token, _ := n.subscribe([]string{"test:order:1"}, nil, user...)
	c1 := n.listen(token, "")
	c1.greeting()
	c2 := orderStream(t, n, "")
	waitFor(t, "two streams", func() bool { return n.p.hub.openCount() == 2 })

	n.p.Drain()
	for _, c := range []*client{c1, c2} {
		if rest := c.ended(10 * time.Second); len(rest) != 0 {
			t.Errorf("a drained stream ends without an event, got %q", rest)
		}
	}
	if got := counter(n.metrics, "fw_push_streams_ended_total", "drain"); got != 2 {
		t.Errorf("ended by drain: %v", got)
	}
	if a := n.call("POST", "/push/subscriptions", `{"topics":["test:order:1"]}`, user...); a.status != 503 || a.code() != "UNAVAILABLE" {
		t.Errorf("subscribe after the drain: %d %s", a.status, a.body)
	}
	if c, status := n.tryListen(token, ""); c != nil || status != 503 {
		t.Errorf("listen after the drain: %d", status)
	}
	n.p.Drain() // twice is harmless
}

func TestShutdownEndsTheStreamsOfAHandlerHost(t *testing.T) {
	t.Parallel()
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{handler: true})
	c := orderStream(t, n, "")
	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = n.run.Shutdown()
	}()
	c.ended(10 * time.Second)
	<-done
}

func TestExternalMigrationsAndOptionalTicketKey(t *testing.T) {
	t.Parallel()
	url := fwtest.NewDatabase(t).URL
	own := startNode(t, url, nodeConfig{})
	if !slices.Contains(own.migrationModules, ModuleID) {
		t.Errorf("the module declares its migration set by default: %v", own.migrationModules)
	}
	host := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{declareOpts: []DeclareOption{ExternalMigrations()}})
	if slices.Contains(host.migrationModules, ModuleID) {
		t.Errorf("ExternalMigrations still declares the set: %v", host.migrationModules)
	}
	// the tables are the host's: a host that created them from the module's file works as any other
	host.publish(acme, "test:order:1", "order.updated", map[string]any{})
	host.settle(1)

	// a multi-application host that mints nothing needs no ticket key
	w := newWorld()
	cfg := w.multi()
	cfg.env = map[string]string{"PUSH_TICKET_KEY": ""}
	nokey, err := tryNode(t, fwtest.NewDatabase(t).URL, cfg)
	if err != nil {
		t.Fatal(err)
	}
	if a := nokey.call("GET", "/push/keys", ""); a.status != 200 || a.body != `{"keys":[]}` {
		t.Errorf("keys: %d %s", a.status, a.body)
	}
	if _, err := nokey.p.Ticket(fw.ContextFor(acme), "alpha:order:{id}"); err == nil {
		t.Error("a host without a key minted a ticket")
	}
	// a single-application host still needs it
	_, err = tryNode(t, fwtest.NewDatabase(t).URL, nodeConfig{declareOpts: []DeclareOption{OptionalTicketKey()}, env: map[string]string{"PUSH_TICKET_KEY": ""}})
	if err == nil || !strings.Contains(err.Error(), "push.ticket.key") {
		t.Errorf("a single-application host without a key: %v", err)
	}
}

func TestPublishBatch(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, b backend) {
		w := newWorld()
		w.setRules("alpha", mustPublic(t, "alpha:feed"))
		w.setRules("beta", mustPublic(t, "beta:feed"))
		w.setLimits("alpha", AppLimits{MaxDataBytes: 64})
		n := b.start(t, w.multi())
		h := []string{"X-Client", "acme"}
		token, _ := n.subscribe([]string{"alpha:feed", "beta:feed"}, nil, h...)
		c := n.listen(token, "")
		c.greeting()
		item := func(client fw.ClientID, topic, typ string, data any) Publication {
			t.Helper()
			p, err := New(topic, typ, data)
			if err != nil {
				t.Fatal(err)
			}
			return Publication{Context: fw.ContextFor(client), Push: p}
		}
		ctx := t.Context()
		big := map[string]any{"s": strings.Repeat("x", 100)}
		sentinel := func(want string) {
			t.Helper()
			n.publish(acme, "alpha:feed", "sentinel", map[string]any{})
			f := c.next()
			if !strings.Contains(f, "event: "+want+"\n") {
				t.Fatalf("expected %s next, got %q", want, f)
			}
		}

		// each push has its own client; the stream of acme hears acme's and the platform's, in order
		if err := n.p.PublishBatchAlone(ctx,
			item(acme, "alpha:feed", "one", map[string]any{}),
			item(globex, "alpha:feed", "two", map[string]any{}),
			item(fw.PlatformClient, "beta:feed", "three", map[string]any{}),
			item(acme, "beta:feed", "four", map[string]any{}),
		); err != nil {
			t.Fatal(err)
		}
		var events []string
		var ids []int64
		for range 3 {
			f := c.next()
			ids = append(ids, pushID(t, f))
			events = append(events, strings.TrimPrefix(strings.SplitN(f, "\n", 3)[1], "event: "))
		}
		if strings.Join(events, " ") != "one three four" || !slices.IsSorted(ids) {
			t.Errorf("events %v ids %v", events, ids)
		}
		n.settle(4) // the log holds all four, globex's too

		// one refused item writes nothing, the valid ones before it included
		err := n.p.PublishBatchAlone(ctx,
			item(acme, "alpha:feed", "never", map[string]any{}),
			item(acme, "alpha:feed", "toolarge", big), // alpha's limit is 64 bytes
			item(acme, "beta:feed", "never", map[string]any{}),
		)
		if e, ok := errors.AsType[*InvalidPushError](err); !ok || e.Reason != "DATA_TOO_LARGE" || !strings.Contains(err.Error(), "item 1") {
			t.Errorf("an oversized item: %v", err)
		}
		// the same data is fine for beta, whose limit is push.max-data-bytes
		if err := n.p.PublishBatchAlone(ctx, item(acme, "beta:feed", "bigfine", big)); err != nil {
			t.Errorf("beta's data: %v", err)
		}
		if f := c.next(); !strings.Contains(f, "event: bigfine\n") {
			t.Errorf("after the refused batch, %q", f)
		}
		if err := n.p.PublishAlone(ctx, fw.ContextFor(acme), item(acme, "alpha:feed", "toolarge", big).Push); err == nil || !strings.Contains(err.Error(), "limit of application alpha") {
			t.Errorf("Publish over alpha's limit: %v", err)
		}
		if err := n.p.PublishBatchAlone(ctx, Publication{Push: item(acme, "beta:feed", "x", map[string]any{}).Push}); err == nil || !strings.Contains(err.Error(), "item 0") {
			t.Errorf("a zero Context: %v", err)
		}

		// a failure in the database rolls the whole batch back: a trigger refuses one topic
		if _, err := n.db.Pool().Exec(ctx, `CREATE FUNCTION refuse_boom() RETURNS trigger AS $$
			BEGIN IF NEW.topic = 'beta:boom' THEN RAISE EXCEPTION 'boom'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql;
			CREATE TRIGGER refuse_boom BEFORE INSERT ON fw_push_outbox FOR EACH ROW EXECUTE FUNCTION refuse_boom()`); err != nil {
			t.Fatal(err)
		}
		err = n.p.PublishBatchAlone(ctx,
			item(acme, "alpha:feed", "never", map[string]any{}),
			item(acme, "beta:boom", "never", map[string]any{}),
		)
		if err == nil || !strings.Contains(err.Error(), "boom") {
			t.Errorf("a row the database refuses: %v", err)
		}

		// inside the caller's transaction: a rollback writes nothing, a commit writes the batch
		tx, err := n.db.Pool().BeginTx(ctx, pgx.TxOptions{})
		if err != nil {
			t.Fatal(err)
		}
		if err := n.p.PublishBatch(ctx, tx, item(acme, "alpha:feed", "never", map[string]any{}), item(acme, "beta:feed", "never", map[string]any{})); err != nil {
			t.Fatal(err)
		}
		if err := tx.Rollback(ctx); err != nil {
			t.Fatal(err)
		}
		if err := n.p.PublishBatchAlone(ctx); err != nil { // empty: nothing
			t.Fatal(err)
		}
		tx, err = n.db.Pool().BeginTx(ctx, pgx.TxOptions{})
		if err != nil {
			t.Fatal(err)
		}
		if err := n.p.PublishBatch(ctx, tx, item(acme, "alpha:feed", "kept1", map[string]any{}), item(acme, "beta:feed", "kept2", map[string]any{})); err != nil {
			t.Fatal(err)
		}
		if err := tx.Commit(ctx); err != nil {
			t.Fatal(err)
		}
		for _, want := range []string{"kept1", "kept2"} {
			if f := c.next(); !strings.Contains(f, "event: "+want+"\n") {
				t.Errorf("want %s, got %q", want, f)
			}
		}
		sentinel("sentinel")
	})
}
