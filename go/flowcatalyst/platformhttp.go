package flowcatalyst

import (
	"bytes"
	"context"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// CallKind says what a failed platform call means, for callers that answer differently (the admin
// pages).
type CallKind int

const (
	// KindOther is anything else: unreachable, a timeout, a server error, a refused credential.
	KindOther CallKind = iota
	// KindForbidden is the platform refusing the application's service account (HTTP 403).
	KindForbidden
	// KindNotFound is the platform having no such record (HTTP 404).
	KindNotFound
	// KindRejected is the platform understanding the request and saying no, with a code and a
	// message meant for the person who made it (HTTP 400, 409 or 422, or a 403 that names a reason
	// of its own such as ROLE_APP_FORBIDDEN).
	KindRejected
)

func (k CallKind) String() string {
	switch k {
	case KindForbidden:
		return "FORBIDDEN"
	case KindNotFound:
		return "NOT_FOUND"
	case KindRejected:
		return "REJECTED"
	case KindOther:
	}
	return "OTHER"
}

// ClientRefusal is what the platform says about a client it will not invite into: a 403 with a
// reason, not a missing permission.
const ClientRefusal = "client is not available to this application"

// platformHTTP is the plain HTTP path to the platform for the admin reads and writes (admin
// contract section 12): one request with the service account's bearer token, JSON in and out, one
// retry after a 401 with a fresh token. It logs nothing: a request or response body can carry an
// invitation link.
type platformHTTP struct {
	http  *http.Client
	base  string
	token func(ctx context.Context) (string, error)
	clear func()
}

func newPlatformHTTP(base string, timeout time.Duration, token func(ctx context.Context) (string, error), clear func()) *platformHTTP {
	return &platformHTTP{
		http: &http.Client{
			Timeout:       timeout,
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
		base: strings.TrimRight(base, "/"), token: token, clear: clear,
	}
}

// query is a list of query parameters in order; an empty value is left out.
type query [][2]string

func (q query) add(name, value string) query {
	if value == "" {
		return q
	}
	return append(q, [2]string{name, value})
}

func (q query) encode() string {
	var sb strings.Builder
	sep := "?"
	for _, p := range q {
		sb.WriteString(sep + url.QueryEscape(p[0]) + "=" + url.QueryEscape(p[1]))
		sep = "&"
	}
	return sb.String()
}

// pathSegment percent-encodes one path segment.
func pathSegment(s string) string { return strings.ReplaceAll(url.PathEscape(s), ":", "%3A") }

// call makes one request (and one more after a 401); body is a value to send as JSON, or nil. It
// returns the answer's bytes (empty for an empty answer).
func (h *platformHTTP) call(ctx context.Context, method, path string, q query, body any) ([]byte, error) {
	return h.exchange(ctx, method, path, q, body, kindOf)
}

// read is call for the plain reads (event types, roles), where the platform's reasons are not
// passed on: a 403 is a missing grant, a 404 a missing record, anything else a failure.
func (h *platformHTTP) read(ctx context.Context, path string) ([]byte, error) {
	return h.exchange(ctx, "GET", path, nil, nil, plainKind)
}

func plainKind(status int, _, _ string) CallKind {
	switch status {
	case http.StatusForbidden:
		return KindForbidden
	case http.StatusNotFound:
		return KindNotFound
	}
	return KindOther
}

func (h *platformHTTP) exchange(ctx context.Context, method, path string, q query, body any, kinds func(int, string, string) CallKind) ([]byte, error) {
	var payload []byte
	if body != nil {
		var err error
		if payload, err = json.Marshal(body); err != nil {
			return nil, &PlatformError{msg: "the request to the platform cannot be encoded", cause: err}
		}
	}
	for attempt := 0; ; attempt++ {
		status, answer, err := h.send(ctx, method, path+q.encode(), payload)
		if err != nil {
			return nil, err
		}
		if status == http.StatusUnauthorized && attempt == 0 {
			h.clear()
			continue
		}
		return interpret(status, answer, kinds)
	}
}

func (h *platformHTTP) send(ctx context.Context, method, target string, payload []byte) (int, []byte, error) {
	token, err := h.token(ctx)
	if err != nil {
		return 0, nil, err
	}
	var reader io.Reader = http.NoBody
	if payload != nil {
		reader = bytes.NewReader(payload)
	}
	req, err := http.NewRequestWithContext(ctx, method, h.base+target, reader)
	if err != nil {
		return 0, nil, &PlatformError{msg: "the request to the platform cannot be made", cause: err}
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Accept", "application/json")
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := h.http.Do(req)
	if err != nil {
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return 0, nil, err
		}
		// the error of an I/O failure can name hosts; it is the cause, never shown
		return 0, nil, &PlatformError{msg: "the platform could not be reached", cause: err}
	}
	defer func() { _ = resp.Body.Close() }()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil {
		return 0, nil, &PlatformError{msg: "the platform's answer could not be read", cause: err}
	}
	return resp.StatusCode, b, nil
}

func interpret(status int, body []byte, kinds func(int, string, string) CallKind) ([]byte, error) {
	if status >= 200 && status < 300 {
		if len(bytes.TrimSpace(body)) > 0 && !jsontext.Value(body).IsValid() {
			return nil, &PlatformError{msg: fmt.Sprintf("the platform answered %d with something that is not JSON", status)}
		}
		return body, nil
	}
	var code, message string
	var tree struct {
		Error   jsontext.Value `json:"error"`
		Message jsontext.Value `json:"message"`
	}
	if json.Unmarshal(body, &tree) == nil {
		if k := tree.Error.Kind(); k == '"' {
			var s string
			if json.Unmarshal(tree.Error, &s) == nil {
				code = cleanText(s, 80)
			}
		} else if k == '{' {
			var o struct {
				Code string `json:"code"`
			}
			if json.Unmarshal(tree.Error, &o) == nil {
				code = cleanText(o.Code, 80)
			}
		}
		if tree.Message.Kind() == '"' {
			var s string
			if json.Unmarshal(tree.Message, &s) == nil {
				message = cleanText(s, 300)
			}
		}
	}
	text := fmt.Sprintf("the platform answered %d", status)
	if code != "" {
		text += " " + code
	}
	return nil, &PlatformError{msg: text, kind: kinds(status, code, message), status: status, code: code, serverMessage: message}
}

// kindOf is what an answer means.
func kindOf(status int, code, message string) CallKind {
	switch {
	case status == http.StatusNotFound:
		return KindNotFound
	case status == http.StatusForbidden:
		if (code != "" && code != "FORBIDDEN") || strings.EqualFold(message, ClientRefusal) {
			return KindRejected
		}
		return KindForbidden
	case status == http.StatusBadRequest || status == http.StatusConflict || status == http.StatusUnprocessableEntity:
		return KindRejected
	case status == http.StatusInternalServerError && code == "INVITE_DELIVERY_FAILED":
		return KindRejected
	}
	return KindOther
}
