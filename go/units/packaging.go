package units

import (
	"math/big"
	"slices"

	"github.com/flowcatalyst/framework/go/decimal"
)

// Packaging holds the levels of one product (the measure contract's section 3.3):
//
//	p, _ := units.NewPackaging(units.C62)
//	p, _ = p.Level(units.XCT, 24)   // a carton holds 24 each
//	p, _ = p.Level(units.XPX, 40)   // a pallet holds 40 cartons
//
// The first unit, the product's smallest handling unit, is a count or mass unit; every further level
// is a packaging unit and says how many of the level below it holds (a positive integer). It is
// immutable.
type Packaging struct {
	levels []Unit
	per    []*big.Int // per[i] is how many of level i-1 level i holds; per[0] is 1
}

// NewPackaging starts a packaging at its smallest unit, a count or mass unit; another unit is
// PACKAGING_INVALID.
func NewPackaging(first Unit) (Packaging, error) {
	if d := first.Dimension(); d != Count && d != Mass {
		return Packaging{}, decimal.NewError(codePackaging, "the first unit must be a count or mass unit")
	}
	return Packaging{levels: []Unit{first}, per: []*big.Int{big.NewInt(1)}}, nil
}

// Level returns the packaging with a level above the last: unit is a packaging unit not used before,
// and count (at least 1) how many of the level below it holds. Anything else is PACKAGING_INVALID.
func (p Packaging) Level(unit Unit, count int64) (Packaging, error) {
	switch {
	case len(p.levels) == 0:
		return Packaging{}, decimal.NewError(codePackaging, "a packaging needs a first unit")
	case !unit.IsPackaging():
		return Packaging{}, decimal.NewError(codePackaging, "a level above the first must be a packaging unit")
	case slices.Contains(p.levels, unit):
		return Packaging{}, decimal.NewError(codePackaging, "a unit is used twice")
	case count <= 0:
		return Packaging{}, decimal.NewError(codePackaging, "a level holds a positive number of the level below")
	}
	return Packaging{
		levels: append(slices.Clone(p.levels), unit),
		per:    append(slices.Clone(p.per), big.NewInt(count)),
	}, nil
}

// Levels returns the level units from the smallest up.
func (p Packaging) Levels() []Unit { return slices.Clone(p.levels) }

// size is how many smallest units one of the level holds.
func (p Packaging) size(level int) decimal.Decimal {
	n := big.NewInt(1)
	for i := 1; i <= level; i++ {
		n.Mul(n, p.per[i])
	}
	return decimal.NewBig(n, 0)
}

func (p Packaging) index(u Unit) (int, error) {
	i := slices.Index(p.levels, u)
	if i < 0 {
		return 0, decimal.NewError(codeNotIn, "the unit is not a level of the packaging")
	}
	return i, nil
}

func (p Packaging) fraction(q Quantity, to Unit) (fraction, error) {
	from, err := p.index(q.unit)
	if err != nil {
		return fraction{}, err
	}
	target, err := p.index(to)
	if err != nil {
		return fraction{}, err
	}
	return fraction{num: q.value.Mul(p.size(from)), den: p.size(target)}, nil
}

// Convert converts between two levels (going down multiplies, going up divides), rounded once to
// scale with mode. A unit that is not a level is UNIT_NOT_IN_PACKAGING.
func (p Packaging) Convert(q Quantity, to Unit, scale int, mode decimal.RoundingMode) (Quantity, error) {
	f, err := p.fraction(q, to)
	if err != nil {
		return Quantity{}, err
	}
	v, err := f.round(scale, mode)
	if err != nil {
		return Quantity{}, err
	}
	return NewQuantity(v, to), nil
}

// ConvertExact converts between two levels without rounding; the result has the smallest scale (at
// least 0) that holds it, or the error is CONVERSION_INEXACT.
func (p Packaging) ConvertExact(q Quantity, to Unit) (Quantity, error) {
	f, err := p.fraction(q, to)
	if err != nil {
		return Quantity{}, err
	}
	v, err := f.exact()
	if err != nil {
		return Quantity{}, err
	}
	return NewQuantity(v, to), nil
}

// Breakdown returns the quantity as whole units from the largest level down, with any fractional rest
// at the smallest level: 1000 each in [C62, XCT x24, XPX x40] is [1 XPX, 1 XCT, 16 C62]. Every level
// appears, zero included, largest first. A negative quantity is broken down as its absolute value and
// every part negated.
func (p Packaging) Breakdown(q Quantity) ([]Quantity, error) {
	from, err := p.index(q.unit)
	if err != nil {
		return nil, err
	}
	negative := q.value.Sign() < 0
	rest := q.value.Abs().Mul(p.size(from))
	if rest.Scale() < 0 {
		rest = rest.Round(0, decimal.Down)
	}
	out := make([]Quantity, 0, len(p.levels))
	for level := len(p.levels) - 1; level >= 1; level-- {
		whole, err := rest.Div(p.size(level), 0, decimal.Floor)
		if err != nil {
			return nil, err // a size is never zero
		}
		rest = rest.Sub(whole.Mul(p.size(level)))
		out = append(out, NewQuantity(signed(whole, negative), p.levels[level]))
	}
	return append(out, NewQuantity(signed(rest, negative), p.levels[0])), nil
}

func signed(d decimal.Decimal, negative bool) decimal.Decimal {
	if negative {
		return d.Neg()
	}
	return d
}
