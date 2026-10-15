package httpclient

import (
	"errors"
	"net/url"
	"strconv"
)

// Kind says why a request produced no usable response.
type Kind int

// The failure kinds.
const (
	// KindConnect means the connection could not be made (including the TLS handshake).
	KindConnect Kind = iota + 1
	// KindTimeout means the attempt ran past its timeout.
	KindTimeout
	// KindResponseTooLarge means the answer was larger than the client's limit.
	KindResponseTooLarge
	// KindIO means the exchange broke after the connection was made.
	KindIO
	// KindDecode means the body is not the JSON asked for. The parser's message is not
	// attached because it can quote the body.
	KindDecode
	// KindAuth means an authentication strategy could not get credentials; a credential
	// function's own error is reduced to its type name.
	KindAuth
	// KindCanceled means the caller's context ended (cancelled or past its deadline).
	KindCanceled
	// KindUnexpectedStatus means a convenience method such as ReceiveJSON got a status that is not
	// 2xx. Status and Code say which.
	KindUnexpectedStatus
	// KindClosed means the engine was closed: a programming error, not a failure of the exchange.
	KindClosed
)

// String returns the lower-case name used as the outcome label of the client metrics.
func (k Kind) String() string {
	switch k {
	case KindConnect:
		return "connect"
	case KindTimeout:
		return "timeout"
	case KindResponseTooLarge:
		return "response_too_large"
	case KindIO:
		return "io"
	case KindDecode:
		return "decode"
	case KindAuth:
		return "auth"
	case KindCanceled:
		return "canceled"
	case KindUnexpectedStatus:
		return "unexpected_status"
	case KindClosed:
		return "closed"
	}
	return "unknown"
}

// Error is a failure of a request. Its message names the service, method and path template.
type Error struct {
	// Kind says what failed.
	Kind Kind
	// Status is the HTTP status of a KindUnexpectedStatus error, else 0.
	Status int
	// Code is the "error" code of the platform's error envelope in a KindUnexpectedStatus
	// answer, when the body has one.
	Code string

	msg   string
	cause error
}

func (e *Error) Error() string { return e.msg }

// Unwrap returns the cause, with the URL (and so any query string) removed from network errors.
func (e *Error) Unwrap() error { return e.cause }

// ErrInvalidRequest is wrapped by the error a request returns when it was built wrongly: a path
// that could leave the service, a placeholder without a value, a header that is not allowed.
var ErrInvalidRequest = errors.New("invalid request")

// stripURL removes the URL from a *url.Error, so that a cause never carries a query string.
func stripURL(err error) error {
	if ue, ok := errors.AsType[*url.Error](err); ok && ue.Err != nil {
		return ue.Err
	}
	return err
}

func statusMessage(description string, status int, code string) string {
	m := description + " answered " + strconv.Itoa(status)
	if code != "" {
		m += " " + code
	}
	return m
}

func describeKind(k Kind) string {
	switch k {
	case KindConnect:
		return "could not connect"
	case KindTimeout:
		return "timed out"
	case KindResponseTooLarge:
		return "the response is larger than the maximum"
	case KindIO:
		return "the exchange failed"
	case KindDecode:
		return "the body could not be decoded"
	case KindAuth:
		return "no credentials"
	case KindCanceled:
		return "canceled"
	case KindClosed:
		return "the HTTP client engine is closed"
	case KindUnexpectedStatus:
		return "unexpected status"
	}
	return "failed"
}
