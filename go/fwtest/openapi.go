package fwtest

import (
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/httpfw"
)

// OpenAPI builds the application's OpenAPI document from its declarations, as the openapi command
// does (Info and the security schemes included), and returns it. A problem fails the test, listing
// every one, so a type the document cannot describe (a plain int, an embedded struct, a pointer
// without omitzero) is caught by the application's own tests:
//
//	func TestTheOpenAPIDocumentBuilds(t *testing.T) {
//		app := fw.New("orders")
//		web := httpfw.Declare(app) // and whatever else the application declares
//		web.Info("orders", "1")
//		web.Operations(PlaceOrder, GetOrder)
//		fwtest.OpenAPI(t, web)
//	}
//
// [httpfw.NewServer] fails at start-up on the same problems, except the two about the whole
// document (no Info, an operation that requires a context with no security scheme), which only the
// command and this helper check.
func OpenAPI(t testing.TB, d *httpfw.Declared) []byte {
	t.Helper()
	doc, problems := d.OpenAPI()
	if len(problems) > 0 {
		t.Fatalf("the OpenAPI document cannot be written: %d problem(s)\n  - %s", len(problems), strings.Join(problems, "\n  - "))
		return nil
	}
	return doc
}
