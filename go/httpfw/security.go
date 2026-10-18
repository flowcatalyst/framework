package httpfw

import (
	"net/http"

	"github.com/flowcatalyst/framework/go/httpfw/spa"
)

// SecurityHeaders is middleware that puts the static-assets contract's section 6.1 headers on every
// response that does not already carry them: Referrer-Policy same-origin, X-Frame-Options DENY and
// a Content-Security-Policy (the platform's baseline unless another is given). X-Content-Type-Options:
// nosniff is not repeated here: the dispatcher sets it on every response it sends. The SPA's own
// responses carry these headers already (package spa); this is for pages and API answers an
// application serves itself.
//
// HSTS is the TLS terminator's job (the HTTP contract leaves TLS outside the application).
func SecurityHeaders(policy ...spa.CSP) Middleware {
	csp := spa.Baseline().String()
	if len(policy) > 0 && policy[0].String() != "" {
		csp = policy[0].String()
	}
	return func(next HandlerFunc) HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) error {
			h := w.Header()
			setDefault(h, "Referrer-Policy", "same-origin")
			setDefault(h, "X-Frame-Options", "DENY")
			setDefault(h, "Content-Security-Policy", csp)
			return next(w, r)
		}
	}
}

func setDefault(h http.Header, name, value string) {
	if h.Get(name) == "" {
		h.Set(name, value)
	}
}
