package httpfw

import (
	"encoding/json/jsontext"
	"errors"
	"net/http"
	"slices"
	"strconv"
	"strings"

	"github.com/flowcatalyst/framework/go/internal/javafmt"
)

// Detail is one entry of an error's details: a key (a field path) and a string value.
type Detail struct{ Key, Value string }

// ServiceError is an expected failure that knows how to be answered: a use case's *usecase.Error
// is one. WriteError and the typed operations answer it with the platform's envelope.
type ServiceError interface {
	error
	// Status is the HTTP status, 400 to 599.
	Status() int
	// Code is the envelope's "error" value.
	Code() string
	// Message is safe to show a caller.
	Message() string
	// Details are extra string values, omitted from the envelope when empty.
	Details() map[string]string
}

// Error is an HTTP failure with the envelope's four parts. Return it from a handler (or wrap it
// with %w) and the error-mapping middleware answers it. It implements [ServiceError].
type Error struct {
	status  int
	code    string
	message string
	details []Detail
}

// NewError returns an Error. Details keep the order given.
func NewError(status int, code, message string, details ...Detail) *Error {
	return &Error{status: status, code: code, message: message, details: slices.Clone(details)}
}

// Validation is a 400 VALIDATION failure.
func Validation(message string) *Error { return NewError(http.StatusBadRequest, "VALIDATION", message) }

// Unauthenticated is a 401 UNAUTHENTICATED failure (what a ContextResolver returns).
func Unauthenticated(message string) *Error {
	return NewError(http.StatusUnauthorized, "UNAUTHENTICATED", message)
}

// Forbidden is a 403 failure with the given code.
func Forbidden(code, message string) *Error { return NewError(http.StatusForbidden, code, message) }

// NotFound is a 404 NOT_FOUND failure.
func NotFound(message string) *Error { return NewError(http.StatusNotFound, "NOT_FOUND", message) }

func (e *Error) Error() string { return strconv.Itoa(e.status) + " " + e.code + ": " + e.message }

// Status implements [ServiceError].
func (e *Error) Status() int { return e.status }

// Code implements [ServiceError].
func (e *Error) Code() string { return e.code }

// Message implements [ServiceError].
func (e *Error) Message() string { return e.message }

// Details implements [ServiceError].
func (e *Error) Details() map[string]string {
	if len(e.details) == 0 {
		return nil
	}
	m := make(map[string]string, len(e.details))
	for _, d := range e.details {
		m[d.Key] = d.Value
	}
	return m
}

// orderedDetails returns the details in the order given.
func (e *Error) orderedDetails() []Detail { return e.details }

// serviceDetails returns a ServiceError's details in a stable order: an *Error keeps the order it
// was given, any other implementation is sorted by key (a map has no order).
func serviceDetails(se ServiceError) []Detail {
	if e, ok := se.(*Error); ok {
		return e.orderedDetails()
	}
	m := se.Details()
	out := make([]Detail, 0, len(m))
	for k, v := range m {
		out = append(out, Detail{k, v})
	}
	slices.SortFunc(out, func(a, b Detail) int { return compareStrings(a.Key, b.Key) })
	return out
}

func compareStrings(a, b string) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	}
	return 0
}

// envelope renders {"error":..., "message":..., "details":{...}} compactly, details left out when
// empty, strings escaped as the Java framework's Jackson mapper writes them.
func envelope(code, message string, details []Detail) []byte {
	b := []byte(`{"error":`)
	b = appendQuoted(b, code)
	b = append(b, `,"message":`...)
	b = appendQuoted(b, message)
	if len(details) > 0 {
		b = append(b, `,"details":{`...)
		for i, d := range details {
			if i > 0 {
				b = append(b, ',')
			}
			b = appendQuoted(b, d.Key)
			b = append(b, ':')
			b = appendQuoted(b, d.Value)
		}
		b = append(b, '}')
	}
	return append(b, '}')
}

func appendQuoted(b []byte, s string) []byte {
	q, _ := jsontext.AppendQuote(nil, strings.ToValidUTF8(s, "\uFFFD")) // cannot fail on valid UTF-8
	return append(b, javafmt.UpperHexEscapes(q)...)
}

// writeEnvelope answers with the platform's error envelope.
func writeEnvelope(w http.ResponseWriter, status int, code, message string, details []Detail) {
	if status == http.StatusRequestEntityTooLarge {
		w.Header().Set("Connection", "close") // the unread body is dropped with the connection
	}
	writeBytes(w, status, "application/json", envelope(code, message, details))
}

// writeBytes answers with an in-memory body and its Content-Length.
func writeBytes(w http.ResponseWriter, status int, contentType string, body []byte) {
	h := w.Header()
	h.Set("Content-Type", contentType)
	h.Set("Content-Length", strconv.Itoa(len(body)))
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

// WriteError answers err with the envelope: a [ServiceError] with its status, code, message and
// details; any other error as 500 INTERNAL "internal error" (the cause is the caller's to log,
// never returned).
func WriteError(w http.ResponseWriter, err error) {
	if se, ok := errors.AsType[ServiceError](err); ok {
		writeEnvelope(w, se.Status(), se.Code(), se.Message(), serviceDetails(se))
		return
	}
	writeEnvelope(w, http.StatusInternalServerError, "INTERNAL", "internal error", nil)
}
