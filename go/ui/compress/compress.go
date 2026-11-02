// Package compress is opt-in response compression for the server-rendered UI
// (contract server-rendered-ui.md section 12): Brotli or gzip, chosen
// from Accept-Encoding, for the server-rendered pages (text/html) and the
// Datastar streams (text/event-stream).
//
// Handler wraps the page group. It decides when the response's header is
// committed (WriteHeader, the first Write or the first Flush, which is how
// the Datastar SDK starts a stream), compresses only the types above, never
// a response that already has a Content-Encoding, and adds
// "Vary: Accept-Encoding" to every response of those types, compressed or
// not. A Flush flushes the encoder and then the connection, so every
// Datastar event reaches the browser as soon as it is sent. When the handler
// returns the encoder is closed (the gzip trailer / the final Brotli block)
// and pooled.
//
// The Datastar SDK's own compression option is not the model: it never closes
// its encoder, so every stream ends without the gzip trailer or the final
// Brotli block (a strict decoder reports an unexpected EOF), it sets no Vary,
// and it builds its encoders on every request.
//
// The static assets are not served through Handler: a server that wants them
// compressed encodes each text asset once (Encode) and picks a variant with
// Negotiate.
package compress

import (
	"bytes"
	"compress/gzip"
	"io"
	"net/http"
	"strconv"
	"strings"
	"sync"

	"github.com/andybalholm/brotli"
)

// Content codings, as in Accept-Encoding and Content-Encoding.
const (
	Brotli = "br"
	Gzip   = "gzip"
)

// Levels for responses compressed while they are sent. Measured on a
// 1,000-row dispatch-jobs stream (2.9 MB): Brotli 4 → 49 KB in 5.9 ms, 5 →
// 38 KB in 7.7 ms, 6 → 37 KB in 7.8 ms; gzip 5 → 91 KB in 2.9 ms, 6 → 83 KB
// in 3.8 ms.
const (
	brotliLevel = 5
	gzipLevel   = gzip.DefaultCompression
)

// Negotiate picks the coding for a request's Accept-Encoding: Brotli or
// gzip, whichever has the higher q-value (Brotli on a tie), "" for none.
// q=0 refuses a coding; "*" stands for any coding not listed by name
// (RFC 9110, 12.5.3).
func Negotiate(acceptEncoding string) string {
	qBr, qGz := -1.0, -1.0
	qAny := -1.0
	for part := range strings.SplitSeq(acceptEncoding, ",") {
		name, params, _ := strings.Cut(strings.TrimSpace(part), ";")
		name = strings.ToLower(strings.TrimSpace(name))
		q := 1.0
		if k, v, ok := strings.Cut(strings.TrimSpace(params), "="); ok && strings.EqualFold(strings.TrimSpace(k), "q") {
			f, err := strconv.ParseFloat(strings.TrimSpace(v), 64)
			if err != nil {
				continue
			}
			q = f
		}
		switch name {
		case Brotli:
			qBr = q
		case Gzip, "x-gzip":
			qGz = q
		case "*":
			qAny = q
		}
	}
	if qBr < 0 {
		qBr = qAny
	}
	if qGz < 0 {
		qGz = qAny
	}
	switch {
	case qBr > 0 && qBr >= qGz:
		return Brotli
	case qGz > 0:
		return Gzip
	}
	return ""
}

// streamed are the media types Handler compresses.
var streamed = map[string]bool{"text/html": true, "text/event-stream": true}

func mediaType(contentType string) string {
	t, _, _ := strings.Cut(contentType, ";")
	return strings.ToLower(strings.TrimSpace(t))
}

// AddVary adds Accept-Encoding to the response's Vary header once.
func AddVary(h http.Header) {
	for _, v := range h.Values("Vary") {
		for f := range strings.SplitSeq(v, ",") {
			if f = strings.TrimSpace(f); f == "*" || strings.EqualFold(f, "Accept-Encoding") {
				return
			}
		}
	}
	h.Add("Vary", "Accept-Encoding")
}

// encoder is what both pooled writers provide.
type encoder interface {
	io.Writer
	Flush() error
	Close() error
	Reset(io.Writer)
}

var pools = map[string]*sync.Pool{
	Brotli: {New: func() any { return brotli.NewWriterLevel(io.Discard, brotliLevel) }},
	Gzip: {New: func() any {
		w, err := gzip.NewWriterLevel(io.Discard, gzipLevel)
		if err != nil {
			panic(err) // gzipLevel is a constant in range
		}
		return w
	}},
}

// Encode compresses body once with coding at the best level (for content
// compressed ahead of time, like the embedded assets).
func Encode(coding string, body []byte) []byte {
	var buf bytes.Buffer
	var w io.WriteCloser
	switch coding {
	case Brotli:
		w = brotli.NewWriterLevel(&buf, brotli.BestCompression)
	case Gzip:
		gw, err := gzip.NewWriterLevel(&buf, gzip.BestCompression)
		if err != nil {
			panic(err)
		}
		w = gw
	default:
		panic("compress: unknown coding " + coding)
	}
	if _, err := w.Write(body); err != nil {
		panic(err) // writes to a bytes.Buffer do not fail
	}
	if err := w.Close(); err != nil {
		panic(err)
	}
	return buf.Bytes()
}

// Handler compresses the text/html and text/event-stream responses of next.
func Handler(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		cw := &writer{ResponseWriter: w, coding: Negotiate(r.Header.Get("Accept-Encoding"))}
		defer cw.finish()
		next.ServeHTTP(cw, r)
	})
}

type writer struct {
	http.ResponseWriter
	coding  string
	decided bool
	enc     encoder
}

// decide runs once, when the header is committed.
func (w *writer) decide(status int) {
	if w.decided {
		return
	}
	w.decided = true
	h := w.Header()
	if !streamed[mediaType(h.Get("Content-Type"))] || h.Get("Content-Encoding") != "" {
		return
	}
	AddVary(h)
	if w.coding == "" || status == http.StatusNoContent || status == http.StatusNotModified {
		return
	}
	h.Set("Content-Encoding", w.coding)
	h.Del("Content-Length")
	enc, _ := pools[w.coding].Get().(encoder)
	enc.Reset(w.ResponseWriter)
	w.enc = enc
}

func (w *writer) WriteHeader(status int) {
	if status >= 100 && status < 200 {
		w.ResponseWriter.WriteHeader(status) // informational: the real header follows
		return
	}
	w.decide(status)
	w.ResponseWriter.WriteHeader(status)
}

func (w *writer) Write(p []byte) (int, error) {
	if !w.decided {
		w.WriteHeader(http.StatusOK)
	}
	if w.enc != nil {
		return w.enc.Write(p)
	}
	return w.ResponseWriter.Write(p)
}

// FlushError sends what was written so far: the encoder's pending output,
// then the connection's buffer (http.ResponseController uses it).
func (w *writer) FlushError() error {
	if !w.decided {
		w.WriteHeader(http.StatusOK)
	}
	if w.enc != nil {
		if err := w.enc.Flush(); err != nil {
			return err
		}
	}
	return http.NewResponseController(w.ResponseWriter).Flush()
}

// Flush is http.Flusher.
func (w *writer) Flush() { _ = w.FlushError() }

// Unwrap gives http.ResponseController the connection's writer (deadlines).
func (w *writer) Unwrap() http.ResponseWriter { return w.ResponseWriter }

// finish ends the compressed stream and returns the encoder to its pool.
func (w *writer) finish() {
	if w.enc == nil {
		return
	}
	_ = w.enc.Close() // the client may have gone; nothing to report to
	w.enc.Reset(io.Discard)
	pools[w.coding].Put(w.enc)
	w.enc = nil
}
