package httpfw

import (
	"context"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"strconv"
	"strings"

	"github.com/flowcatalyst/framework/go/fw"
)

// reqState is what the dispatcher keeps about one request, reached through the request's context.
type reqState struct {
	correlation fw.CorrelationID
	route       string // the matched pattern, "(spa)" or "" (unmatched)
	maxBody     int64
	logger      *slog.Logger
	cx          fw.Context
	hasCx       bool
}

type stateKey struct{}

func stateOf(r *http.Request) *reqState {
	if st, ok := r.Context().Value(stateKey{}).(*reqState); ok {
		return st
	}
	panic("httpfw: the request did not come through the framework's dispatcher")
}

// CorrelationID returns the request's correlation id: the valid X-Correlation-Id the caller sent,
// else a generated one.
func CorrelationID(r *http.Request) fw.CorrelationID { return stateOf(r).correlation }

// RoutePattern returns the pattern of the matched route ("/orders/{id}"), "" when none matched.
func RoutePattern(r *http.Request) string {
	if p := stateOf(r).route; p != "(spa)" {
		return p
	}
	return ""
}

// ContextOf returns the fw.Context the ContextResolver produced for a route that requires one; it
// panics on a route that does not (a programming error caught by the first test).
func ContextOf(r *http.Request) fw.Context {
	st := stateOf(r)
	if !st.hasCx {
		panic("httpfw: this route was not registered with HandleContext, RequiresContext or a permission")
	}
	return st.cx
}

// ContextIfAny returns the fw.Context the ContextResolver produced for a route that requires one,
// and false on a request that has none (global middleware, which runs before context resolution,
// and routes registered without RequiresContext). Middleware that works with and without a
// context, such as the rate limiter's key resolvers, uses it instead of [ContextOf].
func ContextIfAny(r *http.Request) (fw.Context, bool) {
	st := stateOf(r)
	return st.cx, st.hasCx
}

// QueryValue returns the first value of a query parameter. A malformed percent-escape in the query
// is a 400 VALIDATION failure.
func QueryValue(r *http.Request, name string) (string, bool, error) {
	vs, err := QueryValues(r, name)
	if err != nil || len(vs) == 0 {
		return "", false, err
	}
	return vs[0], true, nil
}

// QueryValues returns every value of a query parameter, in order.
func QueryValues(r *http.Request, name string) ([]string, error) {
	var out []string
	raw := r.URL.RawQuery
	for pair := range strings.SplitSeq(raw, "&") {
		if pair == "" {
			continue
		}
		k, v, _ := strings.Cut(pair, "=")
		dk, ok1 := decodeSegment(k, true)
		dv, ok2 := decodeSegment(v, true)
		if !ok1 || !ok2 {
			return nil, NewError(http.StatusBadRequest, "VALIDATION", "request is invalid",
				Detail{"query", "INVALID_ENCODING: contains an invalid percent-escape"})
		}
		if dk == name {
			out = append(out, dv)
		}
	}
	return out, nil
}

// jsonOptions decode a request body strictly: unknown members, duplicate names and wrong-case
// names are refused (HTTP contract section 7).
var jsonOptions = json.JoinOptions(json.RejectUnknownMembers(true))

// readBody reads the request body up to the limit; one over it is a 413.
func readBody(r *http.Request) ([]byte, error) {
	limit := stateOf(r).maxBody
	if r.ContentLength > limit {
		return nil, NewError(http.StatusRequestEntityTooLarge, "PAYLOAD_TOO_LARGE", "request body is too large")
	}
	b, err := io.ReadAll(http.MaxBytesReader(nil, r.Body, limit))
	var tooBig *http.MaxBytesError
	switch {
	case errors.As(err, &tooBig):
		return nil, NewError(http.StatusRequestEntityTooLarge, "PAYLOAD_TOO_LARGE", "request body is too large")
	case err != nil:
		if r.Context().Err() != nil {
			return nil, r.Context().Err()
		}
		return nil, NewError(http.StatusBadRequest, "VALIDATION", "the request body could not be read")
	}
	return b, nil
}

// ReadBody reads the whole request body, up to http.max-body-bytes: a body over the limit is a 413
// PAYLOAD_TOO_LARGE error, a body that cannot be read a 400 VALIDATION one. For a handler that
// needs the exact bytes (a signature is computed over them) rather than a decoded value.
func ReadBody(r *http.Request) ([]byte, error) { return readBody(r) }

// requireJSON refuses a request whose content type is not application/json.
func requireJSON(r *http.Request) error {
	mt, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || mt != "application/json" {
		return NewError(http.StatusUnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE", "content type must be application/json")
	}
	return nil
}

// DecodeJSON reads the request body as a T: 415 unless the content type is application/json, 413
// over the body limit, and 400 VALIDATION with one INVALID_JSON detail (the path of the member,
// never its value) for malformed JSON, a wrong type or an unknown member. It does not validate T;
// typed operations do that with their validator.
func DecodeJSON[T any](r *http.Request) (T, error) {
	var v T
	if err := requireJSON(r); err != nil {
		return v, err
	}
	b, err := readBody(r)
	if err != nil {
		return v, err
	}
	if err := json.Unmarshal(b, &v, jsonOptions); err != nil {
		return v, invalidJSON(err)
	}
	return v, nil
}

// invalidJSON turns a decode failure into a 400 with one INVALID_JSON detail whose message never
// echoes the offending value.
func invalidJSON(err error) *Error {
	field, message := "body", "invalid JSON"
	if se, ok := errors.AsType[*json.SemanticError](err); ok {
		field = pointerPath(string(se.JSONPointer))
		switch {
		case errors.Is(se.Err, json.ErrUnknownName):
			message = "unknown property"
		case se.Err != nil && strings.Contains(se.Err.Error(), "cannot be null"):
			message = "must not be null"
		default:
			message = "wrong type or format"
		}
	} else if sy, ok := errors.AsType[*jsontext.SyntacticError](err); ok {
		field = pointerPath(string(sy.JSONPointer))
		message = "malformed JSON"
		if errors.Is(sy.Err, io.ErrUnexpectedEOF) {
			message = "unexpected end of input"
		}
	}
	return NewError(http.StatusBadRequest, "VALIDATION", "request is invalid", Detail{field, "INVALID_JSON: " + message})
}

// pointerPath turns a JSON Pointer (/a/b/1) into the framework's field path (a.b[1]); the root is
// "body".
func pointerPath(p string) string {
	if p == "" {
		return "body"
	}
	var sb strings.Builder
	for part := range strings.SplitSeq(strings.TrimPrefix(p, "/"), "/") {
		part = strings.NewReplacer("~1", "/", "~0", "~").Replace(part)
		if _, err := strconv.Atoi(part); err == nil {
			sb.WriteString("[" + part + "]")
			continue
		}
		if sb.Len() > 0 {
			sb.WriteByte('.')
		}
		sb.WriteString(part)
	}
	if sb.Len() == 0 {
		return "body"
	}
	return sb.String()
}

func loggerOf(r *http.Request) *slog.Logger { return stateOf(r).logger }

// canceled reports a request the client abandoned: nothing can be written to it.
func canceled(r *http.Request, err error) bool {
	return r.Context().Err() != nil && errors.Is(err, context.Canceled)
}
