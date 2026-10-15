package httpfw

import (
	"net/http"

	"github.com/flowcatalyst/framework/go/internal/javafmt"
)

// JSON answers with v as JSON written as the Java framework's Jackson mapper writes it (a nil
// pointer is null; leave a value out with omitzero). Typed operations use the same writer.
func JSON(w http.ResponseWriter, status int, v any) error {
	b, err := javafmt.MarshalJSON(v)
	if err != nil {
		return err
	}
	writeBytes(w, status, "application/json", b)
	return nil
}

// Text answers with plain text.
func Text(w http.ResponseWriter, status int, s string) {
	writeBytes(w, status, "text/plain; charset=utf-8", []byte(s))
}

// HTML answers with an HTML document.
func HTML(w http.ResponseWriter, status int, s string) {
	writeBytes(w, status, "text/html; charset=utf-8", []byte(s))
}

// Bytes answers with a body of a given content type.
func Bytes(w http.ResponseWriter, status int, contentType string, body []byte) {
	writeBytes(w, status, contentType, body)
}

// NoContent answers 204.
func NoContent(w http.ResponseWriter) { w.WriteHeader(http.StatusNoContent) }

// Redirect answers 302 with a Location and no body.
func Redirect(w http.ResponseWriter, location string) {
	w.Header().Set("Location", location)
	w.WriteHeader(http.StatusFound)
}
