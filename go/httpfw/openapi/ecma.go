package openapi

import (
	"regexp"
	"strings"
)

// ecmaProblem says why a Go (RE2) regular expression cannot be written into a JSON Schema pattern,
// or "" when it can (OpenAPI contract section 5.4): constructs ECMA-262 lacks or reads differently
// are refused, and the author uses a custom rule, which documents nothing, instead.
func ecmaProblem(expr string) string {
	if _, err := regexp.Compile(expr); err != nil {
		return "it is not a valid regular expression"
	}
	inClass := false
	for i := 0; i < len(expr); i++ {
		c := expr[i]
		if c == '\\' {
			if i+1 >= len(expr) {
				return "it ends with a lone backslash"
			}
			n := expr[i+1]
			switch {
			case strings.IndexByte("AzQECZGhHvVRXeaN", n) >= 0:
				return "it uses \\" + string(n) + ", which ECMA-262 lacks or reads differently"
			case n == 'p' || n == 'P':
				return "it uses \\" + string(n) + ", a Unicode class ECMA-262 reads only with the u flag"
			}
			i++
			continue
		}
		if inClass {
			switch {
			case c == '[' && i+1 < len(expr) && expr[i+1] == ':':
				return "it uses a POSIX class [[:...:]], which ECMA-262 lacks"
			case c == ']':
				inClass = false
			}
			continue
		}
		if c == '[' {
			inClass = true
			if i+1 < len(expr) && expr[i+1] == '^' {
				i++
			}
			if i+1 < len(expr) && expr[i+1] == ']' {
				return "it starts a character class with ']', which ECMA-262 reads as an empty class"
			}
			continue
		}
		if c == '(' && i+1 < len(expr) && expr[i+1] == '?' {
			rest := expr[i+2:]
			switch {
			case strings.HasPrefix(rest, ":"), strings.HasPrefix(rest, "="), strings.HasPrefix(rest, "!"):
				continue
			case strings.HasPrefix(rest, "P<"):
				return "it uses a named group (?P<...>), which ECMA-262 writes (?<...>)"
			case strings.HasPrefix(rest, "<"):
				continue
			}
			return "it uses inline flags (?...)"
		}
	}
	return ""
}
