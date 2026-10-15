package flowcatalyst_test

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json/v2"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

const (
	fakeClientID     = "fake-client"
	fakeClientSecret = "fake-secret"
	servicePrincipal = "prn_0FAKEAPPSVC01"
)

var (
	syncPath     = regexp.MustCompile(`^/api/applications/([^/]+)/([a-z-]+)/sync$`)
	instancePath = regexp.MustCompile(`^/api/scheduled-jobs/instances/([^/]+)/(complete|log)$`)
)

// recorded is one request the fake platform received.
type recorded struct {
	method string
	path   string
	query  string
	header http.Header
	body   string
}

func (r recorded) json(t testing.TB) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal([]byte(r.body), &m); err != nil {
		t.Fatalf("body of %s %s is not a JSON object: %v\n%s", r.method, r.path, err, r.body)
	}
	if m == nil {
		m = map[string]any{}
	}
	return m
}

// fakePlatform is an in-process stand-in for the platform: a client-credentials token endpoint,
// the application sync endpoints and the scheduled-job instance callbacks. Tokens are JWTs whose
// sub and type claims can be set; the signature is a placeholder. Every request is recorded.
type fakePlatform struct {
	t   testing.TB
	srv *httptest.Server

	mu          sync.Mutex
	reqs        []recorded
	tokens      int
	issued      map[string]bool
	tokenSub    string
	tokenType   string
	hasSub      bool
	hasType     bool
	opaque      bool
	tokenStatus int
	canned      map[string]cannedAnswer
	failing     map[string]*failing
	expiresIn   int
}

type cannedAnswer struct {
	status int
	body   string
}

type failing struct {
	status    int
	remaining atomic.Int32
}

func newFakePlatform(t testing.TB) *fakePlatform {
	f := &fakePlatform{
		t: t, issued: map[string]bool{}, tokenSub: servicePrincipal, tokenType: "SERVICE", hasSub: true, hasType: true,
		canned: map[string]cannedAnswer{}, failing: map[string]*failing{}, expiresIn: 3600,
	}
	f.srv = httptest.NewServer(http.HandlerFunc(f.handle))
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakePlatform) baseURL() string { return f.srv.URL }

func (f *fakePlatform) requests() []recorded {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]recorded(nil), f.reqs...)
}

func (f *fakePlatform) tokenRequests() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.tokens
}

func (f *fakePlatform) clear() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.reqs, f.tokens = nil, 0
}

// setToken sets the claims of the tokens issued from now on; an empty sub or type is left out.
func (f *fakePlatform) setToken(sub, typ string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.tokenSub, f.tokenType, f.hasSub, f.hasType, f.opaque, f.tokenStatus = sub, typ, sub != "", typ != "", false, 0
}

func (f *fakePlatform) setOpaque() { f.mu.Lock(); f.opaque = true; f.mu.Unlock() }

func (f *fakePlatform) failTokens(status int) { f.mu.Lock(); f.tokenStatus = status; f.mu.Unlock() }

// respond sets the answer for a sync resource (roles, event-types, ...).
func (f *fakePlatform) respond(resource string, status int, body string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.canned[resource] = cannedAnswer{status, body}
}

// failInstanceCalls makes the next n calls of an instance callback ("complete" or "log") answer
// with status.
func (f *fakePlatform) failInstanceCalls(kind string, status, n int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	fl := &failing{status: status}
	fl.remaining.Store(int32(n)) //nolint:gosec // a small test count
	f.failing[kind] = fl
}

func (f *fakePlatform) calls(kind string) []recorded {
	out := []recorded{}
	for _, r := range f.requests() {
		if m := instancePath.FindStringSubmatch(r.path); m != nil && m[2] == kind {
			out = append(out, r)
		}
	}
	return out
}

func (f *fakePlatform) syncs() []recorded {
	out := []recorded{}
	for _, r := range f.requests() {
		if syncPath.MatchString(r.path) {
			out = append(out, r)
		}
	}
	return out
}

func (f *fakePlatform) syncedResources() []string {
	out := []string{}
	for _, r := range f.syncs() {
		out = append(out, syncPath.FindStringSubmatch(r.path)[2])
	}
	return out
}

func (f *fakePlatform) syncOf(resource string) (recorded, bool) {
	for _, r := range f.syncs() {
		if syncPath.FindStringSubmatch(r.path)[2] == resource {
			return r, true
		}
	}
	return recorded{}, false
}

func jwt(claims map[string]any) string {
	enc := base64.RawURLEncoding
	h := enc.EncodeToString([]byte(`{"alg":"none","typ":"JWT"}`))
	p, _ := json.Marshal(claims)
	return h + "." + enc.EncodeToString(p) + "." + enc.EncodeToString([]byte("placeholder-signature"))
}

func (f *fakePlatform) handle(w http.ResponseWriter, r *http.Request) {
	b, _ := io.ReadAll(r.Body)
	rec := recorded{method: r.Method, path: r.URL.Path, query: r.URL.RawQuery, header: r.Header.Clone(), body: string(b)}
	f.mu.Lock()
	f.reqs = append(f.reqs, rec)
	f.mu.Unlock()
	switch {
	case r.URL.Path == "/oauth/token":
		f.token(w, string(b))
	case syncPath.MatchString(r.URL.Path):
		f.sync(w, r, rec)
	case instancePath.MatchString(r.URL.Path):
		f.instance(w, r, rec)
	default:
		http.NotFound(w, r)
	}
}

func (f *fakePlatform) token(w http.ResponseWriter, body string) {
	form, _ := url.ParseQuery(body)
	f.mu.Lock()
	status, opaque := f.tokenStatus, f.opaque
	sub, typ, hasSub, hasType := f.tokenSub, f.tokenType, f.hasSub, f.hasType
	expires := f.expiresIn
	f.mu.Unlock()
	if status != 0 {
		http.Error(w, `{"error":"server_error"}`, status)
		return
	}
	if form.Get("client_id") != fakeClientID || form.Get("client_secret") != fakeClientSecret {
		http.Error(w, `{"error":"invalid_client"}`, http.StatusUnauthorized)
		return
	}
	f.mu.Lock()
	f.tokens++
	n := f.tokens
	f.mu.Unlock()
	var tok string
	if opaque {
		tok = fmt.Sprintf("opaque-token-%d", n)
	} else {
		claims := map[string]any{"iss": "fake", "exp": 4102444800, "jti": n}
		if hasSub {
			claims["sub"] = sub
		}
		if hasType {
			claims["type"] = typ
		}
		tok = jwt(claims)
	}
	f.mu.Lock()
	f.issued[tok] = true
	f.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	_ = json.MarshalWrite(w, map[string]any{"access_token": tok, "token_type": "Bearer", "expires_in": expires})
}

func (f *fakePlatform) authorised(r *http.Request) bool {
	tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.issued[tok]
}

func (f *fakePlatform) sync(w http.ResponseWriter, r *http.Request, rec recorded) {
	if !f.authorised(r) {
		http.Error(w, `{"error":"UNAUTHENTICATED"}`, http.StatusUnauthorized)
		return
	}
	m := syncPath.FindStringSubmatch(r.URL.Path)
	resource := m[2]
	f.mu.Lock()
	c, ok := f.canned[resource]
	f.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	if ok {
		w.WriteHeader(c.status)
		_, _ = io.WriteString(w, c.body)
		return
	}
	var body map[string]any
	_ = json.Unmarshal([]byte(rec.body), &body)
	codes := []string{}
	for _, list := range body {
		items, isList := list.([]any)
		if !isList {
			continue
		}
		for _, it := range items {
			obj, isObj := it.(map[string]any)
			if !isObj {
				continue
			}
			for _, key := range []string{"code", "name", "slug"} {
				if s, isString := obj[key].(string); isString {
					codes = append(codes, s)
					break
				}
			}
		}
	}
	if resource == "scheduled-jobs" {
		_ = json.MarshalWrite(w, map[string]any{"applicationCode": m[1], "created": codes, "updated": []string{}, "archived": []string{}})
		return
	}
	_ = json.MarshalWrite(w, map[string]any{"applicationCode": m[1], "created": len(codes), "updated": 0, "deleted": 0, "syncedCodes": codes})
}

func (f *fakePlatform) instance(w http.ResponseWriter, r *http.Request, _ recorded) {
	if !f.authorised(r) {
		http.Error(w, `{"error":"UNAUTHENTICATED"}`, http.StatusUnauthorized)
		return
	}
	kind := instancePath.FindStringSubmatch(r.URL.Path)[2]
	f.mu.Lock()
	fl := f.failing[kind]
	f.mu.Unlock()
	if fl != nil && fl.remaining.Add(-1) >= 0 {
		http.Error(w, `{"error":"UNAVAILABLE"}`, fl.status)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// --- logs --------------------------------------------------------------------------------------

type logCapture struct {
	mu    sync.Mutex
	lines []string
}

func (l *logCapture) Enabled(context.Context, slog.Level) bool { return true }
func (l *logCapture) Handle(_ context.Context, r slog.Record) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.lines = append(l.lines, r.Level.String()+" "+r.Message)
	return nil
}
func (l *logCapture) WithAttrs([]slog.Attr) slog.Handler { return l }
func (l *logCapture) WithGroup(string) slog.Handler      { return l }

func (l *logCapture) all() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return strings.Join(l.lines, "\n")
}

func (l *logCapture) has(substr string) bool { return strings.Contains(l.all(), substr) }

// --- signing -----------------------------------------------------------------------------------

// signature is hex(HMAC-SHA256(secret, timestamp + body)), computed here independently of the
// package under test.
func signature(secret, timestamp, body string) string {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte(timestamp))
	m.Write([]byte(body))
	return hex.EncodeToString(m.Sum(nil))
}
