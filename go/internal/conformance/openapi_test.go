package conformance_test

import (
	"testing"

	"github.com/flowcatalyst/framework/go/examples/orders-demo/orders"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/sessionapi"
	"github.com/flowcatalyst/framework/go/internal/conformance"
)

// The session operations and their fixed components are conformance/openapi/auth.json, byte for
// byte (OpenAPI contract section 10).
func TestOpenAPIAuthVector(t *testing.T) {
	t.Parallel()
	web := httpfw.Declare(fw.New("auth"))
	web.Info("flowcatalyst-auth", "1")
	sessionapi.Declare(web, true)
	doc, problems := web.OpenAPI()
	if len(problems) > 0 {
		t.Fatalf("the document cannot be written: %v", problems)
	}
	conformance.EqualBytes(t, "openapi/auth.json", conformance.ReadFile(t, "openapi/auth.json"), doc)
}

// The orders-demo's document, with the session operations, is conformance/openapi/orders-demo.json
// byte for byte (the Java demo's committed web/openapi.json).
func TestOpenAPIOrdersDemoVector(t *testing.T) {
	t.Parallel()
	web := httpfw.Declare(fw.New("orders-demo"))
	orders.DeclareAPI(web)
	sessionapi.Declare(web, true)
	doc, problems := web.OpenAPI()
	if len(problems) > 0 {
		t.Fatalf("the document cannot be written: %v", problems)
	}
	conformance.EqualBytes(t, "openapi/orders-demo.json", conformance.ReadFile(t, "openapi/orders-demo.json"), doc)
}
