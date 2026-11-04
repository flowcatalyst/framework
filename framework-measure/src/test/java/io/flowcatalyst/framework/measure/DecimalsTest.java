package io.flowcatalyst.framework.measure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/** Measure contract section 1. */
class DecimalsTest {

  @Test
  void plainTextKeepsTheScale() {
    assertThat(Decimals.format(Decimals.parse("3132.40"))).isEqualTo("3132.40");
    assertThat(Decimals.format(Decimals.parse("0.0"))).isEqualTo("0.0");
    assertThat(Decimals.format(Decimals.parse("007.5"))).isEqualTo("7.5");
    assertThat(Decimals.parse("-0.0").scale()).isEqualTo(1);
  }

  @Test
  void onlyPlainDecimalTextIsAccepted() {
    for (String bad : new String[] {"", "-", "1e3", "+1", " 1", "1.", ".5", "1,000", "NaN", "1.2.3", "٣"}) {
      assertThatThrownBy(() -> Decimals.parse(bad))
          .isInstanceOfSatisfying(MeasureException.class, e -> assertThat(e.code()).isEqualTo("DECIMAL_INVALID"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void scalesFollowBigDecimal() {
    BigDecimal a = Decimals.parse("1.5");
    BigDecimal b = Decimals.parse("0.25");
    assertThat(a.add(b).scale()).isEqualTo(2);
    assertThat(a.multiply(b).scale()).isEqualTo(3);
  }

  @Test
  void divisionNamesScaleAndMode() {
    assertThat(Decimals.divide(Decimals.parse("1"), Decimals.parse("3"), 4, RoundingMode.HALF_EVEN)).hasToString("0.3333");
    assertThatThrownBy(() -> Decimals.divide(BigDecimal.ONE, BigDecimal.ZERO, 2, RoundingMode.UP))
        .isInstanceOfSatisfying(MeasureException.class, e -> assertThat(e.code()).isEqualTo("DIVISION_BY_ZERO"));
  }

  @Test
  void everyModeAtTheHalfWayPoints() {
    // 2.5 and -2.5 to a whole number
    assertThat(round("2.5", RoundingMode.UP)).isEqualTo("3");
    assertThat(round("2.5", RoundingMode.DOWN)).isEqualTo("2");
    assertThat(round("2.5", RoundingMode.CEILING)).isEqualTo("3");
    assertThat(round("2.5", RoundingMode.FLOOR)).isEqualTo("2");
    assertThat(round("2.5", RoundingMode.HALF_UP)).isEqualTo("3");
    assertThat(round("2.5", RoundingMode.HALF_DOWN)).isEqualTo("2");
    assertThat(round("2.5", RoundingMode.HALF_EVEN)).isEqualTo("2");
    assertThat(round("-2.5", RoundingMode.UP)).isEqualTo("-3");
    assertThat(round("-2.5", RoundingMode.DOWN)).isEqualTo("-2");
    assertThat(round("-2.5", RoundingMode.CEILING)).isEqualTo("-2");
    assertThat(round("-2.5", RoundingMode.FLOOR)).isEqualTo("-3");
    assertThat(round("-2.5", RoundingMode.HALF_UP)).isEqualTo("-3");
    assertThat(round("-2.5", RoundingMode.HALF_DOWN)).isEqualTo("-2");
    assertThat(round("-2.5", RoundingMode.HALF_EVEN)).isEqualTo("-2");
    assertThat(round("3.5", RoundingMode.HALF_EVEN)).isEqualTo("4");
  }

  @Test
  void exactDivisionHasTheSmallestScaleThatHoldsIt() {
    assertThat(Decimals.divideExact(Decimals.parse("1000"), Decimals.parse("1"))).hasToString("1000");
    assertThat(Decimals.divideExact(Decimals.parse("7831.20"), Decimals.parse("1000"))).hasToString("7.8312");
    assertThatThrownBy(() -> Decimals.divideExact(BigDecimal.ONE, new BigDecimal("3")))
        .isInstanceOfSatisfying(MeasureException.class, e -> assertThat(e.code()).isEqualTo("CONVERSION_INEXACT"));
  }

  @Test
  void hashAgreesWithNumericEquality() {
    assertThat(Decimals.hash(new BigDecimal("2.0"))).isEqualTo(Decimals.hash(new BigDecimal("2.00")));
    assertThat(Decimals.hash(new BigDecimal("0.00"))).isEqualTo(Decimals.hash(BigDecimal.ZERO));
  }

  private static String round(String v, RoundingMode mode) {
    return Decimals.format(Decimals.parse(v).setScale(0, mode));
  }
}
