package httpclient

import (
	"context"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/internal/javafmt"
)

// Request is one request being described; Send runs it. Not safe for concurrent use; use it once.
// A mistake in describing it (a path that could leave the service, a placeholder without a value,
// a header that is not allowed) is collected and returned by Send.
type Request struct {
	svc       *Service
	method    string
	path      pathTemplate
	params    map[string]string
	query     [][2]string
	headers   http.Header
	body      []byte
	retryable *bool
	timeout   time.Duration
	err       error
}

func (r *Request) fail(err error) *Request {
	if r.err == nil {
		r.err = err
	}
	return r
}

// PathParam fills a {name} placeholder; the value is percent-encoded as one segment, and may not
// be empty, "." or "..".
func (r *Request) PathParam(name, value string) *Request {
	if r.err != nil {
		return r
	}
	if !r.path.has(name) {
		return r.fail(fmt.Errorf("%w: the path %s has no placeholder {%s}", ErrInvalidRequest, r.path.text, name))
	}
	if _, err := encodeSegment(value); err != nil {
		return r.fail(err)
	}
	r.params[name] = value
	return r
}

// Query adds a query parameter (repeatable); name and value are percent-encoded.
func (r *Request) Query(name, value string) *Request {
	if name == "" {
		return r.fail(fmt.Errorf("%w: a query parameter needs a name", ErrInvalidRequest))
	}
	r.query = append(r.query, [2]string{name, value})
	return r
}

// Header adds a header. The name is a token and not Host, Content-Length, Transfer-Encoding or
// Connection; the value has no CR, LF or NUL.
func (r *Request) Header(name, value string) *Request {
	if err := checkHeader(name, value); err != nil {
		return r.fail(err)
	}
	r.headers.Add(name, value)
	return r
}

// JSON sends v as a JSON body (Content-Type: application/json), written as the framework's own
// JSON is.
func (r *Request) JSON(v any) *Request {
	b, err := javafmt.MarshalJSON(v)
	if err != nil {
		return r.fail(fmt.Errorf("%w: the body could not be encoded as JSON (%T)", ErrInvalidRequest, v))
	}
	r.body = b
	r.headers.Set("Content-Type", "application/json")
	return r
}

// Body sends raw bytes of a content type.
func (r *Request) Body(b []byte, contentType string) *Request {
	if err := checkHeaderValue("Content-Type", contentType); err != nil {
		return r.fail(err)
	}
	r.body = append([]byte(nil), b...)
	r.headers.Set("Content-Type", contentType)
	return r
}

// Form sends an application/x-www-form-urlencoded body of name, value pairs, in order.
func (r *Request) Form(pairs ...string) *Request {
	if len(pairs)%2 != 0 {
		return r.fail(fmt.Errorf("%w: Form takes name, value pairs", ErrInvalidRequest))
	}
	var sb strings.Builder
	for i := 0; i < len(pairs); i += 2 {
		if i > 0 {
			sb.WriteByte('&')
		}
		sb.WriteString(formEscape(pairs[i]) + "=" + formEscape(pairs[i+1]))
	}
	r.body = []byte(sb.String())
	r.headers.Set("Content-Type", "application/x-www-form-urlencoded")
	return r
}

// IdempotencyKey sets Idempotency-Key (1 to 255 characters), which also makes the request
// retryable.
func (r *Request) IdempotencyKey(key string) *Request {
	if strings.TrimSpace(key) == "" || len(key) > 255 {
		return r.fail(fmt.Errorf("%w: an idempotency key is 1 to 255 characters", ErrInvalidRequest))
	}
	if err := checkHeaderValue("Idempotency-Key", key); err != nil {
		return r.fail(err)
	}
	r.headers.Set("Idempotency-Key", key)
	return r
}

// Retryable overrides whether the request may be retried: true for a non-idempotent request you
// know is safe to repeat, false to send an idempotent one only once.
func (r *Request) Retryable(v bool) *Request {
	r.retryable = &v
	return r
}

// Timeout overrides the service's per-attempt timeout for this request.
func (r *Request) Timeout(d time.Duration) *Request {
	if d <= 0 {
		return r.fail(fmt.Errorf("%w: timeout must be positive", ErrInvalidRequest))
	}
	r.timeout = d
	return r
}

// Send runs the request with retries and authentication as the service has them. It returns an
// error only when there is no response ([*Error]); a status, whatever it is, is a [Response].
func (r *Request) Send(ctx context.Context) (*Response, error) {
	return r.svc.execute(ctx, r)
}

// ReceiveJSON sends the request and decodes a 2xx JSON body into a T; any other status is an
// [*Error] of KindUnexpectedStatus with the status and the error envelope's code (never the body).
// Unknown members of the body are ignored: another service may add fields.
func ReceiveJSON[T any](ctx context.Context, r *Request) (T, error) {
	var zero T
	resp, err := r.Send(ctx)
	if err != nil {
		return zero, err
	}
	if !resp.IsSuccess() {
		code := resp.errorCode()
		return zero, &Error{Kind: KindUnexpectedStatus, Status: resp.status, Code: code, msg: statusMessage(resp.description, resp.status, code)}
	}
	var v T
	if err := resp.JSON(&v); err != nil {
		return zero, err
	}
	return v, nil
}
