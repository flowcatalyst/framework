package io.flowcatalyst.framework.measure.units;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.measure.MeasureException;
import io.flowcatalyst.framework.measure.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Measure contract sections 3 and 4. */
class UnitsTest {

  private static Quantity q(String v, Unit u) {
    return new Quantity(new BigDecimal(v), u);
  }

  private static String code(Runnable r) {
    try {
      r.run();
    } catch (MeasureException e) {
      return e.code();
    }
    return "none";
  }

  @Test
  void unitsParseByCodeAndAlias() {
    assertThat(Unit.of("KGM")).isEqualTo(Unit.KGM);
    assertThat(Unit.of("kg")).isEqualTo(Unit.KGM);
    assertThat(Unit.of("KG")).isEqualTo(Unit.KGM);
    assertThat(Unit.of("m3")).isEqualTo(Unit.MTQ);
    assertThat(Unit.of("L")).isEqualTo(Unit.LTR);
    assertThat(Unit.of("degC")).isEqualTo(Unit.CEL);
    assertThat(Unit.of("PX")).isEqualTo(Unit.XPX);
    assertThat(code(() -> Unit.of("kgm"))).isEqualTo("UNIT_UNKNOWN");
    assertThat(code(() -> Unit.of("furlong"))).isEqualTo("UNIT_UNKNOWN");
    assertThat(Unit.KGM.symbol()).isEqualTo("kg");
    assertThat(Unit.MTK.symbol()).isEqualTo("m²");
    assertThat(Unit.XPX.isPackaging()).isTrue();
    assertThat(Unit.KGM.dimension()).isEqualTo(Dimension.MASS);
  }

  @Test
  void anApplicationRegistersItsPartnersCodes() {
    UnitRegistry registry = UnitRegistry.builtin().with("KILO", Unit.KGM);
    assertThat(registry.parse("kilo")).isEqualTo(Unit.KGM);
    assertThat(code(() -> UnitRegistry.builtin().parse("KILO"))).isEqualTo("UNIT_UNKNOWN");
    assertThat(code(() -> UnitRegistry.builtin().with("kg", Unit.GRM))).isEqualTo("UNIT_UNKNOWN");
  }

  @Test
  void conversionIsExactAndRoundedOnce() {
    assertThat(q("7831.2", Unit.KGM).to(Unit.TNE, 6, RoundingMode.HALF_EVEN)).isEqualTo(q("7.831200", Unit.TNE));
    assertThat(q("7831.2", Unit.KGM).to(Unit.LBR, 4, RoundingMode.HALF_EVEN).value()).hasToString("17264.8407");
    assertThat(q("1", Unit.LBR).toExact(Unit.KGM).value()).hasToString("0.45359237");
    assertThat(q("1", Unit.INH).toExact(Unit.MMT).value()).hasToString("25.4");
    assertThat(q("1000", Unit.KGM).toExact(Unit.TNE).value()).hasToString("1");
    assertThat(code(() -> q("1", Unit.KGM).toExact(Unit.LBR))).isEqualTo("CONVERSION_INEXACT");
    assertThat(code(() -> q("1", Unit.KGM).to(Unit.MTR, 2, RoundingMode.UP))).isEqualTo("DIMENSION_MISMATCH");
    assertThat(code(() -> q("1", Unit.XPX).to(Unit.XCT, 2, RoundingMode.UP))).isEqualTo("NO_CONVERSION");
  }

  @Test
  void temperatureConvertsWithItsOffset() {
    assertThat(q("100", Unit.CEL).to(Unit.FAH, 2, RoundingMode.HALF_EVEN).value()).hasToString("212.00");
    assertThat(q("-40", Unit.CEL).to(Unit.FAH, 2, RoundingMode.HALF_EVEN).value()).hasToString("-40.00");
    assertThat(q("0", Unit.CEL).toExact(Unit.KEL).value()).hasToString("273.15");
    assertThat(code(() -> q("1", Unit.CEL).plus(q("1", Unit.CEL)))).isEqualTo("TEMPERATURE_NOT_ADDITIVE");
    assertThat(code(() -> q("1", Unit.CEL).times(BigDecimal.TWO))).isEqualTo("TEMPERATURE_NOT_ADDITIVE");
  }

  @Test
  void additionConvertsTheRightHandSide() {
    assertThat(q("1", Unit.KGM).plus(q("500", Unit.GRM))).isEqualTo(q("1.5", Unit.KGM));
    assertThat(q("2", Unit.TNE).minus(q("500", Unit.KGM))).isEqualTo(q("1.5", Unit.TNE));
    assertThat(q("1.5", Unit.KGM).plus(q("1.50", Unit.KGM)).value()).hasToString("3.00");
    assertThat(code(() -> q("1", Unit.LBR).plus(q("1", Unit.KGM)))).isEqualTo("CONVERSION_INEXACT");
  }

  @Test
  void comparisonIsExactAcrossUnits() {
    assertThat(q("1000", Unit.GRM).compareTo(q("1", Unit.KGM))).isZero();
    assertThat(q("1000", Unit.GRM)).isNotEqualTo(q("1", Unit.KGM));
    assertThat(q("1", Unit.LBR).compareTo(q("0.45", Unit.KGM))).isPositive();
    assertThat(q("20", Unit.CEL).compareTo(q("68", Unit.FAH))).isZero();
    assertThat(q("2.0", Unit.KGM)).isEqualTo(q("2.00", Unit.KGM));
    assertThat(code(() -> q("1", Unit.KGM).compareTo(q("1", Unit.MTR)))).isEqualTo("DIMENSION_MISMATCH");
  }

  @Test
  void packagingConvertsAndBreaksDown() {
    Packaging p = Packaging.of(Unit.C62).level(Unit.XCT, 24).level(Unit.XPX, 40);
    assertThat(p.convertExact(q("1", Unit.XPX), Unit.C62)).isEqualTo(q("960", Unit.C62));
    assertThat(p.convertExact(q("48", Unit.XCT), Unit.XPX)).isEqualTo(q("1.2", Unit.XPX));
    assertThat(p.convert(q("1", Unit.C62), Unit.XPX, 4, RoundingMode.HALF_EVEN)).isEqualTo(q("0.0010", Unit.XPX));
    assertThat(p.breakdown(q("1000", Unit.C62))).containsExactly(q("1", Unit.XPX), q("1", Unit.XCT), q("16", Unit.C62));
    assertThat(code(() -> p.convert(q("1", Unit.XBX), Unit.C62, 2, RoundingMode.UP))).isEqualTo("UNIT_NOT_IN_PACKAGING");
    assertThat(code(() -> p.convertExact(q("1", Unit.C62), Unit.XPX))).isEqualTo("CONVERSION_INEXACT");
  }

  @Test
  void packagingRefusesBadLevels() {
    assertThat(code(() -> Packaging.of(Unit.MTR))).isEqualTo("PACKAGING_INVALID");
    assertThat(code(() -> Packaging.of(Unit.XPX))).isEqualTo("PACKAGING_INVALID");
    assertThat(code(() -> Packaging.of(Unit.C62).level(Unit.KGM, 2))).isEqualTo("PACKAGING_INVALID");
    assertThat(code(() -> Packaging.of(Unit.C62).level(Unit.XCT, 0))).isEqualTo("PACKAGING_INVALID");
    assertThat(code(() -> Packaging.of(Unit.C62).level(Unit.XCT, 2).level(Unit.XCT, 2))).isEqualTo("PACKAGING_INVALID");
    assertThat(Packaging.of(Unit.KGM).level(Unit.XBG, 25)).isNotNull();
  }

  @Test
  void aPricePerUnitTimesAQuantity() {
    UnitPrice price = new UnitPrice(Money.parse("0.40", "ZAR"), Unit.KGM);
    Quantity tonnes = q("7.8312", Unit.TNE);
    assertThat(price.timesExact(tonnes).amount()).hasToString("3132.480");
    assertThat(price.times(tonnes, 2, RoundingMode.HALF_EVEN)).isEqualTo(Money.parse("3132.48", "ZAR"));
    assertThat(price.times(q("1.2345", Unit.KGM), 2, RoundingMode.UP).amount()).hasToString("0.50");
    // rounded once, so a price per pound applied to kilograms is defined; the exact form refuses
    UnitPrice perPound = new UnitPrice(Money.parse("12.50", "USD"), Unit.LBR);
    assertThat(perPound.times(q("10", Unit.KGM), 2, RoundingMode.HALF_EVEN).amount()).hasToString("275.58");
    assertThat(code(() -> perPound.timesExact(q("10", Unit.KGM)))).isEqualTo("CONVERSION_INEXACT");
    assertThat(code(() -> new UnitPrice(Money.parse("1", "ZAR"), Unit.XPX))).isEqualTo("NO_CONVERSION");
    assertThat(code(() -> new UnitPrice(Money.parse("1", "ZAR"), Unit.CEL))).isEqualTo("TEMPERATURE_NOT_ADDITIVE");
    assertThat(code(() -> price.times(q("1", Unit.MTR), 2, RoundingMode.UP))).isEqualTo("DIMENSION_MISMATCH");
  }

  @Test
  void chargeableWeightIsTheLargerOfActualAndVolume() {
    Quantity light = ChargeableWeight.of(q("100", Unit.KGM), q("1", Unit.MTQ), DensityFactor.kgPerCubicMetre(167), 0, RoundingMode.HALF_EVEN);
    assertThat(light).isEqualTo(q("167", Unit.KGM));
    Quantity dense = ChargeableWeight.of(q("100", Unit.KGM), q("0.5", Unit.MTQ), DensityFactor.kgPerCubicMetre(167), 0, RoundingMode.HALF_EVEN);
    assertThat(dense).isEqualTo(q("100", Unit.KGM));
    // the divisor form: 1 m3 = 1,000,000 cm3 / 6000
    Quantity divisor = ChargeableWeight.of(q("100", Unit.KGM), q("1000000", Unit.CMQ), DensityFactor.divisor(6000), 3, RoundingMode.HALF_EVEN);
    assertThat(divisor.value()).hasToString("166.667");
    Quantity pounds = ChargeableWeight.of(q("220.462", Unit.LBR), q("1", Unit.MTQ), DensityFactor.kgPerCubicMetre(167), 1, RoundingMode.HALF_EVEN);
    assertThat(pounds.value()).hasToString("167.0");
    assertThat(code(() -> ChargeableWeight.of(q("1", Unit.MTR), q("1", Unit.MTQ), DensityFactor.divisor(6000), 0, RoundingMode.UP))).isEqualTo("DIMENSION_MISMATCH");
    assertThat(code(() -> ChargeableWeight.of(q("1", Unit.KGM), q("1", Unit.MTK), DensityFactor.divisor(6000), 0, RoundingMode.UP))).isEqualTo("DIMENSION_MISMATCH");
    assertThat(List.of(Unit.KGM)).hasSize(1);
  }
}
