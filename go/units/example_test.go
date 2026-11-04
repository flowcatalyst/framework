package units_test

import (
	"fmt"

	"github.com/flowcatalyst/framework/go/decimal"
	"github.com/flowcatalyst/framework/go/money"
	"github.com/flowcatalyst/framework/go/units"
)

func ExampleQuantity_To() {
	q := units.MustParseQuantity("7831.2", "KGM")
	got, err := q.To(units.TNE, 6, decimal.HalfEven)
	fmt.Println(got, err)
	_, err = q.ToExact(units.LBR)
	fmt.Println(err)
	// Output:
	// 7.831200 TNE <nil>
	// VALIDATION CONVERSION_INEXACT: the exact result does not terminate
}

func ExampleRegistry_With() {
	r, _ := units.Builtin().With("KILO", units.KGM)
	u, err := r.Parse("kilo")
	fmt.Println(u, err)
	// Output: KGM <nil>
}

func ExamplePackaging() {
	p, _ := units.NewPackaging(units.C62)
	p, _ = p.Level(units.XCT, 24)
	p, _ = p.Level(units.XPX, 40)
	parts, err := p.Breakdown(units.MustParseQuantity("1000", "C62"))
	fmt.Println(parts, err)
	// Output: [1 XPX 1 XCT 16 C62] <nil>
}

func ExampleUnitPrice_TimesExact() {
	price, _ := units.NewUnitPrice(money.MustParse("0.40", "ZAR"), units.KGM)
	charge, err := price.TimesExact(units.MustParseQuantity("7.8312", "TNE"))
	fmt.Println(charge, err)
	// Output: 3132.480 ZAR <nil>
}

func ExampleChargeableWeight() {
	air, _ := units.KgPerCubicMetre(decimal.MustParse("167"))
	w, err := units.ChargeableWeight(units.MustParseQuantity("100", "KGM"), units.MustParseQuantity("1", "MTQ"), air, 0, decimal.HalfEven)
	fmt.Println(w, err)
	// Output: 167 KGM <nil>
}
