package auth

import (
	"net/http"
	"net/url"
	"slices"
	"strings"

	"github.com/flowcatalyst/framework/go/httpfw"
)

// LoginFailure is why a sign-in did not complete: at /auth/callback (contract section 4.2), or, for
// a browser navigation only, a refusal before it (contract 4.1.1 and section 8).
type LoginFailure struct {
	// Status is the default status: 400, 429 or 502.
	Status int
	// Code is INVALID_CALLBACK, LOGIN_NOT_STARTED_HERE, INVALID_STATE, LOGIN_REFUSED, LOGIN_FAILED,
	// INVALID_ID_TOKEN, PLATFORM_UNAVAILABLE or RATE_LIMITED.
	Code string
	// Message is for people; it never contains a token, a code or the state.
	Message string
	// Details are the envelope's details, for example reason or error.
	Details map[string]string
}

// LoginFailureHandler answers a failed sign-in. The default ([JSONFailures]) is the JSON error
// envelope; the framework renders no pages. It receives the failures of /auth/callback and, for a
// browser navigation only, a rate-limited or platform-unavailable /auth/login (contract 4.1.1); an
// API or fetch request always gets the JSON envelope. The login binding cookie is cleared by the
// framework on the response, and Cache-Control is no-store.
type LoginFailureHandler func(w http.ResponseWriter, r *http.Request, f LoginFailure) error

// JSONFailures is the default handler: the error envelope with the failure's status, code and
// details.
func JSONFailures() LoginFailureHandler {
	return func(_ http.ResponseWriter, _ *http.Request, f LoginFailure) error {
		keys := make([]string, 0, len(f.Details))
		for k := range f.Details {
			keys = append(keys, k)
		}
		slices.Sort(keys)
		details := make([]httpfw.Detail, len(keys))
		for i, k := range keys {
			details[i] = httpfw.Detail{Key: k, Value: f.Details[k]}
		}
		return httpfw.NewError(f.Status, f.Code, f.Message, details...)
	}
}

// RedirectFailuresTo is a handler that answers 302 to an application page with ?error=<CODE> (or
// &error= when the path has a query). The path must be a same-origin path.
func RedirectFailuresTo(path string) (LoginFailureHandler, error) {
	if !isSameOriginPath(path) {
		return nil, &pathError{what: "a login failure page", path: path}
	}
	sep := "?"
	if strings.Contains(path, "?") {
		sep = "&"
	}
	return func(w http.ResponseWriter, _ *http.Request, f LoginFailure) error {
		httpfw.Redirect(w, path+sep+"error="+url.QueryEscape(f.Code))
		return nil
	}, nil
}

type pathError struct{ what, path string }

func (e *pathError) Error() string { return e.what + " must be a same-origin path: " + e.path }

// ReturnToPolicy decides where a browser lands after signing in (contract section 4.1). It gets
// the returnTo query parameter ("" when absent) and returns a same-origin path, or "" for the
// default landing. Whatever it returns is checked again.
type ReturnToPolicy func(requested string) string

// SameOriginReturnTo is the default: the requested value when it is a same-origin path.
func SameOriginReturnTo() ReturnToPolicy {
	return func(requested string) string {
		if requested != "" && isSameOriginPath(requested) {
			return requested
		}
		return ""
	}
}
