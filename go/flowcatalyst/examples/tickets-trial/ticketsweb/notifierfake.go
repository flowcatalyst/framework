package ticketsweb

import (
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
)

// NotifierFake is the notification service the application calls, as a local server that records what
// it is sent (the Java NotifierFake).
type NotifierFake struct {
	srv    *httptest.Server
	status atomic.Int32

	mu       sync.Mutex
	received []Received
}

// Received is one request the notification service got.
type Received struct {
	Method, Path string
	Header       http.Header
	Body         string
}

// StartNotifier starts the fake; it answers 202 until told otherwise.
func StartNotifier() *NotifierFake {
	n := &NotifierFake{}
	n.status.Store(http.StatusAccepted)
	n.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		n.mu.Lock()
		n.received = append(n.received, Received{Method: r.Method, Path: r.URL.Path, Header: r.Header.Clone(), Body: string(b)})
		n.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(int(n.status.Load()))
		_, _ = io.WriteString(w, "{}")
	}))
	return n
}

// BaseURL is the address to give the application as http-clients.instances.notifier.base-url.
func (n *NotifierFake) BaseURL() string { return n.srv.URL }

// Answer sets the status the service answers from now on.
func (n *NotifierFake) Answer(status int) { n.status.Store(int32(status)) } //nolint:gosec // an HTTP status

// Received returns the requests received, oldest first.
func (n *NotifierFake) Received() []Received {
	n.mu.Lock()
	defer n.mu.Unlock()
	return append([]Received(nil), n.received...)
}

// Reset forgets the requests and answers 202 again.
func (n *NotifierFake) Reset() {
	n.mu.Lock()
	n.received = nil
	n.mu.Unlock()
	n.status.Store(http.StatusAccepted)
}

// Close stops the fake.
func (n *NotifierFake) Close() { n.srv.CloseClientConnections(); n.srv.Close() }
