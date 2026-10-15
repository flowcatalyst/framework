package spa

import (
	"net/http"
	"strconv"
	"strings"
)

const (
	immutable    = "public, max-age=31536000, immutable"
	notBuiltText = "Frontend not built into this application. Run the web build (pnpm --dir web build) and rebuild.\n"
)

// Reply is what the router gives the SPA to answer with.
type Reply interface {
	// Bind labels the request for the access log and the HTTP metrics (the fixed template "(spa)",
	// never the raw path).
	Bind(label string)
	// Error writes the framework's JSON envelope.
	Error(w http.ResponseWriter, status int, code, message string)
}

// Answer answers a request the router did not match; segments are its path split on "/" and
// percent-decoded per segment, as the router does. It reports false, having written nothing, for a
// path outside the SPA's prefix: that is the router's own 404.
func (h *Handler) Answer(w http.ResponseWriter, r *http.Request, segments []string, reply Reply) bool {
	prefix := h.spa.prefix
	// 1. outside the prefix: the router's 404
	if len(segments) < len(prefix) {
		return false
	}
	for i, p := range prefix {
		if segments[i] != p {
			return false
		}
	}
	rest := segments[len(prefix):]
	root := len(rest) == 0 || (len(rest) == 1 && rest[0] == "")
	// 2. an API prefix: the router's 404, never index.html, for any method
	if !root && h.apiPrefixes[rest[0]] {
		h.notFound(w, reply)
		return true
	}
	reply.Bind("(spa)")
	if h.notBuilt {
		h.secure(w)
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Header().Set("Content-Length", strconv.Itoa(len(notBuiltText)))
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(notBuiltText))
		return true
	}
	// 3. only GET and HEAD
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		h.secure(w)
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Allow", "GET, HEAD")
		reply.Error(w, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "method not allowed for this path")
		return true
	}
	// 4. unclean paths never reach the index
	if !root && !clean(rest) {
		h.notFound(w, reply)
		return true
	}
	// 5. the prefix itself, or index.html
	index := h.spa.files["index.html"]
	if root || (len(rest) == 1 && rest[0] == "index.html") {
		h.serve(w, r, index, "no-cache")
		return true
	}
	// 6. an indexed file
	if f, ok := h.spa.files[strings.Join(rest, "/")]; ok {
		cache := "no-cache"
		if len(rest) > 1 && rest[0] == h.spa.assetsDirectory {
			cache = immutable
		}
		h.serve(w, r, f, cache)
		return true
	}
	// 7. a missing asset: a 404, never index.html loaded as a script
	if rest[0] == h.spa.assetsDirectory || isAssetExtension(rest[len(rest)-1]) {
		h.notFound(w, reply)
		return true
	}
	// 8. a client route
	h.serve(w, r, index, "no-cache")
	return true
}

// clean is contract section 4.1, per decoded segment: nothing empty, no segment starting with a
// dot ('.' and '..' included), no backslash, slash, control character or DEL.
func clean(segments []string) bool {
	for _, s := range segments {
		if s == "" || s[0] == '.' {
			return false
		}
		for i := range len(s) {
			if c := s[i]; c == '\\' || c == '/' || c < 0x20 || c == 0x7f {
				return false
			}
		}
	}
	return true
}

func (h *Handler) notFound(w http.ResponseWriter, reply Reply) {
	h.secure(w)
	w.Header().Set("Cache-Control", "no-store")
	reply.Error(w, http.StatusNotFound, "NOT_FOUND", "no route for this path")
}

func (h *Handler) secure(w http.ResponseWriter) {
	for _, kv := range h.headers {
		w.Header().Set(kv[0], kv[1])
	}
}

func (h *Handler) serve(w http.ResponseWriter, r *http.Request, f file, cacheControl string) {
	h.secure(w)
	w.Header().Set("Cache-Control", cacheControl)
	w.Header()["ETag"] = []string{f.etag} //nolint:staticcheck // the name as the contract writes it, not Go's canonical "Etag"
	if matches(r.Header.Values("If-None-Match"), f.etag) {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	w.Header().Set("Content-Type", f.contentType)
	w.Header().Set("Content-Length", strconv.Itoa(len(f.bytes)))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(f.bytes)
}

// matches is If-None-Match with the weak comparison: a list of tags, W/ ignored, or *.
func matches(values []string, etag string) bool {
	for _, v := range values {
		for part := range strings.SplitSeq(v, ",") {
			tag := strings.TrimSpace(part)
			if tag == "*" {
				return true
			}
			tag = strings.TrimPrefix(tag, "W/")
			if tag == etag {
				return true
			}
		}
	}
	return false
}
