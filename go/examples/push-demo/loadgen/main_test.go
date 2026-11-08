package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestChunkReaderAndReadLine(t *testing.T) {
	t.Parallel()
	body := "5\r\nhello\r\n" + "10\r\n world, and more\r\n" + "0\r\n\r\n"
	got, err := io.ReadAll(&chunkReader{br: bufio.NewReaderSize(strings.NewReader(body), 16)})
	if err != nil || string(got) != "hello world, and more"[:5]+" world, and more" {
		t.Fatalf("got %q, %v", got, err)
	}
	// a line longer than the buffer comes back whole
	long := strings.Repeat("x", 300) + "\nnext\n"
	r := bufio.NewReaderSize(strings.NewReader(long), 16)
	var scratch []byte
	line, scratch, err := readLine(r, scratch)
	if err != nil || len(line) != 301 {
		t.Fatalf("long line: %d, %v", len(line), err)
	}
	line, _, err = readLine(r, scratch)
	if err != nil || string(line) != "next\n" {
		t.Fatalf("next line %q, %v", line, err)
	}
}

func TestParsePushAndHistogram(t *testing.T) {
	t.Parallel()
	seq, ts := parsePush([]byte(`{"topic":"pushdemo:t:1","data":{"seq":42,"ts":1700000000123456789,"pad":""}}`))
	if seq != 42 || ts != 1700000000123456789 {
		t.Fatalf("seq %d ts %d", seq, ts)
	}
	var h hist
	for i := 1; i <= 1000; i++ {
		h.add(int64(i) * 1_000_000) // 1 ms .. 1000 ms
	}
	p50, p99 := float64(h.quantile(0.5))/1e6, float64(h.quantile(0.99))/1e6
	if p50 < 480 || p50 > 540 || p99 < 980 || p99 > 1060 {
		t.Fatalf("p50 %.1f p99 %.1f ms", p50, p99)
	}
	if h.max.Load() != 1_000_000_000 {
		t.Fatalf("max %d", h.max.Load())
	}
}

// fake is a stand-in for the push demo server: it speaks the same wire (chunked SSE).
type fake struct {
	mu      sync.Mutex
	streams map[int]chan string
	nextID  int
}

func (f *fake) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health/ready", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(200) })
	mux.HandleFunc("GET /api/demo/ticket", func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, `{"ticket":"t"}`) })
	mux.HandleFunc("POST /api/push/subscriptions", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"streamToken":"tok","expiresAt":"x","granted":[],"denied":[]}`)
	})
	mux.HandleFunc("GET /api/push/stream", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(200)
		rc := http.NewResponseController(w)
		_, _ = io.WriteString(w, "retry: 3000\n\n")
		_, _ = io.WriteString(w, "event: subscribed\ndata: {\"granted\":[],\"latest\":0}\n\n")
		_ = rc.Flush()
		ch := make(chan string, 64)
		f.mu.Lock()
		f.nextID++
		id := f.nextID
		f.streams[id] = ch
		f.mu.Unlock()
		defer func() {
			f.mu.Lock()
			delete(f.streams, id)
			f.mu.Unlock()
		}()
		for {
			select {
			case <-r.Context().Done():
				return
			case e := <-ch:
				_, _ = io.WriteString(w, e)
				_ = rc.Flush()
			}
		}
	})
	var n int
	var nmu sync.Mutex
	mux.HandleFunc("POST /api/demo/publish", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Items []item `json:"items"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		for _, it := range body.Items {
			nmu.Lock()
			n++
			now := n
			nmu.Unlock()
			ev := fmt.Sprintf("id: %d\nevent: demo.tick\ndata: {\"topic\":%q,\"data\":{\"seq\":%d,\"ts\":%d}}\n\n", now, it.Topic, it.Seq, time.Now().UnixNano())
			f.mu.Lock()
			for _, ch := range f.streams {
				ch <- ev
			}
			f.mu.Unlock()
		}
		_, _ = io.WriteString(w, `{"published":`+strconv.Itoa(len(body.Items))+`,"failed":[]}`)
	})
	return mux
}

func TestFanoutAgainstFakeServer(t *testing.T) {
	t.Parallel()
	f := &fake{streams: map[int]chan string{}}
	srv := httptest.NewServer(f.handler())
	defer srv.Close()
	host, portStr, _ := net.SplitHostPort(strings.TrimPrefix(srv.URL, "http://"))
	port, _ := strconv.Atoi(portStr)
	c := &config{
		mode: "fanout", host: host, port: port, ports: 1, debugPort: 1, streams: 20, topics: 1, rate: 100,
		duration: 2 * time.Second, warmup: 500 * time.Millisecond, settle: time.Second, connectConc: 8,
		retry: 200 * time.Millisecond, track: true, rampStallMax: 10 * time.Second,
	}
	g := newGen(c)
	ctx, cancel := context.WithCancel(t.Context())
	res, err := g.execute(ctx)
	cancel()
	g.wg.Wait()
	if err != nil {
		t.Fatalf("execute: %v (%v)", err, res.Loss)
	}
	if res.Loss["missing"] != 0 || res.Loss["expectedDeliveries"] == 0 || res.Loss["duplicates"] != 0 {
		t.Fatalf("loss %v", res.Loss)
	}
	if res.Loss["expectedDeliveries"] != res.Loss["received"] {
		t.Fatalf("expected %d, received %d", res.Loss["expectedDeliveries"], res.Loss["received"])
	}
}

func TestVerifyFindsAMissingPush(t *testing.T) {
	t.Parallel()
	c := &config{topics: 2, streams: 2}
	g := newGen(c)
	g.acked = make([]uint8, 10)
	for _, s := range []int{1, 2, 3, 4} {
		g.acked[s] = 1
	}
	g.nextSeq = 5
	g.streams = []*stream{{id: 0, seqs: []int32{2, 4}}, {id: 1, seqs: []int32{1}}} // stream 1 lacks seq 3
	var buf bytes.Buffer
	_ = buf
	if l := g.verify(); l["missing"] != 1 || l["expectedDeliveries"] != 4 {
		t.Fatalf("loss %v", l)
	}
}
