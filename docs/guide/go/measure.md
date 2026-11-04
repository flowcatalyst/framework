<!-- types: Decimal, RoundingMode, Currency, Money, ExchangeRate, Unit, Dimension, Registry, Quantity, Packaging, UnitPrice, DensityFactor -->
# Go: money and units

The packages `decimal`, `money` and `units` of the core module implement the
[measure contract](../../contracts/measure.md): exact decimals, money in a currency, quantities in
units of measure, packaging, prices per unit and chargeable weight. They are standard library only
(`math/big`) beyond the framework's own `usecase` (errors) and `httpfw/openapi` (schemas), so they
add no module requirement. The Java module is `framework-measure` ([Money and units](../measure.md));
both give the same digits, and the vectors in `conformance/measure/`, written by Java, are compared
by `go/internal/conformance` (`TestMeasure...Vector`).

## decimal

`decimal.Decimal` is the Go counterpart of `BigDecimal`: an unscaled `big.Int` and a scale, with the same
rules. Text is plain decimal only and keeps its scale; there is no unrounded division.

From `decimal/example_test.go` (`ExampleParse` and `ExampleDecimal_Div`; `1e3`, `+1` and `1,000` are
`DECIMAL_INVALID`):

```go
d := decimal.MustParse("3132.40")
fmt.Println(d, d.Scale())
_, err := decimal.Parse("1e3")
fmt.Println(err)
// Output:
// 3132.40 2
// VALIDATION DECIMAL_INVALID: not a plain decimal number
```

```go
q, err := decimal.MustParse("1").Div(decimal.MustParse("3"), 4, decimal.HalfEven)
fmt.Println(q, err)
// Output: 0.3333 <nil>
```

`Add` and `Sub` take the larger scale, `Mul` adds the scales. `Round(scale, mode)` is Java's `setScale`.
The modes are `decimal.Up`, `Down`, `Ceiling`, `Floor`, `HalfUp`, `HalfDown` and `HalfEven`
(`ParseRoundingMode("HALF_EVEN")` reads the Java names). `Div(o, scale, mode)` is
`BigDecimal.divide(o, scale, mode)`; `DivExact` succeeds only when the quotient terminates and gives it
the smallest scale. `Cmp` and `Equal` are numeric: `2.0` equals `2.00`.

A Decimal marshals as text, so JSON writes a string and refuses a number; it implements
`sql.Scanner` and `driver.Valuer` (read NUMERIC as text) and `openapi.SchemaProvider`
(`{"type": "string", "format": "decimal"}`). The zero value is 0.

Every failure is a `*decimal.Error`, a leaf type of this package (the measure packages import only the
standard library, each other and `httpfw/openapi`; not `usecase`): `Code()` is `DECIMAL_INVALID`,
`DIVISION_BY_ZERO`, or one of the other codes of the contract. It has `Status()` 400, `Message()` and
`Details()`, so it satisfies `httpfw.ServiceError` without importing it, and a bad value in a request
body is a 400 with the contract's code at the property's path (`rate: CURRENCY_UNKNOWN: unknown
currency code`), as in Java. Each package exports its codes as
constants (`decimal.CodeDecimalInvalid`, `money.CodeCurrencyMismatch`, `units.CodeUnitUnknown`, ...).

## money

`money.Currency` is an ISO 4217 code with its minor units (`CurrencyOf("ZAR")`, `MinorUnits()`), from the
embedded `iso4217.json` (a byte-identical copy of the shared file, listed in
`go/internal/conformance/shared-files.txt`). `money.Money` holds a `Decimal` and a `Currency`; compare
with `Equal`, not `==`.

```go
total := money.MustParse("0.40", "ZAR").Times(decimal.MustParse("7831.2"))
fmt.Println(total)
rounded := total.Round(decimal.HalfEven)
fmt.Println(rounded)
// Output:
// 3132.480 ZAR
// 3132.48 ZAR
```

`Plus`, `Minus`, `Cmp` and `money.Sum` return an error for two currencies (`CURRENCY_MISMATCH`);
`money.Sum` of an empty list is `SUM_EMPTY`.
`Allocate(ratios, mode)` splits an amount so the parts add up exactly, as the Java page describes:

```go
ratios := []decimal.Decimal{decimal.MustParse("1"), decimal.MustParse("1"), decimal.MustParse("1")}
parts, err := money.MustParse("100.00", "ZAR").Allocate(ratios, decimal.HalfEven)
fmt.Println(parts, err)
// Output: [33.34 ZAR 33.33 ZAR 33.33 ZAR] <nil>
```

`money.NewExchangeRate(from, to, rate, asOf)` and `Convert(m, mode)` convert and round to the
target currency. Money marshals as `{"amount": "3132.48", "currency": "ZAR"}`; reading refuses a JSON
number, an unknown member and a missing one, with the contract's codes.

## units

`units.Unit` is a built-in unit by its Recommendation 20 code: the variables `units.KGM`, `units.TNE`,
`units.MTQ`, `units.C62`, `units.XPX` and so on (all 42 of the table in the contract), or
`units.Parse("kg")` for a code or alias. A `units.Registry` (`units.Builtin().With("KILO", units.KGM)`)
adds a partner's spellings. A `Quantity` converts within its dimension (`units.Mass`, `Length`, `Area`,
`Volume`, `Count`, `Temperature`; packaging units have `PackagingDim`):

```go
q := units.MustParseQuantity("7831.2", "KGM")
got, err := q.To(units.TNE, 6, decimal.HalfEven)
fmt.Println(got, err)
_, err = q.ToExact(units.LBR)
fmt.Println(err)
// Output:
// 7.831200 TNE <nil>
// VALIDATION CONVERSION_INEXACT: the exact result does not terminate
```

`Plus`, `Minus`, `Times` and `Cmp` follow the contract; temperatures convert but are not added
(`TEMPERATURE_NOT_ADDITIVE`).

`units.NewPackaging(units.C62)` then `Level(units.XCT, 24)` and `Level(units.XPX, 40)` build a
`Packaging`; `Convert`, `ConvertExact` and `Breakdown` work between its levels. `units.NewUnitPrice(price,
per)` is a `UnitPrice`, with `Times(q, scale, mode)` (one rounding) and `TimesExact(q)`. Chargeable
weight is a function, with a `DensityFactor` from `units.KgPerCubicMetre` or `units.Divisor`:

```go
air, _ := units.KgPerCubicMetre(decimal.MustParse("167"))
w, err := units.ChargeableWeight(units.MustParseQuantity("100", "KGM"), units.MustParseQuantity("1", "MTQ"), air, 0, decimal.HalfEven)
fmt.Println(w, err)
// Output: 167 KGM <nil>
```

A packaging breakdown and a price per unit (`ExamplePackaging`, `ExampleUnitPrice_TimesExact`):

```go
p, _ := units.NewPackaging(units.C62)
p, _ = p.Level(units.XCT, 24)
p, _ = p.Level(units.XPX, 40)
parts, err := p.Breakdown(units.MustParseQuantity("1000", "C62"))
fmt.Println(parts, err)
// Output: [1 XPX 1 XCT 16 C62] <nil>
```

```go
price, _ := units.NewUnitPrice(money.MustParse("0.40", "ZAR"), units.KGM)
charge, err := price.TimesExact(units.MustParseQuantity("7.8312", "TNE"))
fmt.Println(charge, err)
// Output: 3132.480 ZAR <nil>
```

All of these marshal as the contract's wire forms and document themselves in OpenAPI
(`openapi.SchemaProvider`: components `Money`, `Quantity` and `UnitPrice`, strings for `Currency` and
`Unit`); `Currency` and `Unit` also implement `sql.Scanner` and `driver.Valuer` for text columns. The
two-column database convention (`<field>_amount` and `<field>_currency`, `<field>_value` and
`<field>_unit`) is composed by the caller.
