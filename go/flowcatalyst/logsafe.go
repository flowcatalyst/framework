package flowcatalyst

import (
	"strings"
	"unicode"
	"unicode/utf8"
)

// cleanText makes text that came from outside (a platform message, a header) safe to put in a log
// line or a response: every control, format, line-separator and paragraph-separator character
// becomes a space, and the text is cut to at most max characters (UTF-16 units as Java counts them,
// never inside a surrogate pair).
func cleanText(text string, limit int) string {
	var sb strings.Builder
	units := 0
	for _, r := range text {
		width := 1
		if r >= 0x10000 {
			width = 2
		}
		if units+width > limit {
			break
		}
		units += width
		if r == utf8.RuneError || unicode.In(r, unicode.Cc, unicode.Cf, unicode.Zl, unicode.Zp) {
			r = ' '
		}
		sb.WriteRune(r)
	}
	return sb.String()
}
