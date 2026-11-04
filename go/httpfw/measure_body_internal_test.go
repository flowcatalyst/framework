package httpfw

import (
	"encoding/json/v2"
	"testing"

	"github.com/flowcatalyst/framework/go/money"
	"github.com/flowcatalyst/framework/go/units"
)

type shipmentBody struct {
	Charge   money.Money     `json:"charge"`
	Weight   units.Quantity  `json:"weight"`
	Rate     units.UnitPrice `json:"rate"`
	Currency money.Currency  `json:"currency"`
	Unit     units.Unit      `json:"unit"`
}

// A measure value that refuses its text is a 400 with the contract's code at the property's path: the
// same detail strings as Java's Json.toValidation (framework-http MeasureWireTest.theSameDetailsAsGo).
func TestMeasureValuesFailWithTheirContractCode(t *testing.T) {
	t.Parallel()
	for _, c := range []struct{ body, field, detail string }{
		{`{"charge":{"amount":"x","currency":"ZAR"}}`, "charge", "DECIMAL_INVALID: not a plain decimal number"},
		{`{"charge":{"amount":"1","currency":"ZZZ"}}`, "charge", "CURRENCY_UNKNOWN: unknown currency code"},
		{`{"weight":{"value":"1","unit":"furlong"}}`, "weight", "UNIT_UNKNOWN: unknown unit"},
		{`{"rate":{"price":{"amount":"1.0","currency":"ZZZ"},"per":"KGM"}}`, "rate", "CURRENCY_UNKNOWN: unknown currency code"},
		{`{"rate":{"price":{"amount":"1","currency":"ZAR"},"per":"XPX"}}`, "rate", "NO_CONVERSION: a price per packaging unit has no conversion"},
		{`{"currency":"zar"}`, "currency", "CURRENCY_UNKNOWN: unknown currency code"},
		{`{"unit":"furlong"}`, "unit", "UNIT_UNKNOWN: unknown unit"},
		// not a contract failure: the framework's own wording
		{`{"charge":{"amount":3132.48,"currency":"ZAR"}}`, "charge", "INVALID_JSON: wrong type or format"},
	} {
		var b shipmentBody
		err := json.Unmarshal([]byte(c.body), &b, jsonOptions)
		if err == nil {
			t.Errorf("%s: accepted", c.body)
			continue
		}
		e := invalidJSON(err)
		d := e.Details()
		if e.Status() != 400 || e.Code() != "VALIDATION" || len(d) != 1 || d[c.field] != c.detail {
			t.Errorf("%s: %d %s %v, want %s: %s", c.body, e.Status(), e.Code(), d, c.field, c.detail)
		}
	}
}
