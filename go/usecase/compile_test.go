package usecase_test

import (
	"strings"
	"testing"

	"golang.org/x/tools/go/packages"
)

// A use case without an Authorize method does not compile (design decision 12): the interface
// has all four phases. testdata/withauth is the same code with the method, as the control.
func TestMissingAuthorizeIsACompileError(t *testing.T) {
	t.Parallel()
	load := func(dir string) []packages.Error {
		t.Helper()
		pkgs, err := packages.Load(&packages.Config{Mode: packages.NeedTypes | packages.NeedSyntax | packages.NeedTypesInfo}, "./testdata/"+dir)
		if err != nil {
			t.Fatal(err)
		}
		var errs []packages.Error
		for _, p := range pkgs {
			errs = append(errs, p.Errors...)
		}
		return errs
	}
	if errs := load("withauth"); len(errs) != 0 {
		t.Fatalf("the control does not compile: %v", errs)
	}
	errs := load("noauth")
	found := false
	for _, e := range errs {
		if strings.Contains(e.Msg, "missing method Authorize") {
			found = true
		}
	}
	if !found {
		t.Fatalf("a use case without Authorize compiled (errors: %v)", errs)
	}
}
