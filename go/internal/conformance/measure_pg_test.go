package conformance_test

import (
	"context"
	"testing"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/decimal"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/money"
	"github.com/flowcatalyst/framework/go/units"
)

// A money value and a quantity are two columns each (measure contract section 5): NUMERIC and CHAR(3) /
// VARCHAR(8). Decimal, Currency and Unit write through driver.Valuer and read back through sql.Scanner,
// and NUMERIC keeps the scale, including the trailing zeros of "3132.480".
func TestMeasureValuesRoundTripThroughPostgres(t *testing.T) {
	t.Parallel()
	db := fwtest.NewDatabase(t)
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, db.URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close(ctx) })
	if _, err := conn.Exec(ctx, `CREATE TABLE charge (
		id int PRIMARY KEY,
		price_amount NUMERIC NOT NULL, price_currency CHAR(3) NOT NULL,
		weight_value NUMERIC NOT NULL, weight_unit VARCHAR(8) NOT NULL)`); err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		amount, currency, value, unit string
	}{
		{"3132.480", "ZAR", "7831.2", "KGM"},
		{"-0.05", "JPY", "0.000001", "MGM"},
		{"0", "BHD", "123456789012345678901234567890.123456789", "MTQ"},
		{"10.00", "USD", "1000", "XPX"},
	}
	for i, c := range cases {
		price := money.MustParse(c.amount, c.currency)
		weight := units.MustParseQuantity(c.value, c.unit)
		if _, err := conn.Exec(ctx, `INSERT INTO charge VALUES ($1, $2, $3, $4, $5)`,
			i, price.Amount(), price.Currency(), weight.Value(), weight.Unit()); err != nil {
			t.Fatalf("insert %v: %v", c, err)
		}
		var amount, value decimal.Decimal
		var currency money.Currency
		var unit units.Unit
		if err := conn.QueryRow(ctx, `SELECT price_amount, price_currency, weight_value, weight_unit FROM charge WHERE id = $1`, i).
			Scan(&amount, &currency, &value, &unit); err != nil {
			t.Fatalf("select %v: %v", c, err)
		}
		if got := money.New(amount, currency); !got.Equal(price) || amount.String() != c.amount {
			t.Errorf("money %v: read %v (amount text %q)", c, got, amount)
		}
		if got := units.NewQuantity(value, unit); !got.Equal(weight) || value.String() != c.value {
			t.Errorf("quantity %v: read %v (value text %q)", c, got, value)
		}
	}
}
