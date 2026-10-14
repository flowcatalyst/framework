package config

import (
	"errors"
	"math"
	"math/big"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"
)

// keyPattern is [a-z][a-z0-9_-]*(\.[a-z][a-z0-9_-]*)*.
var keyPattern = regexp.MustCompile(`^[a-z][a-z0-9_-]*(\.[a-z][a-z0-9_-]*)*$`)

// newSetting builds a setting; an invalid key is remembered and reported by Load.
func newSetting[T any](key, description string, typ settingType[T]) Setting[T] {
	s := Setting[T]{key: key, description: description, typ: typ}
	if !keyPattern.MatchString(key) {
		s.problem = "setting key '" + key + "' must match [a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)*"
	}
	return s
}

// formatError is a value that does not have the expected shape; its text is the description of
// the shape ("an integer").
type formatError struct{ expected string }

func (e *formatError) Error() string { return e.expected }

// scalarText turns a raw value into text: a string is itself; a list (or anything else) is not a
// scalar.
func scalarText(raw any) (string, bool) {
	s, ok := raw.(string)
	return s, ok
}

// textType builds a type whose value is parsed from the stripped scalar text.
func textType[T any](name, describe string, strip bool, parse func(text string) (T, bool)) settingType[T] {
	return settingType[T]{
		name:     name,
		describe: describe,
		parse: func(raw any) (T, error) {
			var zero T
			text, ok := scalarText(raw)
			if !ok {
				return zero, &formatError{describe}
			}
			if strip {
				text = javaStrip(text)
			}
			v, ok := parse(text)
			if !ok {
				return zero, &formatError{describe}
			}
			return v, nil
		},
	}
}

var (
	stringType = textType("string", "a string", false, func(s string) (string, bool) { return s, true })

	intType = textType("integer", "an integer", true, func(s string) (int, bool) {
		n, ok := parseDecimal(s, 32)
		return int(n), ok
	})

	int64Type = textType("long", "a long integer", true, func(s string) (int64, bool) {
		return parseDecimal(s, 64)
	})

	boolType = textType("boolean", "true or false", true, func(s string) (bool, bool) {
		switch s {
		case "true":
			return true, true
		case "false":
			return false, true
		}
		return false, false
	})

	durationType = textType("duration", "a duration such as 500ms, 30s, 5m, 2h, 1d or PT30S", true, parseDuration)

	urlType = textType("url", "a URI", true, func(s string) (url.URL, bool) {
		if !javaURISyntax(s) {
			return url.URL{}, false
		}
		u, err := url.Parse(s)
		if err != nil {
			return url.URL{}, false
		}
		return *u, true
	})

	secretType = settingType[Secret]{
		name:     "secret",
		describe: "a string",
		secret:   true,
		parse: func(raw any) (Secret, error) {
			text, ok := scalarText(raw)
			if !ok {
				return Secret{}, &formatError{"a string"}
			}
			return NewSecret(text), nil
		},
	}

	stringListType = settingType[[]string]{
		name:     "stringList",
		describe: "a list of strings (YAML list, or comma-separated text)",
		parse: func(raw any) ([]string, error) {
			out := []string{}
			if list, ok := raw.([]any); ok {
				for _, item := range list {
					text, ok := scalarText(item)
					if !ok {
						return nil, &formatError{"a list of strings (YAML list, or comma-separated text)"}
					}
					out = append(out, text)
				}
				return out, nil
			}
			text, ok := scalarText(raw)
			if !ok {
				return nil, &formatError{"a list of strings (YAML list, or comma-separated text)"}
			}
			if text = javaStrip(text); text != "" {
				for part := range strings.SplitSeq(text, ",") {
					out = append(out, javaStrip(part))
				}
			}
			return out, nil
		},
	}
)

// enumType matches the stripped text, with '-' read as '_', ignoring case.
func enumType[E ~string](values []E) settingType[E] {
	names := make([]string, len(values))
	for i, v := range values {
		names[i] = string(v)
	}
	return textType("enum", "one of "+strings.Join(names, ", "), true, func(s string) (E, bool) {
		s = strings.ReplaceAll(s, "-", "_")
		for _, v := range values {
			if strings.EqualFold(string(v), s) {
				return v, true
			}
		}
		var zero E
		return zero, false
	})
}

// parseDecimal is Java's Integer.valueOf / Long.valueOf for ASCII digits: an optional sign and
// decimal digits, within the range of bits.
func parseDecimal(s string, bits int) (int64, bool) {
	digits := strings.TrimLeft(s, "+-")
	if len(s)-len(digits) > 1 || digits == "" {
		return 0, false
	}
	for i := range len(digits) {
		if digits[i] < '0' || digits[i] > '9' {
			return 0, false
		}
	}
	n, err := strconv.ParseInt(s, 10, bits)
	return n, err == nil
}

// isJavaWhitespace is Java's Character.isWhitespace.
func isJavaWhitespace(r rune) bool {
	switch r {
	case ' ', ' ', ' ':
		return false
	case '\t', '\n', '\u000b', '\f', '\r', '\u001c', '\u001d', '\u001e', '\u001f':
		return true
	}
	return unicode.In(r, unicode.Zs, unicode.Zl, unicode.Zp)
}

// javaStrip is Java's String.strip.
func javaStrip(s string) string { return strings.TrimFunc(s, isJavaWhitespace) }

// isJavaBlank is Java's String.isBlank.
func isJavaBlank(s string) bool { return javaStrip(s) == "" }

var (
	simpleDuration = regexp.MustCompile(`^([0-9]{1,15})(ms|s|m|h|d)$`)
	// java.time.Duration.parse's pattern, with ASCII-only case folding written out.
	isoDuration = regexp.MustCompile(`^([-+]?)[Pp](?:([-+]?[0-9]+)[Dd])?([Tt](?:([-+]?[0-9]+)[Hh])?(?:([-+]?[0-9]+)[Mm])?(?:([-+]?[0-9]+)(?:[.,]([0-9]{0,9}))?[Ss])?)?$`)
)

var errOverflow = errors.New("overflow")

func mulExact(a, b int64) (int64, error) {
	if a != 0 && (a*b/a != b || (a == -1 && b == math.MinInt64) || (b == -1 && a == math.MinInt64)) {
		return 0, errOverflow
	}
	return a * b, nil
}

func addExact(a, b int64) (int64, error) {
	c := a + b
	if (a >= 0) == (b >= 0) && (c >= 0) != (a >= 0) {
		return 0, errOverflow
	}
	return c, nil
}

// parseDuration reads the simple form (500ms, 30s, 5m, 2h, 1d), then ISO-8601 as
// java.time.Duration.parse does; a negative result, and one that does not fit time.Duration, is
// refused.
func parseDuration(text string) (time.Duration, bool) {
	if m := simpleDuration.FindStringSubmatch(text); m != nil {
		n, err := strconv.ParseInt(m[1], 10, 64)
		if err != nil {
			return 0, false
		}
		unit := map[string]time.Duration{"ms": time.Millisecond, "s": time.Second, "m": time.Minute, "h": time.Hour, "d": 24 * time.Hour}[m[2]]
		ns, err := mulExact(n, int64(unit))
		return time.Duration(ns), err == nil
	}
	return parseISODuration(text)
}

func parseISODuration(text string) (time.Duration, bool) {
	idx := isoDuration.FindStringSubmatchIndex(text)
	if idx == nil {
		return 0, false
	}
	group := func(n int) (string, bool) {
		if idx[2*n] < 0 {
			return "", false
		}
		return text[idx[2*n]:idx[2*n+1]], true
	}
	// Java refuses only a group 3 that is exactly the upper-case "T" (so "P1Dt" is accepted).
	if g3, _ := group(3); g3 == "T" {
		return 0, false
	}
	days, hasDays := group(2)
	hours, hasHours := group(4)
	minutes, hasMinutes := group(5)
	secs, hasSecs := group(6)
	if !hasDays && !hasHours && !hasMinutes && !hasSecs {
		return 0, false
	}
	number := func(s string, mult int64) (int64, bool) {
		if s == "" {
			return 0, true
		}
		n, err := strconv.ParseInt(s, 10, 64)
		if err != nil {
			return 0, false
		}
		v, err := mulExact(n, mult)
		return v, err == nil
	}
	d, ok1 := number(days, 86400)
	h, ok2 := number(hours, 3600)
	m, ok3 := number(minutes, 60)
	s, ok4 := number(secs, 1)
	if !ok1 || !ok2 || !ok3 || !ok4 {
		return 0, false
	}
	var total int64
	for _, part := range []int64{m, s, h, d} { // Java adds days + (hours + (minutes + seconds))
		var err error
		if total, err = addExact(total, part); err != nil {
			return 0, false
		}
	}
	nanos := int64(0)
	if frac, _ := group(7); frac != "" {
		n, err := strconv.ParseInt((frac + "000000000")[:9], 10, 64)
		if err != nil {
			return 0, false
		}
		nanos = n
		if strings.HasPrefix(secs, "-") {
			nanos = -n
		}
	}
	value := new(big.Int).Mul(big.NewInt(total), big.NewInt(1_000_000_000))
	value.Add(value, big.NewInt(nanos))
	if sign, _ := group(1); sign == "-" {
		value.Neg(value)
	}
	if value.Sign() < 0 || !value.IsInt64() {
		return 0, false
	}
	return time.Duration(value.Int64()), true
}

// javaURISyntax is a check of the characters java.net.URI refuses (it is stricter than
// url.Parse): spaces, control characters, the characters of RFC 2396 that are never allowed, and
// an '%' without two hex digits.
func javaURISyntax(s string) bool {
	for i, r := range s {
		switch {
		case r <= 0x20 || r == 0x7f:
			return false
		case strings.ContainsRune(`"<>\^`+"`"+`{|}`, r):
			return false
		case r > 0x7f && (unicode.In(r, unicode.Zs, unicode.Zl, unicode.Zp) || unicode.IsControl(r)):
			return false
		case r == '%':
			if len(s) < i+3 || !isHex(s[i+1]) || !isHex(s[i+2]) {
				return false
			}
		}
	}
	return true
}

func isHex(b byte) bool {
	return b >= '0' && b <= '9' || b >= 'a' && b <= 'f' || b >= 'A' && b <= 'F'
}
