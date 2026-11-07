package httpfw_test

import (
	"bufio"
	"context"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// openStream starts a GET whose response stays open, and returns once the first line of the body
// has arrived; the rest of the body (until the server ends it) is read on a goroutine and handed
// over on the returned channel.
func openStream(t *testing.T, url string) <-chan string {
	t.Helper()
	req, err := http.NewRequestWithContext(t.Context(), http.MethodGet, url, nil)
	if err != nil {
		t.Fatal(err)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	br := bufio.NewReader(res.Body)
	first, err := br.ReadString('\n')
	if err != nil {
		t.Fatalf("first line: %v", err)
	}
	rest := make(chan string, 1)
	go func() {
		defer res.Body.Close()
		b, err := io.ReadAll(br)
		if err != nil {
			rest <- first + string(b) + "<error: " + err.Error() + ">"
			return
		}
		rest <- first + string(b)
	}()
	return rest
}

// A long-lived response (a stream) that ends when a BeforeDrain hook tells it to no longer holds
// the drain: shutdown returns at once, not after http.shutdown-grace, and the stream ends cleanly.
func TestABeforeDrainHookEndsAStreamSoItDoesNotHoldTheDrain(t *testing.T) {
	t.Parallel()
	stop := make(chan struct{})
	var hookSawDraining bool
	var srv *httpfw.Server
	s, err := start(t, map[string]string{"HTTP_SHUTDOWN_GRACE": "5s"}, func(b *fw.Boot, rt *httpfw.Routes) {
		rt.Handle("GET /stream", func(w http.ResponseWriter, r *http.Request) error {
			w.Header().Set("Content-Type", "text/plain")
			_, _ = io.WriteString(w, "open\n")
			_ = http.NewResponseController(w).Flush()
			select {
			case <-stop:
				_, _ = io.WriteString(w, "closed by the hook\n")
			case <-r.Context().Done():
			}
			return nil
		})
	})
	if err != nil {
		t.Fatal(err)
	}
	srv = s.server
	srv.BeforeDrain(func(context.Context) {
		hookSawDraining = srv.IsDraining()
		close(stop)
	})
	body := openStream(t, s.base+"/stream")

	began := time.Now()
	if err := s.run.Shutdown(); err != nil {
		t.Fatalf("shutdown: %v", err)
	}
	if took := time.Since(began); took > 2*time.Second {
		t.Errorf("shutdown took %v: the open stream held the drain", took)
	}
	select {
	case got := <-body:
		if got != "open\nclosed by the hook\n" {
			t.Errorf("the stream: %q", got)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the stream never ended")
	}
	if !hookSawDraining {
		t.Error("the hook ran before the server began draining (new requests must already be refused)")
	}
}

// Every request carries the server's drain signal: a handler that watches httpfw.Draining(r) ends when
// shutdown begins, with no hook of its own.
func TestDrainingIsClosedWhenShutdownBegins(t *testing.T) {
	t.Parallel()
	s, err := start(t, map[string]string{"HTTP_SHUTDOWN_GRACE": "5s"}, func(b *fw.Boot, rt *httpfw.Routes) {
		rt.Handle("GET /stream", func(w http.ResponseWriter, r *http.Request) error {
			_, _ = io.WriteString(w, "open\n")
			_ = http.NewResponseController(w).Flush()
			select {
			case <-httpfw.Draining(r):
				_, _ = io.WriteString(w, "draining\n")
			case <-r.Context().Done():
			}
			return nil
		})
	})
	if err != nil {
		t.Fatal(err)
	}
	body := openStream(t, s.base+"/stream")
	began := time.Now()
	_ = s.run.Shutdown()
	if took := time.Since(began); took > 2*time.Second {
		t.Errorf("shutdown took %v", took)
	}
	if got := <-body; !strings.HasSuffix(got, "draining\n") {
		t.Errorf("the stream: %q", got)
	}
	// a dispatcher without a server never drains: the channel is open and not nil
	d := compile(t, nil, func() *httpfw.Routes {
		rt := httpfw.NewRoutes("x")
		rt.Handle("GET /x", func(w http.ResponseWriter, r *http.Request) error {
			select {
			case <-httpfw.Draining(r):
				return jsonBody(w, `"draining"`)
			default:
				return jsonBody(w, `"serving"`)
			}
		})
		return rt
	}())
	if got := do(d, "GET", "/x", ""); got.body != `"serving"` {
		t.Errorf("without a server: %s", got.body)
	}
}
