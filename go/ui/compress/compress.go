// Package compress is opt-in response compression for the server-rendered UI
// (contract server-rendered-ui.md section 12): Brotli or gzip, chosen
// from Accept-Encoding, for the server-rendered pages (text/html) and the
// Datastar streams (text/event-stream).
//
// Handler wraps the page group (and may wrap the static assets). It decides when the response's header is
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
// An ETag is a validator of one representation, and a compressed body is
// another one (contract section 12): a compressed response's ETag gets the
// coding as a suffix inside the quotes ("abc" becomes "abc-br" or "abc-gz";
// a weak tag keeps its W/), and an If-None-Match entry that carries the
// suffix of the coding this request negotiated is read as the handler's own
// tag, so the handler's conditional-request logic (the assets' 304) works
// unchanged and each variant matches only its own tag. An entry of the other
// coding ("abc-gz" for a Brotli response) stays as it is and matches nothing.
// An entry with no suffix still reaches the handler as sent, so it matches the
// uncompressed tag: the 304 then answers with the plain tag, and the browser
// that stored the uncompressed body keeps it.
//
// Pages, streams and the static assets (text/css, text/javascript,
// image/svg+xml) can go through Handler, which compresses the assets again on
// every request; the assets' WithCompression compresses each file once. The
// SPA handler still does not compress.
// For content compressed ahead of time there are Encode, Negotiate and VariantTag; the
// assets' WithCompression uses them, and an answer that already has a Content-Encoding passes
// through Handler as it is.
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

// streamed are the media types Handler compresses (contract section 12).
var streamed = map[string]bool{
	"text/html": true, "text/event-stream": true,
	"text/css": true, "text/javascript": true, "image/svg+xml": true,
}

// Compressible reports whether a static file of this Content-Type is compressed (contract section
// 12): text/css, text/javascript and image/svg+xml.
func Compressible(contentType string) bool {
	switch mediaType(contentType) {
	case "text/css", "text/javascript", "image/svg+xml":
		return true
	}
	return false
}

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

// Levels for content compressed once and kept (Encode, the assets' WithCompression). Measured on
// mermaid.min.js (5.5 MB): Brotli 9 → 1.31 MB in 0.31 s, 11 → 1.18 MB in 6.7 s (too long for the
// request that pays for it); gzip 9 → 1.58 MB in 0.13 s.
const (
	encodeBrotliLevel = 9
	encodeGzipLevel   = gzip.BestCompression
)

// Encode compresses body once with coding (Brotli 9, gzip 9), for content compressed once and
// kept, like the static assets. An unknown coding panics.
func Encode(coding string, body []byte) []byte {
	var buf bytes.Buffer
	var w io.WriteCloser
	switch coding {
	case Brotli:
		w = brotli.NewWriterLevel(&buf, encodeBrotliLevel)
	case Gzip:
		gw, err := gzip.NewWriterLevel(&buf, encodeGzipLevel)
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

// suffix is the ETag suffix of a coding.
func suffix(coding string) string {
	if coding == Brotli {
		return "-br"
	}
	return "-gz"
}

// Handler compresses the text/html, text/event-stream, text/css, text/javascript and image/svg+xml
// responses of next, and gives a compressed response's ETag the coding's suffix (see the package
// comment).
func Handler(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		cw := &writer{ResponseWriter: w, coding: Negotiate(r.Header.Get("Accept-Encoding")), head: r.Method == http.MethodHead}
		if cw.coding != "" && len(r.Header.Values("If-None-Match")) > 0 {
			var stripped bool
			r = r.Clone(r.Context())
			stripped, r.Header["If-None-Match"] = stripVariant(r.Header.Values("If-None-Match"), suffix(cw.coding))
			cw.matchedVariant = stripped
		}
		defer cw.finish()
		next.ServeHTTP(cw, r)
	})
}

// stripVariant removes the suffix from every entity-tag of the If-None-Match values that carries
// it, and reports whether one did.
func stripVariant(values []string, sfx string) (bool, []string) {
	var stripped bool
	out := make([]string, 0, len(values))
	for _, v := range values {
		parts := strings.Split(v, ",")
		for i, part := range parts {
			t := strings.TrimSpace(part)
			if tag, ok := strings.CutSuffix(t, sfx+`"`); ok && (strings.HasPrefix(tag, `"`) || strings.HasPrefix(tag, `W/"`)) {
				parts[i] = tag + `"`
				stripped = true
			}
		}
		out = append(out, strings.Join(parts, ","))
	}
	return stripped, out
}

// VariantTag is the ETag of etag's representation compressed with coding: the coding's suffix
// inside the quotes ("abc" becomes "abc-br" or "abc-gz", W/ kept). It is what Handler sends, for a
// handler that compresses its own content (the assets' WithCompression).
func VariantTag(etag, coding string) string { return variantTag(etag, suffix(coding)) }

// variantTag adds the suffix to a quoted entity-tag, W/ prefix kept; anything else is returned as
// it is.
func variantTag(etag, sfx string) string {
	if tag, ok := strings.CutSuffix(etag, `"`); ok && (strings.HasPrefix(tag, `"`) || strings.HasPrefix(tag, `W/"`)) && len(tag) > 1 {
		return tag + sfx + `"`
	}
	return etag
}

type writer struct {
	http.ResponseWriter
	coding  string
	decided bool
	enc     encoder
	head    bool // a HEAD request: no body is written, so the header is decided when the handler returns
	// matchedVariant: the request's If-None-Match named this response's variant (a suffixed tag), so
	// a 304 validates the compressed representation
	matchedVariant bool
}

// decide runs once, when the header is committed.
func (w *writer) decide(status int) {
	if w.decided {
		return
	}
	w.decided = true
	h := w.Header()
	if status == http.StatusNotModified && w.matchedVariant && h.Get("ETag") != "" {
		// the 304 of a conditional request that named the compressed variant (it has no Content-Type
		// to look at): the same tag the 200 carries
		h.Set("ETag", variantTag(h.Get("ETag"), suffix(w.coding)))
		AddVary(h)
		return
	}
	if !streamed[mediaType(h.Get("Content-Type"))] || h.Get("Content-Encoding") != "" {
		return
	}
	AddVary(h)
	if w.coding == "" || status == http.StatusNoContent || status == http.StatusNotModified {
		return
	}
	if etag := h.Get("ETag"); etag != "" {
		h.Set("ETag", variantTag(etag, suffix(w.coding)))
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
	if !w.decided && w.head {
		w.decide(http.StatusOK) // a HEAD answer carries the headers of the GET answer
	}
	if w.enc == nil {
		return
	}
	_ = w.enc.Close() // the client may have gone; nothing to report to
	w.enc.Reset(io.Discard)
	pools[w.coding].Put(w.enc)
	w.enc = nil
}
