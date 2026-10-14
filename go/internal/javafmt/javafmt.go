// Package javafmt formats values the way the Java framework does, where the Go framework must
// produce the same text: Java's Double.toString (JDK 19 and later) for numbers in config values
// and Prometheus output, and Instant.toString (ISO_INSTANT) for times in outbox payloads.
package javafmt

import (
	"math"
	"strconv"
	"strings"
	"time"
)

// Double returns Java's Double.toString(v): "NaN", "Infinity", "-Infinity", "0.0", "-0.0"; a plain
// decimal with at least one fraction digit when 1e-3 <= |v| < 1e7 ("1.0", "0.001", "1234.5");
// otherwise computerised scientific notation ("1.0E7", "1.0E-4", "4.9E-324"). The digits are the
// shortest that round to v, except that Java never chooses a one-digit decimal: when the shortest
// has one digit, it takes the closest two-digit decimal (so 5e-324 prints as 4.9E-324).
func Double(v float64) string { return format(v, 64) }

// Float returns Java's Float.toString(v): as [Double], with the digits that round-trip a
// float32 (0.1f prints 0.1, not 0.10000000149011612).
func Float(v float32) string { return format(float64(v), 32) }

func format(v float64, bits int) string {
	switch {
	case math.IsNaN(v):
		return "NaN"
	case math.IsInf(v, 1):
		return "Infinity"
	case math.IsInf(v, -1):
		return "-Infinity"
	case v == 0:
		if math.Signbit(v) {
			return "-0.0"
		}
		return "0.0"
	}
	neg := v < 0
	a := math.Abs(v)
	digits, exp := sciDigits(a, -1, bits)
	if len(digits) == 1 {
		digits, exp = sciDigits(a, 1, bits)
	}
	digits = strings.TrimRight(digits, "0")
	if digits == "" {
		digits = "0"
	}
	var b strings.Builder
	if neg {
		b.WriteByte('-')
	}
	if a >= 1e-3 && a < 1e7 {
		// plain: the point goes after digit exp (exp is the scientific exponent)
		if exp >= 0 {
			intPart := digits
			frac := ""
			if len(digits) > exp+1 {
				intPart, frac = digits[:exp+1], digits[exp+1:]
			} else {
				intPart += strings.Repeat("0", exp+1-len(digits))
			}
			if frac == "" {
				frac = "0"
			}
			b.WriteString(intPart)
			b.WriteByte('.')
			b.WriteString(frac)
		} else {
			b.WriteString("0.")
			b.WriteString(strings.Repeat("0", -exp-1))
			b.WriteString(digits)
		}
		return b.String()
	}
	b.WriteByte(digits[0])
	b.WriteByte('.')
	if len(digits) > 1 {
		b.WriteString(digits[1:])
	} else {
		b.WriteByte('0')
	}
	b.WriteByte('E')
	b.WriteString(strconv.Itoa(exp))
	return b.String()
}

// sciDigits returns the significant digits of a positive finite a and its decimal exponent, with
// prec digits after the first (-1: the shortest that round-trip).
func sciDigits(a float64, prec, bits int) (string, int) {
	s := strconv.FormatFloat(a, 'e', prec, bits) // d.dddde±xx
	mant, expText, _ := strings.Cut(s, "e")
	exp, _ := strconv.Atoi(expText)
	return strings.Replace(mant, ".", "", 1), exp
}

// Instant returns Java's Instant.toString(t) (DateTimeFormatter.ISO_INSTANT): the UTC time as
// yyyy-MM-ddTHH:mm:ss, then the fraction of the second in groups of three digits (none, 3, 6 or 9)
// as needed, then Z. 2026-10-08T10:15:31.500Z, 2026-10-08T10:15:30Z, ...30.000123Z.
func Instant(t time.Time) string {
	u := t.UTC()
	var b strings.Builder
	b.WriteString(u.Format("2006-01-02T15:04:05"))
	if ns := u.Nanosecond(); ns != 0 {
		frac := strconv.Itoa(ns + 1_000_000_000)[1:] // nine digits
		switch {
		case ns%1_000_000 == 0:
			frac = frac[:3]
		case ns%1_000 == 0:
			frac = frac[:6]
		}
		b.WriteByte('.')
		b.WriteString(frac)
	}
	b.WriteByte('Z')
	return b.String()
}
