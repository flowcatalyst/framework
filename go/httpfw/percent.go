package httpfw

import (
	"strings"
	"unicode/utf8"
)

// decodeSegment decodes UTF-8 percent-escapes in one path segment (or, with plusIsSpace, a query
// component). It reports false for a malformed escape or invalid UTF-8.
func decodeSegment(s string, plusIsSpace bool) (string, bool) {
	if !strings.Contains(s, "%") && (!plusIsSpace || !strings.Contains(s, "+")) {
		return s, true
	}
	out := make([]byte, 0, len(s))
	for i := 0; i < len(s); i++ {
		switch c := s[i]; {
		case c == '%':
			if i+2 >= len(s) {
				return "", false
			}
			hi, lo := hexVal(s[i+1]), hexVal(s[i+2])
			if hi < 0 || lo < 0 {
				return "", false
			}
			out = append(out, byte(hi<<4|lo))
			i += 2
		case c == '+' && plusIsSpace:
			out = append(out, ' ')
		default:
			out = append(out, c)
		}
	}
	if !utf8.Valid(out) {
		return "", false
	}
	return string(out), true
}

func hexVal(c byte) int {
	switch {
	case c >= '0' && c <= '9':
		return int(c - '0')
	case c >= 'a' && c <= 'f':
		return int(c-'a') + 10
	case c >= 'A' && c <= 'F':
		return int(c-'A') + 10
	}
	return -1
}
