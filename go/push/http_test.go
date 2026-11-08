package push

import (
	"bytes"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
)

var user = []string{"X-Client", "acme", "X-Principal", "prn_1", "X-Permissions", "test:order:view"}

func TestSubscribeAnswers(t *testing.T) {
	t.Parallel()
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{env: map[string]string{"PUSH_MAX_TOPICS": "5"}})
	ticket, err := n.p.Ticket(fw.ContextFor(acme), "test:order:{id}")
	if err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		name, body string
		headers    []string
		status     int
		code       string
	}{
		{"malformed body", `{"topics":`, user, 400, "VALIDATION"},
		{"unknown member", `{"topics":["test:order:1"],"x":1}`, user, 400, "VALIDATION"},
		{"no topics", `{"topics":[]}`, user, 400, "VALIDATION"},
		{"invalid topic", `{"topics":["Test:order"]}`, user, 400, "VALIDATION"},
		{"too many topics", `{"topics":["test:a:1","test:a:2","test:a:3","test:a:4","test:a:5","test:a:6"]}`, user, 400, "VALIDATION"},
		{"malformed ticket", `{"topics":["test:order:1"],"tickets":["not.a"]}`, user, 400, "VALIDATION"},
		{"bad credentials", `{"topics":["test:order:1"]}`, []string{"X-Fail", "1"}, 401, "UNAUTHENTICATED"},
		{"bad credentials with a good ticket", `{"topics":["test:order:1"],"tickets":["` + ticket + `"]}`, []string{"X-Fail", "1"}, 401, "UNAUTHENTICATED"},
		{"no credentials, no ticket", `{"topics":["test:public"]}`, nil, 401, "UNAUTHENTICATED"},
		{"nothing granted", `{"topics":["test:order:1"]}`, []string{"X-Client", "acme"}, 403, "NO_TOPICS"},
		{"another application's topic", `{"topics":["other:order:1"]}`, user, 403, "NO_TOPICS"},
	}
	for _, c := range cases {
		a := n.call("POST", "/push/subscriptions", c.body, c.headers...)
		if a.status != c.status || a.code() != c.code {
			t.Errorf("%s: %d %s, want %d %s", c.name, a.status, a.body, c.status, c.code)
		}
	}

	// granted and denied together: a rule, a ticket, a forbidden topic, a broken ticket's topic
	broken := ticket[:len(ticket)-4] + "AAAA"
	other, _ := n.p.Ticket(fw.ContextFor(acme), "test:user:{id}")
	a := n.call("POST", "/push/subscriptions",
		`{"topics":["test:user:prn_1","test:order:7","test:user:prn_2","test:client:acme:news","test:user:prn_1"],"tickets":["`+broken+`"]}`, user...)
	if a.status != 200 {
		t.Fatalf("%d %s", a.status, a.body)
	}
	m := a.json(t)
	if g := strings.Join(toStrings(m["granted"]), " "); g != "test:user:prn_1 test:order:7" {
		t.Errorf("granted %s", g)
	}
	if d := denials(m["denied"]); d != "test:user:prn_2=TOPIC_FORBIDDEN test:client:acme:news=TOPIC_FORBIDDEN" {
		t.Errorf("denied %s", d)
	}
	if m["expiresAt"] != "2026-11-08T13:00:00Z" {
		t.Errorf("expiresAt %v, want now + push.stream.max-lifetime", m["expiresAt"])
	}
	// a ticket with a bad signature: its topic is denied TICKET_INVALID, not TOPIC_FORBIDDEN
	a = n.call("POST", "/push/subscriptions", `{"topics":["test:order:1","test:user:x"],"tickets":["`+broken+`","`+other+`"]}`)
	if m = a.json(t); a.status != 200 || denials(m["denied"]) != "test:order:1=TICKET_INVALID" {
		t.Errorf("tickets only: %d %s", a.status, a.body)
	}
}

func toStrings(v any) []string {
	var out []string
	for _, x := range v.([]any) {
		out = append(out, x.(string))
	}
	return out
}

func denials(v any) string {
	var out []string
	for _, x := range v.([]any) {
		d := x.(map[string]any)
		out = append(out, d["topic"].(string)+"="+d["code"].(string))
	}
	return strings.Join(out, " ")
}

func TestStreamTokenAnswers(t *testing.T) {
	t.Parallel()
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{})
	token, _ := n.subscribe([]string{"test:order:1"}, nil, user...)
	payload, sig, _ := strings.Cut(token, ".")
	forged, _ := streamTokens{secret: []byte("another secret of at least thirty-two characters")}.sign(streamClaims{Client: "_platform", Topics: []string{"test:order:1"}, Exp: begin.Add(time.Hour).Unix()})
	for name, st := range map[string]string{
		"missing": "", "garbage": "abc", "tampered payload": payload + "x." + sig, "tampered mac": payload + "." + sig[:len(sig)-2] + "AA",
		"another secret": forged,
	} {
		a := n.call("GET", "/push/stream?st="+st, "")
		if a.status != 401 || a.code() != "STREAM_TOKEN_INVALID" {
			t.Errorf("%s: %d %s", name, a.status, a.body)
		}
	}
	n.clock.Advance(time.Hour)
	if a := n.call("GET", "/push/stream?st="+token, ""); a.status != 401 || a.code() != "STREAM_TOKEN_EXPIRED" {
		t.Errorf("expired: %d %s", a.status, a.body)
	}
}

func TestKeysServeTheCurrentAndThePreviousKey(t *testing.T) {
	t.Parallel()
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{})
	a := n.call("GET", "/push/keys", "")
	want := readVector(t, "tickets.json")["jwks"]
	if a.status != 200 || a.header.Get("Content-Type") != "application/json" || !sameJSON(t, a.body, want) {
		t.Fatalf("%d %s %s", a.status, a.header.Get("Content-Type"), a.body)
	}
}

func TestStreamLimits(t *testing.T) {
	t.Parallel()
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{env: map[string]string{"PUSH_MAX_STREAMS_PER_PRINCIPAL": "1", "PUSH_MAX_STREAMS": "2"}})
	token, _ := n.subscribe([]string{"test:order:1"}, nil, user...)
	first := n.listen(token, "")
	first.greeting()
	if a := n.call("POST", "/push/subscriptions", `{"topics":["test:order:1"]}`, user...); a.status != 429 || a.code() != "PUSH_LIMIT" {
		t.Errorf("subscribe over the principal's limit: %d %s", a.status, a.body)
	}
	if a := n.call("GET", "/push/stream?st="+token, ""); a.status != 429 || a.code() != "PUSH_LIMIT" {
		t.Errorf("a second stream on the token: %d %s", a.status, a.body)
	}
	other, _ := n.subscribe([]string{"test:order:1"}, nil, "X-Client", "acme", "X-Principal", "prn_2", "X-Permissions", "test:order:view")
	n.listen(other, "").greeting()
	if a := n.call("POST", "/push/subscriptions", `{"topics":["test:order:1"]}`, "X-Client", "acme", "X-Principal", "prn_3", "X-Permissions", "test:order:view"); a.status != 429 {
		t.Errorf("subscribe over the instance's limit: %d %s", a.status, a.body)
	}
	first.close()
	waitFor(t, "the stream to close", func() bool { return n.p.hub.openCount() == 1 })
	if a := n.call("POST", "/push/subscriptions", `{"topics":["test:order:1"]}`, user...); a.status != 200 {
		t.Errorf("after the stream closed: %d %s", a.status, a.body)
	}
}

// syncBuffer is a log destination the server's goroutines write to.
type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

func TestTheStreamTokenIsNeverLogged(t *testing.T) {
	t.Parallel()
	logs := &syncBuffer{}
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{logs: logs})
	token, _ := n.subscribe([]string{"test:order:1"}, nil, user...)
	c := n.listen(token, "")
	c.greeting()
	n.call("GET", "/push/stream?st="+token+"x", "")
	c.close()
	waitFor(t, "the access log lines", func() bool { return strings.Count(logs.String(), "route=/push/stream") == 2 })
	_, sig, _ := strings.Cut(token, ".")
	if strings.Contains(logs.String(), sig) || strings.Contains(logs.String(), "st=") {
		t.Fatalf("the logs carry the stream token:\n%s", logs.String())
	}
}

func TestDrainEndsStreamsAndRefusesSubscribes(t *testing.T) {
	t.Parallel()
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{})
	c := orderStream(t, n, "")
	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = n.run.Shutdown()
	}()
	if rest := c.ended(10 * time.Second); len(rest) != 0 {
		t.Errorf("a drained stream ends without an event, got %q", rest)
	}
	req, err := http.NewRequestWithContext(t.Context(), "POST", n.base+"/push/subscriptions", strings.NewReader(`{"topics":["test:order:1"]}`))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	if res, err := http.DefaultClient.Do(req); err == nil { // an error: the server stopped listening, also a refusal
		res.Body.Close()
		if res.StatusCode != 503 {
			t.Errorf("subscribe while draining: %d, want 503 (or no connection)", res.StatusCode)
		}
	}
	<-done
	if v := counter(n.metrics, "fw_push_streams_ended_total", "drain"); v != 1 {
		t.Errorf("ended drain %v, want 1", v)
	}
}

func TestRuleDeclarationRefusesAnOpenRule(t *testing.T) {
	t.Parallel()
	_, err := tryNode(t, fwtest.NewDatabase(t).URL, nodeConfig{declare: func(d *Declared) {
		d.Topic("test:order:{id}")
		d.Topic("other:user:{principal}")
		d.Topic("test:bad seg:{principal}")
	}})
	if err == nil {
		t.Fatal("started")
	}
	for _, want := range []string{"test:order:{id}: " + ErrOpenRule.Error(), "push rule other:user:{principal}: a topic starts with the application's code", "a pattern segment"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("missing %q in %v", want, err)
		}
	}
}

func TestTicketsOverHTTP(t *testing.T) {
	t.Parallel()
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{})
	// an anonymous subscribe with a ticket: the stream's client is the ticket's
	ticket, err := n.p.Ticket(fw.ContextFor(acme).WithPrincipal(fw.MustPrincipalID("prn_9")), "test:order:{id}", "test:public")
	if err != nil {
		t.Fatal(err)
	}
	token, m := n.subscribe([]string{"test:order:1", "test:public"}, []string{ticket})
	if strings.Join(toStrings(m["granted"]), " ") != "test:order:1 test:public" {
		t.Fatalf("%v", m)
	}
	claims, _ := n.p.tokens.verify(token, n.clock.Now())
	if claims.Client != "acme" || claims.Principal != "prn_9" {
		t.Errorf("claims %+v", claims)
	}
	c := n.listen(token, "")
	c.greeting()
	n.publish(fw.MustClientID("other"), "test:order:1", "order.updated", map[string]any{})
	n.publish(acme, "test:order:1", "order.updated", map[string]any{})
	if id := pushID(t, c.next()); id != 2 {
		t.Errorf("ticket stream got %d, want acme's push 2", id)
	}
	// a ticket of another client than the authenticated one grants nothing
	a := n.call("POST", "/push/subscriptions", `{"topics":["test:order:1"],"tickets":["`+ticket+`"]}`, "X-Client", "other")
	if a.status != 403 || a.code() != "NO_TOPICS" {
		t.Errorf("client mismatch: %d %s", a.status, a.body)
	}
	// a ticket bound to a key needs a request that proved it
	bound, _ := n.p.TicketWith(fw.ContextFor(acme), TicketOptions{ProofKey: "jkt-1"}, "test:order:{id}")
	for key, want := range map[string]int{"": 403, "jkt-2": 403, "jkt-1": 200} {
		a := n.call("POST", "/push/subscriptions", `{"topics":["test:order:1"],"tickets":["`+bound+`"]}`, "X-Client", "acme", "X-Proof-Key", key)
		if a.status != want {
			t.Errorf("proof key %q: %d %s", key, a.status, a.body)
		}
	}
	if _, err := n.p.Ticket(fw.ContextFor(acme), "other:order:{id}"); err == nil {
		t.Error("minted a ticket for another application's topics")
	}
}
