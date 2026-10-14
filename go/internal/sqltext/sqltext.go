// Package sqltext prepares the statements the Go framework shares with the Java framework. They
// are written with JDBC '?' placeholders (the shared files under conformance/sql/); Go sends them
// with PostgreSQL's numbered placeholders, numbered once when a package initialises.
package sqltext

import (
	"strconv"
	"strings"
)

// Number replaces each '?' placeholder with $1, $2, ... in order. A '?' inside a single-quoted
// literal, a double-quoted identifier or a comment is left alone.
func Number(sql string) string {
	var b strings.Builder
	n := 0
	inSingle, inDouble, inLine, inBlock := false, false, false, false
	for i := 0; i < len(sql); i++ {
		c := sql[i]
		switch {
		case inLine:
			if c == '\n' {
				inLine = false
			}
		case inBlock:
			if c == '*' && i+1 < len(sql) && sql[i+1] == '/' {
				inBlock = false
				b.WriteByte(c)
				i++
				c = sql[i]
			}
		case inSingle:
			if c == '\'' {
				inSingle = false
			}
		case inDouble:
			if c == '"' {
				inDouble = false
			}
		case c == '\'':
			inSingle = true
		case c == '"':
			inDouble = true
		case c == '-' && i+1 < len(sql) && sql[i+1] == '-':
			inLine = true
		case c == '/' && i+1 < len(sql) && sql[i+1] == '*':
			inBlock = true
		case c == '?':
			n++
			b.WriteString("$" + strconv.Itoa(n))
			continue
		}
		b.WriteByte(c)
	}
	return b.String()
}
