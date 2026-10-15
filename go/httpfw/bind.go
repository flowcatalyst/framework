package httpfw

import (
	"fmt"
	"slices"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
)

// Bind registers a typed operation's route (OpenAPI contract section 3). Problems with the
// operation (a bad descriptor, bound twice, bound but not declared, declared but not bound,
// middleware that requires a context for an operation that does not) are start-up problems,
// reported when the tables are compiled.
func (rt *Routes) Bind(b Binding) {
	rt.mu.Lock()
	defer rt.mu.Unlock()
	rt.mustBeOpen()
	mismatch := b.options.requiresContext && !b.desc.requiresContext
	rt.binds = append(rt.binds, binding{registrant: rt.module, desc: b.desc, mismatch: mismatch})
	if len(b.desc.problems) > 0 || mismatch {
		return // reported when the table is compiled
	}
	p, err := parsePath(b.desc.method, b.desc.pattern)
	if err != nil {
		return
	}
	ro := routeOptions{requiresContext: b.desc.requiresContext, middleware: b.options.middleware}
	rt.regs = append(rt.regs, registration{pattern: p, registrant: rt.module, handler: b.handler, opts: ro})
}

// checkOperations applies the operation rules of OpenAPI contract section 3 to what was declared
// and bound.
func checkOperations(o options, binds []binding) []fw.Problem {
	var problems []fw.Problem
	add := func(module, key, msg string) {
		problems = append(problems, config.Problem{Module: module, Key: key, Message: msg})
	}
	ids := map[string]*opDesc{}
	for _, d := range declaredDescs(o) {
		if d.id == "" {
			continue
		}
		if first, dup := ids[d.id]; dup && first != d {
			add("", "operation "+d.id, fmt.Sprintf("two operations have the id '%s': %s %s and %s %s", d.id, first.method, first.pattern, d.method, d.pattern))
			continue
		}
		ids[d.id] = d
	}
	bound := map[*opDesc]bool{}
	for _, b := range binds {
		d := b.desc
		key := "operation " + d.label()
		if bound[d] {
			add(b.registrant, key, "operation "+d.label()+" is bound twice")
			continue
		}
		bound[d] = true
		if o.declaredSet && !containsDesc(declaredDescs(o), d) {
			add(b.registrant, key, "operation "+d.label()+" is bound but was not declared (Declared.Operations at declaration)")
		}
		for _, p := range d.problems {
			add(b.registrant, key, "operation "+d.label()+": "+p)
		}
		if b.mismatch {
			add(b.registrant, key, "operation "+d.label()+" is bound with options that require a context, but the operation does not (declare it with RequiresContext() or Permission(...))")
		}
	}
	for _, d := range declaredDescs(o) {
		if !bound[d] {
			add("", "operation "+d.label(), "operation "+d.label()+" is declared but not bound (Routes.Bind in build)")
		}
	}
	return problems
}

func declaredDescs(o options) []*opDesc {
	out := make([]*opDesc, len(o.declared))
	for i, d := range o.declared {
		out[i] = d.descriptor()
	}
	return out
}

func containsDesc(list []*opDesc, d *opDesc) bool {
	return slices.Contains(list, d)
}
