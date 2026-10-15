// Package httpfw is the framework's HTTP layer (HTTP contract, docs/contracts/http.md) on net/http
// and encoding/json/v2: routes with the contract's table (404, 405 with Allow, OPTIONS, HEAD, no
// redirects, duplicates and missing context resolvers as start-up problems), the fixed middleware
// (correlation id, error mapping, access log and metrics), the error envelope, strict JSON decoding,
// health and metrics endpoints, a server with a body limit, timeouts and a graceful drain, the
// single-page application as the router's fallback (package spa), and typed operations from which
// the OpenAPI 3.1 document is emitted (package openapi).
//
//	web := httpfw.Declare(app)                         // declaring: http.* settings, the openapi command
//	web.Info("orders-demo", "1")
//	web.Operations(PlaceOrder, GetOrder)
//	...
//	routes := httpfw.NewRoutes("orders")               // in build
//	routes.Bind(httpfw.Handle(PlaceOrder, svc.place))
//	srv, err := httpfw.NewServer(b, web, []*httpfw.Routes{routes}, httpfw.WithResolver(resolver))
package httpfw

import (
	"context"
	"fmt"
	"io"
	"maps"
	"os"
	"reflect" //nolint:forbidigo // registrations are keyed by the wire types: the JSON edge
	"regexp"
	"slices"
	"sync"
	"time"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw/openapi"
)

// ModuleID is the HTTP module's id; it claims the configuration component "http".
const ModuleID = "http"

var literalPath = regexp.MustCompile(`^(/[A-Za-z0-9._~-]+)+$`)

// Declared is the HTTP module's declaration, from [Declare]: the http.* settings and what the
// application declares for its OpenAPI document.
type Declared struct {
	host           config.Handle[string]
	port           config.Handle[int]
	maxBody        config.Handle[int64]
	requestTimeout config.Handle[time.Duration]
	shutdownGrace  config.Handle[time.Duration]
	metricsEnabled config.Handle[bool]
	metricsPath    config.Handle[string]
	metricsToken   config.Handle[config.Secret]

	mu         sync.Mutex
	ops        []Described
	info       *openapi.Info
	infoCalls  int
	schemas    map[reflect.Type]openapi.Schema
	names      map[reflect.Type]string
	properties map[reflect.Type]map[string]openapi.Schema
	security   []openapi.Scheme
	problems   []string
}

// Declare declares the HTTP module: the http.* settings (service settings, so configuration:
// http.host, http.port, http.max-body-bytes, http.request-timeout, http.shutdown-grace,
// http.metrics.enabled, http.metrics.path and http.metrics.bearer-token), the claim on the "http"
// component (a misspelt key under it fails start-up) and the declaration-phase command openapi.
func Declare(app *fw.App) *Declared {
	m := app.Module(ModuleID)
	m.ClaimComponent("http")
	d := &Declared{
		host: config.Declare(m, config.String("http.host", "Address the HTTP server binds to.").WithDefault("0.0.0.0")),
		port: config.Declare(m, config.Int("http.port", "Port the HTTP server listens on; 0 picks any free port.").
			WithDefault(8080).Checked(config.Between(0, 65_535))),
		maxBody: config.Declare(m, config.Int64("http.max-body-bytes", "Largest request body accepted, in bytes; larger requests get 413.").
			WithDefault(DefaultMaxBodyBytes).Checked(config.Between[int64](0, 1<<62))),
		requestTimeout: config.Declare(m, config.Duration("http.request-timeout",
			"Time a request may take to be read, and an idle connection to be kept.").WithDefault(30*time.Second)),
		shutdownGrace: config.Declare(m, config.Duration("http.shutdown-grace",
			"Time in-flight requests may take on shutdown; defaults to app.shutdown.drain.")),
		metricsEnabled: config.Declare(m, config.Bool("http.metrics.enabled", "Mount the Prometheus metrics endpoint at http.metrics.path.").WithDefault(false)),
		metricsPath: config.Declare(m, config.String("http.metrics.path", "Path of the Prometheus metrics endpoint: literal segments, no trailing slash.").
			WithDefault("/metrics").Checked(func(p string) string {
			if !literalPath.MatchString(p) {
				return "must be literal segments such as /metrics"
			}
			return ""
		})),
		metricsToken: config.Declare(m, config.SecretValue("http.metrics.bearer-token",
			"When set, the metrics endpoint requires 'Authorization: Bearer <token>'.")),
		schemas:    map[reflect.Type]openapi.Schema{},
		names:      map[reflect.Type]string{},
		properties: map[reflect.Type]map[string]openapi.Schema{},
	}
	m.DeclarationCommand("openapi", d.openapiCommand)
	return d
}

// Operations declares typed operations, once each. Declare them at declaration time (Declare
// functions, before Launch): the openapi command needs no configuration, service or database, and
// the server checks that each is bound exactly once.
func (d *Declared) Operations(ops ...Described) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.ops = append(d.ops, ops...)
}

// Info sets the document's title and version. Once per application.
func (d *Declared) Info(title, version string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.infoCalls++
	d.info = &openapi.Info{Title: title, Version: version}
}

// Schema registers the schema of a type the document cannot derive (an id, a decimal, a union).
func (d *Declared) Schema(t reflect.Type, s openapi.Schema) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if _, dup := d.schemas[t]; dup {
		d.problems = append(d.problems, "Schema: "+t.String()+" has two schemas")
		return
	}
	d.schemas[t] = s
}

// Name registers the component name of a type, for two types that would produce one name.
func (d *Declared) Name(t reflect.Type, name string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if _, dup := d.names[t]; dup {
		d.problems = append(d.problems, "Name: "+t.String()+" has two names")
		return
	}
	d.names[t] = name
}

// Property replaces one property's schema of a record type (whether it is required still follows
// the field), for a property with a fixed value.
func (d *Declared) Property(t reflect.Type, property string, s openapi.Schema) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.properties[t] == nil {
		d.properties[t] = map[string]openapi.Schema{}
	}
	if _, dup := d.properties[t][property]; dup {
		d.problems = append(d.problems, "Property: "+t.String()+"."+property+" has two schemas")
		return
	}
	d.properties[t][property] = s
}

// Security contributes a security scheme: the module that resolves the Context says how callers
// authenticate. module names the contributor in problems.
func (d *Declared) Security(module, name string, s openapi.SecurityScheme) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.security = append(d.security, openapi.Scheme{Name: name, Scheme: s, Module: module})
}

func (d *Declared) declaredOperations() []Described {
	d.mu.Lock()
	defer d.mu.Unlock()
	return slices.Clone(d.ops)
}

// OpenAPI writes the document of the declared operations, or returns every problem found. It
// needs nothing but the declarations.
func (d *Declared) OpenAPI() (document []byte, problems []string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	in := openapi.Input{
		Info: d.info, Schemas: cloneMap(d.schemas), Names: d.names, Properties: d.properties,
		Security: slices.Clone(d.security), Problems: slices.Clone(d.problems),
	}
	for t, s := range builtinSchemas() {
		if _, taken := in.Schemas[t]; !taken {
			in.Schemas[t] = s
		}
	}
	if d.infoCalls > 1 {
		in.Problems = append(in.Problems, fmt.Sprintf("Info is called %d times; once per application", d.infoCalls))
	}
	for _, o := range d.ops {
		in.Operations = append(in.Operations, o.descriptor().openapi())
	}
	return openapi.Emit(in)
}

func cloneMap[K comparable, V any](m map[K]V) map[K]V {
	out := make(map[K]V, len(m))
	maps.Copy(out, m)
	return out
}

// builtinSchemas are the framework's own value types (OpenAPI contract section 5.1).
func builtinSchemas() map[reflect.Type]openapi.Schema {
	nonBlank := openapi.String().Pattern(`\S`).MinLength(1)
	return map[reflect.Type]openapi.Schema{
		reflect.TypeFor[fw.ClientID]():        openapi.String().Pattern(`^[A-Za-z0-9._-]{1,100}$`),
		reflect.TypeFor[fw.ApplicationCode](): openapi.String().Pattern(`^[a-z][a-z0-9-]*$`),
		reflect.TypeFor[fw.PrincipalID]():     nonBlank,
		reflect.TypeFor[fw.CorrelationID]():   nonBlank,
	}
}

// openapi converts the descriptor for the emitter.
func (d *opDesc) openapi() *openapi.Operation {
	op := &openapi.Operation{
		Method: d.method, Pattern: d.pattern, ID: d.id, Summary: d.summary, Description: d.description,
		Tags: d.tags, Status: d.status, RequiresContext: d.requiresContext, Permission: d.permission.String(),
		Errors: d.errors, Problems: d.problems, Body: d.validator,
	}
	for _, p := range d.path {
		op.Path = append(op.Path, openapi.Param{Name: p.name, Type: p.typ})
	}
	for _, q := range d.query {
		oq := openapi.Query{Mode: q.mode, Default: q.def}
		oq.Name, oq.Type = q.name, q.typ
		op.Query = append(op.Query, oq)
	}
	if d.hasResp {
		op.Response = d.respType
	}
	return op
}

// openapiCommand is `<app> openapi [--out file]`: the document on standard output or in a file.
func (d *Declared) openapiCommand(_ context.Context, args []string, out io.Writer) int {
	var file string
	switch {
	case len(args) == 0:
	case len(args) == 2 && args[0] == "--out":
		file = args[1]
	default:
		fmt.Fprintln(out, "usage: openapi [--out <file>]")
		return 2
	}
	doc, problems := d.OpenAPI()
	if len(problems) > 0 {
		fmt.Fprintf(os.Stderr, "the OpenAPI document cannot be written: %d problem(s)\n", len(problems))
		for _, p := range problems {
			fmt.Fprintln(os.Stderr, "  - "+p)
		}
		return 1
	}
	if file == "" {
		_, _ = out.Write(doc)
		return 0
	}
	if err := os.WriteFile(file, doc, 0o644); err != nil { //nolint:gosec // a committed, public document
		fmt.Fprintln(os.Stderr, "cannot write "+file+": "+err.Error())
		return 1
	}
	return 0
}
