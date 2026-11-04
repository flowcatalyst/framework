package units

import (
	"encoding/json/v2"
	"errors"

	"github.com/flowcatalyst/framework/go/decimal"
	"github.com/flowcatalyst/framework/go/httpfw/openapi"
)

// fraction is an exact ratio of two decimals with a positive denominator: the unrounded result of a
// conversion or a multiplication, rounded once at the end.
type fraction struct{ num, den decimal.Decimal }

func (f fraction) times(x decimal.Decimal) fraction { return fraction{f.num.Mul(x), f.den} }

func (f fraction) dividedBy(x decimal.Decimal) fraction { return fraction{f.num, f.den.Mul(x)} }

func (f fraction) round(scale int, mode decimal.RoundingMode) (decimal.Decimal, error) {
	return f.num.Div(f.den, scale, mode)
}

func (f fraction) exact() (decimal.Decimal, error) { return f.num.DivExact(f.den) }

func (f fraction) cmp(o fraction) int { return f.num.Mul(o.den).Cmp(o.num.Mul(f.den)) }

// Quantity is a value in a unit, immutable. Conversions within a dimension are computed exactly and
// rounded once. Equality is numeric value and unit ([Quantity.Equal]): 1000 GRM does not equal 1 KGM,
// though [Quantity.Cmp] says they are the same amount.
type Quantity struct {
	value decimal.Decimal
	unit  Unit
}

// NewQuantity returns the value in the unit.
func NewQuantity(value decimal.Decimal, unit Unit) Quantity {
	return Quantity{value: value, unit: unit}
}

// ParseQuantity returns a quantity from plain decimal text and a unit code or alias.
func ParseQuantity(value, unit string) (Quantity, error) {
	v, err := decimal.Parse(value)
	if err != nil {
		return Quantity{}, err
	}
	u, err := Parse(unit)
	if err != nil {
		return Quantity{}, err
	}
	return NewQuantity(v, u), nil
}

// MustParseQuantity is [ParseQuantity] for constants; it panics on bad input.
func MustParseQuantity(value, unit string) Quantity {
	q, err := ParseQuantity(value, unit)
	if err != nil {
		panic(err)
	}
	return q
}

// Value returns the value.
func (q Quantity) Value() decimal.Decimal { return q.value }

// Unit returns the unit.
func (q Quantity) Unit() Unit { return q.unit }

// String returns "7831.2 KGM".
func (q Quantity) String() string { return q.value.String() + " " + q.unit.code }

// IsZero reports whether the value is zero.
func (q Quantity) IsZero() bool { return q.value.IsZero() }

// Signum returns the sign of the value: -1, 0 or 1.
func (q Quantity) Signum() int { return q.value.Sign() }

// Equal reports whether the value (numerically) and the unit are equal.
func (q Quantity) Equal(o Quantity) bool { return q.unit == o.unit && q.value.Equal(o.value) }

func checkConvertible(from, to Unit) error {
	if from.IsPackaging() || to.IsPackaging() {
		return decimal.NewError(codeNoConv, "a packaging unit converts only through a Packaging")
	}
	if from.Dimension() != to.Dimension() {
		return decimal.NewError(codeDimension, "the units are of different dimensions")
	}
	return nil
}

// fractionIn is the exact value in another unit as a ratio:
// ((v + oF) pF qT - oT qF pT) / (qF pT), F the unit of q and T the target.
func (q Quantity) fractionIn(target Unit) (fraction, error) {
	if err := checkConvertible(q.unit, target); err != nil {
		return fraction{}, err
	}
	f, t := q.unit.def(), target.def()
	num := q.value.Add(f.offset).Mul(f.p).Mul(t.q).Sub(t.offset.Mul(f.q).Mul(t.p))
	return fraction{num: num, den: f.q.Mul(t.p)}, nil
}

// To converts within the dimension: computed exactly, divided to scale with mode once. A packaging
// unit is NO_CONVERSION, another dimension DIMENSION_MISMATCH.
func (q Quantity) To(target Unit, scale int, mode decimal.RoundingMode) (Quantity, error) {
	f, err := q.fractionIn(target)
	if err != nil {
		return Quantity{}, err
	}
	v, err := f.round(scale, mode)
	if err != nil {
		return Quantity{}, err
	}
	return NewQuantity(v, target), nil
}

// ToExact converts without rounding. The result has the smallest scale (at least 0) that holds it;
// CONVERSION_INEXACT when it does not terminate (kg to lb).
func (q Quantity) ToExact(target Unit) (Quantity, error) {
	f, err := q.fractionIn(target)
	if err != nil {
		return Quantity{}, err
	}
	v, err := f.exact()
	if err != nil {
		return Quantity{}, err
	}
	return NewQuantity(v, target), nil
}

// addend returns o's value in q's unit for an addition.
func (q Quantity) addend(o Quantity) (decimal.Decimal, error) {
	if q.unit.Dimension() == Temperature || o.unit.Dimension() == Temperature {
		return decimal.Decimal{}, decimal.NewError(codeTemp, "temperatures are not added or subtracted")
	}
	if q.unit == o.unit {
		return o.value, nil
	}
	c, err := o.ToExact(q.unit)
	return c.value, err
}

// Plus adds; o is converted to q's unit exactly first, so this can fail with CONVERSION_INEXACT.
// Temperatures are TEMPERATURE_NOT_ADDITIVE.
func (q Quantity) Plus(o Quantity) (Quantity, error) {
	a, err := q.addend(o)
	if err != nil {
		return Quantity{}, err
	}
	return NewQuantity(q.value.Add(a), q.unit), nil
}

// Minus subtracts; o is converted to q's unit exactly first (see [Quantity.Plus]).
func (q Quantity) Minus(o Quantity) (Quantity, error) {
	a, err := q.addend(o)
	if err != nil {
		return Quantity{}, err
	}
	return NewQuantity(q.value.Sub(a), q.unit), nil
}

// Times multiplies the value, unrounded; the unit stays. A temperature is TEMPERATURE_NOT_ADDITIVE.
func (q Quantity) Times(f decimal.Decimal) (Quantity, error) {
	if q.unit.Dimension() == Temperature {
		return Quantity{}, decimal.NewError(codeTemp, "a temperature is not multiplied")
	}
	return NewQuantity(q.value.Mul(f), q.unit), nil
}

// base is the value in the dimension's base unit as a ratio: (v + o) p / q.
func (q Quantity) base() fraction {
	d := q.unit.def()
	return fraction{num: q.value.Add(d.offset).Mul(d.p), den: d.q}
}

// Cmp compares the amounts exactly across units of one dimension: -1, 0 or 1. Another dimension is
// DIMENSION_MISMATCH, and two different packaging units NO_CONVERSION.
func (q Quantity) Cmp(o Quantity) (int, error) {
	if q.unit == o.unit {
		return q.value.Cmp(o.value), nil
	}
	if err := checkConvertible(q.unit, o.unit); err != nil {
		return 0, err
	}
	return q.base().cmp(o.base()), nil
}

// errMalformed is a body that is not the {value, unit} object (or {price, per} for a [UnitPrice]).
var errMalformed = errors.New("units: not an object of the expected members")

// wire is Quantity's JSON form; both members are read as strings.
type quantityWire struct {
	Value string `json:"value"`
	Unit  string `json:"unit"`
}

// MarshalJSON writes {"value": "7831.2", "unit": "KGM"}; the zero Unit is an error.
func (q Quantity) MarshalJSON() ([]byte, error) {
	if q.unit.IsZero() {
		return nil, decimal.NewError(codeUnknown, "the zero unit")
	}
	return json.Marshal(quantityWire{Value: q.value.String(), Unit: q.unit.code})
}

// UnmarshalJSON reads the object of [Quantity.MarshalJSON]: both members, no others, the value a JSON
// string. Failures carry the contract's codes (DECIMAL_INVALID, UNIT_UNKNOWN).
func (q *Quantity) UnmarshalJSON(b []byte) error {
	var w quantityWire
	if err := json.Unmarshal(b, &w, json.RejectUnknownMembers(true)); err != nil {
		return errMalformed // not the decoder's error: its path would extend the property's
	}
	v, err := ParseQuantity(w.Value, w.Unit)
	if err != nil {
		return err
	}
	*q = v
	return nil
}

// JSONSchema implements openapi.SchemaProvider: the object {value, unit}, both required.
func (Quantity) JSONSchema() openapi.Schema {
	return openapi.Object(
		openapi.Prop{Name: "value", Schema: openapi.String().Format("decimal")},
		openapi.Prop{Name: "unit", Schema: openapi.String()},
	)
}
