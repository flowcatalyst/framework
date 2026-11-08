// Command loadgen is the load generator for the push module's load test (push contract section 11).
// It opens N server-sent-event streams against ../push-demo the way a client does (POST
// /api/push/subscriptions with a ticket, then GET /api/push/stream?st=... with Last-Event-ID on
// every reconnect), spreads them over topics and several ports, and measures:
//
//   - delay from the push's stamped time (inside its data) to the client's receipt, as a histogram;
//   - pushes received against pushes expected, per stream, from the pushes the server acknowledged;
//   - reconnects, resets, errors;
//   - the server process: resident memory (ps), Go heap and goroutines (/debug/stats), open fds
//     (/debug/stats and lsof), CPU seconds (ps).
//
// Modes (-mode):
//
//	idle       open -streams streams, hold them -hold, report memory per stream
//	fanout     -rate pushes/s over -topics topics for -duration, with -streams streams (one topic each)
//	broadcast  -streams streams on one topic, -bcast-count pushes -bcast-gap apart; time to the last receiver
//	restart    fanout, and at -restart-at run -restart-cmd (it must kill -9 and start the server again);
//	           time until every stream is back, pushes lost, resets seen
//
// See docs/push-load-test-results.md for the method and loadtest.sh for the processes.
package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"math"
	"math/rand/v2"
	"net"
	"net/http"
	"os"
	"os/exec"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

type config struct {
	mode         string
	host         string
	port, ports  int
	debugPort    int
	streams      int
	topics       int
	rate         float64
	duration     time.Duration
	warmup       time.Duration
	hold         time.Duration
	settle       time.Duration
	bcastCount   int
	bcastGap     time.Duration
	connectConc  int
	pidFile      string
	restartCmd   string
	restartAt    time.Duration
	tail         time.Duration
	retry        time.Duration
	profileDir   string
	profileSecs  int
	pad          int
	lsof         bool
	track        bool
	jsonOut      string
	rampStallMax time.Duration
	bindLocal    bool
}

func main() {
	var c config
	flag.StringVar(&c.mode, "mode", "idle", "idle | fanout | broadcast | restart")
	flag.StringVar(&c.host, "host", "127.0.0.1", "server address")
	flag.IntVar(&c.port, "port", 18100, "first server port")
	flag.IntVar(&c.ports, "ports", 8, "number of consecutive server ports connections are spread over")
	flag.IntVar(&c.debugPort, "debug-port", 18199, "the server's debug port (stats, pprof)")
	flag.IntVar(&c.streams, "streams", 1000, "streams to open")
	flag.IntVar(&c.topics, "topics", 0, "topics (default: one per stream; 1 for broadcast)")
	flag.Float64Var(&c.rate, "rate", 1000, "pushes per second (fanout, restart)")
	flag.DurationVar(&c.duration, "duration", 60*time.Second, "how long pushes are published (fanout, restart)")
	flag.DurationVar(&c.warmup, "warmup", 3*time.Second, "publish this long before measuring starts counting profiles")
	flag.DurationVar(&c.hold, "hold", 10*time.Second, "idle: how long to hold the streams after the ramp")
	flag.DurationVar(&c.settle, "settle", 10*time.Second, "how long to wait for the last pushes after publishing stops")
	flag.IntVar(&c.bcastCount, "bcast-count", 10, "broadcast: pushes")
	flag.DurationVar(&c.bcastGap, "bcast-gap", 2*time.Second, "broadcast: gap between pushes")
	flag.IntVar(&c.connectConc, "connect-conc", 300, "streams being connected at once")
	flag.StringVar(&c.pidFile, "pid-file", "", "file with the server's PID (for RSS, CPU, fds)")
	flag.StringVar(&c.restartCmd, "restart-cmd", "", "restart mode: shell command that kills -9 and starts the server")
	flag.DurationVar(&c.restartAt, "restart-at", 20*time.Second, "restart mode: when, after publishing starts")
	flag.DurationVar(&c.tail, "tail", 15*time.Second, "restart mode: publish this long after every stream is back")
	flag.DurationVar(&c.retry, "retry", 3*time.Second, "reconnect delay after a drop (the server's retry field), with jitter of up to half of it")
	flag.StringVar(&c.profileDir, "profile-dir", "", "fanout: write CPU and heap profiles of the server here")
	flag.IntVar(&c.profileSecs, "profile-seconds", 30, "fanout: CPU profile length")
	flag.IntVar(&c.pad, "pad", 0, "bytes of padding in each push's data")
	flag.BoolVar(&c.lsof, "lsof", true, "count the server's open files with lsof as well")
	flag.BoolVar(&c.track, "track", true, "record every received seq per stream (loss check)")
	flag.StringVar(&c.jsonOut, "json", "", "append the result as a JSON line to this file")
	flag.DurationVar(&c.rampStallMax, "ramp-stall", 45*time.Second, "give up when no stream connects for this long")
	flag.BoolVar(&c.bindLocal, "bind-local", true, "bind each connection's local port (SO_REUSEPORT) to get past macOS's 16k ephemeral ports")
	flag.Parse()
	if c.topics == 0 {
		c.topics = c.streams
		if c.mode == "broadcast" {
			c.topics = 1
		}
	}
	os.Exit(run(&c))
}

func run(c *config) int {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	g := newGen(c)
	res, err := g.execute(ctx)
	cancel()
	g.wg.Wait()
	if res != nil {
		out, _ := json.Marshal(res)
		fmt.Println("RESULT " + string(out))
		if c.jsonOut != "" {
			if f, ferr := os.OpenFile(c.jsonOut, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600); ferr == nil {
				_, _ = f.Write(append(out, '\n'))
				_ = f.Close()
			}
		}
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "loadgen: FAILED:", err)
		return 1
	}
	return 0
}

// stream is one client stream. Only its own goroutine touches it until the run is quiet.
type stream struct {
	id     int32
	token  string
	lastID int64
	hadUp  bool
	mu     sync.Mutex // guards seqs
	seqs   []int32
}

type gen struct {
	c     *config
	httpc *http.Client // subscribe, ticket
	pubc  *http.Client // publish
	sem   chan struct{}
	wg    sync.WaitGroup

	streams []*stream

	errMu      sync.Mutex
	errSamples []string

	ticketMu sync.Mutex
	ticket   string
	ticketAt time.Time

	up         atomic.Int64 // streams with a live, subscribed connection
	firstUps   atomic.Int64
	reconnects atomic.Int64
	drops      atomic.Int64
	connErrs   atomic.Int64
	subErrs    atomic.Int64
	badStatus  atomic.Int64
	resets     atomic.Int64
	authExp    atomic.Int64
	recv       atomic.Int64
	lastSlow   atomic.Int64 // unix nanos of the last receipt that was over a second late
	firstUpAft atomic.Int64

	hist     hist
	nextSeq  int64
	acked    []uint8
	ackedN   atomic.Int64
	pubSent  atomic.Int64
	pubFail  atomic.Int64
	pubSkip  atomic.Int64
	bSeqMax  []atomic.Int64 // broadcast: per seq, the largest delay
	bSeqRecv []atomic.Int64
}

func newGen(c *config) *gen {
	tr := &http.Transport{MaxIdleConnsPerHost: 512, MaxConnsPerHost: 0, IdleConnTimeout: 30 * time.Second}
	ptr := &http.Transport{MaxIdleConnsPerHost: 64, IdleConnTimeout: 30 * time.Second}
	g := &gen{
		c:     c,
		httpc: &http.Client{Transport: tr, Timeout: 30 * time.Second},
		pubc:  &http.Client{Transport: ptr, Timeout: 10 * time.Second},
		sem:   make(chan struct{}, c.connectConc),
	}
	return g
}

func (g *gen) addr(i int) string {
	return net.JoinHostPort(g.c.host, strconv.Itoa(g.c.port+i%g.c.ports))
}

func (g *gen) base(i int) string { return "http://" + g.addr(i) }

func topicName(k int) string { return "pushdemo:t:" + strconv.Itoa(k) }

// ---- result ----

type sampleRow struct {
	Label        string  `json:"label"`
	Streams      int64   `json:"streams"`
	ServerRSSKB  int64   `json:"serverRssKB"`
	HeapAlloc    int64   `json:"heapAlloc"`
	HeapInuse    int64   `json:"heapInuse"`
	HeapSys      int64   `json:"heapSys"`
	StackInuse   int64   `json:"stackInuse"`
	Goroutines   int64   `json:"goroutines"`
	FDs          int64   `json:"fds"`
	LsofFDs      int64   `json:"lsofFds"`
	NumGC        int64   `json:"numGC"`
	ServerCPUSec float64 `json:"serverCpuSec"`
	GenRSSKB     int64   `json:"genRssKB"`
	GenCPUSec    float64 `json:"genCpuSec"`
}

type result struct {
	Mode         string         `json:"mode"`
	Streams      int            `json:"streams"`
	Topics       int            `json:"topics"`
	Samples      []sampleRow    `json:"samples"`
	RampSeconds  float64        `json:"rampSeconds,omitempty"`
	Counters     map[string]int `json:"counters"`
	Delay        map[string]any `json:"delayMs,omitempty"`
	Loss         map[string]int `json:"loss,omitempty"`
	Notes        map[string]any `json:"notes,omitempty"`
	ServerMetric []string       `json:"serverMetrics,omitempty"`
}

func (g *gen) counters() map[string]int {
	return map[string]int{
		"up": int(g.up.Load()), "firstUps": int(g.firstUps.Load()), "reconnects": int(g.reconnects.Load()),
		"drops": int(g.drops.Load()), "connectErrors": int(g.connErrs.Load()), "subscribeErrors": int(g.subErrs.Load()),
		"badStatus": int(g.badStatus.Load()), "resets": int(g.resets.Load()), "authExpired": int(g.authExp.Load()),
		"received": int(g.recv.Load()), "publishSent": int(g.pubSent.Load()), "publishFailed": int(g.pubFail.Load()),
		"publishSkipped": int(g.pubSkip.Load()), "acked": int(g.ackedN.Load()),
	}
}

func (g *gen) execute(ctx context.Context) (*result, error) {
	c := g.c
	res := &result{Mode: c.mode, Streams: c.streams, Topics: c.topics, Notes: map[string]any{}}
	finish := func(err error) (*result, error) {
		res.Counters = g.counters()
		return res, err
	}
	if c.mode != "idle" && c.mode != "fanout" && c.mode != "broadcast" && c.mode != "restart" {
		return finish(fmt.Errorf("unknown -mode %q", c.mode))
	}
	if err := g.waitServer(ctx, 30*time.Second); err != nil {
		return finish(err)
	}
	sample := func(label string) {
		s := g.sample(label)
		res.Samples = append(res.Samples, s)
		fmt.Printf("SAMPLE %-28s streams=%-6d serverRSS=%6.0fMB heapAlloc=%6.0fMB heapInuse=%6.0fMB stack=%5.0fMB goroutines=%-6d fds=%-6d lsof=%-6d cpu=%7.1fs | gen RSS=%6.0fMB cpu=%7.1fs\n",
			label, s.Streams, float64(s.ServerRSSKB)/1024, float64(s.HeapAlloc)/1048576, float64(s.HeapInuse)/1048576,
			float64(s.StackInuse)/1048576, s.Goroutines, s.FDs, s.LsofFDs, s.ServerCPUSec, float64(s.GenRSSKB)/1024, s.GenCPUSec)
	}
	sample("0 streams")

	// publishing needs the acked table; sized for the longest run
	if c.mode != "idle" {
		secs := c.duration.Seconds() + c.tail.Seconds() + c.restartAt.Seconds() + 120
		n := int(c.rate*secs) + 100000
		if c.mode == "broadcast" {
			n = c.bcastCount + 10
			g.bSeqMax = make([]atomic.Int64, n)
			g.bSeqRecv = make([]atomic.Int64, n)
		}
		g.acked = make([]uint8, n)
	}
	g.nextSeq = 1

	runCtx, stopStreams := context.WithCancel(ctx)
	defer func() {
		stopStreams()
	}()
	g.streams = make([]*stream, c.streams)
	for i := range g.streams {
		g.streams[i] = &stream{id: int32(i)}
	}
	rampStart := time.Now()
	g.wg.Add(1)
	go g.launch(runCtx)
	if err := g.waitUp(ctx, int64(c.streams), c.rampStallMax); err != nil {
		sample("ramp failed")
		return finish(err)
	}
	res.RampSeconds = time.Since(rampStart).Seconds()
	fmt.Printf("RAMP %d streams in %.1fs (%.0f/s)\n", c.streams, res.RampSeconds, float64(c.streams)/res.RampSeconds)
	time.Sleep(3 * time.Second)
	sample(fmt.Sprintf("%d streams, after ramp", c.streams))

	var err error
	switch c.mode {
	case "idle":
		time.Sleep(c.hold)
		sample(fmt.Sprintf("%d streams, after hold", c.streams))
		if d := g.drops.Load(); d != 0 || g.up.Load() != int64(c.streams) {
			err = fmt.Errorf("idle streams dropped: %d drops, %d up of %d", d, g.up.Load(), c.streams)
		}
	case "fanout":
		err = g.fanout(ctx, res, sample, false)
	case "restart":
		err = g.fanout(ctx, res, sample, true)
	case "broadcast":
		err = g.broadcast(ctx, res, sample)
	}
	res.ServerMetric = g.serverMetrics()
	return finish(err)
}

// ---- streams ----

// launch starts the streams, at most connectConc connecting at a time.
func (g *gen) launch(ctx context.Context) {
	defer g.wg.Done()
	for i := range g.streams {
		select {
		case g.sem <- struct{}{}:
		case <-ctx.Done():
			return
		}
		g.wg.Add(1)
		go g.runStream(ctx, g.streams[i])
	}
}

func (g *gen) topicOf(s *stream) int { return int(s.id) % g.c.topics }

func (g *gen) runStream(ctx context.Context, s *stream) {
	defer g.wg.Done()
	pre := true // the launcher already holds a connect slot for the first connection
	for ctx.Err() == nil {
		wasUp, err := g.connectOnce(ctx, s, pre)
		pre = false
		if ctx.Err() != nil {
			return
		}
		var d time.Duration
		switch {
		case wasUp:
			d = jitter(g.c.retry)
		case errors.Is(err, errLimit):
			d = time.Second + jitter(time.Second)
		default:
			d = 200*time.Millisecond + time.Duration(rand.Int64N(int64(300*time.Millisecond))) //nolint:gosec // jitter, not security
		}
		select {
		case <-time.After(d):
		case <-ctx.Done():
			return
		}
	}
}

// jitter is d with up to half of it added, as a client does after the retry field.
func jitter(d time.Duration) time.Duration {
	return d + time.Duration(rand.Int64N(int64(d/2)+1)) //nolint:gosec // jitter, not security
}

var (
	errLimit  = errors.New("limit")
	errReauth = errors.New("subscribe again")
)

// connectOnce subscribes when it has no token, opens the stream and reads it until it ends. It
// reports whether the stream was subscribed (up) at some point.
func (g *gen) connectOnce(ctx context.Context, s *stream, pre bool) (bool, error) {
	if !pre {
		select {
		case g.sem <- struct{}{}:
		case <-ctx.Done():
			return false, ctx.Err()
		}
	}
	released := false
	release := func() {
		if !released {
			released = true
			<-g.sem
		}
	}
	defer release()
	if s.token == "" {
		if err := g.subscribe(ctx, s); err != nil {
			g.subErrs.Add(1)
			g.noteErr(err)
			return false, err
		}
	}
	conn, err := g.dial(ctx, s)
	if err != nil {
		g.connErrs.Add(1)
		g.noteErr(err)
		return false, err
	}
	defer conn.Close()
	stop := context.AfterFunc(ctx, func() { _ = conn.Close() })
	defer stop()
	var req strings.Builder
	req.WriteString("GET /api/push/stream?st=" + s.token + " HTTP/1.1\r\nHost: " + g.addr(int(s.id)) + "\r\nAccept: text/event-stream\r\n")
	if s.hadUp {
		req.WriteString("Last-Event-ID: " + strconv.FormatInt(s.lastID, 10) + "\r\n")
	}
	req.WriteString("\r\n")
	if _, err := io.WriteString(conn, req.String()); err != nil {
		g.connErrs.Add(1)
		return false, err
	}
	_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	br := bufio.NewReaderSize(conn, 512)
	status, chunked, err := readHead(br)
	if err != nil {
		g.connErrs.Add(1)
		return false, err
	}
	switch status {
	case 200:
	case 401:
		g.badStatus.Add(1)
		s.token = ""
		return false, errReauth
	case 429:
		g.badStatus.Add(1)
		return false, errLimit
	default:
		g.badStatus.Add(1)
		return false, fmt.Errorf("status %d", status)
	}
	var src io.Reader = br
	if chunked {
		src = &chunkReader{br: br}
	}
	eb := bufio.NewReaderSize(src, 1024)
	up := false
	defer func() {
		if up {
			g.up.Add(-1)
			g.drops.Add(1)
		}
	}()
	var scratch, data []byte
	var evType string
	var evID int64 = -1
	for {
		if up {
			_ = conn.SetReadDeadline(time.Now().Add(90 * time.Second)) // heartbeat is 25 s
		}
		var line []byte
		line, scratch, err = readLine(eb, scratch)
		if err != nil {
			return up || s.hadUp, err
		}
		line = bytes.TrimRight(line, "\r\n")
		switch {
		case len(line) == 0:
			switch evType {
			case "":
			case "subscribed":
				if !up {
					up = true
					g.up.Add(1)
					if s.hadUp {
						g.reconnects.Add(1)
						g.firstUpAft.CompareAndSwap(0, time.Now().UnixNano())
					} else {
						g.firstUps.Add(1)
					}
					s.hadUp = true
					release()
				}
			case "reset":
				g.resets.Add(1)
			case "auth-expired":
				g.authExp.Add(1)
				s.token = ""
				return up, errReauth
			default:
				g.onPush(s, data)
			}
			if evID >= 0 {
				s.lastID = evID
			}
			evType, evID, data = "", -1, data[:0]
		case line[0] == ':':
		case bytes.HasPrefix(line, []byte("id:")):
			if n, perr := strconv.ParseInt(string(bytes.TrimSpace(line[3:])), 10, 64); perr == nil {
				evID = n
			}
		case bytes.HasPrefix(line, []byte("event:")):
			evType = string(bytes.TrimSpace(line[6:]))
		case bytes.HasPrefix(line, []byte("data:")):
			data = append(data[:0], bytes.TrimSpace(line[5:])...)
		}
	}
}

// dial opens the stream's TCP connection. macOS gives one machine about 16k ephemeral ports in all,
// whatever the destination, so with bindLocal each connection binds its own local port (SO_REUSEPORT)
// below the ephemeral range: the 4-tuple stays unique because the destination port differs between
// the server's ports, and the stream i uses local port 25000 + (i / ports) mod 24000.
func (g *gen) dial(ctx context.Context, s *stream) (net.Conn, error) {
	remote := g.addr(int(s.id))
	if !g.c.bindLocal {
		d := net.Dialer{Timeout: 20 * time.Second}
		return d.DialContext(ctx, "tcp", remote)
	}
	var lastErr error
	for try := range 6 {
		lp := 25000 + (int(s.id)/g.c.ports+try*997)%24000
		d := net.Dialer{
			Timeout:   20 * time.Second,
			LocalAddr: &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: lp},
			Control: func(_, _ string, rc syscall.RawConn) error {
				return rc.Control(func(fd uintptr) {
					_ = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_REUSEPORT, 1)
					_ = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_REUSEADDR, 1)
				})
			},
		}
		conn, err := d.DialContext(ctx, "tcp", remote)
		if err == nil {
			return conn, nil
		}
		lastErr = err
		if !errors.Is(err, syscall.EADDRINUSE) && !errors.Is(err, syscall.EADDRNOTAVAIL) {
			return nil, lastErr
		}
	}
	// every chosen local port was taken by another program: take an ephemeral one
	d := net.Dialer{Timeout: 20 * time.Second}
	return d.DialContext(ctx, "tcp", remote)
}

func (g *gen) noteErr(err error) {
	g.errMu.Lock()
	defer g.errMu.Unlock()
	if len(g.errSamples) < 5 && !slices.Contains(g.errSamples, err.Error()) {
		g.errSamples = append(g.errSamples, err.Error())
		fmt.Println("ERROR sample:", err)
	}
}

// onPush records one push event: its delay and its seq.
func (g *gen) onPush(s *stream, data []byte) {
	now := time.Now().UnixNano()
	seq, ts := parsePush(data)
	if ts == 0 {
		return
	}
	d := now - ts
	g.hist.add(d)
	g.recv.Add(1)
	if d > int64(time.Second) {
		g.lastSlow.Store(now)
	}
	if g.c.track {
		s.mu.Lock()
		s.seqs = append(s.seqs, int32(seq))
		s.mu.Unlock()
	}
	if g.bSeqMax != nil && seq > 0 && int(seq) < len(g.bSeqMax) {
		g.bSeqRecv[seq].Add(1)
		for {
			cur := g.bSeqMax[seq].Load()
			if d <= cur || g.bSeqMax[seq].CompareAndSwap(cur, d) {
				break
			}
		}
	}
}

// parsePush reads seq and ts out of {"topic":"...","data":{"seq":N,"ts":N,...}}.
func parsePush(data []byte) (seq, ts int64) {
	return numAfter(data, `"seq":`), numAfter(data, `"ts":`)
}

func numAfter(b []byte, key string) int64 {
	if b == nil {
		return 0
	}
	i := bytes.Index(b, []byte(key))
	if i < 0 {
		return 0
	}
	var n int64
	for _, ch := range b[i+len(key):] {
		if ch < '0' || ch > '9' {
			break
		}
		n = n*10 + int64(ch-'0')
	}
	return n
}

// readHead reads the status line and headers; chunked is whether the body is chunk-encoded.
func readHead(br *bufio.Reader) (status int, chunked bool, err error) {
	var scratch []byte
	line, scratch, err := readLine(br, scratch)
	if err != nil {
		return 0, false, err
	}
	parts := strings.SplitN(string(bytes.TrimSpace(line)), " ", 3)
	if len(parts) < 2 {
		return 0, false, fmt.Errorf("bad status line %q", line)
	}
	if status, err = strconv.Atoi(parts[1]); err != nil {
		return 0, false, err
	}
	for {
		line, scratch, err = readLine(br, scratch)
		if err != nil {
			return 0, false, err
		}
		line = bytes.TrimRight(line, "\r\n")
		if len(line) == 0 {
			return status, chunked, nil
		}
		if k, v, ok := bytes.Cut(line, []byte(":")); ok && bytes.EqualFold(k, []byte("transfer-encoding")) &&
			bytes.Contains(bytes.ToLower(v), []byte("chunked")) {
			chunked = true
		}
	}
}

// readLine returns the next line including its newline; a line longer than the reader's buffer is
// collected in scratch.
func readLine(r *bufio.Reader, scratch []byte) (line, scratchOut []byte, err error) {
	part, err := r.ReadSlice('\n')
	if err == nil {
		return part, scratch, nil
	}
	if !errors.Is(err, bufio.ErrBufferFull) {
		return nil, scratch, err
	}
	scratch = append(scratch[:0], part...)
	for {
		part, err = r.ReadSlice('\n')
		scratch = append(scratch, part...)
		if err == nil {
			return scratch, scratch, nil
		}
		if !errors.Is(err, bufio.ErrBufferFull) {
			return nil, scratch, err
		}
	}
}

// chunkReader decodes an HTTP/1.1 chunked body.
type chunkReader struct {
	br   *bufio.Reader
	left int
	done bool
}

func (c *chunkReader) Read(p []byte) (int, error) {
	if c.done {
		return 0, io.EOF
	}
	if c.left == 0 {
		line, err := c.br.ReadSlice('\n')
		if err != nil {
			return 0, err
		}
		line = bytes.TrimSpace(line)
		if i := bytes.IndexByte(line, ';'); i >= 0 {
			line = line[:i]
		}
		n, err := strconv.ParseInt(string(line), 16, 32)
		if err != nil {
			return 0, fmt.Errorf("bad chunk size %q", line)
		}
		if n == 0 {
			c.done = true
			return 0, io.EOF
		}
		c.left = int(n)
	}
	n, err := c.br.Read(p[:min(len(p), c.left)])
	c.left -= n
	if c.left == 0 && err == nil {
		_, err = c.br.Discard(2)
	}
	return n, err
}

// ---- subscribe ----

func (g *gen) getTicket(ctx context.Context) (string, error) {
	g.ticketMu.Lock()
	defer g.ticketMu.Unlock()
	if g.ticket != "" && time.Since(g.ticketAt) < 45*time.Second {
		return g.ticket, nil
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, g.base(0)+"/api/demo/ticket", nil)
	if err != nil {
		return "", err
	}
	resp, err := g.httpc.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var out struct {
		Ticket string `json:"ticket"`
	}
	if resp.StatusCode != 200 || json.NewDecoder(resp.Body).Decode(&out) != nil || out.Ticket == "" {
		return "", fmt.Errorf("ticket: status %d", resp.StatusCode)
	}
	g.ticket, g.ticketAt = out.Ticket, time.Now()
	return g.ticket, nil
}

func (g *gen) subscribe(ctx context.Context, s *stream) error {
	t, err := g.getTicket(ctx)
	if err != nil {
		return err
	}
	body, _ := json.Marshal(map[string]any{"topics": []string{topicName(g.topicOf(s))}, "tickets": []string{t}})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, g.base(int(s.id))+"/api/push/subscriptions", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := g.httpc.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusTooManyRequests {
		return errLimit
	}
	var out struct {
		StreamToken string `json:"streamToken"`
	}
	if resp.StatusCode != 200 || json.NewDecoder(resp.Body).Decode(&out) != nil || out.StreamToken == "" {
		return fmt.Errorf("subscribe: status %d", resp.StatusCode)
	}
	s.token = out.StreamToken
	return nil
}

func (g *gen) waitServer(ctx context.Context, d time.Duration) error {
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, g.base(0)+"/health/ready", nil)
		if resp, err := g.httpc.Do(req); err == nil {
			_ = resp.Body.Close()
			if resp.StatusCode == 200 {
				return nil
			}
		}
		time.Sleep(200 * time.Millisecond)
	}
	return errors.New("the server is not ready")
}

func (g *gen) waitUp(ctx context.Context, target int64, stall time.Duration) error {
	last, lastChange := int64(-1), time.Now()
	tick := time.NewTicker(500 * time.Millisecond)
	defer tick.Stop()
	nextLog := time.Now().Add(5 * time.Second)
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-tick.C:
		}
		u := g.up.Load()
		if u >= target {
			return nil
		}
		if u != last {
			last, lastChange = u, time.Now()
		}
		if time.Since(lastChange) > stall {
			return fmt.Errorf("ramp stalled at %d of %d streams (connect errors %d, subscribe errors %d, bad status %d)",
				u, target, g.connErrs.Load(), g.subErrs.Load(), g.badStatus.Load())
		}
		if time.Now().After(nextLog) {
			nextLog = time.Now().Add(5 * time.Second)
			fmt.Printf("  ... %d of %d up (errors: connect %d, subscribe %d)\n", u, target, g.connErrs.Load(), g.subErrs.Load())
		}
	}
}

// ---- publishing ----

type item struct {
	Seq   int64  `json:"seq"`
	Topic string `json:"topic"`
}

// publishBatch posts items to the server's publish endpoint and records the acknowledged seqs.
func (g *gen) publishBatch(ctx context.Context, items []item, port int) {
	body, _ := json.Marshal(map[string]any{"items": items, "pad": g.c.pad})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, g.base(port)+"/api/demo/publish", bytes.NewReader(body))
	if err != nil {
		g.pubFail.Add(int64(len(items)))
		return
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := g.pubc.Do(req)
	if err != nil {
		g.pubFail.Add(int64(len(items)))
		if g.pubFail.Load() <= int64(len(items)) {
			fmt.Println("PUBLISH error:", err)
		}
		return
	}
	defer resp.Body.Close()
	var out struct {
		Failed []int `json:"failed"`
	}
	if resp.StatusCode != 200 || json.NewDecoder(resp.Body).Decode(&out) != nil {
		if g.pubFail.Add(int64(len(items))) <= int64(len(items)) {
			fmt.Println("PUBLISH error: status", resp.StatusCode)
		}
		return
	}
	bad := map[int]bool{}
	for _, i := range out.Failed {
		bad[i] = true
	}
	for i, it := range items {
		if bad[i] {
			g.pubFail.Add(1)
			continue
		}
		if it.Seq < int64(len(g.acked)) {
			g.acked[it.Seq] = 1
		}
		g.ackedN.Add(1)
	}
	g.pubSent.Add(int64(len(items) - len(out.Failed)))
}

// publishLoop publishes at rate pushes per second, topic = seq mod topics, until ctx ends.
func (g *gen) publishLoop(ctx context.Context) {
	const step = 20 * time.Millisecond
	inflight := make(chan struct{}, 48)
	var wg sync.WaitGroup
	tick := time.NewTicker(step)
	defer tick.Stop()
	carry := 0.0
	n := 0
	for {
		select {
		case <-ctx.Done():
			wg.Wait()
			return
		case <-tick.C:
		}
		carry += g.c.rate * step.Seconds()
		count := int(carry)
		carry -= float64(count)
		if count == 0 {
			continue
		}
		select {
		case inflight <- struct{}{}:
		default:
			g.pubSkip.Add(int64(count))
			continue
		}
		items := make([]item, count)
		for i := range items {
			seq := g.nextSeq
			g.nextSeq++
			items[i] = item{Seq: seq, Topic: topicName(int(seq % int64(g.c.topics)))}
		}
		n++
		port := n
		wg.Go(func() {
			defer func() { <-inflight }()
			g.publishBatch(context.WithoutCancel(ctx), items, port)
		})
	}
}

// ---- modes ----

func (g *gen) fanout(ctx context.Context, res *result, sample func(string), restart bool) error {
	c := g.c
	pubCtx, stopPub := context.WithCancel(ctx)
	pubDone := make(chan struct{})
	go func() {
		g.publishLoop(pubCtx)
		close(pubDone)
	}()
	start := time.Now()
	fmt.Printf("PUBLISH %.0f pushes/s over %d topics, %d streams\n", c.rate, c.topics, c.streams)
	cpu0 := g.sample("cpu-start")
	profErr := make(chan error, 1)
	var killAt time.Time
	var resumeAt time.Time
	switch {
	case restart:
		time.Sleep(c.restartAt)
		sample("before kill")
		g.firstUpAft.Store(0)
		killAt = time.Now()
		fmt.Printf("RESTART running %q\n", c.restartCmd)
		out, err := exec.CommandContext(ctx, "sh", "-c", c.restartCmd).CombinedOutput() //nolint:gosec // the operator's own restart command
		if err != nil {
			stopPub()
			<-pubDone
			return fmt.Errorf("restart command: %w: %s", err, out)
		}
		fmt.Printf("RESTART command returned after %.2fs\n", time.Since(killAt).Seconds())
		// resumed: every stream up again after having been down
		deadline := time.Now().Add(5 * time.Minute)
		for g.up.Load() >= int64(c.streams) && time.Now().Before(deadline) { // wait for the drops to be seen
			time.Sleep(20 * time.Millisecond)
		}
		for g.up.Load() < int64(c.streams) && time.Now().Before(deadline) {
			time.Sleep(20 * time.Millisecond)
		}
		resumeAt = time.Now()
		if g.up.Load() < int64(c.streams) {
			stopPub()
			<-pubDone
			return fmt.Errorf("only %d of %d streams resumed within 5 minutes", g.up.Load(), c.streams)
		}
		fmt.Printf("RESUME all %d streams back %.2fs after the kill (first back after %.2fs)\n", c.streams,
			resumeAt.Sub(killAt).Seconds(), float64(g.firstUpAft.Load()-killAt.UnixNano())/1e9)
		time.Sleep(3 * time.Second)
		sample("after resume")
		time.Sleep(c.tail)
	default:
		time.Sleep(c.warmup)
		if c.profileDir != "" {
			go func() { profErr <- g.profile(ctx) }()
		}
		rem := c.duration - c.warmup
		if rem > 0 {
			time.Sleep(rem / 2)
			sample("mid fan-out")
			time.Sleep(rem - rem/2)
		}
	}
	stopPub()
	<-pubDone
	pubSecs := time.Since(start).Seconds()
	cpu1 := g.sample("cpu-end")
	if c.profileDir != "" && !restart {
		if err := <-profErr; err != nil {
			fmt.Println("PROFILE error:", err)
		}
	}
	// wait for the last pushes
	want := g.ackedN.Load()
	deadline := time.Now().Add(c.settle + 30*time.Second)
	time.Sleep(c.settle)
	for time.Now().Before(deadline) {
		if g.missingTotal(want) == 0 {
			break
		}
		time.Sleep(500 * time.Millisecond)
	}
	sample("after fan-out")
	loss := g.verify()
	res.Loss = loss
	if g.ackedN.Load() == 0 {
		return errors.New("the server acknowledged no push")
	}
	res.Delay = g.hist.summary()
	res.Notes["publishSeconds"] = pubSecs
	res.Notes["achievedPushesPerSec"] = float64(g.ackedN.Load()) / pubSecs
	res.Notes["serverCpuSecDuringPublish"] = cpu1.ServerCPUSec - cpu0.ServerCPUSec
	res.Notes["generatorCpuSecDuringPublish"] = cpu1.GenCPUSec - cpu0.GenCPUSec
	if restart {
		res.Notes["resumeSecondsAfterKill"] = resumeAt.Sub(killAt).Seconds()
		res.Notes["firstBackSecondsAfterKill"] = float64(g.firstUpAft.Load()-killAt.UnixNano()) / 1e9
		if ls := g.lastSlow.Load(); ls != 0 {
			res.Notes["lastLatePushSecondsAfterKill"] = float64(ls-killAt.UnixNano()) / 1e9
		}
	}
	g.printSummary(res)
	if loss["missing"] != 0 {
		return fmt.Errorf("%d pushes lost", loss["missing"])
	}
	return nil
}

func (g *gen) broadcast(ctx context.Context, res *result, sample func(string)) error {
	c := g.c
	type row struct {
		Seq       int64   `json:"seq"`
		Receivers int64   `json:"receivers"`
		LastMs    float64 `json:"lastReceiverMs"`
	}
	for i := range c.bcastCount {
		seq := g.nextSeq
		g.nextSeq++
		g.publishBatch(ctx, []item{{Seq: seq, Topic: topicName(0)}}, i)
		time.Sleep(c.bcastGap)
	}
	time.Sleep(c.settle)
	var rows []row
	for seq := int64(1); seq < g.nextSeq; seq++ {
		r := row{Seq: seq, Receivers: g.bSeqRecv[seq].Load(), LastMs: float64(g.bSeqMax[seq].Load()) / 1e6}
		rows = append(rows, r)
		fmt.Printf("BROADCAST seq=%d receivers=%d/%d time-to-last-receiver=%.1fms\n", seq, r.Receivers, c.streams, r.LastMs)
	}
	sample("after broadcast")
	res.Loss = g.verify()
	res.Delay = g.hist.summary()
	res.Notes["broadcast"] = rows
	g.printSummary(res)
	if res.Loss["missing"] != 0 {
		return fmt.Errorf("%d deliveries missing", res.Loss["missing"])
	}
	return nil
}

func (g *gen) printSummary(res *result) {
	res.Counters = g.counters()
	fmt.Printf("DELAY ms %v\nLOSS %v\nCOUNTERS %v\nNOTES %v\n", res.Delay, res.Loss, res.Counters, res.Notes)
}

// ---- loss check ----

// expected returns, per topic, the acknowledged seqs of the topic, in order.
func (g *gen) expected() map[int][]int32 {
	exp := map[int][]int32{}
	for seq := 1; seq < len(g.acked) && int64(seq) < g.nextSeq; seq++ {
		if g.acked[seq] == 1 {
			k := seq % g.c.topics
			exp[k] = append(exp[k], int32(seq))
		}
	}
	return exp
}

// missingTotal is a cheap running estimate: deliveries still owed (acked pushes times streams on
// their topic) minus received. Used only to know when to stop waiting.
func (g *gen) missingTotal(acked int64) int64 {
	perTopic := int64(g.c.streams / g.c.topics)
	if perTopic == 0 {
		perTopic = 1
	}
	return acked*perTopic - g.recv.Load()
}

// verify compares each stream's received seqs with the acknowledged seqs of its topic.
func (g *gen) verify() map[string]int {
	exp := g.expected()
	var expected, missing, dups int
	for _, s := range g.streams {
		if s == nil {
			continue
		}
		want := exp[g.topicOf(s)]
		s.mu.Lock()
		got := slices.Clone(s.seqs)
		s.mu.Unlock()
		slices.Sort(got)
		uniq := slices.Compact(got)
		dups += len(got) - len(uniq)
		expected += len(want)
		for _, q := range want {
			if _, ok := slices.BinarySearch(uniq, q); !ok {
				missing++
			}
		}
	}
	// received but not acknowledged: counted from the totals
	unacked := 0
	for _, s := range g.streams {
		if s == nil {
			continue
		}
		s.mu.Lock()
		all := slices.Clone(s.seqs)
		s.mu.Unlock()
		slices.Sort(all)
		for _, q := range slices.Compact(all) {
			if int(q) < len(g.acked) && g.acked[q] == 0 {
				unacked++
			}
		}
	}
	return map[string]int{
		"expectedDeliveries": expected, "received": int(g.recv.Load()), "missing": missing,
		"duplicates": dups, "receivedButUnacked": unacked, "streams": g.c.streams,
	}
}

// ---- histogram ----

const (
	histBuckets = 700
	histBase    = 1.04
)

// hist is a log-scale histogram of delays in nanoseconds (about 4% resolution from 1 microsecond).
type hist struct {
	counts [histBuckets]atomic.Int64
	n      atomic.Int64
	sum    atomic.Int64
	max    atomic.Int64
}

func bucketOf(ns int64) int {
	us := float64(ns) / 1000
	if us < 1 {
		return 0
	}
	return min(histBuckets-1, int(math.Log(us)/math.Log(histBase)))
}

func (h *hist) add(ns int64) {
	h.counts[bucketOf(ns)].Add(1)
	h.n.Add(1)
	h.sum.Add(ns)
	for {
		cur := h.max.Load()
		if ns <= cur || h.max.CompareAndSwap(cur, ns) {
			return
		}
	}
}

// quantile returns the delay (ns) at q (0..1), as the upper edge of its bucket.
func (h *hist) quantile(q float64) int64 {
	n := h.n.Load()
	if n == 0 {
		return 0
	}
	target := int64(math.Ceil(q * float64(n)))
	var acc int64
	for i := range h.counts {
		acc += h.counts[i].Load()
		if acc >= target {
			return int64(math.Pow(histBase, float64(i+1)) * 1000)
		}
	}
	return h.max.Load()
}

func ms(ns int64) float64 { return math.Round(float64(ns)/1e4) / 100 }

func (h *hist) summary() map[string]any {
	n := h.n.Load()
	if n == 0 {
		return map[string]any{"count": 0}
	}
	return map[string]any{
		"count": n, "mean": ms(h.sum.Load() / n), "p50": ms(h.quantile(0.50)), "p90": ms(h.quantile(0.90)),
		"p95": ms(h.quantile(0.95)), "p99": ms(h.quantile(0.99)), "p999": ms(h.quantile(0.999)), "max": ms(h.max.Load()),
	}
}

// ---- server measurement ----

func (g *gen) serverPID() int {
	if g.c.pidFile == "" {
		return 0
	}
	b, err := os.ReadFile(g.c.pidFile)
	if err != nil {
		return 0
	}
	n, _ := strconv.Atoi(strings.TrimSpace(string(b)))
	return n
}

func psField(pid int, field string) string {
	out, err := exec.Command("ps", "-o", field+"=", "-p", strconv.Itoa(pid)).Output() //nolint:gosec // fixed command, numeric pid
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

func cpuSeconds(pid int) float64 {
	s := psField(pid, "cputime")
	if s == "" {
		return 0
	}
	total := 0.0
	for p := range strings.SplitSeq(s, ":") {
		v, _ := strconv.ParseFloat(p, 64)
		total = total*60 + v
	}
	return total
}

func rssKB(pid int) int64 {
	n, _ := strconv.ParseInt(psField(pid, "rss"), 10, 64)
	return n
}

func (g *gen) sample(label string) sampleRow {
	s := sampleRow{Label: label, Streams: g.up.Load()}
	pid := g.serverPID()
	if pid > 0 {
		s.ServerRSSKB, s.ServerCPUSec = rssKB(pid), cpuSeconds(pid)
		if g.c.lsof && !strings.HasPrefix(label, "cpu-") {
			out, err := exec.Command("sh", "-c", "lsof -p "+strconv.Itoa(pid)+" 2>/dev/null | wc -l").Output() //nolint:gosec // numeric pid
			if err == nil {
				s.LsofFDs, _ = strconv.ParseInt(strings.TrimSpace(string(out)), 10, 64)
			}
		}
	}
	me := os.Getpid()
	s.GenRSSKB, s.GenCPUSec = rssKB(me), cpuSeconds(me)
	var st map[string]int64
	resp, err := g.httpc.Get("http://" + net.JoinHostPort(g.c.host, strconv.Itoa(g.c.debugPort)) + "/debug/stats")
	if err == nil {
		_ = json.NewDecoder(resp.Body).Decode(&st)
		_ = resp.Body.Close()
	}
	s.HeapAlloc, s.HeapInuse, s.HeapSys, s.StackInuse = st["heapAlloc"], st["heapInuse"], st["heapSys"], st["stackInuse"]
	s.Goroutines, s.FDs, s.NumGC = st["goroutines"], st["fds"], st["numGC"]
	return s
}

// serverMetrics returns the server's fw_push_* metric lines.
func (g *gen) serverMetrics() []string {
	resp, err := g.httpc.Get(g.base(0) + "/metrics")
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	var out []string
	sc := bufio.NewScanner(resp.Body)
	for sc.Scan() {
		if t := sc.Text(); strings.HasPrefix(t, "fw_push_") {
			out = append(out, t)
		}
	}
	return out
}

// profile saves the server's CPU profile (profile-seconds) and then its heap profile.
func (g *gen) profile(ctx context.Context) error {
	base := "http://" + net.JoinHostPort(g.c.host, strconv.Itoa(g.c.debugPort)) + "/debug/pprof/"
	if err := os.MkdirAll(g.c.profileDir, 0o750); err != nil {
		return err
	}
	cl := &http.Client{Timeout: time.Duration(g.c.profileSecs+30) * time.Second}
	get := func(path, file string) error {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+path, nil)
		if err != nil {
			return err
		}
		resp, err := cl.Do(req)
		if err != nil {
			return err
		}
		defer resp.Body.Close()
		f, err := os.Create(g.c.profileDir + "/" + file)
		if err != nil {
			return err
		}
		_, err = io.Copy(f, resp.Body)
		if cerr := f.Close(); err == nil {
			err = cerr
		}
		return err
	}
	if err := get("profile?seconds="+strconv.Itoa(g.c.profileSecs), "cpu.pprof"); err != nil {
		return err
	}
	if err := get("heap", "heap.pprof"); err != nil {
		return err
	}
	return get("allocs", "allocs.pprof")
}
