// Package openapi writes the OpenAPI 3.1 document of an application's typed operations (OpenAPI
// contract, docs/contracts/openapi.md): the shared rules for the type table, closed record schemas,
// validator constraints, the error envelope, implied statuses and security schemes, and a
// deterministic output the Java framework's emitter reproduces byte for byte
// (conformance/openapi/). The httpfw package declares operations and hands their descriptors here;
// the emitter walks the Go types they name once, with reflection, at the JSON edge, and never needs
// configuration, a database or a network.
package openapi

import (
	"reflect" //nolint:forbidigo // the emitter walks the wire types: the JSON edge, as encoding/json does
)

// EnumValuer is implemented by a string type with a closed set of values: its schema is
// {"type": "string", "enum": [...]} in the order EnumValues returns them. EnumValues is called on the
// zero value, so it must not depend on it.
type EnumValuer interface{ EnumValues() []string }

// SchemaProvider is implemented by a value type whose JSON form the emitter cannot derive (an id,
// a decimal, a duration): JSONSchema is called on the zero value and says how it is written. A
// schema that is an object or a union becomes a component named after the type; any other is
// written inline wherever the type appears.
type SchemaProvider interface{ JSONSchema() Schema }

// Unique is implemented by a slice type that holds each value once: the schema gets
// "uniqueItems": true (the Go form of a Java Set).
type Unique interface{ UniqueItems() }

// Schema is a schema registered in code for a type the emitter cannot derive, or for one property
// of a record. Build one with the constructors and the methods that follow; a Schema is a value and
// its methods return changed copies.
type Schema struct {
	kw *node
}

func newSchema(t string) Schema { return Schema{kw: typed(t)} }

// String is {"type": "string"}.
func String() Schema { return newSchema("string") }

// Int32 is {"type": "integer", "format": "int32"}.
func Int32() Schema { return Schema{kw: formatted("integer", "int32")} }

// Int64 is {"type": "integer", "format": "int64"}.
func Int64() Schema { return Schema{kw: formatted("integer", "int64")} }

// Double is {"type": "number", "format": "double"}.
func Double() Schema { return Schema{kw: formatted("number", "double")} }

// Bool is {"type": "boolean"}.
func Bool() Schema { return newSchema("boolean") }

// Array is an array of items.
func Array(items Schema) Schema { return Schema{kw: typed("array").set("items", items)} }

// Of is the schema of another type, as the emitter derives it: a record schema becomes a $ref.
func Of(t reflect.Type) Schema { return Schema{kw: newNode().set("$type", t)} }

// TypeOf returns the reflect.Type of T, for [OneOf], [Of] and the registrations.
func TypeOf[T any]() reflect.Type { return reflect.TypeFor[T]() }

// OneOf is a union without a discriminator (the session answer): the schemas of the variants.
// It needs at least two.
func OneOf(variants ...reflect.Type) Schema {
	if len(variants) < 2 {
		panic("openapi: OneOf needs at least two variants")
	}
	list := make([]any, len(variants))
	for i, v := range variants {
		list[i] = Of(v)
	}
	return Schema{kw: newNode().set("oneOf", list)}
}

// Variant names one variant of a [Union].
type Variant struct {
	// Name is the discriminator's value for the variant.
	Name string
	// Type is the variant's record type; it is documented under its own name, with the
	// discriminator as its first, required, constant property.
	Type reflect.Type
}

// Union is a union with a discriminator property (the form of Java's sealed interface with
// @JsonTypeInfo): "oneOf" lists the variants in the sorted order of their names, with a
// discriminator mapping. Register it for the interface type that stands for the union.
func Union(property string, variants ...Variant) Schema {
	if property == "" || len(variants) < 2 {
		panic("openapi: Union needs a property and at least two variants")
	}
	return Schema{kw: newNode().set("$union", unionSpec{property: property, variants: append([]Variant(nil), variants...)})}
}

type unionSpec struct {
	property string
	variants []Variant
}

func (s Schema) with(k string, v any) Schema {
	if s.kw.has("$type") || s.kw.has("oneOf") || s.kw.has("$union") {
		panic("openapi: Of, OneOf and Union take no further keywords")
	}
	c := s.kw.clone()
	c.set(k, v)
	return Schema{kw: c}
}

// Format sets "format".
func (s Schema) Format(f string) Schema { return s.with("format", f) }

// Pattern sets "pattern": an ECMA-262 regular expression, not anchored.
func (s Schema) Pattern(re string) Schema { return s.with("pattern", re) }

// MinLength sets "minLength".
func (s Schema) MinLength(n int) Schema { return s.with("minLength", int64(n)) }

// MaxLength sets "maxLength".
func (s Schema) MaxLength(n int) Schema { return s.with("maxLength", int64(n)) }

// Minimum sets "minimum" to a JSON number given as text.
func (s Schema) Minimum(n string) Schema { return s.with("minimum", number(n)) }

// Maximum sets "maximum" to a JSON number given as text.
func (s Schema) Maximum(n string) Schema { return s.with("maximum", number(n)) }

// ConstString sets "const" to a string.
func (s Schema) ConstString(v string) Schema { return s.with("const", v) }

// ConstBool sets "const" to a boolean.
func (s Schema) ConstBool(v bool) Schema { return s.with("const", v) }

// Enum sets "enum".
func (s Schema) Enum(values ...string) Schema {
	list := make([]any, len(values))
	for i, v := range values {
		list[i] = v
	}
	return s.with("enum", list)
}

// Description sets "description".
func (s Schema) Description(text string) Schema { return s.with("description", text) }

func (s Schema) isComponent() bool {
	if s.kw == nil {
		return false
	}
	if s.kw.has("oneOf") || s.kw.has("$union") {
		return true
	}
	t, _ := s.kw.get("type")
	return t == "object"
}
