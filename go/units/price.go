package units

import (
	"encoding/json/jsontext"
	"encoding/json/v2"

	"github.com/flowcatalyst/framework/go/decimal"
	"github.com/flowcatalyst/framework/go/httpfw/openapi"
	"github.com/flowcatalyst/framework/go/money"
)

// UnitPrice is a price per unit, a rate (the measure contract's section 4): 0.40 ZAR per KGM. The
// unit is not a packaging or temperature unit.
type UnitPrice struct {
	price money.Money
	per   Unit
}

// NewUnitPrice returns the price of one per. A packaging unit is NO_CONVERSION, a temperature
// TEMPERATURE_NOT_ADDITIVE.
func NewUnitPrice(price money.Money, per Unit) (UnitPrice, error) {
	if per.IsPackaging() {
		return UnitPrice{}, decimal.NewError(codeNoConv, "a price per packaging unit has no conversion")
	}
	if per.Dimension() == Temperature {
		return UnitPrice{}, decimal.NewError(codeTemp, "a price per temperature unit is meaningless")
	}
	return UnitPrice{price: price, per: per}, nil
}

// Price returns the price of one unit.
func (p UnitPrice) Price() money.Money { return p.price }

// Per returns the unit the price is for.
func (p UnitPrice) Per() Unit { return p.per }

// Times prices a quantity: the quantity is converted to the price's unit and multiplied by the price in
// one exact computation, rounded once to scale with mode. 0.40 ZAR/KGM x 7.8312 TNE is 3132.480 ZAR at
// scale 3. Another dimension is DIMENSION_MISMATCH.
func (p UnitPrice) Times(q Quantity, scale int, mode decimal.RoundingMode) (money.Money, error) {
	f, err := q.fractionIn(p.per)
	if err != nil {
		return money.Money{}, err
	}
	v, err := f.times(p.price.Amount()).round(scale, mode)
	if err != nil {
		return money.Money{}, err
	}
	return money.New(v, p.price.Currency()), nil
}

// TimesExact prices a quantity without rounding: the quantity is converted to the price's unit
// exactly (CONVERSION_INEXACT when that does not terminate) and multiplied by the price; the scales add.
func (p UnitPrice) TimesExact(q Quantity) (money.Money, error) {
	c, err := q.ToExact(p.per)
	if err != nil {
		return money.Money{}, err
	}
	return p.price.Times(c.value), nil
}

type unitPriceWire struct {
	Price jsontext.Value `json:"price"`
	Per   string         `json:"per"`
}

// MarshalJSON writes {"price": {"amount": "0.40", "currency": "ZAR"}, "per": "KGM"}.
func (p UnitPrice) MarshalJSON() ([]byte, error) {
	if p.per.IsZero() {
		return nil, decimal.NewError(codeUnknown, "the zero unit")
	}
	price, err := json.Marshal(p.price)
	if err != nil {
		return nil, err
	}
	return json.Marshal(unitPriceWire{Price: price, Per: p.per.code})
}

// UnmarshalJSON reads the object of [UnitPrice.MarshalJSON], with the constructor's validation.
func (p *UnitPrice) UnmarshalJSON(b []byte) error {
	var w unitPriceWire
	if err := json.Unmarshal(b, &w, json.RejectUnknownMembers(true)); err != nil {
		return errMalformed
	}
	var price money.Money
	if err := price.UnmarshalJSON(w.Price); err != nil { // not nested in the decoder: the error keeps the property's path
		return err
	}
	per, err := Parse(w.Per)
	if err != nil {
		return err
	}
	v, err := NewUnitPrice(price, per)
	if err != nil {
		return err
	}
	*p = v
	return nil
}

// JSONSchema implements openapi.SchemaProvider: the object {price, per}, both required.
func (UnitPrice) JSONSchema() openapi.Schema {
	return openapi.Object(
		openapi.Prop{Name: "price", Schema: openapi.Of(openapi.TypeOf[money.Money]())},
		openapi.Prop{Name: "per", Schema: openapi.String()},
	)
}

// DensityFactor is the volumetric factor of a carrier in one of the two forms the trade uses:
// kilograms per cubic metre (167 for air, 333 for road) or the divisor 6000 cubic centimetres per
// kilogram. It is held as an exact ratio of kilograms per cubic metre.
type DensityFactor struct{ num, den decimal.Decimal }

// KgPerCubicMetre returns the factor "167 kg for each m³"; a value that is not greater than zero is
// RATE_INVALID.
func KgPerCubicMetre(kg decimal.Decimal) (DensityFactor, error) {
	return newDensity(kg, decimal.FromInt(1))
}

// Divisor returns the factor "one kilogram for each 6000 cm³" (166.666... kg per m³); a divisor that
// is not greater than zero is RATE_INVALID.
func Divisor(cubicCentimetresPerKg decimal.Decimal) (DensityFactor, error) {
	return newDensity(decimal.FromInt(1_000_000), cubicCentimetresPerKg)
}

func newDensity(num, den decimal.Decimal) (DensityFactor, error) {
	if num.Sign() <= 0 || den.Sign() <= 0 {
		return DensityFactor{}, decimal.NewError(codeRate, "the density factor must be greater than zero")
	}
	return DensityFactor{num: num, den: den}, nil
}

// ChargeableWeight returns the larger of the actual weight and the volume weight (volume x factor), in
// KGM, computed exactly and rounded once to scale with mode. actual must be a mass and volume a
// volume, else DIMENSION_MISMATCH.
func ChargeableWeight(actual, volume Quantity, factor DensityFactor, scale int, mode decimal.RoundingMode) (Quantity, error) {
	if actual.unit.Dimension() != Mass {
		return Quantity{}, decimal.NewError(codeDimension, "the actual weight must be a mass")
	}
	if volume.unit.Dimension() != Volume {
		return Quantity{}, decimal.NewError(codeDimension, "the volume must be a volume")
	}
	actualKg, err := actual.fractionIn(KGM)
	if err != nil {
		return Quantity{}, err
	}
	cubicMetres, err := volume.fractionIn(MTQ)
	if err != nil {
		return Quantity{}, err
	}
	volumeKg := cubicMetres.times(factor.num).dividedBy(factor.den)
	larger := volumeKg
	if actualKg.cmp(volumeKg) >= 0 {
		larger = actualKg
	}
	v, err := larger.round(scale, mode)
	if err != nil {
		return Quantity{}, err
	}
	return NewQuantity(v, KGM), nil
}
