package validate_test

import (
	"slices"
	"testing"

	"github.com/flowcatalyst/framework/go/httpfw/validate"
)

type line struct {
	SKU      string
	Quantity int32
}

var lineV = validate.Of(
	validate.Field("sku", func(l line) string { return l.SKU }, validate.NotBlank()),
	validate.Field("quantity", func(l line) int32 { return l.Quantity }, validate.Range[int32](1, 99)),
)

type widget struct {
	Name  string
	Code  string
	Note  *string
	Qty   int32
	Ratio float64
	Tags  []string
	Line  line
	Lines []line
	Color string
	Free  string
}

var widgetV = validate.Of(
	validate.Field("name", func(w widget) string { return w.Name }, validate.NotBlank(), validate.MaxLength(100)),
	validate.Field("code", func(w widget) string { return w.Code }, validate.MinLength(3), validate.MaxLength(12), validate.Pattern("[A-Z][A-Z0-9-]*")),
	validate.Field("note", func(w widget) *string { return w.Note }, validate.Ptr(validate.MaxLength(500))),
	validate.Field("qty", func(w widget) int32 { return w.Qty }, validate.Range[int32](1, 1000)),
	validate.Field("ratio", func(w widget) float64 { return w.Ratio }, validate.Range(0.5, 2.5)),
	validate.Field("tags", func(w widget) []string { return w.Tags }, validate.Size[string](1, 5), validate.Each(validate.MaxLength(20))),
	validate.Field("color", func(w widget) string { return w.Color }, validate.OneOf("red", "green")),
	validate.Field("line", func(w widget) line { return w.Line }, validate.Nested(lineV)),
	validate.Field("lines", func(w widget) []line { return w.Lines }, validate.Each(validate.Nested(lineV))),
	validate.Field("free", func(w widget) string { return w.Free }, validate.Custom("NOPE", "must not be nope", func(s string) bool { return s != "nope" })),
)

func good() widget {
	return widget{Name: "n", Code: "ABC", Qty: 5, Ratio: 1, Tags: []string{"a"}, Color: "red", Line: line{"x", 1}, Lines: []line{{"y", 2}}}
}

func codes(errs []validate.FieldError) []string {
	var out []string
	for _, e := range errs {
		out = append(out, e.Field+"="+e.Code)
	}
	return out
}

func TestValidWidget(t *testing.T) {
	if errs := widgetV.Validate(good()); len(errs) != 0 {
		t.Fatalf("want no errors, got %v", errs)
	}
}

func TestEveryRuleFailsWithItsCodeAndPath(t *testing.T) {
	w := widget{
		Name: " ", Code: "ab!", Note: new(string(make([]rune, 501))), Qty: 0, Ratio: 9, Tags: []string{"a", "b", "c", "d", "e", "f"},
		Color: "blue", Line: line{"", 100}, Lines: []line{{"ok", 1}, {" ", 1}}, Free: "nope",
	}
	got := codes(widgetV.Validate(w))
	want := []string{
		"name=NOT_BLANK", "code=PATTERN", "note=TOO_LONG", "qty=OUT_OF_RANGE", "ratio=OUT_OF_RANGE", "tags=SIZE",
		"color=NOT_ONE_OF", "line.sku=NOT_BLANK", "line.quantity=OUT_OF_RANGE", "lines[1].sku=NOT_BLANK", "free=NOPE",
	}
	// code is too short? "ab!" is 3 chars: min 3 passes, the pattern fails
	if !slices.Equal(got, want) {
		t.Fatalf("errors differ\n want %v\n  got %v", want, got)
	}
}

func TestMessagesNeverContainTheValue(t *testing.T) {
	w := good()
	w.Name = "   "
	w.Code = "secret-value-1"
	for _, e := range widgetV.Validate(w) {
		if e.Field == "code" && (e.Message != "has an invalid format" && e.Message != "must be at most 12 characters") {
			t.Errorf("unexpected message %q", e.Message)
		}
	}
}

func TestNaNFailsRange(t *testing.T) {
	w := good()
	nan := 0.0
	w.Ratio = nan / nan
	if got := codes(widgetV.Validate(w)); !slices.Equal(got, []string{"ratio=OUT_OF_RANGE"}) {
		t.Fatalf("want ratio out of range, got %v", got)
	}
}

func TestNilPointerPassesPtrRules(t *testing.T) {
	w := good()
	w.Note = nil
	if errs := widgetV.Validate(w); len(errs) != 0 {
		t.Fatal(errs)
	}
}

func TestOneOfMessageListsSortedValues(t *testing.T) {
	w := good()
	w.Color = "blue"
	errs := widgetV.Validate(w)
	if len(errs) != 1 || errs[0].Message != "must be one of: green, red" {
		t.Fatalf("got %v", errs)
	}
}

func TestDescribeExposesConstraintsAsData(t *testing.T) {
	found := widgetV.Fields()
	if len(found) != 10 {
		t.Fatalf("want 10 fields, got %d", len(found))
	}
	first := func(name string, want int) validate.Constraint {
		t.Helper()
		for _, f := range found {
			if f.Name == name {
				if len(f.Constraints) != want {
					t.Fatalf("%s: want %d constraints, got %v", name, want, f.Constraints)
				}
				return f.Constraints[0]
			}
		}
		t.Fatalf("no field %s", name)
		return validate.NoneConstraint{}
	}
	code := found[1]
	if code.Name != "code" || len(code.Constraints) != 3 {
		t.Fatalf("code: %v", code)
	}
	if p, ok := code.Constraints[2].(validate.PatternConstraint); !ok || p.Regex != "[A-Z][A-Z0-9-]*" {
		t.Errorf("code pattern: %#v", code.Constraints[2])
	}
	if r, ok := first("qty", 1).(validate.RangeConstraint); !ok || r.Min != "1" || r.Max != "1000" {
		t.Errorf("qty range: %#v", first("qty", 1))
	}
	if r, ok := first("ratio", 1).(validate.RangeConstraint); !ok || r.Min != "0.5" || r.Max != "2.5" {
		t.Errorf("ratio range: %#v", first("ratio", 1))
	}
	if _, ok := first("free", 1).(validate.NoneConstraint); !ok {
		t.Errorf("custom documents nothing: %#v", first("free", 1))
	}
	tags := found[5]
	if tags.Name != "tags" || len(tags.Constraints) != 2 {
		t.Fatalf("tags: %v", tags)
	}
	if e, ok := tags.Constraints[1].(validate.EachConstraint); !ok {
		t.Errorf("tags each: %#v", tags.Constraints[1])
	} else if m, ok := e.Element.(validate.MaxLengthConstraint); !ok || m.Max != 20 {
		t.Errorf("tags each element: %#v", e.Element)
	}
	if _, ok := first("line", 1).(validate.ValidConstraint); !ok {
		t.Errorf("nested: %#v", first("line", 1))
	}
	if o, ok := first("color", 1).(validate.OneOfConstraint); !ok || !slices.Equal(o.Values, []any{"red", "green"}) {
		t.Errorf("oneOf keeps declaration order: %#v", first("color", 1))
	}
}

func TestPatternIsWholeString(t *testing.T) {
	w := good()
	w.Code = "ABC def"
	if got := codes(widgetV.Validate(w)); !slices.Equal(got, []string{"code=PATTERN"}) {
		t.Fatalf("want a pattern failure for a partial match, got %v", got)
	}
}
