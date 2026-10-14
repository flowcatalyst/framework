package sqltext_test

import (
	"testing"

	"github.com/flowcatalyst/framework/go/internal/sqltext"
)

func TestNumber(t *testing.T) {
	t.Parallel()
	cases := map[string]string{
		"VALUES (?, ?, ?)":                       "VALUES ($1, $2, $3)",
		"SELECT '?' , ? -- why?\n, ?":            "SELECT '?' , $1 -- why?\n, $2",
		`SELECT "a?" , /* ? */ ? FROM t WHERE ?`: `SELECT "a?" , /* ? */ $1 FROM t WHERE $2`,
		"no placeholders":                        "no placeholders",
	}
	for in, want := range cases {
		if got := sqltext.Number(in); got != want {
			t.Errorf("Number(%q) = %q, want %q", in, got, want)
		}
	}
}
