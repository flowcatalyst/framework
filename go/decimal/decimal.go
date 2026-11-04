// Package decimal is an exact base-10 number: an unscaled integer and a scale, value = unscaled x
// 10^-scale. It is the Go counterpart of Java's BigDecimal with the same rules (the measure contract,
// docs/contracts/measure.md section 1), so a calculation gives the same digits in both languages; the
// vectors in conformance/measure/ are the proof.
//
//   - Text is plain decimal only, "-?[0-9]+(\.[0-9]+)?": no exponent, grouping, "+" or locale.
//     Formatting keeps the scale: 3132.40 prints "3132.40", 0.0 prints "0.0" (BigDecimal.toPlainString).
//   - Add and Sub take the larger scale, Mul adds the scales.
//   - There is no unrounded division: [Decimal.Div] always names a scale and a [RoundingMode], so an
//     inexact division never happens silently. [Decimal.DivExact] succeeds only when the quotient
//     terminates.
//   - Cmp is numeric (2.0 equals 2.00).
//
// A Decimal marshals as its text (JSON as a string, never a number; sql.Scanner and driver.Valuer for
// Postgres NUMERIC as text) and documents itself in OpenAPI as {"type": "string", "format": "decimal"}.
// The zero Decimal is 0 at scale 0. A Decimal is a value: no method changes its receiver.
package decimal

import (
	"database/sql/driver"
	"fmt"
	"math/big"
	"strings"

	"github.com/flowcatalyst/framework/go/httpfw/openapi"
)

// The error codes of this package (measure contract section 6). Every failure is a
// [*Error] carrying one of them.
const (
	// CodeDecimalInvalid is the code of text that is not a plain decimal number.
	CodeDecimalInvalid = "DECIMAL_INVALID"
	// CodeDivisionByZero is the code of a division by zero.
	CodeDivisionByZero = "DIVISION_BY_ZERO"
	// CodeConversionInexact is the code of an exact result that does not terminate.
	CodeConversionInexact = "CONVERSION_INEXACT"
)

var (
	codeInvalid = CodeDecimalInvalid
	codeZero    = CodeDivisionByZero
	codeInexact = CodeConversionInexact

	bigTen = big.NewInt(10)
)

// Decimal is an exact decimal number.
type Decimal struct {
	unscaled *big.Int // nil is 0
	scale    int
}

// New returns unscaled x 10^-scale: New(314, 2) is 3.14.
func New(unscaled int64, scale int) Decimal {
	return Decimal{unscaled: big.NewInt(unscaled), scale: scale}
}

// NewBig returns unscaled x 10^-scale for an arbitrary-size integer (the integer is copied).
func NewBig(unscaled *big.Int, scale int) Decimal {
	return Decimal{unscaled: new(big.Int).Set(unscaled), scale: scale}
}

// FromInt returns n at scale 0.
func FromInt(n int64) Decimal { return New(n, 0) }

// Parse reads plain decimal text: -?[0-9]+(\.[0-9]+)?. The scale is the number of fraction digits
// ("3132.40" has scale 2); leading zeros are dropped. Anything else, including an exponent, a "+",
// grouping, whitespace or non-ASCII digits, is a DECIMAL_INVALID validation error.
func Parse(s string) (Decimal, error) {
	i := 0
	if i < len(s) && s[i] == '-' {
		i++
	}
	digitsStart := i
	for i < len(s) && isDigit(s[i]) {
		i++
	}
	ok := i > digitsStart
	scale := 0
	if ok && i < len(s) {
		ok = s[i] == '.'
		i++
		fracStart := i
		for ok && i < len(s) && isDigit(s[i]) {
			i++
		}
		scale = i - fracStart
		ok = ok && scale > 0 && i == len(s)
	}
	if !ok {
		return Decimal{}, NewError(codeInvalid, "not a plain decimal number")
	}
	u, parsed := new(big.Int).SetString(strings.Replace(s, ".", "", 1), 10)
	if !parsed {
		return Decimal{}, NewError(codeInvalid, "not a plain decimal number")
	}
	return Decimal{unscaled: u, scale: scale}, nil
}

// MustParse is [Parse] for constants; it panics on bad text (fwvet checks the literal).
func MustParse(s string) Decimal {
	d, err := Parse(s)
	if err != nil {
		panic(err)
	}
	return d
}

func isDigit(c byte) bool { return c >= '0' && c <= '9' }

func (d Decimal) u() *big.Int {
	if d.unscaled == nil {
		return new(big.Int)
	}
	return d.unscaled
}

// Unscaled returns the unscaled integer (a copy): 3132.40 is 313240 at scale 2.
func (d Decimal) Unscaled() *big.Int { return new(big.Int).Set(d.u()) }

// Scale returns the number of digits after the decimal point (negative for a rounded-to-tens value).
func (d Decimal) Scale() int { return d.scale }

// Sign returns -1, 0 or 1.
func (d Decimal) Sign() int { return d.u().Sign() }

// IsZero reports whether d is zero at any scale.
func (d Decimal) IsZero() bool { return d.Sign() == 0 }

// Neg returns -d, at the same scale.
func (d Decimal) Neg() Decimal {
	return Decimal{unscaled: new(big.Int).Neg(d.u()), scale: d.scale}
}

// Abs returns |d|, at the same scale.
func (d Decimal) Abs() Decimal {
	return Decimal{unscaled: new(big.Int).Abs(d.u()), scale: d.scale}
}

// Cmp compares numerically: 2.0 and 2.00 are equal. It returns -1, 0 or 1.
func (d Decimal) Cmp(o Decimal) int {
	a, b := align(d, o)
	return a.Cmp(b)
}

// Equal reports whether d and o are numerically equal (Cmp == 0).
func (d Decimal) Equal(o Decimal) bool { return d.Cmp(o) == 0 }

// Add returns d + o, at the larger scale.
func (d Decimal) Add(o Decimal) Decimal {
	a, b := align(d, o)
	return Decimal{unscaled: new(big.Int).Add(a, b), scale: max(d.scale, o.scale)}
}

// Sub returns d - o, at the larger scale.
func (d Decimal) Sub(o Decimal) Decimal {
	a, b := align(d, o)
	return Decimal{unscaled: new(big.Int).Sub(a, b), scale: max(d.scale, o.scale)}
}

// Mul returns d x o, exactly; the scales add.
func (d Decimal) Mul(o Decimal) Decimal {
	return Decimal{unscaled: new(big.Int).Mul(d.u(), o.u()), scale: d.scale + o.scale}
}

// align returns the unscaled values of a and b at the larger of their scales.
func align(a, b Decimal) (*big.Int, *big.Int) {
	switch {
	case a.scale == b.scale:
		return a.u(), b.u()
	case a.scale < b.scale:
		return new(big.Int).Mul(a.u(), pow10(b.scale-a.scale)), b.u()
	default:
		return a.u(), new(big.Int).Mul(b.u(), pow10(a.scale-b.scale))
	}
}

func pow10(n int) *big.Int { return new(big.Int).Exp(bigTen, big.NewInt(int64(n)), nil) }

// Round returns d at the given scale (Java's setScale): a larger scale appends zeros, a smaller one
// rounds with mode. A scale may be negative (Round(-2, ...) rounds to hundreds).
func (d Decimal) Round(scale int, mode RoundingMode) Decimal {
	switch {
	case scale == d.scale:
		return d
	case scale > d.scale:
		return Decimal{unscaled: new(big.Int).Mul(d.u(), pow10(scale-d.scale)), scale: scale}
	}
	if !mode.valid() {
		panic(fmt.Sprintf("decimal: %v", mode))
	}
	return Decimal{unscaled: divRound(d.u(), pow10(d.scale-scale), mode), scale: scale}
}

// Div returns d / o at the given scale, rounded with mode (Java's divide(o, scale, mode)). A zero
// divisor is a DIVISION_BY_ZERO validation error.
func (d Decimal) Div(o Decimal, scale int, mode RoundingMode) (Decimal, error) {
	if o.IsZero() {
		return Decimal{}, NewError(codeZero, "division by zero")
	}
	if !mode.valid() {
		panic(fmt.Sprintf("decimal: %v", mode))
	}
	// d/o = (ud/uo) x 10^(so-sd); the result's unscaled value is that at 10^-scale.
	num, den := d.u(), o.u()
	if e := scale + o.scale - d.scale; e >= 0 {
		num = new(big.Int).Mul(num, pow10(e))
	} else {
		den = new(big.Int).Mul(den, pow10(-e))
	}
	return Decimal{unscaled: divRound(num, den, mode), scale: scale}, nil
}

// DivExact returns d / o without rounding, with the smallest scale (at least 0) that holds the
// quotient. A zero divisor is DIVISION_BY_ZERO; a quotient that does not terminate (1/3) is
// CONVERSION_INEXACT.
func (d Decimal) DivExact(o Decimal) (Decimal, error) {
	if o.IsZero() {
		return Decimal{}, NewError(codeZero, "division by zero")
	}
	// d/o = (ud x 10^so) / (uo x 10^sd) as an integer ratio
	num := new(big.Int).Mul(d.u(), pow10(o.scale))
	den := new(big.Int).Mul(o.u(), pow10(d.scale))
	if num.Sign() == 0 {
		return Decimal{unscaled: new(big.Int)}, nil
	}
	g := new(big.Int).GCD(nil, nil, new(big.Int).Abs(num), new(big.Int).Abs(den))
	num.Quo(num, g)
	den.Quo(den, g)
	if den.Sign() < 0 {
		num.Neg(num)
		den.Neg(den)
	}
	// the quotient terminates when the reduced denominator is 2^a 5^b; it then has max(a, b) digits
	twos, fives := 0, 0
	two, five := big.NewInt(2), big.NewInt(5)
	rest := new(big.Int).Set(den)
	r := new(big.Int)
	for {
		q, m := new(big.Int).QuoRem(rest, two, r)
		if m.Sign() != 0 {
			break
		}
		rest, twos = q, twos+1
	}
	for {
		q, m := new(big.Int).QuoRem(rest, five, r)
		if m.Sign() != 0 {
			break
		}
		rest, fives = q, fives+1
	}
	if rest.Cmp(big.NewInt(1)) != 0 {
		return Decimal{}, NewError(codeInexact, "the exact result does not terminate")
	}
	scale := max(twos, fives)
	// num/den x 10^scale is an integer
	u := new(big.Int).Mul(num, pow10(scale))
	u.Quo(u, den)
	// the reduced fraction already gives the smallest scale; it is at least 0 by construction
	return Decimal{unscaled: u, scale: scale}, nil
}

// String returns the plain decimal text with the scale kept (Java's toPlainString).
func (d Decimal) String() string {
	u := d.u()
	if d.scale <= 0 {
		if u.Sign() == 0 {
			return "0"
		}
		return u.String() + strings.Repeat("0", -d.scale)
	}
	digits := new(big.Int).Abs(u).String()
	if len(digits) <= d.scale {
		digits = strings.Repeat("0", d.scale-len(digits)+1) + digits
	}
	var sb strings.Builder
	if u.Sign() < 0 {
		sb.WriteByte('-')
	}
	sb.WriteString(digits[:len(digits)-d.scale])
	sb.WriteByte('.')
	sb.WriteString(digits[len(digits)-d.scale:])
	return sb.String()
}

// MarshalText returns the plain text; JSON writes it as a string.
func (d Decimal) MarshalText() ([]byte, error) { return []byte(d.String()), nil }

// UnmarshalText parses plain decimal text; a JSON number is refused (only a string is read).
func (d *Decimal) UnmarshalText(b []byte) error {
	v, err := Parse(string(b))
	if err != nil {
		return err
	}
	*d = v
	return nil
}

// Value implements driver.Valuer: the plain text, which Postgres reads as NUMERIC.
func (d Decimal) Value() (driver.Value, error) { return d.String(), nil }

// Scan implements sql.Scanner for a NUMERIC read as text (a string or bytes) or an integer column.
// A float is refused: nothing in this package goes through a float.
func (d *Decimal) Scan(src any) error {
	switch v := src.(type) {
	case string:
		return d.UnmarshalText([]byte(v))
	case []byte:
		return d.UnmarshalText(v)
	case int64:
		*d = FromInt(v)
		return nil
	}
	return fmt.Errorf("decimal: cannot scan %T into a Decimal (read NUMERIC as text)", src)
}

// JSONSchema implements openapi.SchemaProvider: {"type": "string", "format": "decimal"}.
func (Decimal) JSONSchema() openapi.Schema { return openapi.String().Format("decimal") }
