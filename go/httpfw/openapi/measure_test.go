package openapi_test

import (
	"reflect"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/decimal"
	"github.com/flowcatalyst/framework/go/money"
	"github.com/flowcatalyst/framework/go/units"
)

// The measure types document themselves (measure contract section 5): the same components, byte for
// byte after the key order, as Java's emitter writes for Money, Quantity and UnitPrice.
func TestMeasureTypesAreDocumented(t *testing.T) {
	t.Parallel()
	type shipment struct {
		Charge   money.Money     `json:"charge"`
		Weight   units.Quantity  `json:"weight"`
		Rate     units.UnitPrice `json:"rate"`
		Currency money.Currency  `json:"currency"`
		Unit     units.Unit      `json:"unit"`
		Amount   decimal.Decimal `json:"amount"`
	}
	o := op("o", "GET", "/o")
	o.Response = reflect.TypeFor[shipment]()
	got := mustEmit(t, input(o))
	for _, want := range []string{
		`"Money": {
        "type": "object",
        "properties": {
          "amount": {
            "type": "string",
            "format": "decimal"
          },
          "currency": {
            "type": "string",
            "pattern": "^[A-Z]{3}$"
          }
        },
        "required": [
          "amount",
          "currency"
        ],
        "additionalProperties": false
      }`,
		`"UnitPrice": {`,
		`"Quantity": {`,
		`"$ref": "#/components/schemas/Money"`,
		`"$ref": "#/components/schemas/UnitPrice"`,
	} {
		if !strings.Contains(got, want) {
			t.Errorf("document lacks %q:\n%s", want, got)
		}
	}
}
