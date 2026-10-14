// Package mustliteral is the fwvet analyzer for the framework's Must... functions. Must... is for
// literals in source: its argument must be a constant, and the constant must be accepted by the
// matching Parse... function, which this analyzer runs at lint time. A misspelt literal is then
// reported in the editor and in CI instead of panicking when the package initialises.
package mustliteral

import (
	"go/ast"
	"go/constant"
	"go/types"
	"strings"

	"golang.org/x/tools/go/analysis"
	"golang.org/x/tools/go/analysis/passes/inspect"
	"golang.org/x/tools/go/ast/inspector"
)

// Analyzer reports framework Must... calls whose argument is not a constant, or is a constant the
// matching Parse... function refuses.
var Analyzer = &analysis.Analyzer{
	Name:     "mustliteral",
	Doc:      "check that the arguments of the framework's Must... functions are valid literals",
	Requires: []*analysis.Analyzer{inspect.Analyzer},
	Run:      run,
}

// frameworkPrefix is the module path of the framework; Must... functions elsewhere are ignored.
const frameworkPrefix = "github.com/flowcatalyst/framework/go"

func run(pass *analysis.Pass) (any, error) {
	ins, ok := pass.ResultOf[inspect.Analyzer].(*inspector.Inspector)
	if !ok {
		return nil, nil
	}
	ins.Preorder([]ast.Node{(*ast.CallExpr)(nil)}, func(n ast.Node) {
		call, ok := n.(*ast.CallExpr)
		if !ok {
			return
		}
		fn := callee(pass, call)
		if fn == nil || fn.Pkg() == nil || !strings.HasPrefix(fn.Name(), "Must") {
			return
		}
		path := fn.Pkg().Path()
		if path != frameworkPrefix && !strings.HasPrefix(path, frameworkPrefix+"/") {
			return
		}
		if len(call.Args) != 1 {
			return
		}
		if pass.Pkg.Path() == path || pass.Pkg.Path() == path+"_test" {
			return // the declaring package and its own tests (which test Must itself) are exempt
		}
		full := path + "." + fn.Name()
		check, known := checks[full]
		if !known {
			pass.Reportf(call.Pos(), "fwvet does not know how to check %s; add it to cmd/fwvet/mustliteral/checks.go", full)
			return
		}
		tv, ok := pass.TypesInfo.Types[call.Args[0]]
		if !ok || tv.Value == nil || tv.Value.Kind() != constant.String {
			pass.Reportf(call.Args[0].Pos(), "%s takes a string literal; use the Parse function for values known only at run time", fn.Name())
			return
		}
		if err := check(constant.StringVal(tv.Value)); err != nil {
			pass.Reportf(call.Args[0].Pos(), "%s: %v", fn.Name(), err)
		}
	})
	return nil, nil
}

func callee(pass *analysis.Pass, call *ast.CallExpr) *types.Func {
	var id *ast.Ident
	switch f := ast.Unparen(call.Fun).(type) {
	case *ast.Ident:
		id = f
	case *ast.SelectorExpr:
		id = f.Sel
	case *ast.IndexExpr:
		if sel, ok := f.X.(*ast.SelectorExpr); ok {
			id = sel.Sel
		} else if i, ok := f.X.(*ast.Ident); ok {
			id = i
		}
	default:
		return nil
	}
	if id == nil {
		return nil
	}
	fn, _ := pass.TypesInfo.Uses[id].(*types.Func)
	return fn
}
