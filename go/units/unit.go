// Package units is units of measure, quantities and what is built on them (the measure contract's
// sections 3 and 4): [Unit] (a UN/ECE Recommendation 20 code with a dimension and an exact factor to
// the dimension's base unit), [Quantity] (a [decimal.Decimal] in a unit, converted exactly and rounded
// once), [Packaging] (pallets, cartons, eaches for one product), [UnitPrice] (a price per unit) and
// [ChargeableWeight]. The unit table is the embedded conformance/measure/units.json, the same file
// Java embeds.
//
//	price, _ := units.NewUnitPrice(money.MustParse("0.40", "ZAR"), units.KGM)
//	charge, _ := price.TimesExact(units.NewQuantity(decimal.MustParse("7.8312"), units.TNE)) // 3132.480 ZAR
//
// A unit converts within its dimension only; packaging units (pallet, carton) convert only through a
// [Packaging]; temperature converts but is not added or multiplied. Failures are [decimal.Error]
// values with the contract's codes (UNIT_UNKNOWN, DIMENSION_MISMATCH, NO_CONVERSION,
// CONVERSION_INEXACT, TEMPERATURE_NOT_ADDITIVE, UNIT_NOT_IN_PACKAGING, PACKAGING_INVALID, RATE_INVALID).
package units

import (
	"database/sql/driver"
	_ "embed" // the unit table
	"encoding/json/v2"
	"fmt"
	"maps"
	"strings"
	"sync"

	"github.com/flowcatalyst/framework/go/decimal"
	"github.com/flowcatalyst/framework/go/httpfw/openapi"
)

// The error codes of this package (measure contract section 6).
const (
	// CodeUnitUnknown is the code of text that is not a unit code or alias.
	CodeUnitUnknown = "UNIT_UNKNOWN"
	// CodeDimensionMismatch is the code of units of two dimensions.
	CodeDimensionMismatch = "DIMENSION_MISMATCH"
	// CodeNoConversion is the code of a packaging unit outside a packaging.
	CodeNoConversion = "NO_CONVERSION"
	// CodeTemperatureNotAdditive is the code of adding, subtracting or multiplying temperatures.
	CodeTemperatureNotAdditive = "TEMPERATURE_NOT_ADDITIVE"
	// CodeUnitNotInPackaging is the code of a quantity whose unit is not a level of the packaging.
	CodeUnitNotInPackaging = "UNIT_NOT_IN_PACKAGING"
	// CodePackagingInvalid is the code of a packaging that cannot be built.
	CodePackagingInvalid = "PACKAGING_INVALID"
	// CodeRateInvalid is the code of a density factor that is not positive.
	CodeRateInvalid = "RATE_INVALID"
)

var (
	codeUnknown   = CodeUnitUnknown
	codeDimension = CodeDimensionMismatch
	codeNoConv    = CodeNoConversion
	codeTemp      = CodeTemperatureNotAdditive
	codeNotIn     = CodeUnitNotInPackaging
	codePackaging = CodePackagingInvalid
	codeRate      = CodeRateInvalid
)

// Dimension is what a unit measures: units convert only within one dimension.
type Dimension int

// The dimensions.
const (
	// Mass has the base unit KGM.
	Mass Dimension = iota + 1
	// Length has the base unit MTR.
	Length
	// Area has the base unit MTK.
	Area
	// Volume has the base unit MTQ.
	Volume
	// Count has the base unit C62.
	Count
	// Temperature is affine (it converts, but is not added) and has the base unit KEL.
	Temperature
	// PackagingDim units (pallet, carton) have no base unit and convert only through a [Packaging].
	PackagingDim
)

var dimensionNames = [...]string{Mass: "mass", Length: "length", Area: "area", Volume: "volume", Count: "count", Temperature: "temperature", PackagingDim: "packaging"}

// String returns the dimension's name as the data file writes it: "mass".
func (d Dimension) String() string {
	if d < Mass || d > PackagingDim {
		return fmt.Sprintf("Dimension(%d)", int(d))
	}
	return dimensionNames[d]
}

// Unit is a built-in unit of measure, identified by its UN/ECE Recommendation 20 code (the wire form).
// Its definition against the dimension's base unit is base = (value + offset) x p / q. The zero Unit is
// invalid. Build one with [Parse] or use the variables below.
type Unit struct{ code string }

// The units of the table (measure contract section 3.1).
var (
	// MGM is the milligram.
	MGM = Unit{code: "MGM"}
	// GRM is the gram.
	GRM = Unit{code: "GRM"}
	// KGM is the kilogram, the base unit of mass.
	KGM = Unit{code: "KGM"}
	// TNE is the tonne.
	TNE = Unit{code: "TNE"}
	// LBR is the pound.
	LBR = Unit{code: "LBR"}
	// ONZ is the ounce.
	ONZ = Unit{code: "ONZ"}
	// STN is the short ton.
	STN = Unit{code: "STN"}
	// LTN is the long ton.
	LTN = Unit{code: "LTN"}
	// MMT is the millimetre.
	MMT = Unit{code: "MMT"}
	// CMT is the centimetre.
	CMT = Unit{code: "CMT"}
	// MTR is the metre, the base unit of length.
	MTR = Unit{code: "MTR"}
	// KMT is the kilometre.
	KMT = Unit{code: "KMT"}
	// INH is the inch.
	INH = Unit{code: "INH"}
	// FOT is the foot.
	FOT = Unit{code: "FOT"}
	// YRD is the yard.
	YRD = Unit{code: "YRD"}
	// SMI is the statute mile.
	SMI = Unit{code: "SMI"}
	// NMI is the nautical mile.
	NMI = Unit{code: "NMI"}
	// MTK is the square metre, the base unit of area.
	MTK = Unit{code: "MTK"}
	// FTK is the square foot.
	FTK = Unit{code: "FTK"}
	// CMQ is the cubic centimetre.
	CMQ = Unit{code: "CMQ"}
	// MLT is the millilitre.
	MLT = Unit{code: "MLT"}
	// LTR is the litre.
	LTR = Unit{code: "LTR"}
	// MTQ is the cubic metre, the base unit of volume.
	MTQ = Unit{code: "MTQ"}
	// INQ is the cubic inch.
	INQ = Unit{code: "INQ"}
	// FTQ is the cubic foot.
	FTQ = Unit{code: "FTQ"}
	// GLL is the US gallon.
	GLL = Unit{code: "GLL"}
	// GLI is the imperial gallon.
	GLI = Unit{code: "GLI"}
	// C62 is the one (each), the base unit of count.
	C62 = Unit{code: "C62"}
	// EA is the each.
	EA = Unit{code: "EA"}
	// H87 is the piece.
	H87 = Unit{code: "H87"}
	// DZN is the dozen.
	DZN = Unit{code: "DZN"}
	// KEL is the kelvin, the base unit of temperature.
	KEL = Unit{code: "KEL"}
	// CEL is the degree Celsius.
	CEL = Unit{code: "CEL"}
	// FAH is the degree Fahrenheit.
	FAH = Unit{code: "FAH"}
	// XPX is the pallet.
	XPX = Unit{code: "XPX"}
	// XCT is the carton.
	XCT = Unit{code: "XCT"}
	// XCS is the case.
	XCS = Unit{code: "XCS"}
	// XBX is the box.
	XBX = Unit{code: "XBX"}
	// XPK is the pack.
	XPK = Unit{code: "XPK"}
	// XBG is the bag.
	XBG = Unit{code: "XBG"}
	// XCR is the crate.
	XCR = Unit{code: "XCR"}
	// XDR is the drum.
	XDR = Unit{code: "XDR"}
)

type def struct {
	code      string
	symbol    string
	dimension Dimension
	p, q      decimal.Decimal // the factor p/q; zero for packaging
	offset    decimal.Decimal
}

type unitTable struct {
	byCode  map[string]*def
	aliases map[string]string // lower-cased alias to code
}

//go:embed units.json
var unitsJSON []byte

var table = sync.OnceValue(func() *unitTable {
	var doc struct {
		Units []struct {
			Code      string `json:"code"`
			Symbol    string `json:"symbol"`
			Dimension string `json:"dimension"`
			Factor    *struct {
				P string `json:"p"`
				Q string `json:"q"`
			} `json:"factor"`
			Offset  *string  `json:"offset"`
			Aliases []string `json:"aliases"`
		} `json:"units"`
	}
	if err := json.Unmarshal(unitsJSON, &doc); err != nil {
		panic(fmt.Sprintf("units: units.json: %v", err))
	}
	t := &unitTable{byCode: map[string]*def{}, aliases: map[string]string{}}
	for _, u := range doc.Units {
		d := &def{code: u.Code, symbol: u.Symbol}
		for dim := Mass; dim <= PackagingDim; dim++ {
			if dimensionNames[dim] == u.Dimension {
				d.dimension = dim
			}
		}
		if u.Factor != nil && u.Offset != nil {
			d.p, d.q, d.offset = parseTable(u.Factor.P), parseTable(u.Factor.Q), parseTable(*u.Offset)
		}
		t.byCode[u.Code] = d
		for _, a := range u.Aliases {
			t.aliases[strings.ToLower(a)] = u.Code
		}
	}
	return t
})

// parseTable reads a decimal of the embedded table, which is valid by construction.
func parseTable(s string) decimal.Decimal {
	d, err := decimal.Parse(s)
	if err != nil {
		panic(fmt.Sprintf("units: units.json: %q: %v", s, err))
	}
	return d
}

// unknown is the definition of the zero Unit: no symbol, dimension 0.
var unknown = &def{}

func (u Unit) def() *def {
	if d, ok := table().byCode[u.code]; ok {
		return d
	}
	return unknown
}

// Code returns the Recommendation 20 code, "KGM".
func (u Unit) Code() string { return u.code }

// String returns the code.
func (u Unit) String() string { return u.code }

// IsZero reports whether u is the zero Unit.
func (u Unit) IsZero() bool { return u.code == "" }

// Symbol returns the symbol for display: "kg", "m³".
func (u Unit) Symbol() string { return u.def().symbol }

// Dimension returns what the unit measures; the zero Unit has dimension 0.
func (u Unit) Dimension() Dimension { return u.def().dimension }

// IsPackaging reports whether u is a packaging unit (pallet, carton, ...).
func (u Unit) IsPackaging() bool { return u.Dimension() == PackagingDim }

// Parse returns the unit for a code (case-sensitive) or a built-in alias (case-insensitive: "kg", "m3",
// "L", "degC", "PX"); anything else is UNIT_UNKNOWN. Use a [Registry] for an application's own aliases.
func Parse(text string) (Unit, error) { return Builtin().Parse(text) }

// MustParse is [Parse] for constants; it panics on an unknown unit.
func MustParse(text string) Unit {
	u, err := Parse(text)
	if err != nil {
		panic(err)
	}
	return u
}

// Registry turns text into a [Unit]: the code, a built-in alias, or an alias an application registered
// for a partner's own codes. It is immutable; [Registry.With] returns a new one.
type Registry struct{ extra map[string]Unit }

// Builtin returns the registry of the built-in codes and aliases.
func Builtin() Registry { return Registry{} }

// With returns a registry with one more alias, matched case-insensitively. An empty alias, or one that
// is already a code or a built-in alias, is UNIT_UNKNOWN.
func (r Registry) With(alias string, unit Unit) (Registry, error) {
	key := strings.ToLower(alias)
	t := table()
	if strings.TrimSpace(alias) == "" || t.byCode[alias] != nil || t.aliases[key] != "" {
		return Registry{}, decimal.NewError(codeUnknown, "the alias is empty or already a built-in code or alias")
	}
	extra := make(map[string]Unit, len(r.extra)+1)
	maps.Copy(extra, r.extra)
	extra[key] = unit
	return Registry{extra: extra}, nil
}

// Parse returns the unit for text: the code exactly, then the built-in aliases, then this registry's
// own, both ignoring case.
func (r Registry) Parse(text string) (Unit, error) {
	t := table()
	if t.byCode[text] != nil {
		return Unit{code: text}, nil
	}
	key := strings.ToLower(text)
	if code, ok := t.aliases[key]; ok {
		return Unit{code: code}, nil
	}
	if u, ok := r.extra[key]; ok {
		return u, nil
	}
	return Unit{}, decimal.NewError(codeUnknown, "unknown unit")
}

// MarshalText returns the code; the zero Unit is an error.
func (u Unit) MarshalText() ([]byte, error) {
	if u.IsZero() {
		return nil, decimal.NewError(codeUnknown, "the zero unit")
	}
	return []byte(u.code), nil
}

// UnmarshalText parses a code or built-in alias.
func (u *Unit) UnmarshalText(b []byte) error {
	v, err := Parse(string(b))
	if err != nil {
		return err
	}
	*u = v
	return nil
}

// Value implements driver.Valuer: the code (a VARCHAR column); the zero Unit is an error.
func (u Unit) Value() (driver.Value, error) {
	if u.IsZero() {
		return nil, decimal.NewError(codeUnknown, "the zero unit cannot be stored")
	}
	return u.code, nil
}

// Scan implements sql.Scanner for a text column.
func (u *Unit) Scan(src any) error {
	switch v := src.(type) {
	case string:
		return u.UnmarshalText([]byte(v))
	case []byte:
		return u.UnmarshalText(v)
	}
	return fmt.Errorf("units: cannot scan %T into a Unit", src)
}

// JSONSchema implements openapi.SchemaProvider: {"type": "string"}.
func (Unit) JSONSchema() openapi.Schema { return openapi.String() }
