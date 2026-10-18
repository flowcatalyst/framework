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
	checkMissingAuthorize(t, "withauth", "noauth")
}

// The same holds for a use case with a load phase (write-path contract 3a, rule 5): the load phase
// is additional, never a replacement for Authorize.
func TestMissingAuthorizeOnALoadedUseCaseIsACompileError(t *testing.T) {
	t.Parallel()
	checkMissingAuthorize(t, "loadedwithauth", "loadednoauth")
}

func checkMissingAuthorize(t *testing.T, control, broken string) {
	t.Helper()
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
	if errs := load(control); len(errs) != 0 {
		t.Fatalf("the control does not compile: %v", errs)
	}
	errs := load(broken)
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

// The record-level check is part of a loaded use case's type: one that has no AuthorizeLoaded does
// not compile (embed usecase.NoRecordCheck to say there is none), and neither does one whose
// AuthorizeLoaded has drifted from the command and record types (friction G76-3).
func TestMissingOrDriftedAuthorizeLoadedIsACompileError(t *testing.T) {
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
	if errs := load("loadedwithauth"); len(errs) != 0 {
		t.Fatalf("the control does not compile: %v", errs)
	}
	for _, dir := range []string{"loadednocheck", "loadeddrift"} {
		found := false
		for _, e := range load(dir) {
			if strings.Contains(e.Msg, "AuthorizeLoaded") {
				found = true
			}
		}
		if !found {
			t.Fatalf("%s compiled", dir)
		}
	}
}
