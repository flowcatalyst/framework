package decimal_test

import (
	"fmt"

	"github.com/flowcatalyst/framework/go/decimal"
)

func ExampleParse() {
	d := decimal.MustParse("3132.40")
	fmt.Println(d, d.Scale())
	_, err := decimal.Parse("1e3")
	fmt.Println(err)
	// Output:
	// 3132.40 2
	// VALIDATION DECIMAL_INVALID: not a plain decimal number
}

func ExampleDecimal_Div() {
	q, err := decimal.MustParse("1").Div(decimal.MustParse("3"), 4, decimal.HalfEven)
	fmt.Println(q, err)
	// Output: 0.3333 <nil>
}

func ExampleDecimal_Round() {
	fmt.Println(decimal.MustParse("2.5").Round(0, decimal.HalfEven), decimal.MustParse("2.5").Round(0, decimal.HalfUp))
	// Output: 2 3
}

func ExampleDecimal_DivExact() {
	a, _ := decimal.MustParse("7831.20").DivExact(decimal.MustParse("1000"))
	_, err := decimal.MustParse("1").DivExact(decimal.MustParse("3"))
	fmt.Println(a, err)
	// Output: 7.8312 VALIDATION CONVERSION_INEXACT: the exact result does not terminate
}
