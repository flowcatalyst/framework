# Plan: money, currency and units of measure

Status: ruled 2026-11-03; the contract is [`contracts/measure.md`](contracts/measure.md), which wins
where the two differ. Owner rulings on section 9: (1) the full first cut including packaging,
(2) temperature in, (3) UN/ECE Rec 20 codes on the wire (the supply-chain data standard; symbols
for display and accepted as input), (4) money as an object with a string amount (the PayPal /
ISO 20022 shape; integer minor units cannot hold rates or unrounded amounts), (5) one Java module
`framework-measure`, (6) our own Go decimal (shopspring/decimal rejected: package-global division
precision and JSON switches, and `String()` drops trailing zeros where `BigDecimal` keeps the
scale), (7) display formatting belongs to the i18n work.

A small value-type capability for logistics and supply chain: exact decimal amounts, money in a
currency, quantities in units with the standard conversions, and prices per unit. Java and Go
implement one contract (written first, as for every capability) and are checked against the same
conformance vectors, so a charge computed by a Java service and a Go service is the same to the
last digit.

It is deliberately small. It is not JSR-354/Moneta, not a units-of-measurement framework
(Indriya, UCUM), and has no exchange-rate provider, tax engine or rating engine. Those are
application concerns that sit on top of these types.

## 1. What exists today

- Neither framework has a money, currency, unit or rounding type. The examples store integer cents
  (`go/examples/orders-demo/domain/domain.go` `Cents`; Java `NewOrderForm` / `Order.totalCents`).
- Java has `BigDecimal`, and typed operations already put it on the wire as a JSON string with
  schema `{"type":"string","format":"decimal"}` (contracts/openapi.md 5.1, ruling 6). JDBC and
  checked SQL map `numeric` to `BigDecimal`.
- Go has no decimal type at all (go/milestones.md says `Decimal` is not provided). No decimal
  library is in any framework or platform go.mod. The rule is standard library first
  (go/design.md).
- Real integration code (mono-repo `bevco/functions/yard-trip-processor`) carries weights in kg to
  four decimals (17264.8407), volumes in m3, distances in km, pallet counts, and ZAR charges with
  a per-kg rate basis (`CPKG`, rate 0.4). It parses all of them to `double` today.

## 2. Shape

Five types, the same names in both languages.

| Type | Holds | Notes |
|---|---|---|
| `Decimal` | an exact base-10 number: unscaled integer + scale | Go only; Java uses `BigDecimal` |
| `Currency` | an ISO 4217 alphabetic code and its minor units | from the framework's own table |
| `Money` | `Decimal` amount + `Currency` | arithmetic only within one currency |
| `Unit` | a unit: code, dimension, factor (and offset) to the dimension's base unit | from the framework's own table |
| `Quantity` | `Decimal` value + `Unit` | converts within a dimension |
| `UnitPrice` | `Money` per `Unit` (a rate: 0.40 ZAR per kg) | `price × quantity → Money` |

Plus `RoundingMode` (HALF_UP, HALF_EVEN, DOWN, UP, FLOOR, CEILING: the `BigDecimal` names) and
`ExchangeRate` (section 5).

Placement:
- Go: packages in the core module, `go/decimal`, `go/money`, `go/units`. They import only the
  standard library (`math/big`), so they add no dependency.
- Java: one module, `framework-measure`, with packages `...measure.money` and `...measure.units`.
  It depends on nothing but the JDK and framework-core.

## 3. Decimal (Go)

Our own small type on `math/big`, the Go counterpart of `BigDecimal`: an unscaled `big.Int` and an
`int32` scale. About 400 lines.

- Parse and format plain decimal text only ("17264.8407", "-0.40"). No exponent, no grouping, no
  locale. Formatting keeps the scale ("3132.40" stays "3132.40").
- `Add`, `Sub`, `Mul` are exact (the result's scale follows `BigDecimal`'s rules).
- There is no plain `Div`. Division always names a scale and a rounding mode:
  `a.Div(b, scale, mode)`, as `BigDecimal.divide(b, scale, mode)` does. An inexact division can
  never happen silently.
- `Round(scale, mode)`, `Cmp`, `Sign`, `IsZero`, `Neg`, `Abs`.
- `MarshalText`/`UnmarshalText` (JSON as a string), `sql.Scanner`/`driver.Valuer` (Postgres
  `NUMERIC` as text), and an OpenAPI `SchemaProvider` that gives the Java schema
  (`string`, `format: decimal`).

Why not a library: shopspring/decimal (MIT) divides to a package-global precision unless told
otherwise, which is the silent inexact division the contract forbids; cockroachdb/apd is a full
General Decimal Arithmetic implementation, much more than we need. A small type that matches
`BigDecimal` exactly is the point of the conformance vectors, and is easier to keep identical than
someone else's semantics. This is the go/design.md exception the other way round: writing it is
better here.

## 4. Currency and Money

- `Currency` is an ISO 4217 alphabetic code with its minor units (ZAR 2, USD 2, JPY 0, BHD 3,
  KWD 3...). The table is one data file in the framework (`conformance/iso4217.json`, generated from
  the ISO list), embedded by both languages, so both always agree. Java does not use
  `java.util.Currency`'s table, whose contents follow the JDK version; `Currency.toJdk()` converts
  for interop.
- `Money` keeps its full precision: `0.40 ZAR/kg × 7831.2 kg` is `3132.480 ZAR` until something
  rounds it. Rounding to the currency is explicit: `m.Round(mode)` rounds to the currency's minor
  units, `m.Round(scale, mode)` to any scale. Nothing rounds implicitly.
- `Add`, `Sub`, `Neg`, `Abs`, `Times(Decimal)`, `Cmp`, and `Sum` over a list. Mixing currencies is
  an error (`CURRENCY_MISMATCH`), never a conversion.
- `Allocate(ratios..., mode)` splits an amount across consignments, legs or invoice lines so the
  parts add up exactly to the whole (the remainder goes to the parts in order, one minor unit at a
  time). Freight cost allocation needs this constantly, and it is where hand-written code loses
  cents.
- Wire: `{"amount": "3132.48", "currency": "ZAR"}`. The amount is a string, so no JSON number ever
  passes through a float. Database: two columns, `amount NUMERIC NOT NULL`,
  `currency CHAR(3) NOT NULL`, named `<field>_amount` / `<field>_currency` by convention. Java
  checked SQL maps one column to one value today, so `Row.money("price_amount", "price_currency")`
  and a bind helper are added; Go gets sqlc override examples.

## 5. Exchange rates

Only the value type and the arithmetic, no provider:
`ExchangeRate{from, to, rate Decimal, asOf}` and `rate.Convert(money, mode)` returning money in
`to`, rounded to that currency. Where rates come from (a bank feed, a contract rate, a daily table)
belongs to the application, which stores the rate it used next to the converted amount.

## 6. Units and Quantity

### Codes

Units are identified by their UN/ECE Recommendation 20 common code: the codes EDIFACT, UBL,
Peppol e-invoicing and GS1 use (KGM kilogram, TNE tonne, MTQ cubic metre, LTR litre, KMT kilometre,
C62 one/each). The wire carries that code. Parsing also accepts the common symbols (`kg`, `t`,
`m3`, `l`, `km`, `lb`) and an application can register extra aliases for a partner's own codes.
Printing a symbol for people is a separate call (`unit.Symbol()`).

### Dimensions and the first table

| Dimension | Base | Units |
|---|---|---|
| mass | kg | g, kg, t (tonne), lb, oz, short ton, long ton |
| length | m | mm, cm, m, km, in, ft, yd, mi, nautical mile |
| area | m2 | m2, ft2 (warehouse and floor space) |
| volume | m3 | cm3, l, ml, m3, ft3, in3, US gallon, imperial gallon |
| count | each (C62) | each, dozen |
| temperature | K | °C, °F, K (cold chain) |

Every factor is the exact legal definition, never a rounded float: 1 lb = 0.45359237 kg,
1 in = 0.0254 m, 1 US gallon = 3.785411784 l, 1 imperial gallon = 4.54609 l. So converting is
an exact multiplication, and rounding afterwards is explicit, as with money.

- `q.To(unit)` converts within a dimension; across dimensions it is an error
  (`DIMENSION_MISMATCH`).
- `Add`/`Sub` convert the right-hand side to the left-hand side's unit first; `Cmp` compares across
  units of one dimension.
- Temperature is affine (°C = K − 273.15), so it converts but does not add: adding two
  temperatures is an error. The table carries an offset for it.
- Wire: `{"value": "7831.2", "unit": "KGM"}`. Database: `<field>_value NUMERIC`,
  `<field>_unit VARCHAR(3)`.

### Packages: pallets, cartons, cases

How many kilograms or eaches a pallet holds depends on the product, so the framework does not
pretend to know it. Packaging units (UN/ECE Recommendation 21: PX pallet, CT carton, CS case)
are count units with no built-in conversion. A `Packaging` value (`1 PX = 40 CT`,
`1 CT = 24 C62`) supplied by the application, per product, converts between them. That keeps the
framework honest and the item master where it belongs.

## 7. Prices per unit and chargeable weight

- `UnitPrice{price Money, per Unit}`. `p.Times(quantity)` converts the quantity to the price's unit,
  then multiplies (`0.40 ZAR/KGM × 7.8312 TNE = 3132.480 ZAR`), unrounded, as section 4 says.
- `ChargeableWeight(actual Quantity, volume Quantity, factor)`: the larger of the actual weight
  and the volume weight. Air, road and sea each use a factor (167 kg/m3, 333 kg/m3, 1000 kg/m3,
  or the "1:6000 cm3/kg" form). It is one function, it is the calculation every freight rating
  repeats, and it is easy to get wrong with floats.

## 8. Conformance and tests

- `conformance/measure/*.json` vectors, written from Java's `BigDecimal` and compared by Go, as
  for the existing vectors: parse/format, every rounding mode at the half-way cases, division
  with scale, money allocation, every unit conversion in the table, unit-price multiplication and
  chargeable weight.
- Property tests in both languages: conversion there and back is the identity at enough scale,
  `Allocate` parts always sum to the whole, no operation ever goes through a float.

## 9. Rulings needed

1. **Scope of the first cut.** Proposed: Decimal (Go), Currency, Money, ExchangeRate (value only),
   Unit, Quantity, UnitPrice, ChargeableWeight. Packaging (6) in or later?
2. **Temperature**: in the first cut (cold chain) or left out?
3. **Wire unit code**: UN/ECE Rec 20 codes (`KGM`) as proposed, or symbols (`kg`)? Codes are what
   EDI and e-invoicing exchange; symbols are friendlier in a JSON payload a person reads.
4. **Money JSON**: the object `{"amount","currency"}` as proposed, or a single string
   `"3132.48 ZAR"`?
5. **Java module name**: `framework-measure`, or two modules (`framework-money`,
   `framework-units`)?
6. **The Go decimal**: our own on `math/big` as proposed, or accept shopspring/decimal?
7. **Display formatting**: locale-aware formatting ("R 3 132,48" in en-ZA) belongs with the
   internationalisation work (docs/i18n-plan.md). Proposed: these types only produce plain
   decimal text; the UI kits format.
