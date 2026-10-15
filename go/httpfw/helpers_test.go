package httpfw_test

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"sync"
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// logSink collects log lines so a test can assert what was (and was not) logged.
type logSink struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (l *logSink) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.buf.Write(p)
}

func (l *logSink) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.buf.String()
}

func (l *logSink) logger() *slog.Logger {
	return slog.New(slog.NewTextHandler(l, &slog.HandlerOptions{Level: slog.LevelDebug}))
}

type result struct {
	status int
	header http.Header
	body   string
}

// compile builds a dispatcher from tables, failing the test on start-up problems.
func compile(t *testing.T, opts []httpfw.Option, tables ...*httpfw.Routes) *httpfw.Dispatcher {
	t.Helper()
	d, err := httpfw.Compile(tables, opts...)
	if err != nil {
		t.Fatalf("compile: %v", err)
	}
	return d
}

// request builds a request as a server hands it over: the target as sent in RequestURI.
func request(method, target, body string, headers ...string) *http.Request {
	r := &http.Request{
		Method: method, RequestURI: target, Header: http.Header{}, Proto: "HTTP/1.1", ProtoMajor: 1, ProtoMinor: 1,
		Host: "example.test", ContentLength: int64(len(body)),
	}
	if u, err := url.ParseRequestURI(target); err == nil {
		r.URL = u
	} else {
		r.URL = &url.URL{Path: target}
	}
	if body == "" {
		r.Body = http.NoBody
		r.ContentLength = 0
	} else {
		r.Body = io.NopCloser(strings.NewReader(body))
	}
	for i := 0; i+1 < len(headers); i += 2 {
		r.Header.Add(headers[i], headers[i+1])
	}
	return r
}

func do(d http.Handler, method, target, body string, headers ...string) result {
	rec := httptest.NewRecorder()
	d.ServeHTTP(rec, request(method, target, body, headers...))
	b, _ := io.ReadAll(rec.Result().Body)
	return result{status: rec.Code, header: rec.Result().Header, body: string(b)}
}

func jsonBody(w http.ResponseWriter, s string) error {
	w.Header().Set("Content-Type", "application/json")
	_, err := io.WriteString(w, s)
	return err
}

// resolverFunc adapts a function to httpfw.ContextResolver.
type resolverFunc func(r *http.Request) (fw.Context, error)

func (f resolverFunc) Resolve(r *http.Request) (fw.Context, error) { return f(r) }

func fixedContext(perms ...string) resolverFunc {
	return func(*http.Request) (fw.Context, error) {
		cx := fw.ContextFor(fw.MustClientID("acme")).WithPrincipal(fw.MustPrincipalID("usr_1"))
		ps := make([]fw.Permission, len(perms))
		for i, p := range perms {
			ps[i] = fw.MustPermission(p)
		}
		return cx.WithPermissions(ps...), nil
	}
}

var _ = context.Background

func newRecorder() *httptest.ResponseRecorder { return httptest.NewRecorder() }

func slogDiscard() *slog.Logger { return slog.New(slog.DiscardHandler) }

func writeText(path, text string) error { return os.WriteFile(path, []byte(text), 0o600) }
