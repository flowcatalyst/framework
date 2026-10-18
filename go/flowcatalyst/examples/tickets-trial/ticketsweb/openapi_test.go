package ticketsweb

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"

	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/web"
	"github.com/flowcatalyst/framework/go/fw"
)

// The committed document is current (OpenAPI contract section 9): regenerated from the real
// declarations, with no database and no configuration, and it is the Java service's document, byte for
// byte (the parity gate of G7.6: nine operations, the admin and auth operations, the same schemas).
func TestTheCommittedDocumentIsCurrentAndIsTheJavaServicesDocument(t *testing.T) {
	t.Parallel()
	app := NewApp(fw.WithEnvironment(map[string]string{}), fw.WithConfigDir(t.TempDir()))
	doc, problems := Declare(app).OpenAPI()
	if len(problems) > 0 {
		t.Fatalf("the document cannot be written: %v", problems)
	}
	if !bytes.Equal(doc, web.OpenAPI()) {
		t.Fatal("web/openapi.json is stale: run `go run ./examples/tickets-trial openapi --out examples/tickets-trial/web/openapi.json` from go/flowcatalyst")
	}
	java, err := os.ReadFile(filepath.Join("..", "..", "..", "..", "..", "examples", "tickets-trial", "web", "openapi.json"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(doc, java) {
		t.Fatal("the document differs from examples/tickets-trial/web/openapi.json, the Java service's")
	}
}
