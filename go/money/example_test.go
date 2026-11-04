package money_test

import (
	"fmt"
	"time"

	"github.com/flowcatalyst/framework/go/decimal"
	"github.com/flowcatalyst/framework/go/money"
)

func ExampleMoney_Round() {
	total := money.MustParse("0.40", "ZAR").Times(decimal.MustParse("7831.2"))
	fmt.Println(total)
	rounded := total.Round(decimal.HalfEven)
	fmt.Println(rounded)
	// Output:
	// 3132.480 ZAR
	// 3132.48 ZAR
}

func ExampleMoney_Allocate() {
	ratios := []decimal.Decimal{decimal.MustParse("1"), decimal.MustParse("1"), decimal.MustParse("1")}
	parts, err := money.MustParse("100.00", "ZAR").Allocate(ratios, decimal.HalfEven)
	fmt.Println(parts, err)
	// Output: [33.34 ZAR 33.33 ZAR 33.33 ZAR] <nil>
}

func ExampleMoney_Plus() {
	_, err := money.MustParse("1", "ZAR").Plus(money.MustParse("1", "USD"))
	fmt.Println(err)
	// Output: VALIDATION CURRENCY_MISMATCH: the currencies differ
}

func ExampleExchangeRate_Convert() {
	rate, _ := money.NewExchangeRate(money.MustCurrency("USD"), money.MustCurrency("ZAR"), decimal.MustParse("18.3456"), time.Time{})
	got, err := rate.Convert(money.MustParse("100.00", "USD"), decimal.HalfEven)
	fmt.Println(got, err)
	// Output: 1834.56 ZAR <nil>
}
