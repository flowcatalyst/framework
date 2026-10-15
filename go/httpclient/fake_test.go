package httpclient_test

import (
	"io"
	"maps"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"
)

// recorded is one request the fake server saw.
type recorded struct {
	method     string
	requestURI string // the request line's target, as sent (raw)
	path       string
	rawQuery   string
	header     http.Header
	body       string
}

// answer is what the fake server says.
type answer struct {
	status  int
	headers map[string]string
	body    []byte
	delay   time.Duration
	chunked bool
}

func ok(body string) answer               { return answer{status: 200, body: []byte(body)} }
func status(code int, body string) answer { return answer{status: code, body: []byte(body)} }
func (a answer) with(k, v string) answer {
	h := map[string]string{}
	maps.Copy(h, a.headers)
	h[k] = v
	a.headers = h
	return a
}
func (a answer) delayed(d time.Duration) answer { a.delay = d; return a }

type script func(r recorded, n int) answer

type fakeServer struct {
	t      *testing.T
	srv    *httptest.Server
	mu     sync.Mutex
	routes map[string]script
	reqs   []recorded
	counts map[string]int
}

func newFake(t *testing.T) *fakeServer {
	t.Helper()
	f := &fakeServer{t: t, routes: map[string]script{}, counts: map[string]int{}}
	f.srv = httptest.NewServer(http.HandlerFunc(f.handle))
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeServer) on(path string, a answer) {
	f.onScript(path, func(recorded, int) answer { return a })
}

func (f *fakeServer) onScript(path string, s script) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.routes[path] = s
}

func (f *fakeServer) handle(w http.ResponseWriter, r *http.Request) {
	b, _ := io.ReadAll(r.Body)
	rec := recorded{method: r.Method, requestURI: r.RequestURI, path: r.URL.Path, rawQuery: r.URL.RawQuery, header: r.Header.Clone(), body: string(b)}
	// routes are matched on the raw path, so a test can name an encoded path
	raw := r.URL.EscapedPath()
	f.mu.Lock()
	f.reqs = append(f.reqs, rec)
	f.counts[raw]++
	n := f.counts[raw]
	s := f.routes[raw]
	f.mu.Unlock()
	if s == nil {
		http.NotFound(w, r)
		return
	}
	a := s(rec, n)
	if a.delay > 0 {
		select {
		case <-time.After(a.delay):
		case <-r.Context().Done():
			return
		}
	}
	for k, v := range a.headers {
		w.Header().Set(k, v)
	}
	if !a.chunked {
		w.Header().Set("Content-Length", itoa(len(a.body)))
	}
	w.WriteHeader(a.status)
	if a.chunked {
		fl, _ := w.(http.Flusher)
		for i := 0; i < len(a.body); i += 100 {
			end := min(i+100, len(a.body))
			_, _ = w.Write(a.body[i:end])
			if fl != nil {
				fl.Flush()
			}
		}
		return
	}
	_, _ = w.Write(a.body)
}

func (f *fakeServer) requests() []recorded {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]recorded(nil), f.reqs...)
}

func (f *fakeServer) count(path string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.counts[path]
}

func (f *fakeServer) base(path string) *url.URL {
	u, err := url.Parse(f.srv.URL + path)
	if err != nil {
		f.t.Fatal(err)
	}
	return u
}

func itoa(n int) string {
	var sb strings.Builder
	if n == 0 {
		return "0"
	}
	var d []byte
	for ; n > 0; n /= 10 {
		d = append([]byte{byte('0' + n%10)}, d...)
	}
	sb.Write(d)
	return sb.String()
}
