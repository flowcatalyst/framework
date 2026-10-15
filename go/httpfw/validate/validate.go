// Package validate is request validation as data (HTTP contract section 7, OpenAPI contract section
// 5.4): a [Validator] is a list of fields, each with [Rule]s. A rule both checks a value and
// describes itself as a [Constraint], so the one declaration that rejects a bad request also gives
// the OpenAPI document its minLength, pattern, minimum and the rest.
//
//	var addLine = validate.Of(
//		validate.Field("sku", func(b AddLineBody) string { return b.SKU },
//			validate.NotBlank(), validate.MaxLength(64), validate.Pattern("[A-Z0-9][A-Z0-9-]*")),
//		validate.Field("quantity", func(b AddLineBody) int32 { return b.Quantity }, validate.Range[int32](1, 10_000)),
//	)
//
// All the errors of a body are collected, not the first. A rule on a zero value other than
// [NotBlank] and [NotNull] passes only if the value satisfies it: absence is the optional field's
// business (use [Ptr] for a field that may be absent), as in the Java framework.
package validate

import (
	"fmt"
	"reflect" //nolint:forbidigo // the type a Validator describes, for the OpenAPI emitter (the JSON edge)
	"regexp"
	"slices"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf16"

	"github.com/flowcatalyst/framework/go/internal/javafmt"
)

// FieldError is one thing wrong with a request: the field path (name, name.nested, name[2]), a
// code and a message that never contains the offending value.
type FieldError struct {
	Field, Code, Message string
}

// Violation is what one rule found wrong with one value: a path below the value ("" for the value
// itself, "[2]", "street"), a code and a message.
type Violation struct {
	Path, Code, Message string
}

// Violations collects what a [Rule] finds.
type Violations struct{ entries []Violation }

// Add records a violation of the value itself.
func (v *Violations) Add(code, message string) {
	v.entries = append(v.entries, Violation{Code: code, Message: message})
}

// AddAt records a violation of a part of the value.
func (v *Violations) AddAt(path, code, message string) {
	v.entries = append(v.entries, Violation{Path: path, Code: code, Message: message})
}

// Entries returns the violations in the order found.
func (v *Violations) Entries() []Violation { return v.entries }

// Empty reports whether nothing was recorded.
func (v *Violations) Empty() bool { return len(v.entries) == 0 }

// join joins two field paths: a + b is a.b, a + [1] is a[1].
func join(parent, child string) string {
	switch {
	case parent == "":
		return child
	case child == "":
		return parent
	case strings.HasPrefix(child, "["):
		return parent + child
	}
	return parent + "." + child
}

// Rule checks a value of type V and describes itself as data.
type Rule[V any] interface {
	// Check records what is wrong with v.
	Check(v V, out *Violations)
	// Describe returns the rule as a [Constraint] ([NoneConstraint] when it cannot be documented).
	Describe() Constraint
}

type rule[V any] struct {
	check func(V, *Violations)
	desc  Constraint
}

func (r rule[V]) Check(v V, out *Violations) { r.check(v, out) }
func (r rule[V]) Describe() Constraint       { return r.desc }

// Constraint is a rule described as data, for the OpenAPI emitter.
//
//sumtype:decl
type Constraint interface{ constraint() }

// NoneConstraint is a rule with nothing to document (Custom).
type NoneConstraint struct{}

// NotNullConstraint is [NotNull]: required-ness comes from the type, so it documents nothing.
type NotNullConstraint struct{}

// NotBlankConstraint is [NotBlank].
type NotBlankConstraint struct{}

// MinLengthConstraint is [MinLength].
type MinLengthConstraint struct{ Min int }

// MaxLengthConstraint is [MaxLength].
type MaxLengthConstraint struct{ Max int }

// PatternConstraint is [Pattern]: the regular expression as written (whole-string).
type PatternConstraint struct{ Regex string }

// RangeConstraint is [Range]; Min and Max are the bounds as JSON number text.
type RangeConstraint struct{ Min, Max string }

// SizeConstraint is [Size].
type SizeConstraint struct{ Min, Max int }

// OneOfConstraint is [OneOf]: the allowed values in declaration order.
type OneOfConstraint struct{ Values []any }

// EachConstraint is [Each]: the constraint of every element.
type EachConstraint struct{ Element Constraint }

// ValidConstraint is [Nested]: the nested value's own validator.
type ValidConstraint struct{ Validator Described }

func (NoneConstraint) constraint()      {}
func (NotNullConstraint) constraint()   {}
func (NotBlankConstraint) constraint()  {}
func (MinLengthConstraint) constraint() {}
func (MaxLengthConstraint) constraint() {}
func (PatternConstraint) constraint()   {}
func (RangeConstraint) constraint()     {}
func (SizeConstraint) constraint()      {}
func (OneOfConstraint) constraint()     {}
func (EachConstraint) constraint()      {}
func (ValidConstraint) constraint()     {}

// ---- rules ---------------------------------------------------------------------------------------

// NotNull requires a non-nil pointer.
func NotNull[E any]() Rule[*E] {
	return rule[*E]{desc: NotNullConstraint{}, check: func(v *E, out *Violations) {
		if v == nil {
			out.Add("NOT_NULL", "is required")
		}
	}}
}

// NotBlank requires a string with a character that is not white space.
func NotBlank() Rule[string] {
	return rule[string]{desc: NotBlankConstraint{}, check: func(v string, out *Violations) {
		if strings.IndexFunc(v, func(r rune) bool { return !unicode.IsSpace(r) }) < 0 {
			out.Add("NOT_BLANK", "must not be blank")
		}
	}}
}

// length is the string's length in UTF-16 code units, which is what Java's String.length counts
// and so what JSON Schema's minLength agrees with for the characters applications use.
func length(s string) int {
	n := 0
	for _, r := range s {
		n += len(utf16.Encode([]rune{r}))
	}
	return n
}

// MinLength requires at least n characters.
func MinLength(n int) Rule[string] {
	return rule[string]{desc: MinLengthConstraint{Min: n}, check: func(v string, out *Violations) {
		if length(v) < n {
			out.Add("TOO_SHORT", "must be at least "+strconv.Itoa(n)+" characters")
		}
	}}
}

// MaxLength requires at most n characters.
func MaxLength(n int) Rule[string] {
	return rule[string]{desc: MaxLengthConstraint{Max: n}, check: func(v string, out *Violations) {
		if length(v) > n {
			out.Add("TOO_LONG", "must be at most "+strconv.Itoa(n)+" characters")
		}
	}}
}

// Pattern requires the whole string to match the regular expression (Go RE2 syntax, which for the
// patterns the OpenAPI document can carry is the same as ECMA-262's). It panics if the expression
// does not compile: a programming error found when the package that declares the validator
// initialises.
func Pattern(expr string) Rule[string] {
	re := regexp.MustCompile(`^(?:` + expr + `)$`)
	return rule[string]{desc: PatternConstraint{Regex: expr}, check: func(v string, out *Violations) {
		if !re.MatchString(v) {
			out.Add("PATTERN", "has an invalid format")
		}
	}}
}

// Number is the numeric types [Range] accepts.
type Number interface {
	~int | ~int8 | ~int16 | ~int32 | ~int64 | ~uint | ~uint8 | ~uint16 | ~uint32 | ~uint64 | ~float32 | ~float64
}

// Range requires lo <= v <= hi. A NaN fails.
func Range[N Number](lo, hi N) Rule[N] {
	text := numberText(lo) + " and " + numberText(hi)
	return rule[N]{
		desc: RangeConstraint{Min: jsonNumber(lo), Max: jsonNumber(hi)},
		check: func(v N, out *Violations) {
			if v != v || v < lo || v > hi { //nolint:gocritic // v != v is the NaN test
				out.Add("OUT_OF_RANGE", "must be between "+text)
			}
		},
	}
}

func isFloat[N Number]() bool {
	k := reflect.TypeFor[N]().Kind()
	return k == reflect.Float32 || k == reflect.Float64
}

// numberText prints a bound as the Java framework's message does: an integer plainly, a floating
// point value as Double.toString.
func numberText[N Number](n N) string {
	if isFloat[N]() {
		return javafmt.Double(float64(n))
	}
	return fmt.Sprint(n)
}

// jsonNumber prints a bound as the shortest JSON number of that value.
func jsonNumber[N Number](n N) string {
	if isFloat[N]() {
		return strconv.FormatFloat(float64(n), 'f', -1, 64)
	}
	return fmt.Sprint(n)
}

// Size requires lo <= len(v) <= hi.
func Size[E any](lo, hi int) Rule[[]E] {
	return rule[[]E]{desc: SizeConstraint{Min: lo, Max: hi}, check: func(v []E, out *Violations) {
		if len(v) < lo || len(v) > hi {
			out.Add("SIZE", fmt.Sprintf("must have between %d and %d items", lo, hi))
		}
	}}
}

// OneOf requires v to be one of the allowed values.
func OneOf[V comparable](allowed ...V) Rule[V] {
	values := make([]any, len(allowed))
	names := make([]string, len(allowed))
	for i, a := range allowed {
		values[i], names[i] = a, fmt.Sprint(a)
	}
	slices.Sort(names)
	text := strings.Join(slices.Compact(names), ", ")
	return rule[V]{desc: OneOfConstraint{Values: values}, check: func(v V, out *Violations) {
		if !slices.Contains(allowed, v) {
			out.Add("NOT_ONE_OF", "must be one of: "+text)
		}
	}}
}

// Each applies a rule to every element; a failure's path is [i] below the field.
func Each[E any](r Rule[E]) Rule[[]E] {
	return rule[[]E]{desc: EachConstraint{Element: r.Describe()}, check: func(v []E, out *Violations) {
		for i, e := range v {
			var inner Violations
			r.Check(e, &inner)
			prefix := "[" + strconv.Itoa(i) + "]"
			for _, x := range inner.entries {
				out.AddAt(join(prefix, x.Path), x.Code, x.Message)
			}
		}
	}}
}

// Nested applies another validator to a nested value; a failure's path is field.nested.
func Nested[V any](v *Validator[V]) Rule[V] {
	return rule[V]{desc: ValidConstraint{Validator: v}, check: func(value V, out *Violations) {
		for _, e := range v.Validate(value) {
			out.AddAt(e.Field, e.Code, e.Message)
		}
	}}
}

// Custom checks a value with a function. It documents nothing.
func Custom[V any](code, message string, valid func(V) bool) Rule[V] {
	return rule[V]{desc: NoneConstraint{}, check: func(v V, out *Violations) {
		if !valid(v) {
			out.Add(code, message)
		}
	}}
}

// Ptr applies a rule to the value a pointer points to; a nil pointer passes (the field may be
// absent). Use [NotNull] beside it for a field that may not.
func Ptr[V any](r Rule[V]) Rule[*V] {
	return rule[*V]{desc: r.Describe(), check: func(v *V, out *Violations) {
		if v != nil {
			r.Check(*v, out)
		}
	}}
}

// ---- validator -----------------------------------------------------------------------------------

// FieldRules is one field of a validator described as data: its name and its rules' constraints.
type FieldRules struct {
	Name        string
	Constraints []Constraint
}

// Described is a validator without its type parameter, as the OpenAPI emitter reads it.
type Described interface {
	// Type returns the type the validator checks.
	Type() reflect.Type
	// Fields returns the validated fields in declaration order.
	Fields() []FieldRules
}

// FieldSpec is one validated field of a T, made by [Field].
type FieldSpec[T any] struct {
	name  string
	check func(root T, out *[]FieldError)
	desc  []Constraint
}

// Field validates one field of T: name is the field's JSON name, get reads its value and the
// rules check it, in order. Every rule runs; a field can fail several.
func Field[T, V any](name string, get func(T) V, rules ...Rule[V]) FieldSpec[T] {
	desc := make([]Constraint, len(rules))
	for i, r := range rules {
		desc[i] = r.Describe()
	}
	return FieldSpec[T]{
		name: name,
		desc: desc,
		check: func(root T, out *[]FieldError) {
			value := get(root)
			var found Violations
			for _, r := range rules {
				r.Check(value, &found)
			}
			for _, e := range found.entries {
				*out = append(*out, FieldError{Field: join(name, e.Path), Code: e.Code, Message: e.Message})
			}
		},
	}
}

// Validator checks values of type T and describes its rules as data. Build one with [Of]; it is
// immutable and safe for concurrent use.
type Validator[T any] struct {
	fields []FieldSpec[T]
}

// Of returns the validator of the given fields.
func Of[T any](fields ...FieldSpec[T]) *Validator[T] {
	return &Validator[T]{fields: slices.Clone(fields)}
}

// None returns a validator with no rules: for a body whose only checks are its types.
func None[T any]() *Validator[T] { return &Validator[T]{} }

// Validate returns every error found, in field order; nil when the value is valid.
func (v *Validator[T]) Validate(value T) []FieldError {
	var out []FieldError
	for _, f := range v.fields {
		f.check(value, &out)
	}
	return out
}

// Type implements [Described].
func (v *Validator[T]) Type() reflect.Type { return reflect.TypeFor[T]() }

// Fields implements [Described].
func (v *Validator[T]) Fields() []FieldRules {
	out := make([]FieldRules, len(v.fields))
	for i, f := range v.fields {
		out[i] = FieldRules{Name: f.name, Constraints: slices.Clone(f.desc)}
	}
	return out
}
