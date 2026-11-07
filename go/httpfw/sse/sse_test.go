package sse_test

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"maps"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/sse"
)

// history is an application's event log: what Replay reads.
var history = []sse.Event{
	{ID: 1, Type: "pick", Data: `{"n":1}`},
	{ID: 2, Type: "pick", Data: `{"n":2}`},
	{ID: 3, Type: "pick", Data: `{"n":3}`},
	{ID: 4, Type: "pick", Data: `{"n":4}`},
	{ID: 5, Type: "pick", Data: `{"n":5}`},
}

// replayFrom reads the history after a cursor, at most limit at a time, and counts the calls.
func replayFrom(events []sse.Event, calls *atomic.Int32) sse.Replay {
	return func(_ context.Context, after int64, limit int) ([]sse.Event, error) {
		calls.Add(1)
		var out []sse.Event
		for _, e := range events {
			if e.ID > after && len(out) < limit {
				out = append(out, e)
			}
		}
		return out, nil
	}
}

type server struct {
	run  *fw.Running
	base string
}

func start(t *testing.T, env map[string]string, handler httpfw.HandlerFunc) server {
	t.Helper()
	e := map[string]string{"APP_ENV": "test", "HTTP_HOST": "127.0.0.1", "HTTP_PORT": "0"}
	maps.Copy(e, env)
	app := fw.New("sse-test", fw.WithEnvironment(e), fw.WithConfigDir(t.TempDir()), fw.WithOutput(io.Discard, io.Discard))
	web := httpfw.Declare(app)
	var srv *httpfw.Server
	run, err := app.Start(t.Context(), func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
		rt := httpfw.NewRoutes("test")
		rt.Handle("GET /stream", handler)
		s, err := httpfw.NewServer(b, web, []*httpfw.Routes{rt})
		srv = s
		return s, err
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = run.Shutdown() })
	if srv == nil {
		t.Fatal("no server was built")
	}
	return server{run: run, base: fmt.Sprintf("http://127.0.0.1:%d", srv.Port())}
}

// frame is one event block of the stream as a client parses it.
type frame struct {
	id, event, data, comment, retry string
}

// client reads a stream frame by frame.
type client struct {
	res    *http.Response
	frames chan frame
	done   chan error
}

func open(t *testing.T, url string, headers ...string) *client {
	t.Helper()
	req, err := http.NewRequestWithContext(t.Context(), http.MethodGet, url, nil)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i+1 < len(headers); i += 2 {
		req.Header.Set(headers[i], headers[i+1])
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	c := &client{res: res, frames: make(chan frame, 64), done: make(chan error, 1)}
	go func() {
		defer res.Body.Close()
		br := bufio.NewReader(res.Body)
		var f frame
		var data []string
		for {
			line, err := br.ReadString('\n')
			if err != nil {
				c.done <- err
				close(c.frames)
				return
			}
			line = strings.TrimSuffix(line, "\n")
			switch {
			case line == "":
				f.data = strings.Join(data, "\n")
				c.frames <- f
				f, data = frame{}, nil
			case strings.HasPrefix(line, ":"):
				f.comment = strings.TrimSpace(line[1:])
			default:
				k, v, _ := strings.Cut(line, ": ")
				switch k {
				case "id":
					f.id = v
				case "event":
					f.event = v
				case "data":
					data = append(data, v)
				case "retry":
					f.retry = v
				}
			}
		}
	}()
	return c
}

func (c *client) next(t *testing.T) frame {
	t.Helper()
	select {
	case f, ok := <-c.frames:
		if !ok {
			t.Fatal("the stream ended")
		}
		return f
	case <-time.After(5 * time.Second):
		t.Fatal("no frame within 5s")
	}
	return frame{}
}

// ended waits for the stream to end and returns the error the body ended with (io.EOF when the
// server ended it cleanly).
func (c *client) ended(t *testing.T, within time.Duration) error {
	t.Helper()
	for {
		select {
		case _, ok := <-c.frames:
			if !ok {
				return <-c.done
			}
		case <-time.After(within):
			t.Fatalf("the stream did not end within %v", within)
		}
	}
}

func TestAReconnectingStreamIsReplayedFromItsLastEventIDThenFollowsLiveEvents(t *testing.T) {
	t.Parallel()
	live := make(chan sse.Event, 8)
	var calls atomic.Int32
	s := start(t, nil, func(w http.ResponseWriter, r *http.Request) error {
		return sse.Serve(w, r, live, sse.Options{Replay: replayFrom(history, &calls), ReplayLimit: 2})
	})
	c := open(t, s.base+"/stream", "Last-Event-ID", "2")
	if h := c.res.Header; h.Get("Content-Type") != "text/event-stream" || h.Get("Cache-Control") != "no-cache" || h.Get("X-Accel-Buffering") != "no" {
		t.Errorf("headers %v", h)
	}
	if f := c.next(t); f.retry != "3000" {
		t.Errorf("first frame %+v, want the retry delay", f)
	}
	// replayed in pages of ReplayLimit until a short page: 3, 4, then 5
	for _, want := range []string{"3", "4", "5"} {
		if f := c.next(t); f.id != want || f.event != "pick" || f.data != `{"n":`+want+`}` {
			t.Fatalf("replay: %+v, want id %s", f, want)
		}
	}
	// an event the replay already sent arrives live too (the subscription began before the replay):
	// it is not sent twice
	live <- history[4]
	live <- sse.Event{ID: 6, Type: "pick", Data: `{"n":6}`}
	if f := c.next(t); f.id != "6" {
		t.Fatalf("live: %+v, want id 6 (5 was already sent)", f)
	}
	if n := calls.Load(); n != 2 {
		t.Errorf("replay called %d times, want 2 (a full page, then a short one)", n)
	}
	// the source ending ends the stream (the client reconnects with its Last-Event-ID)
	close(live)
	if err := c.ended(t, 5*time.Second); !errors.Is(err, io.EOF) {
		t.Errorf("the stream ended with %v", err)
	}
}

func TestTheLastEventIDMayComeAsAQueryParameterAndAStreamWithoutOneStartsLive(t *testing.T) {
	t.Parallel()
	live := make(chan sse.Event, 8)
	var calls atomic.Int32
	s := start(t, nil, func(w http.ResponseWriter, r *http.Request) error {
		ch := live
		if r.URL.RawQuery != "" {
			ch = make(chan sse.Event) // the replayed stream has a source of its own
		}
		return sse.Serve(w, r, ch, sse.Options{Replay: replayFrom(history, &calls)})
	})
	c := open(t, s.base+"/stream?lastEventId=4")
	c.next(t) // retry
	if f := c.next(t); f.id != "5" {
		t.Fatalf("replay from the query: %+v", f)
	}
	fresh := open(t, s.base+"/stream")
	fresh.next(t) // retry
	live <- sse.Event{ID: 9, Data: "line one\nline two"}
	got := fresh.next(t)
	if got.id != "9" || got.event != "" || got.data != "line one\nline two" {
		t.Fatalf("live without a Last-Event-ID: %+v (no replay, one data line per line)", got)
	}
}

func TestAnIdleStreamSendsHeartbeats(t *testing.T) {
	t.Parallel()
	s := start(t, nil, func(w http.ResponseWriter, r *http.Request) error {
		return sse.Serve(w, r, make(chan sse.Event), sse.Options{Heartbeat: 50 * time.Millisecond})
	})
	c := open(t, s.base+"/stream")
	c.next(t) // retry
	if f := c.next(t); f.comment != "ping" || f.id != "" || f.data != "" {
		t.Fatalf("heartbeat: %+v", f)
	}
}

// A stream outlives http.request-timeout (the read deadline is cleared), and ends when shutdown
// begins, so it does not hold the drain for http.shutdown-grace.
func TestAStreamOutlivesTheRequestTimeoutAndEndsWhenTheDrainBegins(t *testing.T) {
	t.Parallel()
	live := make(chan sse.Event, 1)
	s := start(t, map[string]string{"HTTP_REQUEST_TIMEOUT": "200ms", "HTTP_SHUTDOWN_GRACE": "5s"},
		func(w http.ResponseWriter, r *http.Request) error {
			return sse.Serve(w, r, live, sse.Options{Heartbeat: time.Hour})
		})
	c := open(t, s.base+"/stream")
	c.next(t) // retry
	time.Sleep(500 * time.Millisecond)
	live <- sse.Event{ID: 1, Data: "late"}
	if f := c.next(t); f.data != "late" {
		t.Fatalf("after the request timeout: %+v", f)
	}
	began := time.Now()
	if err := s.run.Shutdown(); err != nil {
		t.Fatal(err)
	}
	if took := time.Since(began); took > 2*time.Second {
		t.Errorf("shutdown took %v: the stream held the drain", took)
	}
	if err := c.ended(t, 5*time.Second); !errors.Is(err, io.EOF) {
		t.Errorf("the stream ended with %v, want a clean end", err)
	}
}

func TestAnEventTypeWithALineBreakIsRefused(t *testing.T) {
	t.Parallel()
	live := make(chan sse.Event, 1)
	result := make(chan error, 1)
	s := start(t, nil, func(w http.ResponseWriter, r *http.Request) error {
		err := sse.Serve(w, r, live, sse.Options{})
		result <- err
		return nil
	})
	c := open(t, s.base+"/stream")
	c.next(t)
	live <- sse.Event{ID: 1, Type: "pick\ndata: forged", Data: "x"}
	select {
	case err := <-result:
		if err == nil || !strings.Contains(err.Error(), "line break") {
			t.Fatalf("got %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the event was written: its type would have forged a data field")
	}
}
