package fwtest

import (
	"fmt"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// recordingTB records a failure instead of ending the test, so OpenAPI's failing path can be seen.
type recordingTB struct {
	testing.TB
	failed string
}

func (r *recordingTB) Helper() {}

func (r *recordingTB) Fatalf(format string, args ...any) { r.failed = fmt.Sprintf(format, args...) }

type goodView struct {
	Name string `json:"name"`
}

type plainIntView struct {
	Count int `json:"count"`
}

func TestOpenAPIReturnsTheDocumentOrFailsListingEveryProblem(t *testing.T) {
	t.Parallel()
	good := httpfw.Declare(fw.New("good"))
	good.Info("good", "1")
	good.Operations(httpfw.Get[goodView]("/good").ID("getGood").Build())
	rec := &recordingTB{TB: t}
	doc := OpenAPI(rec, good)
	if rec.failed != "" || !strings.Contains(string(doc), `"operationId": "getGood"`) {
		t.Fatalf("a good document: failed %q, document %s", rec.failed, doc)
	}

	bad := httpfw.Declare(fw.New("bad"))
	bad.Operations(httpfw.Get[plainIntView]("/bad").ID("getBad").Build())
	rec = &recordingTB{TB: t}
	if doc := OpenAPI(rec, bad); doc != nil {
		t.Errorf("a document came back: %s", doc)
	}
	for _, want := range []string{"2 problem(s)", "int is not in the type table", "the document has no info"} {
		if !strings.Contains(rec.failed, want) {
			t.Errorf("the failure does not say %q: %s", want, rec.failed)
		}
	}
}
