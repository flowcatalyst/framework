// Package fakeplatform is an in-process stand-in for the FlowCatalyst platform, for the tests of an
// application and the browser end-to-end launcher (the Go counterpart of the Java FakePlatform): a
// client-credentials token endpoint whose tokens name a service principal, and GET, POST and DELETE
// routes the test sets answers for. Every request is recorded. Nothing here reaches a real platform,
// and its tokens are placeholders no real platform would accept.
package fakeplatform

import (
	"encoding/base64"
	"encoding/json/v2"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"regexp"
	"strings"
	"sync"
)

// The credentials the fake accepts, and the service principal its tokens name.
const (
	// ClientID is the client id the token endpoint accepts.
	ClientID = "fake-client"
	// ClientSecret is the client secret the token endpoint accepts.
	ClientSecret = "fake-secret"
	// ServicePrincipal is the sub claim of the tokens it issues.
	ServicePrincipal = "prn_0FAKEAPPSVC01"
)

// Recorded is one request the fake received.
type Recorded struct {
	Method string
	Path   string
	Query  string
	Header http.Header
	Body   string
}

// JSON parses the body as a JSON object (nil when it is not one).
func (r Recorded) JSON() map[string]any {
	var m map[string]any
	_ = json.Unmarshal([]byte(r.Body), &m)
	return m
}

// Reply is an answer a route gives.
type Reply struct {
	Status int
	Body   string
}

type route struct {
	method  string
	path    *regexp.Regexp
	handler func(Recorded) Reply
}

// Platform is the fake. Create it with [Start] and close it when done.
type Platform struct {
	srv *httptest.Server

	mu     sync.Mutex
	reqs   []Recorded
	tokens int
	issued map[string]bool
	canned map[string]Reply // "METHOD path"
	routes []route
}

// Start starts the fake on a free port of the loopback interface.
func Start() *Platform {
	p := &Platform{issued: map[string]bool{}, canned: map[string]Reply{}}
	p.srv = httptest.NewServer(http.HandlerFunc(p.handle))
	return p
}

// BaseURL is the address to give the application as flowcatalyst.base-url.
func (p *Platform) BaseURL() string { return p.srv.URL }

// Close stops the fake, dropping connections that are still open.
func (p *Platform) Close() {
	p.srv.CloseClientConnections() // a call still in flight must not hold the shutdown
	p.srv.Close()
}

// Requests returns every request received so far, oldest first.
func (p *Platform) Requests() []Recorded {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]Recorded(nil), p.reqs...)
}

// TokenRequests is how many tokens were issued.
func (p *Platform) TokenRequests() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.tokens
}

// Clear forgets the recorded requests.
func (p *Platform) Clear() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.reqs, p.tokens = nil, 0
}

// RespondGet sets the answer to GET path (a literal path without the query).
func (p *Platform) RespondGet(path string, status int, body string) {
	p.RespondTo("GET", path, status, body)
}

// RespondTo sets the answer to a request of method and path.
func (p *Platform) RespondTo(method, path string, status int, body string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.canned[method+" "+path] = Reply{status, body}
}

// RouteMatching routes a method and a path matching the regular expression (the whole path) to a
// handler. A canned answer for the same request wins.
func (p *Platform) RouteMatching(method, pathRegex string, handler func(Recorded) Reply) {
	re := regexp.MustCompile("^" + pathRegex + "$")
	p.mu.Lock()
	defer p.mu.Unlock()
	p.routes = append(p.routes, route{method: method, path: re, handler: handler})
}

func (p *Platform) handle(w http.ResponseWriter, r *http.Request) {
	b, _ := io.ReadAll(r.Body)
	rec := Recorded{Method: r.Method, Path: r.URL.Path, Query: r.URL.RawQuery, Header: r.Header.Clone(), Body: string(b)}
	p.mu.Lock()
	p.reqs = append(p.reqs, rec)
	p.mu.Unlock()
	if r.URL.Path == "/oauth/token" {
		p.token(w, string(b))
		return
	}
	if !p.authorised(r) {
		http.Error(w, `{"error":"UNAUTHENTICATED"}`, http.StatusUnauthorized)
		return
	}
	p.mu.Lock()
	canned, hasCanned := p.canned[r.Method+" "+r.URL.Path]
	var handler func(Recorded) Reply
	for _, rt := range p.routes {
		if rt.method == r.Method && rt.path.MatchString(r.URL.Path) {
			handler = rt.handler
			break
		}
	}
	p.mu.Unlock()
	switch {
	case hasCanned:
		write(w, canned)
	case handler != nil:
		write(w, handler(rec))
	default:
		http.NotFound(w, r)
	}
}

func write(w http.ResponseWriter, r Reply) {
	if r.Body != "" {
		w.Header().Set("Content-Type", "application/json")
	}
	w.WriteHeader(r.Status)
	_, _ = io.WriteString(w, r.Body)
}

func (p *Platform) token(w http.ResponseWriter, body string) {
	form, _ := url.ParseQuery(body)
	if form.Get("client_id") != ClientID || form.Get("client_secret") != ClientSecret {
		http.Error(w, `{"error":"invalid_client"}`, http.StatusUnauthorized)
		return
	}
	p.mu.Lock()
	p.tokens++
	n := p.tokens
	p.mu.Unlock()
	enc := base64.RawURLEncoding
	claims, _ := json.Marshal(map[string]any{"iss": "fake", "exp": 4102444800, "jti": n, "sub": ServicePrincipal, "type": "SERVICE"})
	tok := enc.EncodeToString([]byte(`{"alg":"none","typ":"JWT"}`)) + "." + enc.EncodeToString(claims) + "." + enc.EncodeToString([]byte("placeholder-signature"))
	p.mu.Lock()
	p.issued[tok] = true
	p.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	_ = json.MarshalWrite(w, map[string]any{"access_token": tok, "token_type": "Bearer", "expires_in": 3600})
}

func (p *Platform) authorised(r *http.Request) bool {
	tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.issued[tok]
}

// String names the fake for logs.
func (p *Platform) String() string { return fmt.Sprintf("fakeplatform(%s)", p.srv.URL) }
