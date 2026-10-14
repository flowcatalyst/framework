package mustliteral_test

import (
	"testing"

	"golang.org/x/tools/go/analysis/analysistest"

	"github.com/flowcatalyst/framework/go/cmd/fwvet/mustliteral"
)

func TestMustLiteral(t *testing.T) {
	t.Parallel()
	// testdata/src/a is a module that requires the framework by a replace directive, so the
	// analyzer sees the real fw package.
	analysistest.Run(t, analysistest.TestData()+"/src/a", mustliteral.Analyzer, "./...")
}
