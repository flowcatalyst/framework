# Contract: money, currency and units of measure

Status: owner rulings 2026-11-03 (plan and reasoning: [`../money-and-units-plan.md`](../money-and-units-plan.md)).
Java: module `framework-measure`. Go: packages `decimal`, `money`, `units` in the core module
`github.com/flowcatalyst/framework/go`, standard library only.

Exact values for logistics: decimal numbers, money in a currency, quantities in units of
measure with the standard conversions, packaging levels, prices per unit and chargeable weight.
Both languages give the same result to the last digit; the conformance vectors in
`conformance/measure/` (written by Java, compared by Go) are the proof.

Rulings (owner, 2026-11-03): the Go decimal is our own, not a library; units travel as UN/ECE
Recommendation 20 codes; money travels as an object with a string amount; packaging and
temperature are in the first cut.

## 1. Decimal

An exact base-10 number: an unscaled integer and a scale (value = unscaled × 10^-scale). Java uses
`java.math.BigDecimal`; Go has `decimal.Decimal` on `math/big` with the same semantics.

- **Text**: plain decimal only, `-?[0-9]+(\.[0-9]+)?`. No exponent, grouping, `+`, or locale on input
  or output. Formatting keeps the scale: `3132.40` prints `3132.40`, `0.0` prints `0.0`
  (Java: `toPlainString()`). Leading zeros on input are accepted and dropped (`007.5` is `7.5`).
- **Scale of results** (as `BigDecimal`): `add`/`subtract` take the larger scale; `multiply` adds the
  scales.
- **No unrounded division.** `divide(b, scale, mode)` only. Division by zero is the error
  `DIVISION_BY_ZERO`.
- **Rounding modes**: `UP`, `DOWN`, `CEILING`, `FLOOR`, `HALF_UP`, `HALF_DOWN`, `HALF_EVEN`, with
  `BigDecimal`'s meanings. `round(scale, mode)` (Java `setScale`) may raise or lower the scale.
- `compare` is numeric (`2.0` equals `2.00`); `equals` in Go is `Cmp == 0`. Java keeps
  `BigDecimal.equals` semantics; framework types in this contract compare numerically (2.3).
- Go also: `Sign`, `IsZero`, `Neg`, `Abs`, `Scale`, `MarshalText`/`UnmarshalText`,
  `sql.Scanner`/`driver.Valuer` (Postgres `NUMERIC` as text), an OpenAPI `SchemaProvider`
  (`{"type":"string","format":"decimal"}`), `MustParse` for constants.

## 2. Currency and Money

### 2.1 Currency

An ISO 4217 alphabetic code (`^[A-Z]{3}$`) and its minor units (ZAR 2, JPY 0, BHD 3). The table
is `conformance/measure/iso4217.json`, generated once from JDK 25's `java.util.Currency` (codes with
a fraction of −1, such as XAU, are left out) and embedded by both languages. An unknown code is the
error `CURRENCY_UNKNOWN`. Java: `Currency.of("ZAR")`, `Currency.toJdk()`. Go: `money.CurrencyOf`.

### 2.2 Money

`Money(amount Decimal, currency Currency)`, immutable.

- `plus`, `minus` (same currency only, else `CURRENCY_MISMATCH`), `negate`, `abs`, `times(Decimal)`,
  `isZero`, `signum`, `compareTo` (same currency only), `sum(list)` (non-empty, one currency).
- **Precision is kept**: no operation rounds unless asked. `round(mode)` rounds to the currency's
  minor units; `round(scale, mode)` to any scale.
- `allocate(ratios, mode)`: splits the amount into parts proportional to the non-negative ratios
  (Decimals, at least one non-zero). The amount is first rounded to the currency's minor units
  with `mode`; each part is `floor(amount × ratio / total)` at the minor-unit scale; the leftover
  minor units are then given one each to the parts in order. The parts always sum exactly to the
  rounded amount. A negative amount is allocated as its absolute value and every part negated.
- Equality is numeric amount + currency (`10.0 ZAR` equals `10.00 ZAR`).

### 2.3 ExchangeRate

`ExchangeRate(from Currency, to Currency, rate Decimal > 0, asOf Instant)`.
`convert(money, mode)`: `money.currency` must be `from` (else `CURRENCY_MISMATCH`); the result is
`amount × rate` in `to`, rounded to `to`'s minor units with `mode`. There is no rate provider.

## 3. Units

### 3.1 Unit

A unit has a **code** (UN/ECE Rec 20, the wire form), a **symbol** (for people), a **dimension**,
and its definition against the dimension's base unit: `base = (value + offset) × factor`, where
`factor` is an exact fraction `p/q` of two decimals and `offset` an exact decimal (zero except for
temperature).

| Dimension | Code | Symbol | factor to base | offset |
|---|---|---|---|---|
| mass (base KGM) | MGM | mg | 0.000001 | |
| | GRM | g | 0.001 | |
| | KGM | kg | 1 | |
| | TNE | t | 1000 | |
| | LBR | lb | 0.45359237 | |
| | ONZ | oz | 0.028349523125 | |
| | STN | short ton | 907.18474 | |
| | LTN | long ton | 1016.0469088 | |
| length (base MTR) | MMT | mm | 0.001 | |
| | CMT | cm | 0.01 | |
| | MTR | m | 1 | |
| | KMT | km | 1000 | |
| | INH | in | 0.0254 | |
| | FOT | ft | 0.3048 | |
| | YRD | yd | 0.9144 | |
| | SMI | mi | 1609.344 | |
| | NMI | nmi | 1852 | |
| area (base MTK) | MTK | m² | 1 | |
| | FTK | ft² | 0.09290304 | |
| volume (base MTQ) | CMQ | cm³ | 0.000001 | |
| | MLT | ml | 0.000001 | |
| | LTR | l | 0.001 | |
| | MTQ | m³ | 1 | |
| | INQ | in³ | 0.000016387064 | |
| | FTQ | ft³ | 0.028316846592 | |
| | GLL | US gal | 0.003785411784 | |
| | GLI | imp gal | 0.00454609 | |
| count (base C62) | C62 | each | 1 | |
| | EA | each | 1 | |
| | H87 | piece | 1 | |
| | DZN | dozen | 12 | |
| temperature (base KEL) | KEL | K | 1 | 0 |
| | CEL | °C | 1 | 273.15 |
| | FAH | °F | 5/9 | 459.67 |
| packaging (no base) | XPX | pallet | none | |
| | XCT | carton | none | |
| | XCS | case | none | |
| | XBX | box | none | |
| | XPK | pack | none | |
| | XBG | bag | none | |
| | XCR | crate | none | |
| | XDR | drum | none | |

The table is `conformance/measure/units.json`, embedded by both languages.

- **Parsing** (`Unit.of` / `units.Parse`): the code, case-sensitive, or an alias. Built-in aliases:
  every symbol above in ASCII (`m2`, `m3`, `cm3`, `in3`, `ft2`, `ft3`, `degC`, `degF`), plus `kgs`,
  `lbs`, `L`, `ltr`, `pcs`, `ea`, `each`, and the Rec 21 two-letter packaging codes (`PX`, `CT`, `CS`,
  `BX`, `PK`, `BG`, `CR`, `DR`). Aliases are case-insensitive. An application may register more on
  a `UnitRegistry` (a partner's own codes); the built-in table cannot be changed. Unknown is
  `UNIT_UNKNOWN`.
- Output is always the code; `symbol()` is for display.

### 3.2 Quantity

`Quantity(value Decimal, unit Unit)`, immutable.

- `to(unit, scale, mode)`: converts within the dimension, computed exactly and rounded **once**:
  `result = ((v + oF)·pF·qT − oT·qF·pT) / (qF·pT)` with `F` the from-unit and `T` the to-unit,
  divided to `scale` with `mode`. Across dimensions: `DIMENSION_MISMATCH`. Packaging units have no
  conversion (`NO_CONVERSION`) except through a `Packaging` (3.3).
- `toExact(unit)`: the same, unrounded; `CONVERSION_INEXACT` when the result does not terminate
  (kg → lb). The result's scale is the smallest that holds it exactly.
- `plus`, `minus`: the right-hand side is converted to the left's unit with `toExact`, so they can
  fail with `CONVERSION_INEXACT`. Temperatures cannot be added or subtracted
  (`TEMPERATURE_NOT_ADDITIVE`).
- `times(Decimal)` (not for temperature), `compareTo` (any two units of one dimension, compared
  exactly), `isZero`, `signum`.
- Equality is numeric value + unit (`1000 GRM` does not equal `1 KGM`; `compareTo` says they are the
  same amount).

### 3.3 Packaging

The levels of one product: `Packaging.of(C62).level(XCT, 24).level(XPX, 40)` means a carton holds
24 each and a pallet holds 40 cartons. Each level names a unit and how many of the level below
it holds (a positive integer); the first unit may be any count or mass unit (the product's
smallest handling unit, e.g. `C62` or `KGM`).

- `convert(quantity, unit, scale, mode)` and `convertExact(quantity, unit)` between any two levels
  (going down multiplies; going up divides). A quantity whose unit is not a level is
  `UNIT_NOT_IN_PACKAGING`.
- `breakdown(quantity)`: the quantity as whole units from the largest level down, with any
  fractional rest at the smallest level: 1000 each → `[1 XPX, 1 XCT, 16 C62]`.

## 4. Prices per unit and chargeable weight

- `UnitPrice(price Money, per Unit)`, not packaging, not temperature.
  `times(quantity, scale, mode)` converts the quantity to `per` exactly, multiplies, and rounds the
  money once to `scale` with `mode`; `timesExact(quantity)` is unrounded (`CONVERSION_INEXACT`
  possible). `0.40 ZAR/KGM × 7.8312 TNE = 3132.480 ZAR`.
- `chargeableWeight(actual, volume, factor, scale, mode)`: `actual` is mass, `volume` is volume,
  `factor` is a `DensityFactor`: `DensityFactor.kgPerCubicMetre(167)` or
  `DensityFactor.divisor(6000)` (cm³ per kg). The result is the larger of `actual` and
  `volume × factor`, in `KGM`, computed exactly and rounded once.

## 5. Wire, database and OpenAPI

- Money: `{"amount": "3132.48", "currency": "ZAR"}`. Quantity: `{"value": "7831.2", "unit": "KGM"}`.
  UnitPrice: `{"price": {"amount": "0.40", "currency": "ZAR"}, "per": "KGM"}`. Currency and Unit
  alone: their code as a string. Amounts and values are always strings, never JSON numbers, and
  carry their scale.
- Parsing a wire value applies the same validation as the constructors and reports the error codes
  of this contract.
- OpenAPI component schemas `Money`, `Quantity`, `UnitPrice` (objects, required properties, amount
  and value `string`/`format: decimal`, currency `^[A-Z]{3}$`), and Currency and Unit as strings.
  Java registers the Jackson serializers and schemas in `framework-http` (which depends on
  `framework-measure`); Go types implement the JSON marshalling and `SchemaProvider` themselves.
- Database: two columns per value, by convention `<field>_amount NUMERIC` + `<field>_currency CHAR(3)`
  and `<field>_value NUMERIC` + `<field>_unit VARCHAR(8)`. Decimal maps to `NUMERIC`; Currency and
  Unit map to text (Go: `sql.Scanner`/`driver.Valuer`). Composing the pair is the caller's code.

## 6. Errors

Every failure is a validation error with one of these codes: `DECIMAL_INVALID`,
`DIVISION_BY_ZERO`, `CURRENCY_UNKNOWN`, `CURRENCY_MISMATCH`, `SUM_EMPTY`, `ALLOCATION_INVALID`,
`UNIT_UNKNOWN`, `DIMENSION_MISMATCH`, `NO_CONVERSION`, `CONVERSION_INEXACT`,
`TEMPERATURE_NOT_ADDITIVE`, `UNIT_NOT_IN_PACKAGING`, `PACKAGING_INVALID`, `RATE_INVALID`.

- Java: `MeasureException extends IllegalArgumentException`, carrying `code()`.
- Go: `*decimal.Error` (code and message), used by all three packages. They are value types and
  import only the standard library and each other; they do not import `usecase` (amended
  2026-11-03: `usecase` pulls in the database, metrics and background packages). The error
  implements the interface the HTTP layer uses to answer a validation error, so a bad value in a
  request body is a 400 with the contract's code at the property's path, as in Java.

Which code, where the rules above leave it open (amended 2026-11-03 from the build):
- `Money.sum` of an empty list is `SUM_EMPTY`.
- `to`/`toExact`/`compareTo` with a packaging unit on either side are `NO_CONVERSION`, checked
  before the dimension (`XPX` to `KGM` is `NO_CONVERSION`).
- `UnitPrice` per a packaging unit is `NO_CONVERSION`; per a temperature unit, or
  `Quantity.times` on a temperature, is `TEMPERATURE_NOT_ADDITIVE`.
- `DensityFactor` of zero or less, and an exchange rate of zero or less, are `RATE_INVALID`.
- A `Packaging` whose first level is a packaging unit, or whose count is not a positive integer,
  is `PACKAGING_INVALID`.

## 6a. Details settled by the build (amended 2026-11-03)

- `toExact` gives the smallest scale of at least 0 that holds the result (`1000`, never `1E+3`).
- `plus`/`minus` of two quantities in one unit convert nothing; the scale follows section 1.
- `UnitPrice.times(quantity, scale, mode)` converts and multiplies in one exact computation and
  rounds once, so it never fails with `CONVERSION_INEXACT`; only `timesExact` can.
- `allocate`: leftover minor units go one each, in order, to the parts with a non-zero ratio (a
  zero-ratio part is always zero). A negative amount is rounded signed, allocated on its absolute
  value, and every part negated.
- `breakdown` lists every level, zeros included, largest first (960 each with 24 per carton and 40
  cartons per pallet is `[1 XPX, 0 XCT, 0 C62]`); a negative quantity is broken down on its absolute
  value and every part negated.
- Aliases are ASCII only (`m2`, `degC`; not `m²`, `°C`); `each` is `C62`, `ea` is `EA`. The alias of
  every unit is listed in `units.json`, which is authoritative.
- Reading a wire value is strict: every member required, no others, amounts and values as JSON
  strings (a JSON number is refused). A `Unit` on the wire may be an alias; it is written as the
  code.
- `iso4217.json` is the JDK's list, which keeps withdrawn codes (ADP, AFA); they parse like any
  other. `ExchangeRate` with `from` equal to `to` is allowed. `UNNECESSARY` is not a rounding mode
  here.
- OpenAPI: Go builds the closed object schemas with `openapi.Object`; Java's `TypeWalker` knows
  the three components.

## 7. Conformance

`conformance/measure/` holds `iso4217.json`, `units.json` and the vector files Java writes and Go
compares: decimal parse/format, every rounding mode at and around the half-way points, division,
money arithmetic and allocation, every conversion between every pair of units in a dimension (at
scale 12, `HALF_EVEN`, plus `toExact` where it terminates), packaging conversion and breakdown,
unit price and chargeable weight, and every error code.
