package httpclient

import (
	"encoding/json/v2"
	"net/http"
	"regexp"
	"strconv"
)

var errorCodePattern = regexp.MustCompile(`^[A-Z][A-Z0-9_]{0,99}$`)

// Response is an answer, whatever its status.
type Response struct {
	status      int
	headers     http.Header
	body        []byte
	description string
}

// Status returns the HTTP status.
func (r *Response) Status() int { return r.status }

// IsSuccess reports a 2xx status.
func (r *Response) IsSuccess() bool { return r.status >= 200 && r.status < 300 }

// Header returns the first value of a header (matched ignoring case), or "".
func (r *Response) Header(name string) string { return r.headers.Get(name) }

// Headers returns every value of a header.
func (r *Response) Headers(name string) []string {
	return append([]string(nil), r.headers.Values(name)...)
}

// Bytes returns a copy of the body.
func (r *Response) Bytes() []byte { return append([]byte(nil), r.body...) }

// Text returns the body as text (UTF-8).
func (r *Response) Text() string { return string(r.body) }

// JSON decodes the body into v, ignoring unknown members. A body that is not that JSON is a
// KindDecode error whose message has no part of the body (the parser's message can quote it).
func (r *Response) JSON(v any) error {
	if err := json.Unmarshal(r.body, v); err != nil {
		return &Error{Kind: KindDecode, msg: r.description + ": the " + itoa(r.status) + " response body is not JSON for the type asked for"}
	}
	return nil
}

// errorCode returns the "error" code of the platform's error envelope, when the body is one.
func (r *Response) errorCode() string {
	var env struct {
		Error any `json:"error"`
	}
	if err := json.Unmarshal(r.body, &env); err != nil {
		return ""
	}
	if s, ok := env.Error.(string); ok && errorCodePattern.MatchString(s) {
		return s
	}
	return ""
}

func itoa(n int) string { return strconv.Itoa(n) }
