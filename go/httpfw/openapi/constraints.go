package openapi

import (
	"fmt"
	"math/big"
	"reflect" //nolint:forbidigo // the validators' record types: the JSON edge
	"slices"
	"strings"

	"github.com/flowcatalyst/framework/go/httpfw/validate"
)

// constraints holds the constraints of the request bodies' validators by record type and field
// (OpenAPI contract section 5.4) and applies them to property schemas.
type constraints struct {
	byType   map[reflect.Type]constraintEntry
	seen     map[validate.Described]bool
	problems *[]string
}

type constraintEntry struct {
	fields    map[string][]validate.Constraint
	signature string
}

func newConstraints(problems *[]string) *constraints {
	return &constraints{byType: map[reflect.Type]constraintEntry{}, seen: map[validate.Described]bool{}, problems: problems}
}

func (c *constraints) problem(format string, args ...any) {
	*c.problems = append(*c.problems, fmt.Sprintf(format, args...))
}

// collect gathers a body validator and every validator nested in it.
func (c *constraints) collect(v validate.Described, path string) {
	if c.seen[v] {
		return
	}
	c.seen[v] = true
	t := v.Type()
	fields := map[string][]validate.Constraint{}
	names := fieldNames(t)
	var order []string
	for _, f := range v.Fields() {
		if !slices.Contains(names, f.Name) {
			c.problem("%s: the validator of %s checks '%s', which is not a field of the record", path, t.Name(), f.Name)
			continue
		}
		for _, k := range f.Constraints {
			c.nested(k, path+"."+f.Name)
			if documents(k) {
				if _, ok := fields[f.Name]; !ok {
					order = append(order, f.Name)
				}
				fields[f.Name] = append(fields[f.Name], k)
			}
		}
	}
	var sig strings.Builder
	for _, n := range order {
		fmt.Fprintf(&sig, "%s=%v;", n, fields[n])
	}
	existing, ok := c.byType[t]
	switch {
	case !ok:
		c.byType[t] = constraintEntry{fields: fields, signature: sig.String()}
	case existing.signature != sig.String():
		c.problem("%s: two validators give %s different constraints; a type has one component, so give each its own body type or share the validator", path, t.Name())
	}
}

func (c *constraints) nested(k validate.Constraint, path string) {
	switch x := k.(type) {
	case validate.ValidConstraint:
		c.collect(x.Validator, path)
	case validate.EachConstraint:
		c.nested(x.Element, path+"[]")
	case validate.NoneConstraint, validate.NotNullConstraint, validate.NotBlankConstraint, validate.MinLengthConstraint,
		validate.MaxLengthConstraint, validate.PatternConstraint, validate.RangeConstraint, validate.SizeConstraint,
		validate.OneOfConstraint:
	}
}

func documents(k validate.Constraint) bool {
	switch x := k.(type) {
	case validate.NoneConstraint, validate.NotNullConstraint, validate.ValidConstraint:
		return false
	case validate.EachConstraint:
		return documents(x.Element)
	case validate.NotBlankConstraint, validate.MinLengthConstraint, validate.MaxLengthConstraint, validate.PatternConstraint,
		validate.RangeConstraint, validate.SizeConstraint, validate.OneOfConstraint:
	}
	return true
}

// of returns the documented constraints of one field.
func (c *constraints) of(t reflect.Type, field string) []validate.Constraint {
	return c.byType[t].fields[field]
}

// apply merges constraints into a property's schema: the tighter bound wins, and an explicit
// pattern replaces notBlank's.
func (c *constraints) apply(schema *node, list []validate.Constraint, path string) {
	explicit := slices.ContainsFunc(list, func(k validate.Constraint) bool {
		_, ok := k.(validate.PatternConstraint)
		return ok
	})
	for _, k := range list {
		c.applyOne(schema, k, path, explicit)
	}
}

func (c *constraints) applyOne(schema *node, k validate.Constraint, path string, explicitPattern bool) {
	kind := kindOf(schema)
	switch x := k.(type) {
	case validate.NotBlankConstraint:
		if !c.requireKind(kind, "string", "notBlank", path) {
			return
		}
		tighten(schema, "minLength", number("1"), true)
		if !explicitPattern {
			c.setPattern(schema, `\S`, path)
		}
	case validate.MinLengthConstraint:
		if c.requireKind(kind, "string", "minLength", path) {
			tighten(schema, "minLength", number(fmt.Sprint(x.Min)), true)
		}
	case validate.MaxLengthConstraint:
		if c.requireKind(kind, "string", "maxLength", path) {
			tighten(schema, "maxLength", number(fmt.Sprint(x.Max)), false)
		}
	case validate.PatternConstraint:
		if !c.requireKind(kind, "string", "pattern", path) {
			return
		}
		if why := ecmaProblem(x.Regex); why != "" {
			c.problem("%s: the pattern %s cannot be documented: %s; use validate.Custom", path, x.Regex, why)
			return
		}
		c.setPattern(schema, "^(?:"+x.Regex+")$", path)
	case validate.RangeConstraint:
		if kind != "integer" && kind != "number" {
			c.problem("%s: range applies to a number, not a %s", path, kind)
			return
		}
		tighten(schema, "minimum", number(x.Min), true)
		tighten(schema, "maximum", number(x.Max), false)
	case validate.SizeConstraint:
		if c.requireKind(kind, "array", "size", path) {
			tighten(schema, "minItems", number(fmt.Sprint(x.Min)), true)
			tighten(schema, "maxItems", number(fmt.Sprint(x.Max)), false)
		}
	case validate.OneOfConstraint:
		c.oneOf(schema, x, kind, path)
	case validate.EachConstraint:
		if !c.requireKind(kind, "array", "each", path) {
			return
		}
		if items, ok := schema.get("items"); ok {
			if m, ok := items.(*node); ok {
				cp := m.clone()
				_, isPattern := x.Element.(validate.PatternConstraint)
				c.applyOne(cp, x.Element, path+"[]", isPattern)
				schema.set("items", cp)
			}
		}
	case validate.NoneConstraint, validate.NotNullConstraint, validate.ValidConstraint:
	}
}

func (c *constraints) oneOf(schema *node, o validate.OneOfConstraint, kind, path string) {
	values := make([]any, 0, len(o.Values))
	for _, v := range o.Values {
		switch x := v.(type) {
		case string:
			values = append(values, x)
		case bool:
			values = append(values, x)
		case int, int8, int16, int32, int64, uint, uint8, uint16, uint32, uint64:
			values = append(values, number(fmt.Sprint(x)))
		default:
			if s, ok := enumName(v); ok {
				values = append(values, s)
				continue
			}
			c.problem("%s: oneOf values must be strings, integers, booleans or enum constants", path)
			return
		}
	}
	if kind == "ref" || kind == "array" || kind == "object" || kind == "any" {
		c.problem("%s: oneOf applies to a single value, not a %s", path, kind)
		return
	}
	if existing, ok := schema.get("enum"); ok {
		if allowed, ok := existing.([]any); ok {
			for _, v := range values {
				if !slices.Contains(allowed, v) {
					c.problem("%s: oneOf names %v, which is not a constant of the enum", path, v)
					return
				}
			}
		}
	}
	schema.set("enum", values)
}

// enumName returns the string of a value of a named string type.
func enumName(v any) (string, bool) {
	rv := reflect.ValueOf(v)
	if rv.Kind() == reflect.String {
		return rv.String(), true
	}
	return "", false
}

func (c *constraints) requireKind(kind, wanted, rule, path string) bool {
	if kind != wanted {
		article := "a "
		if wanted == "array" {
			article = "an "
		}
		c.problem("%s: %s applies to %s%s, not a %s", path, rule, article, wanted, kind)
		return false
	}
	return true
}

func (c *constraints) setPattern(schema *node, pattern, path string) {
	if existing, ok := schema.get("pattern"); ok && existing != pattern {
		c.problem("%s: two patterns apply to one value (%v and %s)", path, existing, pattern)
		return
	}
	schema.set("pattern", pattern)
}

// tighten keeps the larger lower bound or the smaller upper bound.
func tighten(schema *node, key string, value number, lower bool) {
	existing, ok := schema.get(key)
	if !ok {
		schema.set(key, value)
		return
	}
	a, b := rat(existing), rat(value)
	if (lower && b.Cmp(a) > 0) || (!lower && b.Cmp(a) < 0) {
		schema.set(key, value)
	}
}

func rat(v any) *big.Rat {
	r := new(big.Rat)
	switch n := v.(type) {
	case number:
		r.SetString(string(n))
	case int64:
		r.SetInt64(n)
	}
	return r
}

// kindOf is the kind of a schema: its type, "ref" for a reference or a union, "any" otherwise.
func kindOf(schema *node) string {
	if schema.has("$ref") || schema.has("oneOf") {
		return "ref"
	}
	if t, ok := schema.get("type"); ok {
		if s, ok := t.(string); ok {
			return s
		}
	}
	return "any"
}
