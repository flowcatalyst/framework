// Package ds is the framework's Datastar 1.0 adapter on net/http, written to
// docs/contracts/http.md section 10 (as amended by the server-rendered UI
// contract, section 15). It has no dependency on the framework's router or
// lifecycle and none on the Datastar SDK, so any net/http program can use it.
//
// A Datastar backend action (`@get`, `@post`, ...) sends `Datastar-Request:
// true`; the answer is `text/event-stream` carrying `datastar-patch-elements`
// and `datastar-patch-signals` events. The client's signals arrive as the
// `datastar` query parameter on GET, HEAD and DELETE and as a JSON body on
// every other method (a `{contentType: 'form'}` action sends the form and no
// signals); [ReadSignals] reads them, capped at [MaxSignalBytes].
//
// Error contract (docs/contracts/server-rendered-ui.md section 6 rule 4):
// Datastar applies a body only when the status is 200, so a handler that
// reached its own logic answers every outcome with 200 and at least one patch.
// Non-200 answers are left to the guards, crashes and proxies.
//
// Typical handler:
//
//	if ds.IsDatastar(r) {
//		sse := ds.NewSSE(w, r)
//		err := errors.Join(
//			sse.Patch(PoolRows(view)), // morphs the element with the same id
//			sse.Patch(PoolCount(view), ds.Inner("#pools-count")),
//		)
//		...
//		return
//	}
//
// Every event is written whole and flushed before the call returns. An SSE is
// for one goroutine at a time.
package ds

import (
	"context"
	"encoding/json"
	jsonv2 "encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
)

// RequestHeader is the header every Datastar backend action sends.
const RequestHeader = "Datastar-Request"

// MaxSignalBytes caps how much of a request body [ReadSignals] reads (1 MiB);
// a larger body is refused with 413.
const MaxSignalBytes = 1 << 20

// DefaultRetry is the SSE default reconnect delay; a retry field is written
// only for another value.
const DefaultRetry = 1000 * time.Millisecond

// IsDatastar reports whether r was sent by a Datastar backend action (as
// opposed to a browser navigation or a native form post). The header value is
// compared case-insensitively.
func IsDatastar(r *http.Request) bool {
	return strings.EqualFold(r.Header.Get(RequestHeader), "true")
}

// Component is anything that renders HTML: a templ.Component satisfies it.
// Declared here so this package does not import templ.
type Component interface {
	Render(ctx context.Context, w io.Writer) error
}

// Mode is how a patched element is applied to the page.
type Mode string

// Patch modes (Datastar 1.0). Outer is the default: the patched element
// morphs into the page element with the same id.
const (
	ModeOuter   Mode = "outer"
	ModeInner   Mode = "inner"
	ModeReplace Mode = "replace"
	ModePrepend Mode = "prepend"
	ModeAppend  Mode = "append"
	ModeBefore  Mode = "before"
	ModeAfter   Mode = "after"
	ModeRemove  Mode = "remove"
)

var modes = []Mode{ModeOuter, ModeInner, ModeReplace, ModePrepend, ModeAppend, ModeBefore, ModeAfter, ModeRemove}

// Namespace is the XML namespace the patched markup is parsed in.
type Namespace string

// Namespaces. HTML is the default and is not written.
const (
	NamespaceHTML   Namespace = "html"
	NamespaceSVG    Namespace = "svg"
	NamespaceMathML Namespace = "mathml"
)

type commonOptions struct {
	eventID  string
	retry    time.Duration
	hasRetry bool
}

type elementsOptions struct {
	commonOptions
	selector       string
	mode           Mode
	viewTransition bool
	namespace      Namespace
}

type signalsOptions struct {
	commonOptions
	onlyIfMissing bool
}

// PatchOption adjusts one patch-elements event.
type PatchOption interface{ applyElements(*elementsOptions) }

// SignalsOption adjusts one patch-signals event.
type SignalsOption interface{ applySignals(*signalsOptions) }

type elementsOpt func(*elementsOptions)

func (f elementsOpt) applyElements(o *elementsOptions) { f(o) }

type signalsOpt func(*signalsOptions)

func (f signalsOpt) applySignals(o *signalsOptions) { f(o) }

// EventOption is an option both kinds of event take: [WithEventID], [WithRetry].
type EventOption func(*commonOptions)

func (f EventOption) applyElements(o *elementsOptions) { f(&o.commonOptions) }
func (f EventOption) applySignals(o *signalsOptions)   { f(&o.commonOptions) }

// WithEventID sets the event's `id:` field.
func WithEventID(id string) EventOption {
	return func(o *commonOptions) { o.eventID = id }
}

// WithRetry sets the event's `retry:` field (written only when it is not
// [DefaultRetry]; milliseconds on the wire).
func WithRetry(d time.Duration) EventOption {
	return func(o *commonOptions) { o.retry, o.hasRetry = d, true }
}

// WithSelector targets the elements matching a CSS selector instead of the
// element with the patched element's id.
func WithSelector(selector string) PatchOption {
	return elementsOpt(func(o *elementsOptions) { o.selector = selector })
}

// WithMode sets the patch mode. Every mode but outer and replace needs a
// selector.
func WithMode(m Mode) PatchOption {
	return elementsOpt(func(o *elementsOptions) { o.mode = m })
}

// WithViewTransition asks the browser to animate the change with a view
// transition (written only when true).
func WithViewTransition() PatchOption {
	return elementsOpt(func(o *elementsOptions) { o.viewTransition = true })
}

// WithNamespace parses the markup in an XML namespace (SVG, MathML).
func WithNamespace(n Namespace) PatchOption {
	return elementsOpt(func(o *elementsOptions) { o.namespace = n })
}

func selecting(selector string, m Mode) PatchOption {
	return elementsOpt(func(o *elementsOptions) { o.selector, o.mode = selector, m })
}

// Inner replaces the children of the element matching selector.
func Inner(selector string) PatchOption { return selecting(selector, ModeInner) }

// Append appends to the element matching selector (e.g. a flash stack).
func Append(selector string) PatchOption { return selecting(selector, ModeAppend) }

// Prepend prepends to the element matching selector.
func Prepend(selector string) PatchOption { return selecting(selector, ModePrepend) }

// Replace removes the element matching selector and inserts the markup (for a
// modal dialog the answer closes: a morph would leave it modal).
func Replace(selector string) PatchOption { return selecting(selector, ModeReplace) }

// Before inserts before the element matching selector.
func Before(selector string) PatchOption { return selecting(selector, ModeBefore) }

// After inserts after the element matching selector.
func After(selector string) PatchOption { return selecting(selector, ModeAfter) }

// OnlyIfMissing sets only the signals the page does not have yet.
func OnlyIfMissing() SignalsOption {
	return signalsOpt(func(o *signalsOptions) { o.onlyIfMissing = true })
}

// SSE is one Datastar response stream.
type SSE struct {
	w   http.ResponseWriter
	rc  *http.ResponseController
	ctx context.Context
	err error // sticky: the first failure to start or write
}

// NewSSE starts a `text/event-stream` response: it sets the headers
// (`Content-Type: text/event-stream`, `Cache-Control: no-cache`, and
// `Connection: keep-alive` for HTTP/1.1 only), writes the status line and
// flushes. A ResponseWriter that cannot flush makes every later call return
// the error (net/http's and its wrappers that implement Unwrap can).
func NewSSE(w http.ResponseWriter, r *http.Request) *SSE {
	s := &SSE{w: w, rc: http.NewResponseController(w), ctx: r.Context()}
	h := w.Header()
	h.Set("Content-Type", "text/event-stream")
	h.Set("Cache-Control", "no-cache")
	if r.ProtoMajor == 1 && r.ProtoMinor == 1 {
		h.Set("Connection", "keep-alive")
	}
	w.WriteHeader(http.StatusOK)
	if err := s.rc.Flush(); err != nil {
		s.err = fmt.Errorf("ds: start stream: %w", err)
	}
	return s
}

// Context is the request context the stream is bound to.
func (s *SSE) Context() context.Context { return s.ctx }

// Patch renders c and sends it as one datastar-patch-elements event. The
// component is rendered completely before anything is written.
func (s *SSE) Patch(c Component, opts ...PatchOption) error {
	var b strings.Builder
	if err := c.Render(s.ctx, &b); err != nil {
		return fmt.Errorf("ds: render patch: %w", err)
	}
	return s.PatchHTML(b.String(), opts...)
}

// PatchHTML sends already-rendered (and already-escaped) HTML as one
// datastar-patch-elements event. Prefer [SSE.Patch] with a component.
// The markup may be blank only with [ModeRemove] (with a selector, or with the
// elements to remove); a selector or event id with a line break is refused.
func (s *SSE) PatchHTML(elements string, opts ...PatchOption) error {
	o := elementsOptions{mode: ModeOuter, namespace: NamespaceHTML}
	for _, opt := range opts {
		opt.applyElements(&o)
	}
	if !validMode(o.mode) {
		return fmt.Errorf("ds: unknown patch mode %q", string(o.mode))
	}
	remove := o.mode == ModeRemove
	blank := strings.TrimSpace(elements) == ""
	switch {
	case o.selector != "" && strings.TrimSpace(o.selector) == "":
		return errors.New("ds: selector must not be blank")
	case remove && o.selector == "" && blank:
		return errors.New("ds: removing needs a selector or the elements to remove")
	case !remove && blank:
		return errors.New("ds: elements must not be blank")
	case o.namespace != NamespaceHTML && o.namespace != NamespaceSVG && o.namespace != NamespaceMathML:
		return fmt.Errorf("ds: unknown namespace %q", string(o.namespace))
	}
	var sb strings.Builder
	if err := begin(&sb, "datastar-patch-elements", o.commonOptions); err != nil {
		return err
	}
	if o.selector != "" {
		if err := singleLine("selector", o.selector); err != nil {
			return err
		}
		sb.WriteString("data: selector " + o.selector + "\n")
	}
	if o.mode != ModeOuter {
		sb.WriteString("data: mode " + string(o.mode) + "\n")
	}
	if o.viewTransition {
		sb.WriteString("data: useViewTransition true\n")
	}
	if o.namespace != NamespaceHTML {
		sb.WriteString("data: namespace " + string(o.namespace) + "\n")
	}
	if !blank {
		writeLines(&sb, "elements", elements)
	}
	return s.send(&sb)
}

// Remove removes the elements matching selector.
func (s *SSE) Remove(selector string, opts ...EventOption) error {
	popts := []PatchOption{WithSelector(selector), WithMode(ModeRemove)}
	for _, o := range opts {
		popts = append(popts, o)
	}
	return s.PatchHTML("", popts...)
}

// PatchSignals JSON-encodes v and merges it into the page's signals (a JSON
// merge patch: a null member removes that signal).
func (s *SSE) PatchSignals(v any, opts ...SignalsOption) error {
	b, err := json.Marshal(v)
	if err != nil {
		return fmt.Errorf("ds: encode signals: %w", err)
	}
	return s.PatchSignalsJSON(string(b), opts...)
}

// PatchSignalsJSON sends a JSON object as one datastar-patch-signals event;
// text that is not valid JSON is refused.
func (s *SSE) PatchSignalsJSON(raw string, opts ...SignalsOption) error {
	if !json.Valid([]byte(raw)) {
		return errors.New("ds: signals must be valid JSON")
	}
	var o signalsOptions
	for _, opt := range opts {
		opt.applySignals(&o)
	}
	var sb strings.Builder
	if err := begin(&sb, "datastar-patch-signals", o.commonOptions); err != nil {
		return err
	}
	if o.onlyIfMissing {
		sb.WriteString("data: onlyIfMissing true\n")
	}
	writeLines(&sb, "signals", raw)
	return s.send(&sb)
}

func validMode(m Mode) bool { return slices.Contains(modes, m) }

// begin writes the event line, then id: and retry: (before the data lines).
func begin(sb *strings.Builder, event string, c commonOptions) error {
	sb.WriteString("event: " + event + "\n")
	if c.eventID != "" {
		if err := singleLine("event id", c.eventID); err != nil {
			return err
		}
		sb.WriteString("id: " + c.eventID + "\n")
	}
	if c.hasRetry {
		if c.retry < 0 {
			return errors.New("ds: retry must not be negative")
		}
		if c.retry != DefaultRetry {
			sb.WriteString("retry: " + strconv.FormatInt(c.retry.Milliseconds(), 10) + "\n")
		}
	}
	return nil
}

func singleLine(what, v string) error {
	if strings.ContainsAny(v, "\r\n") {
		return fmt.Errorf("ds: %s must not contain a line break", what)
	}
	return nil
}

// writeLines writes one `data: <field> <line>` per line (CRLF, CR and LF all
// separate lines).
func writeLines(sb *strings.Builder, field, text string) {
	text = strings.ReplaceAll(text, "\r\n", "\n")
	text = strings.ReplaceAll(text, "\r", "\n")
	for line := range strings.SplitSeq(text, "\n") {
		sb.WriteString("data: " + field + " " + line + "\n")
	}
}

// send ends the event with a blank line, writes it and flushes.
func (s *SSE) send(sb *strings.Builder) error {
	if s.err != nil {
		return s.err
	}
	if err := s.ctx.Err(); err != nil {
		return fmt.Errorf("ds: client gone: %w", err)
	}
	sb.WriteString("\n")
	if _, err := io.WriteString(s.w, sb.String()); err != nil {
		s.err = fmt.Errorf("ds: write event: %w", err)
		return s.err
	}
	if err := s.rc.Flush(); err != nil {
		s.err = fmt.Errorf("ds: flush event: %w", err)
		return s.err
	}
	return nil
}

// ErrUnsafeRedirect is returned by Redirect and ReplaceURL for anything but a
// local path.
var ErrUnsafeRedirect = errors.New("ds: target must be a local path")

// ErrInvalidID is returned by Focus and CloseDialog for an id that is not
// [A-Za-z][A-Za-z0-9_-]* (contract section 3 rule 4: an id that enters an
// expression is validated).
var ErrInvalidID = errors.New("ds: id must match [A-Za-z][A-Za-z0-9_-]*")

var idPattern = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_-]*$`)

// Redirect navigates the browser to path, which must be a local path (see
// [IsLocalPath]); an absolute URL or a protocol-relative "//host" is refused
// so a redirect can never leave the origin.
//
// It does not patch a <script> element, because the page's
// Content-Security-Policy allows no inline script. It appends a hidden <span>
// whose `data-init` runs the navigation: Datastar evaluates attribute
// expressions itself. The path is JSON-encoded (which escapes <, > and &) and
// then attribute-escaped.
func (s *SSE) Redirect(path string) error {
	return s.runOnce("window.location.assign(", path, ")")
}

// ReplaceURL sets the address bar to path (history.replaceState, no
// navigation and no new history entry), so a view filtered or sorted through
// Datastar keeps a URL that reloads to the same view. The appended element
// removes itself. path must be local, as for [SSE.Redirect].
func (s *SSE) ReplaceURL(path string) error {
	return s.runOnce("history.replaceState(history.state, '', ", path, "); el.remove()")
}

// Focus moves keyboard focus to the element with this id once the patches
// before it have been applied (a closed dialog hands focus back to its
// trigger). It appends a self-removing `<span hidden data-init="...">` to the
// body. The id is validated ([ErrInvalidID]).
func (s *SSE) Focus(id string) error {
	if !idPattern.MatchString(id) {
		return ErrInvalidID
	}
	return s.runElement("requestAnimationFrame(() => document.getElementById('" + id + "')?.focus()); el.remove()")
}

// CloseDialog closes the modal <dialog> with this id by appending a
// self-removing `<span hidden data-init="...">` to the body. Datastar
// processes it asynchronously: when the same answer also re-renders the
// dialog, use [Replace] for that patch instead. The id is validated
// ([ErrInvalidID]).
func (s *SSE) CloseDialog(id string) error {
	if !idPattern.MatchString(id) {
		return ErrInvalidID
	}
	return s.runElement("document.getElementById('" + id + "').close(); el.remove()")
}

func (s *SSE) runOnce(prefix, path, suffix string) error {
	if !IsLocalPath(path) {
		return ErrUnsafeRedirect
	}
	target, err := json.Marshal(path)
	if err != nil {
		return fmt.Errorf("ds: encode url: %w", err)
	}
	return s.runElement(prefix + string(target) + suffix)
}

// runElement appends <span hidden data-init="expr"></span> to the body.
func (s *SSE) runElement(expr string) error {
	el := `<span hidden data-init="` + escapeAttr(expr) + `"></span>`
	return s.PatchHTML(el, WithSelector("body"), WithMode(ModeAppend))
}

// escapeAttr escapes text for a double-quoted attribute value: & < > " '.
func escapeAttr(s string) string {
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;", "'", "&#39;").Replace(s)
}

// IsLocalPath reports whether p is a local path (contract
// server-rendered-ui.md section 8 rule 3): it starts with a single "/", does
// not start with "//" or "/\" (browsers treat those as another host), and has
// no control character or backslash. A query and a fragment are allowed.
func IsLocalPath(p string) bool {
	if !strings.HasPrefix(p, "/") || strings.HasPrefix(p, "//") {
		return false
	}
	for _, r := range p {
		if r < 0x20 || r == 0x7f || r == '\\' {
			return false
		}
	}
	return true
}

// SignalsError is why [ReadSignals] failed. Status is the HTTP status the
// request deserves: 400 for absent or invalid signals, 413 for a body above
// [MaxSignalBytes].
type SignalsError struct {
	Status  int
	Message string
	Err     error
}

func (e *SignalsError) Error() string {
	if e.Err != nil {
		return "ds: " + e.Message + ": " + e.Err.Error()
	}
	return "ds: " + e.Message
}

func (e *SignalsError) Unwrap() error { return e.Err }

// ReadSignals decodes the request's Datastar signals into v: from the
// `datastar` query parameter for GET, HEAD and DELETE (Datastar 1.0.5 sends a
// body only for the other methods), from the JSON body otherwise. Absent,
// empty, `null` or invalid signals are a *[SignalsError] with status 400; a
// body above [MaxSignalBytes] is one with status 413. Unknown members are
// ignored (the page sends all of its signals).
func ReadSignals(r *http.Request, v any) error {
	var raw []byte
	switch r.Method {
	case http.MethodGet, http.MethodHead, http.MethodDelete:
		q := r.URL.Query().Get("datastar")
		if q == "" {
			return &SignalsError{Status: http.StatusBadRequest, Message: "the datastar query parameter is required"}
		}
		raw = []byte(q)
	default:
		if r.Body == nil || r.Body == http.NoBody {
			return &SignalsError{Status: http.StatusBadRequest, Message: "signals are required"}
		}
		b, err := io.ReadAll(http.MaxBytesReader(nil, r.Body, MaxSignalBytes))
		if err != nil {
			if _, ok := errors.AsType[*http.MaxBytesError](err); ok {
				return &SignalsError{Status: http.StatusRequestEntityTooLarge, Message: "signals are larger than 1 MiB", Err: err}
			}
			return &SignalsError{Status: http.StatusBadRequest, Message: "read signals", Err: err}
		}
		raw = b
	}
	if t := strings.TrimSpace(string(raw)); t == "" || t == "null" {
		return &SignalsError{Status: http.StatusBadRequest, Message: "signals are required"}
	}
	if err := jsonv2.Unmarshal(raw, v); err != nil {
		return &SignalsError{Status: http.StatusBadRequest, Message: "signals are not valid JSON for the target", Err: err}
	}
	return nil
}
