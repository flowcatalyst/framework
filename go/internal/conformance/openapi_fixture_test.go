package conformance_test

import (
	"encoding/json/jsontext"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/openapi"
	"github.com/flowcatalyst/framework/go/httpfw/validate"
	"github.com/flowcatalyst/framework/go/internal/conformance"
)

// The fixture API of conformance/openapi/README.md, written in Go with the package-level types the
// README names. Its document is conformance/openapi/fixture.json byte for byte: every row of the
// type table, a union, a generic page, each validator rule, each implied status, three security
// schemes.

type Colour string

func (Colour) EnumValues() []string { return []string{"RED", "GREEN", "BLUE"} }

// ColourSet holds each colour once (a Java Set).
type ColourSet []Colour

func (ColourSet) UniqueItems() {}

// Money is an application value type with a schema of its own.
type Money string

func (Money) JSONSchema() openapi.Schema {
	return openapi.String().Pattern(`^-?[0-9]+\.[0-9]{2}$`).Description("an amount with two decimals")
}

// Decimal, Date, ISODuration and UUID are the value types of the type table that Go has no
// standard JSON form for.
type (
	Decimal     string
	Date        string
	ISODuration string
	UUID        string
)

func (Decimal) JSONSchema() openapi.Schema     { return openapi.String().Format("decimal") }
func (Date) JSONSchema() openapi.Schema        { return openapi.String().Format("date") }
func (ISODuration) JSONSchema() openapi.Schema { return openapi.String().Format("duration") }
func (UUID) JSONSchema() openapi.Schema        { return openapi.String().Format("uuid") }

type Scalars struct {
	Text       string         `json:"text"`
	Flag       bool           `json:"flag"`
	MaybeFlag  *bool          `json:"maybeFlag,omitzero"`
	Count      int32          `json:"count"`
	MaybeCount *int32         `json:"maybeCount,omitzero"`
	Big        int64          `json:"big"`
	MaybeBig   *int64         `json:"maybeBig,omitzero"`
	Ratio      float64        `json:"ratio"`
	MaybeRatio *float64       `json:"maybeRatio,omitzero"`
	Amount     Decimal        `json:"amount"`
	At         time.Time      `json:"at"`
	Day        Date           `json:"day"`
	Timeout    ISODuration    `json:"timeout"`
	UUID       UUID           `json:"uuid"`
	Blob       []byte         `json:"blob"`
	Anything   jsontext.Value `json:"anything"`
	Colour     Colour         `json:"colour"`
	Price      Money          `json:"price"`
}

type Ids struct {
	Client      fw.ClientID        `json:"client"`
	Principal   fw.PrincipalID     `json:"principal"`
	Application fw.ApplicationCode `json:"application"`
	Correlation fw.CorrelationID   `json:"correlation"`
}

type Line struct {
	SKU      string `json:"sku"`
	Quantity int32  `json:"quantity"`
}

type Collections struct {
	Tags    []string            `json:"tags"`
	Counts  []int32             `json:"counts"`
	Colours ColourSet           `json:"colours"`
	Totals  map[string]int64    `json:"totals"`
	Lines   []Line              `json:"lines"`
	Groups  map[string][]string `json:"groups"`
}

type Shape interface{ isShape() }

type ShapeCircle struct {
	Radius float64 `json:"radius"`
}

type ShapeSquare struct {
	Side  float64 `json:"side"`
	Label *string `json:"label,omitzero"`
}

func (ShapeCircle) isShape() {}
func (ShapeSquare) isShape() {}

type CursorPage[T any] struct {
	Items      []T     `json:"items"`
	HasMore    bool    `json:"hasMore"`
	NextCursor *string `json:"nextCursor,omitzero"`
}

type Widget struct {
	ID          string      `json:"id"`
	Name        string      `json:"name"`
	Shape       Shape       `json:"shape"`
	Scalars     Scalars     `json:"scalars"`
	Ids         Ids         `json:"ids"`
	Collections Collections `json:"collections"`
	Note        *string     `json:"note,omitzero"`
}

type WidgetBody struct {
	Name     string   `json:"name"`
	Code     string   `json:"code"`
	Note     *string  `json:"note,omitzero"`
	Quantity int32    `json:"quantity"`
	Ratio    float64  `json:"ratio"`
	Tags     []string `json:"tags"`
	Colour   string   `json:"colour"`
	Shape    Shape    `json:"shape"`
	Line     Line     `json:"line"`
	Lines    []Line   `json:"lines"`
	Free     string   `json:"free"`
}

type NoteBody struct {
	Note *string `json:"note,omitzero"`
}

type Pong struct {
	At time.Time `json:"at"`
}

var (
	lineRules = validate.Of(
		validate.Field("sku", func(l Line) string { return l.SKU }, validate.NotBlank()),
		validate.Field("quantity", func(l Line) int32 { return l.Quantity }, validate.Range[int32](1, 99)))
	widgetRules = validate.Of(
		validate.Field("name", func(b WidgetBody) string { return b.Name }, validate.NotBlank(), validate.MaxLength(100)),
		validate.Field("code", func(b WidgetBody) string { return b.Code }, validate.MinLength(3), validate.MaxLength(12), validate.Pattern("[A-Z][A-Z0-9-]*")),
		validate.Field("note", func(b WidgetBody) *string { return b.Note }, validate.Ptr(validate.MaxLength(500))),
		validate.Field("quantity", func(b WidgetBody) int32 { return b.Quantity }, validate.Range[int32](1, 1000)),
		validate.Field("ratio", func(b WidgetBody) float64 { return b.Ratio }, validate.Range(0.5, 2.5)),
		validate.Field("tags", func(b WidgetBody) []string { return b.Tags }, validate.Size[string](1, 5), validate.Each(validate.MaxLength(20))),
		validate.Field("colour", func(b WidgetBody) string { return b.Colour }, validate.OneOf("red", "green")),
		validate.Field("line", func(b WidgetBody) Line { return b.Line }, validate.Nested(lineRules)),
		validate.Field("lines", func(b WidgetBody) []Line { return b.Lines }, validate.Each(validate.Nested(lineRules))),
		validate.Field("free", func(b WidgetBody) string { return b.Free }, validate.Custom("NEVER", "never documented", func(string) bool { return true })))
	noteRules = validate.Of(
		validate.Field("note", func(b NoteBody) *string { return b.Note }, validate.Ptr(validate.MaxLength(500))))

	manageWidgets = fw.MustPermission("fixture:widget:manage")

	widgetID = httpfw.Path[string]("id")
	listOp   = httpfw.Get[CursorPage[Widget]]("/api/widgets").ID("listWidgets").Summary("List widgets").Tag("widgets").
			Query(httpfw.Query[Colour]("colour").Optional(), httpfw.Query[int32]("limit").Default(20), httpfw.Query[string]("cursor").Optional(),
			httpfw.Query[Colour]("status").List(), httpfw.Query[bool]("includeArchived").Default(false)).Build()
	getOp = httpfw.Get[Widget]("/api/widgets/{id}").ID("getWidget").Summary("Read a widget").Tag("widgets").Path(widgetID).RequiresContext().
		Error(404, "WIDGET_NOT_FOUND", "no widget has this id").Build()
	createOp = httpfw.Post[WidgetBody, Widget]("/api/widgets", widgetRules).ID("createWidget").Summary("Create a widget").Tag("widgets").
			Status(201).Permission(manageWidgets).
			Error(409, "NAME_TAKEN", "a widget has this name").Error(409, "CODE_TAKEN", "a widget has this code").Build()
	replaceOp = httpfw.Put[WidgetBody, Widget]("/api/widgets/{id}", widgetRules).ID("replaceWidget").Tag("widgets").Path(widgetID).Permission(manageWidgets).Build()
	noteOp    = httpfw.Patch[NoteBody, Widget]("/api/widgets/{id}/note", noteRules).ID("noteWidget").Tag("widgets").Path(widgetID).
			Status(202).Permission(manageWidgets).Build()
	deleteOp = httpfw.Delete[httpfw.NoBody]("/api/widgets/{id}").ID("deleteWidget").Tag("widgets").Path(widgetID).Permission(manageWidgets).Build()
	shapeOp  = httpfw.Get[Shape]("/api/widgets/{id}/shapes/{index}").ID("getWidgetShape").Tag("shapes").
			Path(widgetID, httpfw.Path[int32]("index")).Query(httpfw.Query[int64]("after")).RequiresContext().Build()
	pingOp = httpfw.PostNoBody[Pong]("/api/ping").ID("ping").Summary("Answer at once").
		Description("A public operation without a body or parameters: only the default response.").Build()
	colourOp = httpfw.Get[Widget]("/api/colours/{colour}/{version}").ID("getWidgetByColour").
			Path(httpfw.Path[int64]("version"), httpfw.Path[Colour]("colour")).RequiresContext().Build()
)

func fixtureDeclared() *httpfw.Declared {
	web := httpfw.Declare(fw.New("fixture"))
	web.Info("fixture", "1")
	web.Operations(listOp, getOp, createOp, replaceOp, noteOp, deleteOp, shapeOp, pingOp, colourOp)
	web.Schema(openapi.TypeOf[Shape](), openapi.Union("kind",
		openapi.Variant{Name: "square", Type: openapi.TypeOf[ShapeSquare]()},
		openapi.Variant{Name: "circle", Type: openapi.TypeOf[ShapeCircle]()}))
	web.Security("fixture", "session", openapi.Cookie("__Host-fw_session"))
	web.Security("fixture", "bearer", openapi.Bearer("JWT"))
	web.Security("fixture", "basic", openapi.HTTPBasic())
	return web
}

func TestOpenAPIFixtureVector(t *testing.T) {
	t.Parallel()
	doc, problems := fixtureDeclared().OpenAPI()
	if len(problems) > 0 {
		t.Fatalf("the document cannot be written: %v", problems)
	}
	conformance.EqualBytes(t, "openapi/fixture.json", conformance.ReadFile(t, "openapi/fixture.json"), doc)
}
