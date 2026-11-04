package conformance_test

import (
	"os/exec"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/internal/conformance"
)

// The measure packages are value types: they import the standard library, each other and, for their
// OpenAPI schemas, httpfw/openapi (with the two packages that one needs) and nothing else of the
// framework: not usecase, with its database, metrics and background dependencies (measure contract
// section 6).
func TestMeasurePackagesHaveLeafDependencies(t *testing.T) {
	t.Parallel()
	const prefix = "github.com/flowcatalyst/framework/go"
	allowed := map[string]bool{
		prefix + "/decimal": true, prefix + "/money": true, prefix + "/units": true,
		prefix + "/httpfw/openapi": true, prefix + "/httpfw/validate": true, prefix + "/internal/javafmt": true,
	}
	for _, pkg := range []string{"decimal", "money", "units"} {
		cmd := exec.CommandContext(t.Context(), "go", "list", "-deps", prefix+"/"+pkg)
		cmd.Dir = conformance.ModuleDir(t)
		out, err := cmd.Output()
		if err != nil {
			t.Fatalf("go list -deps %s: %v", pkg, err)
		}
		for dep := range strings.FieldsSeq(string(out)) {
			if strings.HasPrefix(dep, prefix) && !allowed[dep] {
				t.Errorf("%s imports %s, which the measure packages may not depend on", pkg, dep)
			}
		}
	}
}
