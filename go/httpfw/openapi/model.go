package openapi

import (
	"reflect" //nolint:forbidigo // descriptors name the wire types the emitter walks: the JSON edge
	"slices"

	"github.com/flowcatalyst/framework/go/httpfw/validate"
)

// Info is the document's title and version, given once per application.
type Info struct{ Title, Version string }

// QueryMode says how a query parameter is read.
type QueryMode int

const (
	// QueryRequired is a single value that must be present.
	QueryRequired QueryMode = iota
	// QueryOptional is a single value that may be absent.
	QueryOptional
	// QueryDefault is a single value with a default for when it is absent.
	QueryDefault
	// QueryList is a repeated parameter (?status=A&status=B).
	QueryList
)

// Param is a path parameter: its name and its Go type (string, int32, int64, bool or an
// [EnumValuer] string type).
type Param struct {
	Name string
	Type reflect.Type
}

// Query is a query parameter.
type Query struct {
	Param
	Mode QueryMode
	// Default is the default value for [QueryDefault] (a string, bool, int32, int64 or the enum
	// value as a string), documented as the schema's "default".
	Default any
}

// DeclaredError is an expected failure an operation documents.
type DeclaredError struct {
	Status        int
	Code, Message string
}

// Operation describes one typed operation for the emitter. httpfw builds it from its generic
// descriptor.
type Operation struct {
	// Method is GET, PUT, POST, DELETE or PATCH.
	Method string
	// Pattern is the path with {name} segments.
	Pattern string
	// ID is the operation id (the TypeScript function name).
	ID              string
	Summary         string
	Description     string
	Tags            []string
	Path            []Param
	Query           []Query
	Body            validate.Described // the request body's validator; nil when there is no body
	Response        reflect.Type       // nil when there is no body (status 204)
	Status          int
	RequiresContext bool
	Permission      string
	Errors          []DeclaredError
	Problems        []string // descriptor mistakes found when it was built
}

// Label names the operation in problems.
func (o *Operation) Label() string {
	if o.ID != "" {
		return o.ID
	}
	return o.Method + " " + o.Pattern
}

// hasRequestBody reports whether the operation has a request body.
func (o *Operation) hasRequestBody() bool { return o.Body != nil }

// documentedStatuses are the error statuses the operation lists, ascending (OpenAPI contract
// section 6): 400 for a body or any parameter, 401 and 403 for a context, 413 and 415 for a body,
// and each declared one.
func (o *Operation) documentedStatuses() []int {
	set := map[int]bool{}
	if o.hasRequestBody() || len(o.Path) > 0 || len(o.Query) > 0 {
		set[400] = true
	}
	if o.RequiresContext {
		set[401], set[403] = true, true
	}
	if o.hasRequestBody() {
		set[413], set[415] = true, true
	}
	for _, e := range o.Errors {
		set[e.Status] = true
	}
	out := make([]int, 0, len(set))
	for s := range set {
		out = append(out, s)
	}
	slices.Sort(out)
	return out
}

// SecurityKind is the kind of a security scheme.
type SecurityKind int

const (
	// CookieKey is an API key in a cookie.
	CookieKey SecurityKind = iota + 1
	// HeaderKey is an API key in a header.
	HeaderKey
	// BearerToken is HTTP bearer authentication.
	BearerToken
	// BasicAuth is HTTP basic authentication.
	BasicAuth
)

// SecurityScheme is a way callers authenticate, contributed by the module that resolves the
// Context (OpenAPI contract section 7).
type SecurityScheme struct {
	Kind SecurityKind
	// Name is the cookie or header name.
	Name string
	// Format is the bearer format ("JWT").
	Format string
}

// Cookie is an API key in the named cookie.
func Cookie(name string) SecurityScheme { return SecurityScheme{Kind: CookieKey, Name: name} }

// Header is an API key in the named header.
func Header(name string) SecurityScheme { return SecurityScheme{Kind: HeaderKey, Name: name} }

// Bearer is HTTP bearer authentication with a token format such as "JWT".
func Bearer(format string) SecurityScheme { return SecurityScheme{Kind: BearerToken, Format: format} }

// HTTPBasic is HTTP basic authentication.
func HTTPBasic() SecurityScheme { return SecurityScheme{Kind: BasicAuth} }

// Scheme names a security scheme.
type Scheme struct {
	Name   string
	Scheme SecurityScheme
	Module string
}

// Input is everything the emitter writes the document from.
type Input struct {
	Info       *Info
	Operations []*Operation
	// Schemas are the schemas registered for types the emitter cannot derive.
	Schemas map[reflect.Type]Schema
	// Names override a type's component name.
	Names map[reflect.Type]string
	// Properties replace one property's schema of a record type, by JSON name.
	Properties map[reflect.Type]map[string]Schema
	Security   []Scheme
	// Problems found while collecting the input (a type registered twice, info given twice); they
	// are reported with the emitter's own.
	Problems []string
}
