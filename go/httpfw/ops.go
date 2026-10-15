package httpfw

//nolint:forbidigo // typed operations read their parameter and wire types: the JSON edge

import (
	"context"
	"encoding/json/v2"
	"errors"
	"fmt"
	"net/http"
	"reflect"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw/openapi"
	"github.com/flowcatalyst/framework/go/httpfw/validate"
	"github.com/flowcatalyst/framework/go/internal/javafmt"
)

// NoBody is the body type of an operation without a request body and the response type of one
// answered 204.
type NoBody struct{}

var noBodyType = reflect.TypeFor[NoBody]()

// ParamValue is what a path or query parameter may be: string, int32, int64, bool, or a string
// type with an openapi.EnumValuer (the values it lists are the only ones accepted).
type ParamValue interface {
	~string | ~int32 | ~int64 | ~bool
}

type paramSpec struct {
	name string
	typ  reflect.Type
	mode openapi.QueryMode
	def  any
}

// PathDecl is a declared path parameter ([Path]).
type PathDecl interface{ pathSpec() paramSpec }

// QueryDecl is a declared query parameter ([Query] and what it returns).
type QueryDecl interface{ querySpec() paramSpec }

// Params holds the parsed parameter values of one request.
type Params struct{ path, query map[string]any }

// PathParam is a path parameter of type T, declared with [Path] and read with [PathParam.Value].
type PathParam[T ParamValue] struct{ name string }

// Path declares a path parameter: name matches [a-z][A-Za-z0-9]* and appears in the pattern.
func Path[T ParamValue](name string) PathParam[T] { return PathParam[T]{name: name} }

func (p PathParam[T]) pathSpec() paramSpec {
	return paramSpec{name: p.name, typ: reflect.TypeFor[T]()}
}

// Value returns the parsed value of the parameter. It panics for a parameter the operation did not
// declare (a programming error).
func (p PathParam[T]) Value(in Params) T {
	v, ok := in.path[p.name].(T)
	if !ok {
		panic("httpfw: the operation declares no path parameter '" + p.name + "' of this type")
	}
	return v
}

// QueryParam is a required query parameter of type T, or one with a default ([QueryParam.Default]).
type QueryParam[T ParamValue] struct {
	name   string
	def    T
	hasDef bool
}

// Query declares a required query parameter: name matches [A-Za-z][A-Za-z0-9_.-]*. A missing one
// is REQUIRED, a malformed one INVALID, all in one 400 VALIDATION.
func Query[T ParamValue](name string) QueryParam[T] { return QueryParam[T]{name: name} }

// Default returns the parameter with a default value for when it is absent (documented as the
// schema's default).
func (q QueryParam[T]) Default(v T) QueryParam[T] {
	q.def, q.hasDef = v, true
	return q
}

// Optional returns the parameter as one that may be absent.
func (q QueryParam[T]) Optional() OptionalQuery[T] { return OptionalQuery[T]{name: q.name} }

// List returns the parameter as a repeated one (?status=A&status=B).
func (q QueryParam[T]) List() ListQuery[T] { return ListQuery[T]{name: q.name} }

func (q QueryParam[T]) querySpec() paramSpec {
	s := paramSpec{name: q.name, typ: reflect.TypeFor[T]()}
	if q.hasDef {
		s.mode, s.def = openapi.QueryDefault, q.def
	}
	return s
}

// Value returns the parsed value, or the default when it was absent.
func (q QueryParam[T]) Value(in Params) T {
	v, ok := in.query[q.name].(T)
	if !ok {
		panic("httpfw: the operation declares no query parameter '" + q.name + "' of this type")
	}
	return v
}

// OptionalQuery is a query parameter that may be absent.
type OptionalQuery[T ParamValue] struct{ name string }

func (q OptionalQuery[T]) querySpec() paramSpec {
	return paramSpec{name: q.name, typ: reflect.TypeFor[T](), mode: openapi.QueryOptional}
}

// Value returns the parsed value and whether the parameter was present.
func (q OptionalQuery[T]) Value(in Params) (T, bool) {
	raw, present := in.query[q.name]
	if !present || raw == nil {
		var zero T
		return zero, false
	}
	v, ok := raw.(T)
	if !ok {
		panic("httpfw: the operation declares no query parameter '" + q.name + "' of this type")
	}
	return v, true
}

// ListQuery is a repeated query parameter.
type ListQuery[T ParamValue] struct{ name string }

func (q ListQuery[T]) querySpec() paramSpec {
	return paramSpec{name: q.name, typ: reflect.TypeFor[T](), mode: openapi.QueryList}
}

// Value returns the parsed values in order, empty when absent.
func (q ListQuery[T]) Value(in Params) []T {
	v, ok := in.query[q.name].([]T)
	if !ok {
		panic("httpfw: the operation declares no query parameter '" + q.name + "' of this type")
	}
	return v
}

// ---- the descriptor ----------------------------------------------------------------------------

var (
	idPattern   = regexp.MustCompile(`^[a-z][A-Za-z0-9]*$`)
	tagPattern  = regexp.MustCompile(`^[a-z][a-z0-9-]*$`)
	codePattern = regexp.MustCompile(`^[A-Z][A-Z0-9_]*$`)
	queryName   = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_.-]*$`)
	pathName    = regexp.MustCompile(`^[a-z][A-Za-z0-9]*$`)
)

type opDesc struct {
	method, pattern, id, summary, description string
	tags                                      []string
	path                                      []paramSpec
	query                                     []paramSpec
	validator                                 validate.Described
	respType                                  reflect.Type
	hasResp                                   bool
	status                                    int
	requiresContext                           bool
	permission                                fw.Permission
	errors                                    []openapi.DeclaredError
	problems                                  []string
	documented                                map[int]bool
	warned                                    *sync.Map
}

// Described is a typed operation of any body and response type, as Declared takes it.
type Described interface{ descriptor() *opDesc }

// label names the operation in problems.
func (d *opDesc) label() string {
	if d.id != "" {
		return d.id
	}
	return d.method + " " + d.pattern
}

// Operation is a typed JSON operation (OpenAPI contract sections 2 and 3): a value written once,
// declared at declaration time with [Declared.Operations] and bound to a handler in the application's
// build function with [Handle] and [Routes.Bind]. The router serves it like any route and the
// OpenAPI document is written from it. B is the body type ([NoBody] when there is none), R the
// response type ([NoBody] for 204).
type Operation[B, R any] struct {
	d *opDesc
	v *validate.Validator[B]
}

func (o Operation[B, R]) descriptor() *opDesc { return o.d }

// ID returns the operation id.
func (o Operation[B, R]) ID() string { return o.d.id }

// Builder builds an [Operation]. It never panics for a descriptor mistake (a missing id, a path
// parameter missing from the pattern): those are listed with the operation and reported as
// start-up problems when the routes are compiled and by the openapi command, so a package-level
// variable never fails initialisation.
type Builder[B, R any] struct {
	d opDesc
	v *validate.Validator[B]
}

func newBuilder[B, R any](method, pattern string, v *validate.Validator[B]) *Builder[B, R] {
	b := &Builder[B, R]{v: v}
	b.d.method, b.d.pattern = method, pattern
	b.d.respType = reflect.TypeFor[R]()
	b.d.hasResp = b.d.respType != noBodyType
	if v != nil {
		b.d.validator = v
	}
	return b
}

// Get declares a GET operation.
func Get[R any](pattern string) *Builder[NoBody, R] {
	return newBuilder[NoBody, R]("GET", pattern, nil)
}

// Delete declares a DELETE operation.
func Delete[R any](pattern string) *Builder[NoBody, R] {
	return newBuilder[NoBody, R]("DELETE", pattern, nil)
}

// PostNoBody declares a POST operation without a request body (logout): a body sent to it is not
// read.
func PostNoBody[R any](pattern string) *Builder[NoBody, R] {
	return newBuilder[NoBody, R]("POST", pattern, nil)
}

// Post declares a POST operation whose body is a B, decoded and validated by v.
func Post[B, R any](pattern string, v *validate.Validator[B]) *Builder[B, R] {
	return newBuilder[B, R]("POST", pattern, v)
}

// Put declares a PUT operation whose body is a B.
func Put[B, R any](pattern string, v *validate.Validator[B]) *Builder[B, R] {
	return newBuilder[B, R]("PUT", pattern, v)
}

// Patch declares a PATCH operation whose body is a B.
func Patch[B, R any](pattern string, v *validate.Validator[B]) *Builder[B, R] {
	return newBuilder[B, R]("PATCH", pattern, v)
}

// ID sets the operation id: [a-z][A-Za-z0-9]*, unique in the application (it becomes the
// TypeScript function name).
func (b *Builder[B, R]) ID(id string) *Builder[B, R] { b.d.id = id; return b }

// Summary sets the one-line summary.
func (b *Builder[B, R]) Summary(s string) *Builder[B, R] { b.d.summary = s; return b }

// Description sets the longer description.
func (b *Builder[B, R]) Description(s string) *Builder[B, R] { b.d.description = s; return b }

// Tag adds tags: [a-z][a-z0-9-]*.
func (b *Builder[B, R]) Tag(tags ...string) *Builder[B, R] {
	b.d.tags = append(b.d.tags, tags...)
	return b
}

// Path declares the path parameters; every {name} in the pattern needs one.
func (b *Builder[B, R]) Path(params ...PathDecl) *Builder[B, R] {
	for _, p := range params {
		b.d.path = append(b.d.path, p.pathSpec())
	}
	return b
}

// Query declares the query parameters, in the order they are documented.
func (b *Builder[B, R]) Query(params ...QueryDecl) *Builder[B, R] {
	for _, p := range params {
		b.d.query = append(b.d.query, p.querySpec())
	}
	return b
}

// Status sets the success status: 200 (default), 201, 202 or 204 (204 if and only if the response
// is [NoBody]).
func (b *Builder[B, R]) Status(status int) *Builder[B, R] { b.d.status = status; return b }

// RequiresContext resolves the fw.Context before the handler runs and documents the security
// schemes and the 401 and 403 statuses.
func (b *Builder[B, R]) RequiresContext() *Builder[B, R] { b.d.requiresContext = true; return b }

// Permission requires the Context to hold a permission (403 FORBIDDEN otherwise, checked before
// anything about the request is read); it implies RequiresContext and is documented as
// x-fw-permission.
func (b *Builder[B, R]) Permission(p fw.Permission) *Builder[B, R] { b.d.permission = p; return b }

// Error documents an expected failure: its status and code, and what it means.
func (b *Builder[B, R]) Error(status int, code, message string) *Builder[B, R] {
	b.d.errors = append(b.d.errors, openapi.DeclaredError{Status: status, Code: code, Message: message})
	return b
}

// Build returns the immutable operation.
func (b *Builder[B, R]) Build() Operation[B, R] {
	d := b.d // a copy: later calls on the builder do not change the operation
	d.path, d.query = slices.Clone(d.path), slices.Clone(d.query)
	d.tags, d.errors = slices.Clone(d.tags), slices.Clone(d.errors)
	if d.permission != (fw.Permission{}) {
		d.requiresContext = true
	}
	if d.status == 0 {
		d.status = http.StatusOK
		if !d.hasResp {
			d.status = http.StatusNoContent
		}
	}
	d.problems = checkDescriptor(&d)
	d.documented = map[int]bool{}
	d.warned = &sync.Map{}
	if d.validator != nil || len(d.path) > 0 || len(d.query) > 0 {
		d.documented[400] = true
	}
	if d.requiresContext {
		d.documented[401], d.documented[403] = true, true
	}
	if d.validator != nil {
		d.documented[413], d.documented[415] = true, true
	}
	for _, e := range d.errors {
		d.documented[e.Status] = true
	}
	return Operation[B, R]{d: &d, v: b.v}
}

func checkDescriptor(d *opDesc) []string {
	var out []string
	switch {
	case d.id == "":
		out = append(out, `an operation needs an id (.ID("listOrders"))`)
	case !idPattern.MatchString(d.id):
		out = append(out, "operation id '"+d.id+"' must match [a-z][A-Za-z0-9]*")
	}
	for _, t := range d.tags {
		if !tagPattern.MatchString(t) {
			out = append(out, "tag '"+t+"' must match [a-z][a-z0-9-]*")
		}
	}
	var names []string
	if p, err := parsePath(d.method, d.pattern); err != nil {
		out = append(out, err.Error())
	} else {
		names = p.params
	}
	declared := map[string]bool{}
	for _, p := range d.path {
		switch {
		case !pathName.MatchString(p.name):
			out = append(out, "a path parameter name must match [a-z][A-Za-z0-9]*: "+p.name)
		case declared[p.name]:
			out = append(out, "path parameter '"+p.name+"' is declared twice")
		case !slices.Contains(names, p.name):
			out = append(out, "path parameter '"+p.name+"' is not in the pattern "+d.pattern)
		}
		declared[p.name] = true
		out = append(out, paramTypeProblems(p)...)
	}
	for _, n := range names {
		if !declared[n] {
			out = append(out, "{"+n+"} in the pattern "+d.pattern+" is not declared (.Path(httpfw.Path[...]))")
		}
	}
	queries := map[string]bool{}
	for _, q := range d.query {
		if !queryName.MatchString(q.name) {
			out = append(out, "a query parameter name must match [A-Za-z][A-Za-z0-9_.-]*: "+q.name)
		}
		if queries[q.name] {
			out = append(out, "query parameter '"+q.name+"' is declared twice")
		}
		queries[q.name] = true
		out = append(out, paramTypeProblems(q)...)
	}
	noContent := !d.hasResp
	switch {
	case d.status != 0 && !slices.Contains([]int{200, 201, 202, 204}, d.status):
		out = append(out, "the success status is 200, 201, 202 or 204, not "+strconv.Itoa(d.status))
	case d.status == 204 && !noContent:
		out = append(out, "status 204 needs a NoBody response")
	case noContent && d.status != 0 && d.status != 204:
		out = append(out, "a NoBody response is answered 204, not "+strconv.Itoa(d.status))
	}
	keys := map[string]bool{}
	for _, e := range d.errors {
		if e.Status < 400 || e.Status > 599 {
			out = append(out, fmt.Sprintf("error %s has status %d; an error status is 400 to 599", e.Code, e.Status))
		}
		if !codePattern.MatchString(e.Code) {
			out = append(out, "error code '"+e.Code+"' must match [A-Z][A-Z0-9_]*")
		}
		k := strconv.Itoa(e.Status) + " " + e.Code
		if keys[k] {
			out = append(out, "error "+k+" is declared twice")
		}
		keys[k] = true
		if strings.TrimSpace(e.Message) == "" {
			out = append(out, "error "+e.Code+" needs a message")
		}
	}
	return out
}

func paramTypeProblems(p paramSpec) []string {
	if p.typ.Kind() == reflect.String && p.typ != reflect.TypeFor[string]() && !p.typ.Implements(reflect.TypeFor[openapi.EnumValuer]()) {
		return []string{"parameter '" + p.name + "': the string type " + p.typ.String() + " needs EnumValues (openapi.EnumValuer) to say which values are accepted"}
	}
	return nil
}

// ---- binding -----------------------------------------------------------------------------------

// In is what an operation's handler is given: the decoded and validated body, the parsed
// parameters, the request, and headers to add to the success response (Location, Set-Cookie).
type In[B any] struct {
	Body    B
	Params  Params
	Request *http.Request
	// Header collects headers added to the success response; Add appends, so two Set-Cookies are
	// fine.
	Header http.Header
}

// OperationHandler handles a typed operation. A returned [ServiceError] (a *usecase.Error passes
// straight through) is answered with its status, code, message and details; any other error as 500
// INTERNAL. cx is the zero Context for an operation that does not require one.
type OperationHandler[B, R any] func(ctx context.Context, cx fw.Context, in In[B]) (R, error)

// Binding is an operation tied to its handler; register it with [Routes.Bind].
type Binding struct {
	desc    *opDesc
	handler HandlerFunc
	opts    []RouteOption
	options routeOptions
}

// Handle ties an operation to its handler. The route checks the permission (403), then every
// parameter (one 400), then the body (415, 400), then calls the handler; the result is written
// with the operation's status. Route middleware options may be given (a rate limit); options that
// require a context for an operation that does not are a start-up problem, because the document
// would call the operation public.
func Handle[B, R any](op Operation[B, R], h OperationHandler[B, R], opts ...RouteOption) Binding {
	d := op.d
	run := func(w http.ResponseWriter, r *http.Request) error {
		var cx fw.Context
		if d.requiresContext {
			cx = ContextOf(r)
		}
		if d.permission != (fw.Permission{}) && !cx.HasPermission(d.permission) {
			return Forbidden("FORBIDDEN", "this needs the permission "+d.permission.String())
		}
		params, errs := parseParams(d, r)
		if len(errs) > 0 {
			return validationError(errs)
		}
		var body B
		if op.v != nil {
			var err error
			if body, err = decodeBody(r, op.v); err != nil {
				return err
			}
		}
		in := In[B]{Body: body, Params: params, Request: r, Header: http.Header{}}
		out, err := h(r.Context(), cx, in)
		if err != nil {
			if se, ok := errors.AsType[ServiceError](err); ok && !d.documented[se.Status()] {
				if _, seen := d.warned.LoadOrStore(se.Status(), true); !seen {
					loggerOf(r).Warn("status is not in the document", "operation", d.label(), "status", se.Status())
				}
			}
			return err
		}
		return writeResult(w, d, out, in.Header)
	}
	var ro routeOptions
	for _, o := range opts {
		o.apply(&ro)
	}
	return Binding{desc: d, handler: run, opts: opts, options: ro}
}

func writeResult(w http.ResponseWriter, d *opDesc, out any, header http.Header) error {
	for k, vs := range header {
		for _, v := range vs {
			w.Header().Add(k, v)
		}
	}
	if !d.hasResp {
		w.WriteHeader(http.StatusNoContent)
		return nil
	}
	b, err := javafmt.MarshalJSON(out)
	if err != nil {
		return fmt.Errorf("cannot encode the response of %s: %w", d.label(), err)
	}
	writeBytes(w, d.status, "application/json", b)
	return nil
}

func decodeBody[B any](r *http.Request, v *validate.Validator[B]) (B, error) {
	var body B
	if err := requireJSON(r); err != nil {
		return body, err
	}
	b, err := readBody(r)
	if err != nil {
		return body, err
	}
	if err := json.Unmarshal(b, &body, jsonOptions); err != nil {
		return body, invalidJSON(err)
	}
	if errs := v.Validate(body); len(errs) > 0 {
		return body, validationError(errs)
	}
	return body, nil
}

func validationError(errs []validate.FieldError) *Error {
	var details []Detail
	seen := map[string]bool{}
	for _, e := range errs {
		if !seen[e.Field] {
			seen[e.Field] = true
			details = append(details, Detail{e.Field, e.Code + ": " + e.Message})
		}
	}
	return NewError(http.StatusBadRequest, "VALIDATION", "request is invalid", details...)
}

// ---- parameters --------------------------------------------------------------------------------

func parseParams(d *opDesc, r *http.Request) (Params, []validate.FieldError) {
	p := Params{path: map[string]any{}, query: map[string]any{}}
	var errs []validate.FieldError
	for _, ps := range d.path {
		v, ok := parseOne(ps.typ, ps.name, r.PathValue(ps.name), &errs)
		if ok {
			p.path[ps.name] = v
		}
	}
	for _, q := range d.query {
		values, err := QueryValues(r, q.name)
		if err != nil {
			errs = append(errs, validate.FieldError{Field: "query", Code: "INVALID_ENCODING", Message: "contains an invalid percent-escape"})
			continue
		}
		if q.mode == openapi.QueryList {
			list := reflect.MakeSlice(reflect.SliceOf(q.typ), 0, len(values))
			for _, raw := range values {
				if v, ok := parseOne(q.typ, q.name, raw, &errs); ok {
					list = reflect.Append(list, reflect.ValueOf(v))
				}
			}
			p.query[q.name] = list.Interface()
			continue
		}
		if len(values) == 0 {
			switch q.mode {
			case openapi.QueryRequired:
				errs = append(errs, validate.FieldError{Field: q.name, Code: "REQUIRED", Message: "is required"})
			case openapi.QueryDefault:
				p.query[q.name] = q.def
			case openapi.QueryOptional:
				p.query[q.name] = nil
			case openapi.QueryList: // handled above
			}
			continue
		}
		if v, ok := parseOne(q.typ, q.name, values[0], &errs); ok {
			p.query[q.name] = v
		}
	}
	return p, errs
}

// parseOne parses one raw value into a value of exactly type t; the error names the parameter and
// never the value.
func parseOne(t reflect.Type, name, raw string, errs *[]validate.FieldError) (any, bool) {
	bad := func(msg string) (any, bool) {
		*errs = append(*errs, validate.FieldError{Field: name, Code: "INVALID", Message: msg})
		return nil, false
	}
	v := reflect.New(t).Elem()
	switch t.Kind() { //nolint:exhaustive // every other kind is refused below
	case reflect.String:
		if ev, ok := reflect.TypeAssert[openapi.EnumValuer](reflect.Zero(t)); ok {
			values := ev.EnumValues()
			if !slices.Contains(values, raw) {
				return bad("must be one of: " + strings.Join(values, ", "))
			}
		}
		v.SetString(raw)
	case reflect.Int32:
		n, err := strconv.ParseInt(raw, 10, 32)
		if err != nil {
			return bad("must be an integer")
		}
		v.SetInt(n)
	case reflect.Int64:
		n, err := strconv.ParseInt(raw, 10, 64)
		if err != nil {
			return bad("must be an integer")
		}
		v.SetInt(n)
	case reflect.Bool:
		switch raw {
		case "true":
			v.SetBool(true)
		case "false":
			v.SetBool(false)
		default:
			return bad("must be true or false")
		}
	default:
		return bad("unsupported parameter type " + t.String())
	}
	return v.Interface(), true
}
