<!-- types: MeasureException, Decimals, Currency, Money, ExchangeRate, Unit, UnitRegistry, Dimension, Quantity, Packaging, UnitPrice, DensityFactor, ChargeableWeight -->
# Money and units of measure

Exact values for logistics: money in a currency, quantities in units of measure with the standard
conversions, packaging levels, prices per unit and chargeable weight. The module is
`framework-measure` (packages `io.flowcatalyst.framework.measure.money` and
`io.flowcatalyst.framework.measure.units`); it depends on nothing but the JDK. The Go packages
`decimal`, `money` and `units` give the same result to the last digit
([Go: money and units](go/measure.md)); the vectors in `conformance/measure/`, written by Java and
compared by Go, are the proof. The specification is the [measure contract](../contracts/measure.md);
the reasoning is in the [plan](../money-and-units-plan.md).

Nothing rounds unless you ask, nothing divides without a scale, and nothing goes through a
`double`.

## Decimals

Java uses `BigDecimal` directly. `Decimals` adds the rules the contract fixes: plain decimal text
only (`-?[0-9]+(\.[0-9]+)?`: no exponent, grouping, `+` or locale), and division only with a scale
and a rounding mode.

```java
assertThat(Decimals.format(Decimals.parse("3132.40"))).isEqualTo("3132.40");
assertThat(Decimals.format(Decimals.parse("007.5"))).isEqualTo("7.5");
assertThat(Decimals.divide(Decimals.parse("1"), Decimals.parse("3"), 4, RoundingMode.HALF_EVEN))
    .hasToString("0.3333");
```

Scales follow `BigDecimal`: add and subtract take the larger, multiply adds them. The seven rounding
modes are `java.math.RoundingMode`'s `UP`, `DOWN`, `CEILING`, `FLOOR`, `HALF_UP`, `HALF_DOWN` and
`HALF_EVEN`. A failure is a `MeasureException` (an `IllegalArgumentException`) whose `code()` is one
of the contract's codes: `DECIMAL_INVALID`, `DIVISION_BY_ZERO`, `CURRENCY_UNKNOWN`,
`CURRENCY_MISMATCH`, `SUM_EMPTY`, `ALLOCATION_INVALID`, `UNIT_UNKNOWN`, `DIMENSION_MISMATCH`, `NO_CONVERSION`,
`CONVERSION_INEXACT`, `TEMPERATURE_NOT_ADDITIVE`, `UNIT_NOT_IN_PACKAGING`, `PACKAGING_INVALID` and
`RATE_INVALID`.

## Money

`Currency` is an ISO 4217 code with its minor units, from the table `conformance/measure/iso4217.json`
(generated from the JDK's `java.util.Currency`, embedded by both languages). `Money` is a `BigDecimal`
amount and a `Currency`:

```java
Money rate = Money.parse("0.40", "ZAR");
Money total = rate.times(d("7831.2"));
assertThat(total.amount()).hasToString("3132.480");
assertThat(total.round(RoundingMode.HALF_EVEN).amount()).hasToString("3132.48");
```

`round(mode)` rounds to the currency's minor units, `round(scale, mode)` to any scale. `plus`, `minus`,
`compareTo` and `Money.sum(list)` work within one currency; two currencies are `CURRENCY_MISMATCH`,
never a conversion, and the sum of an empty list is `SUM_EMPTY`. Equality is numeric: `10.0 ZAR` equals `10.00 ZAR`.

`allocate` splits an amount across consignments, legs or invoice lines so the parts add up to the
whole. The amount is rounded to minor units first, each part is the floor of its share, and the
leftover minor units go one each to the parts in order (a part with a zero ratio stays zero; a
negative amount is allocated as its absolute value, every part negated):

```java
List<Money> parts = Money.parse("100.00", "ZAR").allocate(List.of(d("1"), d("1"), d("1")), RoundingMode.HALF_EVEN);
assertThat(parts).extracting(m -> m.amount().toPlainString()).containsExactly("33.34", "33.33", "33.33");
```

`ExchangeRate(from, to, rate, asOf)` converts and rounds to the target currency; there is no rate
provider, the application stores the rate it used next to the converted amount:

```java
ExchangeRate rate = new ExchangeRate(Currency.of("USD"), Currency.of("ZAR"), d("18.3456"), Instant.parse("2026-11-03T00:00:00Z"));
assertThat(rate.convert(Money.parse("100.00", "USD"), RoundingMode.HALF_EVEN)).isEqualTo(Money.parse("1834.56", "ZAR"));
```

## Units and quantities

A `Unit` is identified by its UN/ECE Recommendation 20 code (`KGM`, `TNE`, `MTQ`, `LTR`, `C62`), the form
on the wire; `symbol()` is for people. The built-in table is `conformance/measure/units.json`: mass,
length, area, volume, count, temperature and packaging units, every factor an exact fraction (1 lb is
exactly 0.45359237 kg). `Unit.of` accepts the code (case-sensitive) or an alias (case-insensitive:
`kg`, `m3`, `L`, `degC`, `PX`); an application adds its partners' spellings on a `UnitRegistry`, and
the built-in table cannot change.

```java
assertThat(Unit.of("kg")).isEqualTo(Unit.KGM);
UnitRegistry registry = UnitRegistry.builtin().with("KILO", Unit.KGM);
assertThat(registry.parse("kilo")).isEqualTo(Unit.KGM);
```

A `Quantity` is a value and a unit (the snippets below use two test helpers: `d("7.8312")` is
`new BigDecimal("7.8312")` and `q("7.8312", Unit.TNE)` is `new Quantity(d("7.8312"), Unit.TNE)`).
Conversion is computed exactly and rounded once, in one of two
forms: `to(unit, scale, mode)` rounds, `toExact(unit)` does not and fails with `CONVERSION_INEXACT`
when the result does not terminate (kilograms to pounds). Another dimension is `DIMENSION_MISMATCH`.

```java
assertThat(q("7831.2", Unit.KGM).to(Unit.TNE, 6, RoundingMode.HALF_EVEN)).isEqualTo(q("7.831200", Unit.TNE));
assertThat(q("1", Unit.LBR).toExact(Unit.KGM).value()).hasToString("0.45359237");
```

`plus` and `minus` convert the right-hand side to the left's unit exactly first; `compareTo` compares
across units of one dimension (`1000 GRM` and `1 KGM` compare equal but are not `equals`).
Temperature is affine (the table carries an offset): it converts (100 °C is 212 °F) but is never added
or multiplied (`TEMPERATURE_NOT_ADDITIVE`).

## Packaging

How many eaches a pallet holds depends on the product, so the framework does not know it: the
application supplies a `Packaging` per product. Packaging units (`XPX` pallet, `XCT` carton, `XCS`
case, `XBX` box, ...) convert only through one:

```java
Packaging p = Packaging.of(Unit.C62).level(Unit.XCT, 24).level(Unit.XPX, 40);
assertThat(p.convertExact(q("1", Unit.XPX), Unit.C62)).isEqualTo(q("960", Unit.C62));
assertThat(p.breakdown(q("1000", Unit.C62))).containsExactly(q("1", Unit.XPX), q("1", Unit.XCT), q("16", Unit.C62));
```

`convert` and `convertExact` work between any two levels; `breakdown` returns every level, largest
first, with any fractional rest at the smallest. A unit that is not a level is
`UNIT_NOT_IN_PACKAGING`; a bad level (a count of zero, a repeated unit, a first unit that is not a
count or mass unit) is `PACKAGING_INVALID`.

## Prices per unit and chargeable weight

`UnitPrice(price, per)` is a rate: `0.40 ZAR` per `KGM`. `times` converts the quantity, multiplies and
rounds the money once; `timesExact` is unrounded:

```java
UnitPrice price = new UnitPrice(Money.parse("0.40", "ZAR"), Unit.KGM);
assertThat(price.timesExact(q("7.8312", Unit.TNE)).amount()).hasToString("3132.480");
assertThat(price.times(q("7.8312", Unit.TNE), 2, RoundingMode.HALF_EVEN)).isEqualTo(Money.parse("3132.48", "ZAR"));
```

`ChargeableWeight.of(actual, volume, factor, scale, mode)` is the larger of the actual weight and
`volume x factor`, in `KGM`. The factor is a `DensityFactor`, in either form the trade uses:
`kgPerCubicMetre(167)` or `divisor(6000)` (cm³ per kg):

```java
Quantity light = ChargeableWeight.of(q("100", Unit.KGM), q("1", Unit.MTQ), DensityFactor.kgPerCubicMetre(167), 0, RoundingMode.HALF_EVEN);
assertThat(light).isEqualTo(q("167", Unit.KGM));
```

## On the wire, in OpenAPI and in the database

`framework-http` depends on `framework-measure`: its typed-operation mapper writes and reads `Money`
as `{"amount": "3132.48", "currency": "ZAR"}`, `Quantity` as `{"value": "7831.2", "unit": "KGM"}`,
`UnitPrice` as `{"price": {...}, "per": "KGM"}`, and `Currency` and `Unit` as their code. Amounts and
values are always JSON strings, never numbers, and keep their scale. Reading applies the
constructors' validation, and a failure is reported with the contract's code (`DECIMAL_INVALID`,
`CURRENCY_UNKNOWN`, `UNIT_UNKNOWN`, ...) at the property's path. The OpenAPI document has the
components `Money`, `Quantity` and `UnitPrice` (closed objects, amounts `string` with `format:
decimal`, the currency `^[A-Z]{3}$`) and `Currency` and `Unit` as strings
([typed operations](openapi.md)).

In the database a value is two columns by convention: `<field>_amount NUMERIC` and
`<field>_currency CHAR(3)`, `<field>_value NUMERIC` and `<field>_unit VARCHAR(8)`. Composing the pair
is the caller's code.
