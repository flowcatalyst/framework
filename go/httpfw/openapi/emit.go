package openapi

import (
	"fmt"
	"regexp"
	"slices"
	"strconv"
	"strings"
)

// keywords is the order of schema keywords (OpenAPI contract section 5.4, plus default).
var keywords = []string{
	"$ref", "type", "const", "enum", "format", "contentEncoding", "pattern", "minLength", "maxLength",
	"minimum", "maximum", "items", "minItems", "maxItems", "uniqueItems", "properties", "required",
	"additionalProperties", "oneOf", "discriminator", "default", "description",
}

var (
	methodOrder = []string{"GET", "PUT", "POST", "DELETE", "PATCH"}
	schemeName  = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_-]*$`)
	patternName = regexp.MustCompile(`\{([a-z][A-Za-z0-9]*)\}`)
)

const errorComponent = "Error"

// Emit writes the OpenAPI 3.1 document, or returns every problem found (OpenAPI contract sections
// 4 to 8). It is deterministic: the same input gives the same bytes in any declaration order.
func Emit(in Input) (document []byte, problems []string) {
	problems = slices.Clone(in.Problems)
	securityByName := map[string]SecurityScheme{}
	var schemeNames []string
	for _, s := range in.Security {
		switch {
		case !schemeName.MatchString(s.Name):
			problems = append(problems, "security scheme '"+s.Name+"' must match [A-Za-z][A-Za-z0-9_-]*")
		case securityByName[s.Name] != (SecurityScheme{}):
			problems = append(problems, "security scheme '"+s.Name+"' is contributed twice (module '"+s.Module+"')")
		default:
			securityByName[s.Name] = s.Scheme
			schemeNames = append(schemeNames, s.Name)
		}
	}
	info := newNode()
	if in.Info == nil {
		problems = append(problems, "the document has no info: call Info(title, version) once, at declaration")
	} else {
		info.set("title", in.Info.Title).set("version", in.Info.Version)
	}

	cons := newConstraints(&problems)
	for _, op := range in.Operations {
		if op.Body != nil {
			cons.collect(op.Body, op.Label()+".body")
		}
	}
	w := newWalker(in, cons, &problems)
	w.reserve(errorComponent, errorSchema())

	order := schemeOrder(schemeNames)
	paths := map[string]map[string]*node{}
	ids := map[string]*Operation{}
	shapes := map[string]*Operation{}
	tagSet := map[string]bool{}
	for _, op := range in.Operations {
		for _, p := range op.Problems {
			problems = append(problems, op.Label()+": "+p)
		}
		if op.ID != "" {
			if first, dup := ids[op.ID]; dup {
				if first == op {
					problems = append(problems, op.ID+": the operation is declared twice")
				} else {
					problems = append(problems, fmt.Sprintf("%s: two operations have this id (%s %s and %s %s)", op.ID, first.Method, first.Pattern, op.Method, op.Pattern))
				}
				continue
			}
			ids[op.ID] = op
		}
		shape := op.Method + " " + patternName.ReplaceAllString(op.Pattern, "{}")
		if same, dup := shapes[shape]; dup {
			problems = append(problems, fmt.Sprintf("%s: %s %s collides with %s", op.Label(), op.Method, op.Pattern, same.Label()))
			continue
		}
		shapes[shape] = op
		if len(op.Problems) > 0 {
			continue
		}
		if op.RequiresContext && len(schemeNames) == 0 {
			problems = append(problems, op.Label()+": the operation requires a context but no security scheme was contributed; say how callers authenticate (the module that resolves the context contributes them)")
		}
		for _, t := range op.Tags {
			tagSet[t] = true
		}
		if paths[op.Pattern] == nil {
			paths[op.Pattern] = map[string]*node{}
		}
		paths[op.Pattern][op.Method] = w.operation(op, order, &problems)
	}
	if len(problems) > 0 {
		return []byte{}, dedupe(problems)
	}

	doc := newNode().set("openapi", "3.1.0").set("info", info)
	pathsNode := newNode()
	for _, p := range sortedKeys(paths) {
		item := newNode()
		for _, m := range methodOrder {
			if o, ok := paths[p][m]; ok {
				item.set(strings.ToLower(m), o)
			}
		}
		pathsNode.set(p, item)
	}
	doc.set("paths", pathsNode)
	components := newNode()
	schemas := newNode()
	for _, name := range sortedKeys(w.components) {
		schemas.set(name, canonical(w.components[name]))
	}
	components.set("schemas", schemas)
	components.set("responses", newNode().set(errorComponent, jsonResponse("Error", ref(errorComponent))))
	if len(schemeNames) > 0 {
		schemes := newNode()
		for _, name := range order {
			schemes.set(name, scheme(securityByName[name]))
		}
		components.set("securitySchemes", schemes)
	}
	doc.set("components", components)
	if len(tagSet) > 0 {
		tags := make([]any, 0, len(tagSet))
		for _, t := range sortedKeys(tagSet) {
			tags = append(tags, newNode().set("name", t))
		}
		doc.set("tags", tags)
	}
	return write(doc), nil
}

func sortedKeys[V any](m map[string]V) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	slices.Sort(keys)
	return keys
}

func dedupe(in []string) []string {
	seen := map[string]bool{}
	out := make([]string, 0, len(in))
	for _, p := range in {
		if !seen[p] {
			seen[p] = true
			out = append(out, p)
		}
	}
	return out
}

// schemeOrder is session, then bearer, then the others sorted by name.
func schemeOrder(names []string) []string {
	var out []string
	if slices.Contains(names, "session") {
		out = append(out, "session")
	}
	if slices.Contains(names, "bearer") {
		out = append(out, "bearer")
	}
	var rest []string
	for _, n := range names {
		if n != "session" && n != "bearer" {
			rest = append(rest, n)
		}
	}
	slices.Sort(rest)
	return append(out, rest...)
}

func scheme(s SecurityScheme) *node {
	m := newNode()
	switch s.Kind {
	case CookieKey:
		m.set("type", "apiKey").set("in", "cookie").set("name", s.Name)
	case HeaderKey:
		m.set("type", "apiKey").set("in", "header").set("name", s.Name)
	case BearerToken:
		m.set("type", "http").set("scheme", "bearer").set("bearerFormat", s.Format)
	case BasicAuth:
		m.set("type", "http").set("scheme", "basic")
	}
	return m
}

// ---- one operation ---------------------------------------------------------------------------

func (w *walker) operation(op *Operation, schemes []string, problems *[]string) *node {
	label := op.Label()
	o := newNode().set("operationId", op.ID)
	if op.Summary != "" {
		o.set("summary", op.Summary)
	}
	if op.Description != "" {
		o.set("description", op.Description)
	}
	if len(op.Tags) > 0 {
		tags := make([]any, len(op.Tags))
		for i, t := range op.Tags {
			tags[i] = t
		}
		o.set("tags", tags)
	}
	var parameters []any
	for _, m := range patternName.FindAllStringSubmatch(op.Pattern, -1) {
		for _, p := range op.Path {
			if p.Name == m[1] {
				parameters = append(parameters, newNode().set("name", p.Name).set("in", "path").set("required", true).
					set("schema", canonical(w.schema(p.Type, label+".path."+p.Name))))
			}
		}
	}
	for _, q := range op.Query {
		param := newNode().set("name", q.Name).set("in", "query").set("required", q.Mode == QueryRequired)
		element := w.schema(q.Type, label+".query."+q.Name)
		if q.Mode == QueryList {
			param.set("style", "form").set("explode", true)
			param.set("schema", canonical(typed("array").set("items", element)))
		} else {
			if q.Mode == QueryDefault && q.Default != nil {
				element.set("default", defaultValue(q.Default))
			}
			param.set("schema", canonical(element))
		}
		parameters = append(parameters, param)
	}
	if len(parameters) > 0 {
		o.set("parameters", parameters)
	}
	if op.Body != nil {
		schema := w.schema(op.Body.Type(), label+".body")
		if !schema.has("$ref") && !schema.empty() {
			*problems = append(*problems, label+".body: a request body is a struct or a union, not "+op.Body.Type().String())
		}
		o.set("requestBody", newNode().set("required", true).set("content", content(schema)))
	}
	responses := newNode()
	if op.Response != nil {
		schema := w.schema(op.Response, label+".response")
		if !schema.has("$ref") && !schema.empty() {
			*problems = append(*problems, label+".response: a response is a struct, a union or NoBody; never a map, a slice or a primitive at the top level")
		}
		responses.set(strconv.Itoa(op.Status), jsonResponse(successText(op.Status), schema))
	} else {
		responses.set("204", newNode().set("description", "No Content"))
	}
	declared := map[int][]DeclaredError{}
	for _, e := range op.Errors {
		declared[e.Status] = append(declared[e.Status], e)
	}
	for _, status := range op.documentedStatuses() {
		errs, ok := declared[status]
		if !ok {
			responses.set(strconv.Itoa(status), responseRef())
			continue
		}
		lines := make([]string, len(errs))
		for i, e := range errs {
			lines[i] = e.Code + ": " + e.Message
		}
		slices.Sort(lines)
		responses.set(strconv.Itoa(status), jsonResponse(strings.Join(lines, "\n"), ref(errorComponent)))
	}
	responses.set("default", responseRef())
	o.set("responses", responses)
	if op.RequiresContext && len(schemes) > 0 {
		security := make([]any, len(schemes))
		for i, n := range schemes {
			security[i] = newNode().set(n, []any{})
		}
		o.set("security", security)
	}
	if op.Permission != "" {
		o.set("x-fw-permission", op.Permission)
	}
	return o
}

func defaultValue(v any) any {
	switch x := v.(type) {
	case int:
		return int64(x)
	case int32:
		return int64(x)
	case string, bool, int64:
		return x
	}
	if s, ok := enumName(v); ok {
		return s
	}
	return fmt.Sprint(v)
}

func successText(status int) string {
	switch status {
	case 201:
		return "Created"
	case 202:
		return "Accepted"
	}
	return "OK"
}

// ---- shared parts ----------------------------------------------------------------------------

func errorSchema() *node {
	details := typed("object").set("additionalProperties", typed("string"))
	props := newNode().set("error", typed("string")).set("message", typed("string")).set("details", details)
	return typed("object").set("properties", props).set("required", []any{"error", "message"}).set("additionalProperties", false)
}

func responseRef() *node { return newNode().set("$ref", "#/components/responses/"+errorComponent) }

func jsonResponse(description string, schema *node) *node {
	return newNode().set("description", description).set("content", content(schema))
}

func content(schema *node) *node {
	media := newNode().set("schema", canonical(schema))
	return newNode().set("application/json", media)
}

// canonical returns a schema with its keywords in the contract's order, recursively.
func canonical(schema *node) *node {
	if schema == nil {
		return newNode()
	}
	for _, k := range schema.keys {
		if !slices.Contains(keywords, k) {
			panic("openapi: schema keyword '" + k + "' has no place in the keyword order")
		}
	}
	out := newNode()
	for _, k := range keywords {
		v, ok := schema.get(k)
		if !ok {
			continue
		}
		switch k {
		case "items":
			out.set(k, canonical(v.(*node))) //nolint:forcetypeassert // the walker builds items as a node
		case "additionalProperties":
			if m, ok := v.(*node); ok {
				out.set(k, canonical(m))
			} else {
				out.set(k, v)
			}
		case "properties":
			props := newNode()
			p := v.(*node) //nolint:forcetypeassert // the walker builds properties as a node
			for _, name := range p.keys {
				props.set(name, canonical(p.vals[name].(*node))) //nolint:forcetypeassert // as above
			}
			out.set(k, props)
		case "oneOf":
			list := v.([]any) //nolint:forcetypeassert // the walker builds oneOf as a list
			items := make([]any, len(list))
			for i, item := range list {
				items[i] = canonical(item.(*node)) //nolint:forcetypeassert // as above
			}
			out.set(k, items)
		case "discriminator":
			d := v.(*node)                       //nolint:forcetypeassert // the walker builds a node
			mapping := d.vals["mapping"].(*node) //nolint:forcetypeassert // as above
			sorted := newNode()
			for _, name := range sortedStrings(mapping.keys) {
				sorted.set(name, mapping.vals[name])
			}
			out.set(k, newNode().set("propertyName", d.vals["propertyName"]).set("mapping", sorted))
		default:
			out.set(k, v)
		}
	}
	return out
}
