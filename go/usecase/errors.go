package usecase

import (
	"errors"
	"fmt"
	"maps"
)

// Name is a use case's name, [a-z][a-z0-9-]*, used in logs, metrics and the audit row.
type Name struct{ v string }

// ParseName checks a use-case name.
func ParseName(s string) (Name, error) {
	if !isSlug(s) {
		return Name{}, fmt.Errorf("use case name must match [a-z][a-z0-9-]*: %q", s)
	}
	return Name{v: s}, nil
}

// MustName is [ParseName] for literals; it panics on a bad name (fwvet checks it).
func MustName(s string) Name {
	n, err := ParseName(s)
	if err != nil {
		panic(err)
	}
	return n
}

// String returns the name.
func (n Name) String() string { return n.v }

// IsZero reports whether n is the zero Name.
func (n Name) IsZero() bool { return n.v == "" }

// ErrorCode is an UPPER_SNAKE error code ([A-Z][A-Z0-9_]*).
type ErrorCode struct{ v string }

// ParseErrorCode checks an error code.
func ParseErrorCode(s string) (ErrorCode, error) {
	if s == "" || s[0] < 'A' || s[0] > 'Z' {
		return ErrorCode{}, fmt.Errorf("error code must be UPPER_SNAKE: %q", s)
	}
	for i := 1; i < len(s); i++ {
		c := s[i]
		if (c < 'A' || c > 'Z') && (c < '0' || c > '9') && c != '_' {
			return ErrorCode{}, fmt.Errorf("error code must be UPPER_SNAKE: %q", s)
		}
	}
	return ErrorCode{v: s}, nil
}

// MustErrorCode is [ParseErrorCode] for literals; it panics on a bad code (fwvet checks it).
func MustErrorCode(s string) ErrorCode {
	c, err := ParseErrorCode(s)
	if err != nil {
		panic(err)
	}
	return c
}

// String returns the code.
func (c ErrorCode) String() string { return c.v }

// IsZero reports whether c is the zero ErrorCode.
func (c ErrorCode) IsZero() bool { return c.v == "" }

// Kind is what kind of failure an [Error] is; it fixes the HTTP status (write-path contract
// section 5).
type Kind int

// The kinds.
const (
	KindValidation Kind = iota + 1
	KindAuthorization
	KindNotFound
	KindConflict
	KindBusinessRule
	KindInternal
)

// String returns the kind's name as the contract writes it (VALIDATION, ...).
func (k Kind) String() string {
	switch k {
	case KindValidation:
		return "VALIDATION"
	case KindAuthorization:
		return "AUTHORIZATION"
	case KindNotFound:
		return "NOT_FOUND"
	case KindConflict:
		return "CONFLICT"
	case KindBusinessRule:
		return "BUSINESS_RULE"
	case KindInternal:
		return "INTERNAL"
	}
	return "UNKNOWN"
}

// Status returns the HTTP status of the kind.
func (k Kind) Status() int {
	switch k {
	case KindValidation:
		return 400
	case KindAuthorization:
		return 403
	case KindNotFound:
		return 404
	case KindConflict, KindBusinessRule:
		return 409
	case KindInternal:
		return 500
	}
	return 500
}

// Error is why a use case did not commit: kind, code, a message safe to show a caller, and
// string details. A cause, when there is one, is logged and never part of the message.
type Error struct {
	kind    Kind
	code    string
	message string
	details map[string]string
	cause   error
}

func newError(kind Kind, code ErrorCode, message string) *Error {
	if code.IsZero() {
		code = ErrorCode{v: "UNSPECIFIED"}
	}
	return &Error{kind: kind, code: code.v, message: message}
}

// Validation returns a VALIDATION error (400).
func Validation(code ErrorCode, message string) *Error {
	return newError(KindValidation, code, message)
}

// Authorization returns an AUTHORIZATION error (403).
func Authorization(code ErrorCode, message string) *Error {
	return newError(KindAuthorization, code, message)
}

// NotFound returns a NOT_FOUND error (404).
func NotFound(code ErrorCode, message string) *Error { return newError(KindNotFound, code, message) }

// Conflict returns a CONFLICT error (409).
func Conflict(code ErrorCode, message string) *Error { return newError(KindConflict, code, message) }

// BusinessRule returns a BUSINESS_RULE error (409).
func BusinessRule(code ErrorCode, message string) *Error {
	return newError(KindBusinessRule, code, message)
}

// Internal returns an INTERNAL error (500) whose message is fixed text naming the code; pass the
// cause to the logger, never into the message.
func Internal(code ErrorCode) *Error {
	e := newError(KindInternal, code, "")
	e.message = "The operation could not be completed (" + e.code + ")."
	return e
}

// internal is Internal for the runner's own codes, with the cause kept for errors.Is/As.
func internal(code string, cause error) *Error {
	e := Internal(ErrorCode{v: code})
	e.cause = cause
	return e
}

// WithDetails returns a copy with details.
func (e *Error) WithDetails(details map[string]string) *Error {
	c := *e
	c.details = maps.Clone(details)
	return &c
}

// Error returns "KIND CODE: message".
func (e *Error) Error() string { return e.kind.String() + " " + e.code + ": " + e.message }

// Kind returns the kind.
func (e *Error) Kind() Kind { return e.kind }

// Code returns the UPPER_SNAKE code.
func (e *Error) Code() string { return e.code }

// Message returns the message for the caller.
func (e *Error) Message() string { return e.message }

// Details returns a copy of the details (never nil).
func (e *Error) Details() map[string]string {
	if e.details == nil {
		return map[string]string{}
	}
	return maps.Clone(e.details)
}

// Status returns the HTTP status of the kind (the http layer's ServiceError).
func (e *Error) Status() int { return e.kind.Status() }

// Unwrap returns the cause of an INTERNAL error, if any.
func (e *Error) Unwrap() error { return e.cause }

// AsError returns err as an *Error when it is one (or wraps one).
func AsError(err error) (*Error, bool) { return errors.AsType[*Error](err) }

func isSlug(s string) bool {
	if s == "" || s[0] < 'a' || s[0] > 'z' {
		return false
	}
	for i := 1; i < len(s); i++ {
		c := s[i]
		if (c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '-' {
			return false
		}
	}
	return true
}
