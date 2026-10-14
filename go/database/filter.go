package database

import (
	"fmt"
	"strings"
)

// Filter accumulates positional WHERE conditions and their arguments for dynamic list queries:
// the framework's own code uses it, and so may an application that does not adopt go-jet
// (docs/go/design.md section 5.2). Values are always bound; column names are strings, so they
// must be constants in source, never input. Copied from the FlowCatalyst platform's
// repocommon.Filter.
//
//	var f database.Filter
//	f.Eq("client_id", cx.Client().String())
//	f.Clause("customer LIKE $%d", "%"+search+"%")
//	limit := f.Arg(size)
//	sql := "SELECT id FROM orders" + f.Where() + fmt.Sprintf(" ORDER BY id LIMIT $%d", limit)
//	rows, err := db.Pool().Query(ctx, sql, f.Args()...)
type Filter struct {
	conds []string
	args  []any
}

// Eq appends `col = $n`.
func (f *Filter) Eq(col string, v any) {
	f.args = append(f.args, v)
	f.conds = append(f.conds, fmt.Sprintf("%s = $%d", col, len(f.args)))
}

// EqPtr appends `col = $n` when v is not nil; nil means no condition.
func (f *Filter) EqPtr(col string, v *string) {
	if v == nil {
		return
	}
	f.Eq(col, *v)
}

// Any appends `col = ANY($n)` when vs is not empty.
func (f *Filter) Any(col string, vs []string) {
	if len(vs) == 0 {
		return
	}
	f.args = append(f.args, vs)
	f.conds = append(f.conds, fmt.Sprintf("%s = ANY($%d)", col, len(f.args)))
}

// Clause appends a custom condition; format receives the new argument's position, for example
// f.Clause("created_at >= $%d", since).
func (f *Filter) Clause(format string, v any) {
	f.args = append(f.args, v)
	f.conds = append(f.conds, fmt.Sprintf(format, len(f.args)))
}

// Cond appends a condition without a parameter, for example "deleted_at IS NULL".
func (f *Filter) Cond(cond string) {
	f.conds = append(f.conds, cond)
}

// Arg appends an argument outside the WHERE clause (LIMIT, OFFSET) and returns its position.
func (f *Filter) Arg(v any) int {
	f.args = append(f.args, v)
	return len(f.args)
}

// Where renders "" without conditions, otherwise " WHERE c1 AND c2 ..." with the leading space.
func (f *Filter) Where() string {
	if len(f.conds) == 0 {
		return ""
	}
	return " WHERE " + strings.Join(f.conds, " AND ")
}

// Args returns the accumulated arguments.
func (f *Filter) Args() []any { return f.args }
