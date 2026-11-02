package fwui

import (
	"errors"
	"log/slog"
	"net/http"

	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/ui/ds"
	"github.com/flowcatalyst/framework/go/ui/kit"
	"github.com/flowcatalyst/framework/go/ui/views"
)

// genericFailure is shown for anything that is not an expected failure.
const genericFailure = "The request could not be completed. Try again, or reload the page."

// Failure is what a page shows for an error a handler got back, mapped from the framework's errors
// (a use case's *usecase.Error, an httpfw error).
type Failure struct {
	// Status is the HTTP status of the error, 400 to 599 (500 for an error that is not a
	// ServiceError). A Datastar answer is 200 whatever this is; the real status is only logged.
	Status int
	// Code is the error code of the envelope ("VALIDATION", "ORDER_CLOSED"), "INTERNAL" for an
	// unexpected error.
	Code string
	// Message is safe to show: the message of an expected (4xx) failure, a generic sentence for
	// anything else (the cause is logged, never shown).
	Message string
	// Details are the error's string details (field name to message), empty for unexpected errors.
	Details map[string]string
}

// Expected reports a failure caused by the request (a 4xx): the user can be told why.
func (f Failure) Expected() bool { return f.Status >= 400 && f.Status < 500 }

// Rerender reports a failure a native form post answers by showing the form again with the message
// and Status 422: a validation failure (400), a conflict or a broken business rule (409) or a 422.
// The others (403, 404, 5xx) are not about the form's fields; the handler returns the error.
func (f Failure) Rerender() bool {
	return f.Status == http.StatusBadRequest || f.Status == http.StatusConflict || f.Status == http.StatusUnprocessableEntity
}

// FormStatus is the status of the re-rendered page for a native form post: 422 when [Failure.Rerender],
// else the failure's own status.
func (f Failure) FormStatus() int {
	if f.Rerender() {
		return http.StatusUnprocessableEntity
	}
	return f.Status
}

// Title is the short headline of a flash message for the failure.
func (f Failure) Title() string {
	switch {
	case f.Status == http.StatusForbidden || f.Status == http.StatusUnauthorized:
		return "Not allowed."
	case f.Status == http.StatusNotFound:
		return "Not found."
	case f.Status == http.StatusConflict:
		return "That could not be done."
	case f.Expected():
		return "Check the details."
	}
	return "Something went wrong."
}

// Classify maps an error to a [Failure]. A [httpfw.ServiceError] (which a use case's *usecase.Error
// is) with a 4xx status keeps its code, message and details; anything else, a 5xx included, becomes
// a 500 with a generic message, because its message may name internals.
func Classify(err error) Failure {
	if se, ok := errors.AsType[httpfw.ServiceError](err); ok {
		if status := se.Status(); status >= 400 && status < 500 {
			return Failure{Status: status, Code: se.Code(), Message: se.Message(), Details: se.Details()}
		}
		return Failure{Status: se.Status(), Code: se.Code(), Message: genericFailure}
	}
	return Failure{Status: http.StatusInternalServerError, Code: "INTERNAL", Message: genericFailure}
}

func (m *Module) log(r *http.Request, f Failure, err error) {
	if f.Expected() {
		m.logger().InfoContext(r.Context(), "ui: action refused", slog.String("path", r.URL.Path),
			slog.Int("status", f.Status), slog.String("code", f.Code), slog.String("message", f.Message))
		return
	}
	m.logger().ErrorContext(r.Context(), "ui: handler failed", slog.String("path", r.URL.Path), slog.Any("error", err))
}

func (m *Module) logger() *slog.Logger {
	if m.logr == nil {
		return slog.New(slog.DiscardHandler)
	}
	return m.logr
}

// FlashFailure patches the failure into the page's flash stack, on a stream the handler has already
// opened (the status line is sent, so the failure can only be a patch). It returns the error of the
// patch, not err.
func (m *Module) FlashFailure(p *views.Patches, r *http.Request, err error) error {
	f := Classify(err)
	m.log(r, f, err)
	return p.Flash(kit.FlashError, f.Title(), f.Message)
}

// Fail answers err for a request that can only show a patch. On a Datastar request it writes the
// answer: status 200 and one error flash message (Datastar discards the body of any other status),
// and returns the Failure with answered true. On a native request it writes nothing and returns
// answered false: the handler re-renders the form with the failure when [Failure.Rerender]
// (views.WithStatus(f.FormStatus()), f.Message and f.Details in the page), and otherwise returns err
// for the framework to answer.
//
//	if err != nil {
//		f, answered := mod.Fail(w, r, err)
//		if answered {
//			return nil
//		}
//		if !f.Rerender() {
//			return err
//		}
//		return mod.Views().Page(w, r, "New order", orderForm(f), views.WithStatus(f.FormStatus()))
//	}
func (m *Module) Fail(w http.ResponseWriter, r *http.Request, err error) (f Failure, answered bool) {
	f = Classify(err)
	if !ds.IsDatastar(r) {
		m.log(r, f, err)
		return f, false
	}
	// a stream that fails to start or write has nobody to tell
	if sErr := m.views.Stream(w, r, func(p *views.Patches) error { return m.FlashFailure(p, r, err) }); sErr != nil {
		m.logger().DebugContext(r.Context(), "ui: failure patch not delivered", slog.Any("error", sErr))
	}
	return f, true
}
