package openapi_test

import (
	"encoding/json/jsontext"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/httpfw/openapi"
	"github.com/flowcatalyst/framework/go/httpfw/validate"
)

// ---- a small API to emit ---------------------------------------------------------------------

type Status string

func (Status) EnumValues() []string { return []string{"OPEN", "SHUT"} }

type Item struct {
	ID    string    `json:"id"`
	Name  string    `json:"name"`
	State Status    `json:"state"`
	Note  *string   `json:"note,omitzero"`
	At    time.Time `json:"at"`
}

type Page[T any] struct {
	Items []T     `json:"items"`
	Next  *string `json:"next,omitzero"`
}

type ItemBody struct {
	Name  string   `json:"name"`
	Count int32    `json:"count"`
	Tags  []string `json:"tags"`
	Mode  string   `json:"mode"`
}

var itemRules = validate.Of(
	validate.Field("name", func(b ItemBody) string { return b.Name }, validate.NotBlank(), validate.MaxLength(40)),
	validate.Field("count", func(b ItemBody) int32 { return b.Count }, validate.Range[int32](1, 9)),
	validate.Field("tags", func(b ItemBody) []string { return b.Tags }, validate.Size[string](0, 3), validate.Each(validate.MaxLength(8))),
	validate.Field("mode", func(b ItemBody) string { return b.Mode }, validate.OneOf("fast", "slow")),
)

func op(id, method, pattern string) *openapi.Operation {
	return &openapi.Operation{Method: method, Pattern: pattern, ID: id, Status: 200, Response: reflect.TypeFor[Item]()}
}

func input(ops ...*openapi.Operation) openapi.Input {
	return openapi.Input{Info: &openapi.Info{Title: "t", Version: "1"}, Operations: ops}
}

func mustEmit(t *testing.T, in openapi.Input) string {
	t.Helper()
	doc, problems := openapi.Emit(in)
	if len(problems) > 0 {
		t.Fatalf("problems: %v", problems)
	}
	return string(doc)
}

func problemsOf(t *testing.T, in openapi.Input) string {
	t.Helper()
	doc, problems := openapi.Emit(in)
	if len(problems) == 0 {
		t.Fatalf("want problems, got a document:\n%s", doc)
	}
	return strings.Join(problems, "\n")
}

func wantProblem(t *testing.T, in openapi.Input, want string) {
	t.Helper()
	if got := problemsOf(t, in); !strings.Contains(got, want) {
		t.Errorf("want a problem containing %q, got:\n%s", want, got)
	}
}

// ---- format ------------------------------------------------------------------------------------

func TestTheDocumentIsWrittenAsJSStringifyDoes(t *testing.T) {
	t.Parallel()
	got := mustEmit(t, input(op("getItem", "GET", "/items")))
	if !strings.HasSuffix(got, "}\n") || strings.Contains(got, "\r") || strings.Contains(got, "\t") {
		t.Errorf("a final newline, \\n line ends, spaces for indentation")
	}
	want := "{\n  \"openapi\": \"3.1.0\",\n  \"info\": {\n    \"title\": \"t\",\n    \"version\": \"1\"\n  },\n  \"paths\": {"
	if !strings.HasPrefix(got, want) {
		t.Errorf("document starts\n%s\nwant a prefix\n%s", got[:len(want)], want)
	}
	if !strings.Contains(got, "\"required\": [\n          \"id\",") {
		t.Errorf("arrays hold one element per line, indented by depth")
	}
}

func TestEscapingIsOnlyTheQuoteBackslashAndControlCharacters(t *testing.T) {
	t.Parallel()
	o := op("getItem", "GET", "/items")
	o.Summary = "<b>&\"quoted\" / \\ é \u0001   日本"
	got := mustEmit(t, input(o))
	want := `"summary": "<b>&\"quoted\" / \\ é \u0001 ` + " " + ` 日本"`
	if !strings.Contains(got, want) {
		t.Errorf("want %s in\n%s", want, got)
	}
}

func TestTheSameDeclarationsGiveTheSameBytesInAnyOrder(t *testing.T) {
	t.Parallel()
	a, b, c := op("a", "GET", "/z"), op("b", "POST", "/a/{x}"), op("c", "GET", "/a/{x}")
	b.Path = []openapi.Param{{Name: "x", Type: reflect.TypeFor[string]()}}
	c.Path = b.Path
	b.Tags, c.Tags = []string{"beta"}, []string{"alpha"}
	first := mustEmit(t, input(a, b, c))
	for _, order := range [][]*openapi.Operation{{c, b, a}, {b, a, c}, {a, c, b}} {
		if got := mustEmit(t, input(order...)); got != first {
			t.Fatalf("order changed the bytes:\n%s\n%s", first, got)
		}
	}
	// paths sorted, methods in the order get, put, post, delete, patch, tags sorted
	if i, j := strings.Index(first, `"/a/{x}"`), strings.Index(first, `"/z"`); i < 0 || j < i {
		t.Errorf("paths are sorted")
	}
	if i, j := strings.Index(first, `"get": {`), strings.Index(first, `"post": {`); i < 0 || j < i {
		t.Errorf("get comes before post")
	}
	if i, j := strings.Index(first, `{
      "name": "alpha"`), strings.Index(first, `"name": "beta"`); i < 0 || j < i {
		t.Errorf("tags are sorted by name")
	}
}

// ---- schemas -----------------------------------------------------------------------------------

func TestGenericTypesAreNamedByTheirArguments(t *testing.T) {
	t.Parallel()
	o := op("list", "GET", "/items")
	o.Response = reflect.TypeFor[Page[Item]]()
	got := mustEmit(t, input(o))
	if !strings.Contains(got, `"$ref": "#/components/schemas/PageItem"`) || !strings.Contains(got, `"PageItem": {`) {
		t.Errorf("Page[Item] is PageItem:\n%s", got)
	}
}

func TestOptionalIsNotRequiredAndNeverNullable(t *testing.T) {
	t.Parallel()
	got := mustEmit(t, input(op("getItem", "GET", "/items")))
	if strings.Contains(got, "null") {
		t.Errorf("no nullable types are emitted")
	}
	if !strings.Contains(got, `"required": [
          "id",
          "name",
          "state",
          "at"
        ]`) {
		t.Errorf("the optional note is not required:\n%s", got)
	}
}

type recursive struct {
	Name     string      `json:"name"`
	Children []recursive `json:"children"`
}

func TestARecursiveTypeIsOneComponent(t *testing.T) {
	t.Parallel()
	o := op("tree", "GET", "/tree")
	o.Response = reflect.TypeFor[recursive]()
	got := mustEmit(t, input(o))
	if strings.Count(got, `"recursive": {`) != 1 || !strings.Contains(got, `"$ref": "#/components/schemas/recursive"`) {
		t.Errorf("got\n%s", got)
	}
}

func TestRefusals(t *testing.T) {
	t.Parallel()
	type withInt struct {
		N int `json:"n"`
	}
	type withUint struct {
		N uint32 `json:"n"`
	}
	type withFloat32 struct {
		N float32 `json:"n"`
	}
	type withArray struct {
		A [3]string `json:"a"`
	}
	type withIntKey struct {
		M map[int]string `json:"m"`
	}
	type withPtrElem struct {
		L []*string `json:"l"`
	}
	type withAny struct {
		V any `json:"v"`
	}
	type embedded struct {
		Item
	}
	type withOption struct {
		A string `json:"a,string"`
	}
	type withBarePointer struct {
		A *string `json:"a"`
	}
	type withMarshaler struct {
		T marshaler `json:"t"`
	}
	for _, c := range []struct {
		name string
		typ  reflect.Type
		want string
	}{
		{"int", reflect.TypeFor[withInt](), "n: int is not in the type table"},
		{"uint32", reflect.TypeFor[withUint](), "uint32 is not in the type table"},
		{"float32", reflect.TypeFor[withFloat32](), "float32 is not in the type table"},
		{"array", reflect.TypeFor[withArray](), "the array [3]string is refused"},
		{"map key", reflect.TypeFor[withIntKey](), "a map's key must be a string"},
		{"pointer element", reflect.TypeFor[withPtrElem](), "a pointer element is refused"},
		{"interface", reflect.TypeFor[withAny](), "the interface interface {} needs a registered schema"},
		{"embedded", reflect.TypeFor[embedded](), "an embedded field is refused"},
		{"json option", reflect.TypeFor[withOption](), "the json option 'string' is not understood"},
		{"bare pointer", reflect.TypeFor[withBarePointer](), "a pointer field is optional in the document, so it needs omitzero"},
		{"marshaler", reflect.TypeFor[withMarshaler](), "writes its own JSON"},
		{"bare string response", reflect.TypeFor[string](), "a response is a struct, a union or NoBody; never a map, a slice or a primitive"},
		{"slice response", reflect.TypeFor[[]Item](), "never a map, a slice or a primitive"},
	} {
		o := op("o", "GET", "/x")
		o.Response = c.typ
		if got := problemsOf(t, input(o)); !strings.Contains(got, c.want) {
			t.Errorf("%s: want %q in\n%s", c.name, c.want, got)
		}
	}
}

type marshaler struct{}

func (marshaler) MarshalJSON() ([]byte, error) { return []byte(`"x"`), nil }

// ---- names -------------------------------------------------------------------------------------

func TestTwoTypesWithOneNameAreAProblemUnlessNamed(t *testing.T) {
	t.Parallel()
	type Item struct { // a different type with Item's name
		Other string `json:"other"`
	}
	one, two := op("one", "GET", "/one"), op("two", "GET", "/two")
	one.Response, two.Response = reflect.TypeFor[Item](), reflect.TypeFor[outerItem]()
	in := input(one, two)
	wantProblem(t, in, "two types produce the component name Item")
	in.Names = map[reflect.Type]string{reflect.TypeFor[Item](): "OtherItem"}
	if got := mustEmit(t, in); !strings.Contains(got, `"OtherItem": {`) {
		t.Errorf("a registered name renames the component:\n%s", got)
	}
}

// outerItem is Item: the package-level type the local one collides with.
type outerItem = Item

func TestTheErrorComponentNameIsReserved(t *testing.T) {
	t.Parallel()
	type Error struct {
		Code string `json:"code"`
	}
	o := op("o", "GET", "/x")
	o.Response = reflect.TypeFor[Error]()
	wantProblem(t, input(o), "two types produce the component name Error")
}

// ---- constraints -------------------------------------------------------------------------------

func bodyOp(v validate.Described) *openapi.Operation {
	o := op("create", "POST", "/items")
	o.Body = v
	return o
}

func TestConstraintsFromTheValidatorBecomeSchemaKeywords(t *testing.T) {
	t.Parallel()
	got := mustEmit(t, input(bodyOp(itemRules)))
	for _, want := range []string{
		`"name": {
            "type": "string",
            "pattern": "\\S",
            "minLength": 1,
            "maxLength": 40
          }`,
		`"count": {
            "type": "integer",
            "format": "int32",
            "minimum": 1,
            "maximum": 9
          }`,
		`"tags": {
            "type": "array",
            "items": {
              "type": "string",
              "maxLength": 8
            },
            "minItems": 0,
            "maxItems": 3
          }`,
		`"mode": {
            "type": "string",
            "enum": [
              "fast",
              "slow"
            ]
          }`,
	} {
		if !strings.Contains(got, want) {
			t.Errorf("missing\n%s\nin\n%s", want, got)
		}
	}
}

func TestOverlappingRulesTakeTheTighterBoundAndAPatternReplacesNotBlank(t *testing.T) {
	t.Parallel()
	v := validate.Of(
		validate.Field("name", func(b ItemBody) string { return b.Name },
			validate.MinLength(2), validate.NotBlank(), validate.MinLength(5), validate.MaxLength(50), validate.MaxLength(20), validate.Pattern("[a-z]+")),
		validate.Field("count", func(b ItemBody) int32 { return b.Count }, validate.Range[int32](0, 100), validate.Range[int32](5, 50)))
	got := mustEmit(t, input(bodyOp(v)))
	for _, want := range []string{`"pattern": "^(?:[a-z]+)$"`, `"minLength": 5`, `"maxLength": 20`, `"minimum": 5`, `"maximum": 50`} {
		if !strings.Contains(got, want) {
			t.Errorf("missing %s in\n%s", want, got)
		}
	}
	if strings.Contains(got, `\\S`) {
		t.Errorf("an explicit pattern replaces notBlank's")
	}
}

func TestConstraintProblems(t *testing.T) {
	t.Parallel()
	one := func(rules ...validate.Rule[string]) validate.Described {
		return validate.Of(validate.Field("name", func(b ItemBody) string { return b.Name }, rules...))
	}
	for _, c := range []struct {
		name string
		v    validate.Described
		want string
	}{
		{"inline flags", one(validate.Pattern("(?i)abc")), "inline flags"},
		{"unicode class", one(validate.Pattern(`\pL+`)), "Unicode class"},
		{"begin text", one(validate.Pattern(`\Aabc`)), `\A`},
		{"posix class", one(validate.Pattern(`[[:alpha:]]+`)), "POSIX class"},
		{"named group", one(validate.Pattern(`(?P<x>a)`)), "named group"},
		{"two patterns", one(validate.Pattern("a+"), validate.Pattern("b+")), "two patterns apply to one value"},
		{"oneOf outside the enum", validate.Of(validate.Field("state", func(b struct{ State Status }) Status { return b.State }, validate.OneOf[Status]("OPEN", "GONE"))), ""},
		{"a field the record lacks", validate.Of(validate.Field("nope", func(b ItemBody) string { return b.Name }, validate.NotBlank())), "checks 'nope', which is not a field"},
		{"maxLength on a number", validate.Of(validate.Field("count", func(b ItemBody) string { return "" }, validate.MaxLength(3))), "maxLength applies to a string, not a integer"},
		{"range on a string", validate.Of(validate.Field("name", func(b ItemBody) int32 { return 0 }, validate.Range[int32](1, 2))), "range applies to a number, not a string"},
		{"size on a string", validate.Of(validate.Field("name", func(b ItemBody) []string { return nil }, validate.Size[string](1, 2))), "size applies to an array, not a string"},
		{"oneOf on an array", validate.Of(validate.Field("tags", func(b ItemBody) []string { return nil }, validate.Each(validate.OneOf("a")))), ""},
	} {
		if c.want == "" {
			continue // exercised for not panicking only
		}
		o := bodyOp(c.v)
		if got := problemsOf(t, input(o)); !strings.Contains(got, c.want) {
			t.Errorf("%s: want %q in\n%s", c.name, c.want, got)
		}
	}
}

func TestOneOfOutsideAnEnumIsAProblemAndInsideNarrowsIt(t *testing.T) {
	t.Parallel()
	type body struct {
		State Status `json:"state"`
	}
	bad := validate.Of(validate.Field("state", func(b body) Status { return b.State }, validate.OneOf[Status]("OPEN", "GONE")))
	o := bodyOp(bad)
	wantProblem(t, input(o), "oneOf names GONE, which is not a constant of the enum")
	good := validate.Of(validate.Field("state", func(b body) Status { return b.State }, validate.OneOf[Status]("OPEN")))
	got := mustEmit(t, input(bodyOp(good)))
	if !strings.Contains(got, `"enum": [
              "OPEN"
            ]`) {
		t.Errorf("the enum narrows:\n%s", got)
	}
}

func TestTwoValidatorsGivingOneTypeDifferentConstraintsIsAProblem(t *testing.T) {
	t.Parallel()
	other := validate.Of(validate.Field("name", func(b ItemBody) string { return b.Name }, validate.MaxLength(3)))
	a, b := bodyOp(itemRules), bodyOp(other)
	b.ID, b.Pattern = "create2", "/items2"
	wantProblem(t, input(a, b), "two validators give ItemBody different constraints")
	// the same validator twice, or constraints that differ only in what documents nothing, are fine
	c := bodyOp(itemRules)
	c.ID, c.Pattern = "create3", "/items3"
	mustEmit(t, input(a, c))
}

// ---- operations --------------------------------------------------------------------------------

func TestOperationRules(t *testing.T) {
	t.Parallel()
	dup := op("same", "GET", "/a")
	dup2 := op("same", "GET", "/b")
	wantProblem(t, input(dup, dup2), "same: two operations have this id")
	s1, s2 := op("one", "GET", "/p/{a}"), op("two", "GET", "/p/{b}")
	wantProblem(t, input(s1, s2), "collides with one")
	noInfo := input(op("a", "GET", "/a"))
	noInfo.Info = nil
	wantProblem(t, noInfo, "the document has no info")
	ctx := op("a", "GET", "/a")
	ctx.RequiresContext = true
	wantProblem(t, input(ctx), "requires a context but no security scheme was contributed")
	bad := op("a", "GET", "/a")
	bad.Problems = []string{"a descriptor mistake"}
	wantProblem(t, input(bad), "a: a descriptor mistake")
	in := input(op("a", "GET", "/a"))
	in.Security = []openapi.Scheme{{Name: "x y", Scheme: openapi.HTTPBasic()}}
	wantProblem(t, in, "security scheme 'x y' must match")
	in.Security = []openapi.Scheme{{Name: "basic", Scheme: openapi.HTTPBasic(), Module: "m"}, {Name: "basic", Scheme: openapi.HTTPBasic(), Module: "n"}}
	wantProblem(t, in, "security scheme 'basic' is contributed twice (module 'n')")
}

func TestImpliedStatusesAndSecurity(t *testing.T) {
	t.Parallel()
	o := op("a", "POST", "/a/{id}")
	o.Path = []openapi.Param{{Name: "id", Type: reflect.TypeFor[string]()}}
	o.Body = itemRules
	o.RequiresContext = true
	o.Permission = "x:y:z"
	o.Errors = []openapi.DeclaredError{{Status: 403, Code: "NOPE", Message: "not for you"}, {Status: 409, Code: "B", Message: "b"}, {Status: 409, Code: "A", Message: "a"}}
	in := input(o)
	in.Security = []openapi.Scheme{{Name: "basic", Scheme: openapi.HTTPBasic()}, {Name: "bearer", Scheme: openapi.Bearer("JWT")}, {Name: "session", Scheme: openapi.Cookie("c")}}
	got := mustEmit(t, in)
	order := []string{`"200":`, `"400":`, `"401":`, `"403":`, `"409":`, `"413":`, `"415":`, `"default":`}
	last := -1
	for _, s := range order {
		i := strings.Index(got, s)
		if i < 0 || i < last {
			t.Fatalf("%s missing or out of order (statuses ascending, default last):\n%s", s, got)
		}
		last = i
	}
	if !strings.Contains(got, `"description": "NOPE: not for you"`) {
		t.Errorf("a declared 403 replaces the implied reference")
	}
	if !strings.Contains(got, `"description": "A: a\nB: b"`) {
		t.Errorf("codes of one status are sorted, one per line")
	}
	sec := `"security": [
          {
            "session": []
          },
          {
            "bearer": []
          },
          {
            "basic": []
          }
        ]`
	if !strings.Contains(got, sec) {
		t.Errorf("security lists session, bearer, then the rest by name:\n%s", got)
	}
	if !strings.Contains(got, `"x-fw-permission": "x:y:z"`) {
		t.Errorf("permission is documented")
	}
	pub := mustEmit(t, input(op("p", "GET", "/p")))
	if strings.Contains(pub, `"security"`) || strings.Contains(pub, `"401"`) || strings.Contains(pub, `"400"`) {
		t.Errorf("a public operation without parameters lists only its success and default:\n%s", pub)
	}
}

func query(name string, typ reflect.Type, mode openapi.QueryMode, def any) openapi.Query {
	q := openapi.Query{Mode: mode, Default: def}
	q.Name, q.Type = name, typ
	return q
}

func TestQueryParameterForms(t *testing.T) {
	t.Parallel()
	o := op("q", "GET", "/q")
	o.Query = []openapi.Query{
		query("a", reflect.TypeFor[string](), openapi.QueryRequired, nil),
		query("b", reflect.TypeFor[int32](), openapi.QueryDefault, int32(7)),
		query("c", reflect.TypeFor[Status](), openapi.QueryDefault, Status("SHUT")),
		query("d", reflect.TypeFor[bool](), openapi.QueryList, nil),
		query("e", reflect.TypeFor[int64](), openapi.QueryOptional, nil),
	}
	got := mustEmit(t, input(o))
	for _, want := range []string{`"required": true`, `"default": 7`, `"default": "SHUT"`, `"style": "form"`, `"explode": true`} {
		if !strings.Contains(got, want) {
			t.Errorf("missing %s in\n%s", want, got)
		}
	}
}

// ---- registered schemas ------------------------------------------------------------------------

type ID string

func (ID) JSONSchema() openapi.Schema { return openapi.String().Pattern(`^id_[a-z]+$`) }

type Any struct {
	V jsontext.Value `json:"v"`
}

func TestRegisteredAndProvidedSchemas(t *testing.T) {
	t.Parallel()
	type holder struct {
		ID ID `json:"id"`
	}
	o := op("o", "GET", "/o")
	o.Response = reflect.TypeFor[holder]()
	got := mustEmit(t, input(o))
	if !strings.Contains(got, `"pattern": "^id_[a-z]+$"`) {
		t.Errorf("SchemaProvider is written inline:\n%s", got)
	}
	o.Response = reflect.TypeFor[Any]()
	if got := mustEmit(t, input(o)); !strings.Contains(got, `"v": {}`) {
		t.Errorf("raw JSON is the empty schema:\n%s", got)
	}
}

func TestAPropertyOverrideNamingNoFieldIsAProblem(t *testing.T) {
	t.Parallel()
	in := input(op("a", "GET", "/a"))
	in.Properties = map[reflect.Type]map[string]openapi.Schema{reflect.TypeFor[Item](): {"nope": openapi.Bool().ConstBool(true)}}
	wantProblem(t, in, "a property schema is registered for 'nope', which is not a field of Item")
}

func TestUnionNeedsTwoVariantsAndNamedStructs(t *testing.T) {
	t.Parallel()
	defer func() {
		if recover() == nil {
			t.Error("a union with one variant must panic")
		}
	}()
	_ = openapi.Union("kind", openapi.Variant{Name: "a", Type: reflect.TypeFor[Item]()})
}
