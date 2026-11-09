package push

import (
	"encoding/json/jsontext"
	"encoding/json/v2"
	"fmt"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/internal/conformance"
	"github.com/flowcatalyst/framework/go/internal/pushrow"
)

// The runner of conformance/push/ (push contract section 13).

func readVector(t *testing.T, name string) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(conformance.ReadFile(t, "push/"+name), &m); err != nil {
		t.Fatalf("%s: %v", name, err)
	}
	return m
}

func sameJSON(t *testing.T, got string, want any) bool {
	t.Helper()
	var g any
	if err := json.Unmarshal([]byte(got), &g); err != nil {
		return false
	}
	gb, _ := json.Marshal(g, json.Deterministic(true))
	wb, _ := json.Marshal(want, json.Deterministic(true))
	return string(gb) == string(wb)
}

func str(v any) string {
	s, _ := v.(string)
	return s
}

func codeOf(err error) string {
	if e, ok := err.(*pushrow.Error); ok { //nolint:errorlint // the checks return it unwrapped
		return e.Reason
	}
	return ""
}

func TestTopicsVector(t *testing.T) {
	t.Parallel()
	v := readVector(t, "topics.json")
	check := func(kind string, cases []any, run func(string) error, lengthKey string, fill func(int) string) {
		for _, raw := range cases {
			c := raw.(map[string]any)
			text := str(c[kind])
			if n, ok := c[lengthKey].(float64); ok {
				text = fill(int(n))
			}
			err := run(text)
			if want := c["accepted"].(bool); want != (err == nil) || (!want && codeOf(err) != str(c["code"])) {
				t.Errorf("%s %.40q: %v, want accepted=%v %v", kind, text, err, want, c["code"])
			}
		}
	}
	check("topic", v["topics"].([]any), CheckTopic, "topicOfLength", func(n int) string { return "fulfil:" + strings.Repeat("x", n-7) })
	check("type", v["types"].([]any), CheckType, "typeOfLength", func(n int) string { return strings.Repeat("a", n) })

	for _, raw := range v["data"].([]any) {
		c := raw.(map[string]any)
		data := []byte(str(c["json"]))
		if n, ok := c["stringOfLength"].(float64); ok {
			data = []byte(`{"s":"` + strings.Repeat("a", int(n)) + `"}`)
		}
		stored, err := pushrow.Encode(jsontext.Value(data), int(c["maxBytes"].(float64)))
		switch {
		case c["code"] != nil:
			if codeOf(err) != str(c["code"]) {
				t.Errorf("data %.40s: %v, want %v", data, err, c["code"])
			}
		case err != nil:
			t.Errorf("data %.40s: %v", data, err)
		case c["stored"] != nil && stored != str(c["stored"]):
			t.Errorf("data %s: stored %s, want %s", data, stored, c["stored"])
		}
	}

	declare := func(r map[string]any) (Rule, error) {
		if r["public"] == true {
			return NewPublicRule(str(r["pattern"]))
		}
		var opts []RuleOption
		for _, p := range asList(r["requires"]) {
			opts = append(opts, Requires(p))
		}
		return NewRule(str(r["pattern"]), opts...)
	}
	for _, raw := range v["patterns"].([]any) {
		c := raw.(map[string]any)
		_, err := declare(c)
		if (err != nil) != c["refused"].(bool) {
			t.Errorf("pattern %s %v: %v, want refused=%v", c["pattern"], c["requires"], err, c["refused"])
		}
	}
	for _, raw := range v["rules"].([]any) {
		c := raw.(map[string]any)
		r, err := declare(c["rule"].(map[string]any))
		if err != nil {
			t.Fatal(err)
		}
		cx := contextOf(c["context"])
		if got := r.Allows(cx, str(c["topic"])); got != c["allowed"].(bool) {
			t.Errorf("rule %v, context %v, topic %s: %v, want %v", c["rule"], c["context"], c["topic"], got, c["allowed"])
		}
	}
}

func asList(v any) []string {
	var out []string
	for _, x := range asAnyList(v) {
		out = append(out, x.(string))
	}
	return out
}

func asAnyList(v any) []any {
	l, _ := v.([]any)
	return l
}

// contextOf builds the Context of a vector ({client, principal, permissions}; nil for none).
func contextOf(v any) fw.Context {
	m, ok := v.(map[string]any)
	if !ok {
		return fw.Context{}
	}
	cx := fw.ContextFor(fw.MustClientID(str(m["client"])))
	if p := str(m["principal"]); p != "" {
		cx = cx.WithPrincipal(fw.MustPrincipalID(p))
	}
	var perms []fw.Permission
	for _, p := range asList(m["permissions"]) {
		perms = append(perms, fw.MustPermission(p))
	}
	return cx.WithPermissions(perms...)
}

func TestTicketsVector(t *testing.T) {
	t.Parallel()
	v := readVector(t, "tickets.json")
	keys := v["keys"].(map[string]any)
	entry := func(name string) string {
		k := keys[name].(map[string]any)
		return str(k["kid"]) + ":" + str(k["seed"])
	}
	set, err := parseTicketKeys(entry("current") + "," + entry("previous"))
	if err != nil {
		t.Fatal(err)
	}
	if jwks, _ := set.jwks(); !sameJSON(t, string(jwks), v["jwks"]) {
		t.Errorf("jwks %s", jwks)
	}
	g := &granter{application: str(v["application"]), keys: set, maxLifetime: time.Duration(v["maxLifetimeSeconds"].(float64)) * time.Second}
	now := time.Unix(int64(v["now"].(float64)), 0).UTC()
	for _, raw := range v["cases"].([]any) {
		c := raw.(map[string]any)
		name := str(c["name"])
		creds, present := Credentials{}, false
		if m, ok := c["context"].(map[string]any); ok {
			present = true
			creds = Credentials{Context: contextOf(m), ProofKey: str(m["proofKey"])}
		}
		d, err := g.decide(asList(c["topics"]), asList(c["tickets"]), creds, present, now)
		want := c["expect"].(map[string]any)
		gotDenied := []any{}
		for _, x := range d.denied {
			gotDenied = append(gotDenied, map[string]any{"topic": x.Topic, "code": x.Code})
		}
		if code := str(want["error"]); code != "" {
			if se, ok := err.(interface{ Code() string }); !ok || se.Code() != code { //nolint:errorlint // decide returns the answer unwrapped
				t.Errorf("%s: %v, want %s", name, err, code)
			}
			if want["denied"] != nil && !sameJSONValue(gotDenied, want["denied"]) {
				t.Errorf("%s: denied %v, want %v", name, gotDenied, want["denied"])
			}
		} else {
			if err != nil {
				t.Errorf("%s: %v", name, err)
				continue
			}
			principal := any(d.sub.principal)
			if d.sub.principal == "" {
				principal = nil
			}
			got := map[string]any{
				"granted": d.granted, "denied": gotDenied, "streamClient": d.sub.client, "principal": principal,
				"expiresAt": d.expires.Format(time.RFC3339),
			}
			for k, w := range got {
				if !sameJSONValue(w, want[k]) {
					t.Errorf("%s: %s %v, want %v", name, k, w, want[k])
				}
			}
		}
		if reasons, ok := want["ticketReasons"].([]any); ok {
			var got []any
			for _, tk := range d.tickets {
				if tk.valid {
					got = append(got, nil)
				} else {
					got = append(got, tk.reason)
				}
			}
			if !sameJSONValue(got, reasons) {
				t.Errorf("%s: ticket reasons %v, want %v", name, got, reasons)
			}
		}
	}
}

func sameJSONValue(a, b any) bool {
	ab, _ := json.Marshal(a, json.Deterministic(true))
	bb, _ := json.Marshal(b, json.Deterministic(true))
	return string(ab) == string(bb)
}

func TestWireVector(t *testing.T) {
	t.Parallel()
	v := readVector(t, "wire.json")
	if got, want := retryField(streamRetry), str(v["streamStart"].(map[string]any)["bytes"]); got != want {
		t.Errorf("start %q, want %q", got, want)
	}
	for _, raw := range v["events"].([]any) {
		c := raw.(map[string]any)
		in := c["input"].(map[string]any)
		var got string
		switch name := str(c["name"]); {
		case strings.HasPrefix(name, "push"):
			got = pushEvent(Entry{ID: int64(in["id"].(float64)), Type: str(in["type"]), Topic: str(in["topic"]), Data: str(in["data"])})
		case strings.HasPrefix(name, "subscribed"):
			got = subscribedEvent(asList(in["granted"]), int64(in["latest"].(float64)))
		case strings.HasPrefix(name, "reset"):
			got = resetEvent(str(in["reason"]), int64(in["latest"].(float64)))
		case name == "auth-expired":
			got = authExpiredEvent()
		default:
			t.Fatalf("unknown event %s", name)
		}
		if want := str(c["bytes"]); got != want {
			t.Errorf("%s: %q, want %q", c["name"], got, want)
		}
	}
}

// TestWireOverHTTP reads a real stream's first frames and a push, and compares their bytes.
func TestWireOverHTTP(t *testing.T) {
	t.Parallel()
	v := readVector(t, "wire.json")
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{})
	token, _ := n.subscribe([]string{"test:order:ord_1", "test:user:prn_1"}, nil, user...)
	c := n.listen(token, "")
	if f := c.next(); f != str(v["streamStart"].(map[string]any)["bytes"]) {
		t.Errorf("start %q", f)
	}
	if f := c.next(); f != subscribedEvent([]string{"test:order:ord_1", "test:user:prn_1"}, 0) {
		t.Errorf("subscribed %q", f)
	}
	n.publish(acme, "test:order:ord_1", "order.updated", jsontext.Value(`{"orderId": "ord_1", "version": 7}`))
	want := "id: 1\nevent: order.updated\ndata: {\"topic\":\"test:order:ord_1\",\"data\":{\"orderId\":\"ord_1\",\"version\":7}}\n\n"
	if f := c.next(); f != want {
		t.Errorf("push %q, want %q", f, want)
	}
	if res := c.res; res.Header.Get("Content-Type") != "text/event-stream" || res.Header.Get("Cache-Control") != "no-cache" || res.Header.Get("X-Accel-Buffering") != "no" {
		t.Errorf("headers %v", res.Header)
	}
}

func TestReplayVector(t *testing.T) {
	t.Parallel()
	v := readVector(t, "replay.json")
	latest := int64(v["latest"].(float64))
	for _, raw := range v["cases"].([]any) {
		c := raw.(map[string]any)
		t.Run(str(c["name"]), func(t *testing.T) {
			t.Parallel()
			eachDriver(t, func(t *testing.T, b backend) {
				n := b.start(t, nodeConfig{env: map[string]string{"PUSH_RECENT": "0"}})
				loadLog(t, n, v["log"].([]any), int64(v["trimmed"].(float64)))
				waitFor(t, "the hub to follow the log", func() bool { return n.p.hub.lastID() == latest })
				if trimmed, _ := n.p.log.Trimmed(t.Context()); trimmed != int64(v["trimmed"].(float64)) {
					t.Fatalf("trimmed %d", trimmed)
				}
				topics := asList(c["topics"])
				// the vector's topics are application fulfil's; the stream token is signed here, as a
				// subscribe would
				sub := subscription{topics: topics, client: str(c["client"])}
				lastText := ""
				switch l := c["lastEventId"].(type) {
				case float64:
					lastText = fmt.Sprint(int64(l))
				case string:
					lastText = l
				}
				got := replayThrough(t, n, sub, lastText)
				if !sameJSONValue(got, c["expect"]) {
					t.Errorf("got %v, want %v", got, c["expect"])
				}
			})
		})
	}
}

// loadLog writes the vector's log rows into the log with their ids; trimmed is the highest id the
// log deleted (A2: the entry is added and deleted, which is what the stream's max-deleted-entry-id
// records).
func loadLog(t *testing.T, n *node, rows []any, trimmed int64) {
	t.Helper()
	rl, redisDriver := n.p.log.(*RedisStreamLog)
	if redisDriver && trimmed > 0 {
		id := intToID(trimmed)
		if err := rl.client.XAdd(t.Context(), &redis.XAddArgs{Stream: rl.stream, ID: id, Values: []any{"outbox", "0"}}).Err(); err != nil {
			t.Fatal(err)
		}
		if err := rl.client.XDel(t.Context(), rl.stream, id).Err(); err != nil {
			t.Fatal(err)
		}
	}
	for _, raw := range rows {
		r := raw.(map[string]any)
		data, _ := json.Marshal(r["data"])
		var client any
		if r["client"] != nil {
			client = r["client"]
		}
		id := int64(r["id"].(float64))
		if redisDriver {
			c, _ := client.(string)
			values := []any{"topic", r["topic"], "type", r["type"], "client", c, "data", string(data), "outbox", "0", "logged_at", fmt.Sprint(begin.UnixMicro())}
			if err := rl.client.XAdd(t.Context(), &redis.XAddArgs{Stream: rl.stream, ID: intToID(id), Values: values}).Err(); err != nil {
				t.Fatal(err)
			}
			continue
		}
		if _, err := n.db.Pool().Exec(t.Context(), `INSERT INTO fw_push_log (id, topic, type, client, data, logged_at) VALUES ($1, $2, $3, $4, $5, $6)`,
			id, r["topic"], r["type"], client, string(data), begin); err != nil {
			t.Fatal(err)
		}
	}
}

// replayThrough opens a stream on the hub and runs the handler's replay decision (6.2 step 2) over
// the real log, returning {"ids": [...]} or {"reset": {"reason", "id"}}.
func replayThrough(t *testing.T, n *node, sub subscription, lastText string) map[string]any {
	t.Helper()
	claims := streamClaims{Client: sub.client, Topics: sub.topics, Exp: n.clock.Now().Add(time.Hour).Unix()}
	token, err := n.p.tokens.sign(claims)
	if err != nil {
		t.Fatal(err)
	}
	c := n.listen(token, lastText)
	c.greeting()
	// a live push for every topic and client ends the replay: the first frame with an id above the
	// log's is live
	for _, topic := range sub.topics {
		n.publish(fw.PlatformClient, topic, "live", map[string]any{})
	}
	ids := []int64{}
	for {
		f := c.next()
		if strings.Contains(f, "event: reset") {
			var id int64
			var reason string
			_, _ = fmt.Sscanf(f, "id: %d", &id)
			for _, r := range []string{resetRetention, resetUnknownID} {
				if strings.Contains(f, `"reason":"`+r+`"`) {
					reason = r
				}
			}
			return map[string]any{"reset": map[string]any{"reason": reason, "id": id}}
		}
		if strings.Contains(f, "event: live") {
			return map[string]any{"ids": ids}
		}
		ids = append(ids, pushID(t, f))
		if !slices.IsSorted(ids) {
			t.Fatalf("out of order %v", ids)
		}
	}
}
