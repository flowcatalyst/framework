package platformadmin

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"regexp"
	"strconv"
	"strings"

	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// maxLimit is the largest page the admin API serves.
const maxLimit = 200

// idPattern is a platform id as it may appear in a path: nothing that could change the path.
var idPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

// caller makes the platform calls of the sections: the client, the log, and the rule that turns a
// failure into an answer (admin contract sections 11 and 12).
type caller struct {
	client *flowcatalyst.PlatformClient
	log    *slog.Logger
}

// run runs a platform read. A failure is 502 PLATFORM_ERROR (the cause goes to the log, not to the
// browser), except a 404 when notFoundCode is given, a refusal the platform explained for the person
// who asked (its status, code and message, in its own words), and 503 PLATFORM_NOT_CONFIGURED when
// there is no connection.
func run[T any](ctx context.Context, c caller, notFoundCode, notFoundMessage string, call func() (T, error)) (T, error) {
	return execute(ctx, c, notFoundCode, notFoundMessage, false, call)
}

// write is run for a call that changes something (the 502 for a missing permission says so).
func write[T any](ctx context.Context, c caller, notFoundCode, notFoundMessage string, call func() (T, error)) (T, error) {
	return execute(ctx, c, notFoundCode, notFoundMessage, true, call)
}

func execute[T any](ctx context.Context, c caller, notFoundCode, notFoundMessage string, change bool, call func() (T, error)) (T, error) {
	var zero T
	if !c.client.Enabled() {
		return zero, httpfw.NewError(http.StatusServiceUnavailable, "PLATFORM_NOT_CONFIGURED",
			"the application has no platform connection (flowcatalyst.enabled is false)")
	}
	out, err := call()
	if err == nil {
		return out, nil
	}
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return zero, err
	}
	pe, ok := errors.AsType[*flowcatalyst.PlatformError](err)
	kind := flowcatalyst.KindOther
	if ok {
		kind = pe.Kind()
	}
	switch {
	case kind == flowcatalyst.KindNotFound && notFoundCode != "":
		if notFoundMessage == "" {
			notFoundMessage = "not found"
		}
		return zero, httpfw.NewError(http.StatusNotFound, notFoundCode, notFoundMessage)
	case kind == flowcatalyst.KindRejected:
		return zero, rejected(pe)
	}
	// The cause can name hosts and request details: it goes to the log, not to the browser. It never
	// holds a request or response body (the transport reads the platform's code and message only).
	c.log.WarnContext(ctx, "an admin call to the platform failed", "kind", kind.String(), "error", err.Error())
	if kind == flowcatalyst.KindForbidden {
		if change {
			return zero, httpfw.NewError(http.StatusBadGateway, "PLATFORM_ERROR",
				"the platform refused this change for the application's service account; it needs the platform's permission to manage this resource")
		}
		return zero, httpfw.NewError(http.StatusBadGateway, "PLATFORM_ERROR",
			"the platform refused this read for the application's service account; it needs the platform's view permission for this resource")
	}
	return zero, httpfw.NewError(http.StatusBadGateway, "PLATFORM_ERROR", "the platform could not be reached or answered with an error")
}

// rejected is the platform saying no and saying why: its code and message, with the status it used (a
// 500 is a 502 here).
func rejected(e *flowcatalyst.PlatformError) *httpfw.Error {
	status := e.Status()
	if status >= http.StatusInternalServerError {
		status = http.StatusBadGateway
	}
	code := e.Code()
	switch code {
	case "":
		code = "REQUEST_REFUSED"
	case "FORBIDDEN":
		// A 403 with a reason of its own (the client is not available to this application): not the
		// admin gate's FORBIDDEN, which means the signed-in user lacks a permission.
		code = "CLIENT_NOT_AVAILABLE"
	}
	message := e.ServerMessage()
	if message == "" {
		message = "the platform refused the request"
	}
	return httpfw.NewError(status, code, message)
}

// pathID is the id as it may appear in a path, or a 404 with the section's own code (the platform is
// not asked).
func pathID(id, notFoundCode, notFoundMessage string) (string, error) {
	if !idPattern.MatchString(id) {
		return "", httpfw.NewError(http.StatusNotFound, notFoundCode, notFoundMessage)
	}
	return id, nil
}

// clampLimit is a page size: 1 to 200.
func clampLimit(limit int32) int {
	return int(max(1, min(maxLimit, limit)))
}

// cursor is a cursor that is a non-negative number (a page or an offset), 0 for none.
func cursor(after string, present bool) (int, error) {
	if !present || after == "" {
		return 0, nil
	}
	n, err := strconv.Atoi(after)
	if err != nil || n < 0 {
		return 0, httpfw.NewError(http.StatusBadRequest, "INVALID_CURSOR", "the cursor is not one this server gave")
	}
	return n, nil
}

// optional is the text of an optional field: absent when blank.
func optional(text string) *string {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	return &text
}

// Part is a section of this package, bound by [Build].
type Part interface {
	bind(rt *httpfw.Routes, cl caller) error
}

// Build binds the operations of the sections declared with DeclareCatalog, DeclareUsers and
// DeclareActivity, in the build function, once the platform client exists. The client may be a
// disabled one (flowcatalyst.enabled false): every operation then answers 503 PLATFORM_NOT_CONFIGURED.
// An enabled client for another application than the sections serve fails the build.
func Build(b *fw.Boot, client *flowcatalyst.PlatformClient, parts ...Part) (*httpfw.Routes, error) {
	rt := httpfw.NewRoutes("admin-platform")
	cl := caller{client: client, log: b.Logger("admin-platform")}
	for _, p := range parts {
		if err := p.bind(rt, cl); err != nil {
			return nil, err
		}
	}
	return rt, nil
}
