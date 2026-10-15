package openapi

import (
	"encoding"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"fmt"
	"reflect" //nolint:forbidigo // walks the wire types once, at the JSON edge, as encoding/json does
	"slices"
	"strings"
	"time"
	"unicode"
)

const refPrefix = "#/components/schemas/"

// errorOwner stands for the framework's own Error component when two types claim its name.
type errorOwner struct{}

// walker walks the types reachable from the operations once (OpenAPI contract section 5): the type
// table, records as components, unions as oneOf, generic types named by their arguments, and every
// refusal with the path from the operation to the type.
type walker struct {
	registered map[reflect.Type]Schema
	names      map[reflect.Type]string
	properties map[reflect.Type]map[string]Schema
	cons       *constraints
	problems   *[]string

	components map[string]*node
	typeNames  map[reflect.Type]string
	nameOwners map[string]reflect.Type
	variants   map[reflect.Type]variantInfo
}

type variantInfo struct {
	property, name string
}

func newWalker(in Input, cons *constraints, problems *[]string) *walker {
	return &walker{
		registered: in.Schemas, names: in.Names, properties: in.Properties, cons: cons, problems: problems,
		components: map[string]*node{}, typeNames: map[reflect.Type]string{}, nameOwners: map[string]reflect.Type{},
		variants: map[reflect.Type]variantInfo{},
	}
}

func (w *walker) problem(format string, args ...any) {
	*w.problems = append(*w.problems, fmt.Sprintf(format, args...))
}

// reserve claims a component name for the framework.
func (w *walker) reserve(name string, body *node) {
	w.nameOwners[name] = reflect.TypeFor[errorOwner]()
	w.components[name] = body
}

// schema returns the schema of a declared type: inline or a $ref. An empty node is returned (and a
// problem recorded) for a type that is refused.
func (w *walker) schema(t reflect.Type, path string) *node { return w.schemaOf(t, path) }

var (
	timeType        = reflect.TypeFor[time.Time]()
	rawValueType    = reflect.TypeFor[jsontext.Value]()
	bytesType       = reflect.TypeFor[[]byte]()
	enumValuerType  = reflect.TypeFor[EnumValuer]()
	providerType    = reflect.TypeFor[SchemaProvider]()
	uniqueType      = reflect.TypeFor[Unique]()
	textMarshaler   = reflect.TypeFor[encoding.TextMarshaler]()
	jsonMarshaler   = reflect.TypeFor[json.Marshaler]()
	jsonMarshalerTo = reflect.TypeFor[json.MarshalerTo]()
)

func (w *walker) schemaOf(t reflect.Type, path string) *node {
	for t.Kind() == reflect.Pointer {
		t = t.Elem()
	}
	reg, ok := w.registered[t]
	if !ok {
		reg, ok = providedSchema(t)
	}
	if ok {
		if reg.isComponent() {
			return w.component(t, path, func() *node { return w.render(reg, path) })
		}
		return w.render(reg, path)
	}
	switch t {
	case timeType:
		return formatted("string", "date-time")
	case rawValueType:
		return newNode()
	case bytesType:
		return typed("string").set("contentEncoding", "base64")
	}
	if implementsAny(t, textMarshaler, jsonMarshaler, jsonMarshalerTo) {
		w.problem("%s: %s writes its own JSON, so the document cannot be derived; give it a JSONSchema method (openapi.SchemaProvider) or register a schema", path, t.String())
		return newNode()
	}
	switch t.Kind() { //nolint:exhaustive // every other kind is refused below
	case reflect.String:
		if implements(t, enumValuerType) {
			values := reflect.Zero(t).Interface().(EnumValuer).EnumValues() //nolint:forcetypeassert // checked by implements
			list := make([]any, len(values))
			for i, v := range values {
				list[i] = v
			}
			return typed("string").set("enum", list)
		}
		return typed("string")
	case reflect.Bool:
		return typed("boolean")
	case reflect.Int32:
		return formatted("integer", "int32")
	case reflect.Int64:
		return formatted("integer", "int64")
	case reflect.Float64:
		return formatted("number", "double")
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr, reflect.Float32:
		w.problem("%s: %s is not in the type table; use int32, int64 or float64", path, t.String())
		return newNode()
	case reflect.Slice:
		return w.arrayOf(t, path)
	case reflect.Array:
		w.problem("%s: the array %s is refused; use a slice", path, t.String())
		return newNode()
	case reflect.Map:
		if t.Key().Kind() != reflect.String {
			w.problem("%s: a map's key must be a string, not %s", path, t.Key().String())
			return newNode()
		}
		w.noPointerElem(t.Elem(), path)
		return typed("object").set("additionalProperties", w.schemaOf(t.Elem(), path+"{}"))
	case reflect.Interface:
		w.problem("%s: the interface %s needs a registered schema (openapi.Union or openapi.OneOf)", path, t.String())
		return newNode()
	case reflect.Struct:
		return w.component(t, path, func() *node { return w.structBody(t, path) })
	}
	w.problem("%s: %s is not a struct, an enum, a registered type or one of the type table's types", path, t.String())
	return newNode()
}

func (w *walker) arrayOf(t reflect.Type, path string) *node {
	w.noPointerElem(t.Elem(), path)
	m := typed("array").set("items", w.schemaOf(t.Elem(), path+"[]"))
	if implements(t, uniqueType) {
		m.set("uniqueItems", true)
	}
	return m
}

// noPointerElem refuses a pointer element: an array or a map holds no absent values.
func (w *walker) noPointerElem(elem reflect.Type, path string) {
	if elem.Kind() == reflect.Pointer {
		w.problem("%s: a pointer element is refused (arrays and maps hold no absent values)", path)
	}
}

func implements(t, iface reflect.Type) bool {
	return t.Implements(iface) || reflect.PointerTo(t).Implements(iface)
}

func implementsAny(t reflect.Type, ifaces ...reflect.Type) bool {
	return slices.ContainsFunc(ifaces, func(i reflect.Type) bool { return implements(t, i) })
}

// providedSchema returns the schema a type gives itself with JSONSchema.
func providedSchema(t reflect.Type) (Schema, bool) {
	if t.Kind() == reflect.Interface || !implements(t, providerType) {
		return Schema{}, false
	}
	var v reflect.Value
	if t.Implements(providerType) {
		v = reflect.Zero(t)
	} else {
		v = reflect.New(t)
	}
	sp, ok := reflect.TypeAssert[SchemaProvider](v)
	if !ok {
		return Schema{}, false
	}
	return sp.JSONSchema(), true
}

// ---- components ------------------------------------------------------------------------------

func (w *walker) component(t reflect.Type, path string, body func() *node) *node {
	if known, ok := w.typeNames[t]; ok {
		return ref(known)
	}
	name := w.nameOf(t)
	if name == "" {
		w.problem("%s: %s has no name for a component (a local or anonymous type); declare it at package level", path, t.String())
		return newNode()
	}
	if owner, ok := w.nameOwners[name]; ok && owner != t {
		w.problem("%s: two types produce the component name %s: %s and %s (rename one, or register a name with the declarations)",
			path, name, describe(owner), describe(t))
		return ref(name)
	}
	w.typeNames[t] = name
	w.nameOwners[name] = t
	w.components[name] = newNode() // a placeholder while a recursive type is walked
	w.components[name] = body()
	return ref(name)
}

func describe(t reflect.Type) string {
	if t == reflect.TypeFor[errorOwner]() {
		return "the framework's error envelope"
	}
	return t.String()
}

func ref(name string) *node { return newNode().set("$ref", refPrefix+name) }

// nameOf is the component name of a type: a registered name, else the type's name; a generic type
// is followed by its type arguments' names (Page[OrderView] is PageOrderView).
func (w *walker) nameOf(t reflect.Type) string {
	if n, ok := w.names[t]; ok {
		return n
	}
	return typeName(t)
}

func typeName(t reflect.Type) string {
	name := t.Name()
	if name == "" {
		return ""
	}
	open := strings.IndexByte(name, '[')
	if open < 0 {
		return name
	}
	var sb strings.Builder
	sb.WriteString(name[:open])
	for _, arg := range splitTypeArgs(name[open+1 : len(name)-1]) {
		sb.WriteString(argName(arg))
	}
	return sb.String()
}

// splitTypeArgs splits "a.B,c.D[e.F,g.H]" at its top-level commas.
func splitTypeArgs(s string) []string {
	var out []string
	depth, start := 0, 0
	for i, r := range s {
		switch r {
		case '[':
			depth++
		case ']':
			depth--
		case ',':
			if depth == 0 {
				out = append(out, s[start:i])
				start = i + 1
			}
		}
	}
	return append(out, s[start:])
}

// argName turns a type argument as reflect prints it ("github.com/x/pkg.OrderView",
// "[]string", "map[string]pkg.V") into the words that name a component.
func argName(arg string) string {
	arg = strings.TrimPrefix(arg, "*")
	open := strings.IndexByte(arg, '[')
	switch {
	case strings.HasPrefix(arg, "[]"):
		return "List" + argName(arg[2:])
	case strings.HasPrefix(arg, "map["):
		_, value, _ := strings.Cut(arg, "]")
		return "Map" + argName(value)
	case open > 0:
		base := arg[:open]
		var sb strings.Builder
		sb.WriteString(lastElement(base))
		for _, a := range splitTypeArgs(arg[open+1 : len(arg)-1]) {
			sb.WriteString(argName(a))
		}
		return sb.String()
	}
	return upperFirst(lastElement(arg))
}

func lastElement(s string) string {
	if i := strings.LastIndexAny(s, "/."); i >= 0 {
		return s[i+1:]
	}
	return s
}

func upperFirst(s string) string {
	for i, r := range s {
		return string(unicode.ToUpper(r)) + s[i+len(string(r)):]
	}
	return s
}

// ---- records ---------------------------------------------------------------------------------

type fieldInfo struct {
	name     string
	typ      reflect.Type
	optional bool
}

// fieldsOf reads a struct's JSON fields in declaration order (problems are recorded when path is
// not empty).
func (w *walker) fieldsOf(t reflect.Type, path string, report bool) []fieldInfo {
	var out []fieldInfo
	for f := range t.Fields() {
		tag, hasTag := f.Tag.Lookup("json")
		if tag == "-" {
			continue
		}
		if f.Anonymous && !hasTag {
			if report {
				w.problem("%s.%s: an embedded field is refused; give it a name and a json tag", path, f.Name)
			}
			continue
		}
		if !f.IsExported() {
			continue
		}
		name, opts, _ := strings.Cut(tag, ",")
		if name == "" {
			name = f.Name
		}
		info := fieldInfo{name: name, typ: f.Type, optional: f.Type.Kind() == reflect.Pointer}
		omitted := false
		for opt := range strings.SplitSeq(opts, ",") {
			switch opt {
			case "":
			case "omitzero", "omitempty":
				info.optional, omitted = true, true
			default:
				if report {
					w.problem("%s.%s: the json option '%s' is not understood by the document", path, name, opt)
				}
			}
		}
		if report && f.Type.Kind() == reflect.Pointer && !omitted {
			w.problem("%s.%s: a pointer field is optional in the document, so it needs omitzero: a nil must be left out of the JSON, not written as null", path, name)
		}
		out = append(out, info)
	}
	return out
}

// fieldNames are the JSON names of a struct's fields, for checking validators.
func fieldNames(t reflect.Type) []string {
	if t.Kind() != reflect.Struct {
		return nil
	}
	var w walker
	var out []string
	for _, f := range w.fieldsOf(t, "", false) {
		out = append(out, f.name)
	}
	return out
}

func (w *walker) structBody(t reflect.Type, path string) *node {
	props := newNode()
	var required []any
	var discriminator string
	if v, ok := w.variants[t]; ok {
		discriminator = v.property
		props.set(v.property, typed("string").set("const", v.name))
		required = append(required, v.property)
	}
	overrides := w.properties[t]
	seen := map[string]bool{}
	for _, f := range w.fieldsOf(t, path, true) {
		here := path + "." + f.name
		seen[f.name] = true
		if f.name == discriminator {
			w.problem("%s: the field has the name of the discriminator property '%s'", here, f.name)
			continue
		}
		var s *node
		if o, ok := overrides[f.name]; ok {
			s = w.render(o, here)
		} else {
			s = w.schemaOf(f.typ, here)
			w.cons.apply(s, w.cons.of(t, f.name), here)
		}
		props.set(f.name, s)
		if !f.optional {
			required = append(required, f.name)
		}
	}
	for name := range overrides {
		if !seen[name] {
			w.problem("%s: a property schema is registered for '%s', which is not a field of %s", path, name, t.Name())
		}
	}
	body := typed("object").set("properties", props)
	if len(required) > 0 {
		body.set("required", required)
	}
	return body.set("additionalProperties", false)
}

// ---- registered schemas ----------------------------------------------------------------------

func (w *walker) render(s Schema, path string) *node {
	if s.kw == nil {
		w.problem("%s: an empty Schema", path)
		return newNode()
	}
	if t, ok := s.kw.get("$type"); ok {
		return w.schemaOf(t.(reflect.Type), path) //nolint:forcetypeassert // Of stores a reflect.Type
	}
	if u, ok := s.kw.get("$union"); ok {
		return w.union(u.(unionSpec), path) //nolint:forcetypeassert // Union stores a unionSpec
	}
	out := newNode()
	for _, k := range s.kw.keys {
		switch v := s.kw.vals[k].(type) {
		case Schema:
			out.set(k, w.render(v, path))
		case []any:
			items := make([]any, len(v))
			for i, item := range v {
				if sub, ok := item.(Schema); ok {
					items[i] = w.render(sub, path)
				} else {
					items[i] = item
				}
			}
			out.set(k, items)
		default:
			out.set(k, v)
		}
	}
	return out
}

func (w *walker) union(u unionSpec, path string) *node {
	byName := map[string]reflect.Type{}
	var names []string
	for _, v := range u.variants {
		if v.Name == "" || v.Type == nil || v.Type.Kind() != reflect.Struct {
			w.problem("%s: a union variant needs a name and a struct type", path)
			return newNode()
		}
		if _, dup := byName[v.Name]; dup {
			w.problem("%s: two variants of the union have the name '%s'", path, v.Name)
			return newNode()
		}
		byName[v.Name] = v.Type
		names = append(names, v.Name)
		w.variants[v.Type] = variantInfo{property: u.property, name: v.Name}
	}
	slices.Sort(names)
	oneOf := make([]any, 0, len(names))
	mapping := newNode()
	for _, n := range names {
		vt, ok := byName[n]
		if !ok {
			continue // names holds the keys of byName
		}
		r := w.schemaOf(vt, path+"."+vt.Name())
		oneOf = append(oneOf, r)
		if target, ok := r.get("$ref"); ok {
			mapping.set(n, target)
		}
	}
	disc := newNode().set("propertyName", u.property).set("mapping", mapping)
	return newNode().set("oneOf", oneOf).set("discriminator", disc)
}
