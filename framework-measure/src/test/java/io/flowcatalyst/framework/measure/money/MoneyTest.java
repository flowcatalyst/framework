package io.flowcatalyst.framework.measure.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.measure.MeasureException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Measure contract section 2. */
class MoneyTest {

  private static BigDecimal d(String s) {
    return new BigDecimal(s);
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
  void currencyKnowsItsMinorUnits() {
    assertThat(Currency.of("ZAR").minorUnits()).isEqualTo(2);
    assertThat(Currency.of("JPY").minorUnits()).isZero();
    assertThat(Currency.of("BHD").minorUnits()).isEqualTo(3);
    assertThat(Currency.of("ZAR").toJdk()).isEqualTo(java.util.Currency.getInstance("ZAR"));
    assertThat(code(() -> Currency.of("ZZZ"))).isEqualTo("CURRENCY_UNKNOWN");
    assertThat(code(() -> Currency.of("zar"))).isEqualTo("CURRENCY_UNKNOWN");
    assertThat(code(() -> Currency.of("XAU"))).isEqualTo("CURRENCY_UNKNOWN");
  }

  @Test
  void precisionIsKeptUntilRounded() {
    Money rate = Money.parse("0.40", "ZAR");
    Money total = rate.times(d("7831.2"));
    assertThat(total.amount()).hasToString("3132.480");
    assertThat(total.round(RoundingMode.HALF_EVEN).amount()).hasToString("3132.48");
    assertThat(Money.parse("2.5", "JPY").round(RoundingMode.HALF_EVEN).amount()).hasToString("2");
    assertThat(total.round(1, RoundingMode.UP).amount()).hasToString("3132.5");
  }

  @Test
  void arithmeticIsWithinOneCurrency() {
    Money a = Money.parse("10.00", "ZAR");
    assertThat(a.plus(Money.parse("2.555", "ZAR")).amount()).hasToString("12.555");
    assertThat(a.minus(Money.parse("2.5", "ZAR")).amount()).hasToString("7.50");
    assertThat(a.negate().abs()).isEqualTo(a);
    assertThat(code(() -> a.plus(Money.parse("1", "USD")))).isEqualTo("CURRENCY_MISMATCH");
    assertThat(code(() -> a.compareTo(Money.parse("1", "USD")))).isEqualTo("CURRENCY_MISMATCH");
    assertThat(Money.sum(List.of(a, a, a)).amount()).hasToString("30.00");
    assertThat(code(() -> Money.sum(List.of()))).isEqualTo("SUM_EMPTY");
    assertThat(a.isZero()).isFalse();
    assertThat(Money.zero(Currency.of("ZAR")).isZero()).isTrue();
    assertThat(a.signum()).isEqualTo(1);
  }

  @Test
  void equalityIsNumeric() {
    assertThat(Money.parse("10.0", "ZAR")).isEqualTo(Money.parse("10.00", "ZAR")).hasSameHashCodeAs(Money.parse("10.00", "ZAR"));
    assertThat(Money.parse("10", "ZAR")).isNotEqualTo(Money.parse("10", "USD"));
  }

  @Test
  void allocationAddsUpExactly() {
    List<Money> parts = Money.parse("100.00", "ZAR").allocate(List.of(d("1"), d("1"), d("1")), RoundingMode.HALF_EVEN);
    assertThat(parts).extracting(m -> m.amount().toPlainString()).containsExactly("33.34", "33.33", "33.33");
    assertThat(Money.sum(parts).amount()).hasToString("100.00");
  }

  @Test
  void allocationOfNegativeAmountsAndZeroRatios() {
    assertThat(Money.parse("-100.00", "ZAR").allocate(List.of(d("1"), d("1"), d("1")), RoundingMode.HALF_EVEN))
        .extracting(m -> m.amount().toPlainString())
        .containsExactly("-33.34", "-33.33", "-33.33");
    assertThat(Money.parse("0.05", "ZAR").allocate(List.of(d("0"), d("1"), d("1")), RoundingMode.HALF_EVEN))
        .extracting(m -> m.amount().toPlainString())
        .containsExactly("0.00", "0.03", "0.02");
    assertThat(Money.parse("100", "JPY").allocate(List.of(d("1"), d("2")), RoundingMode.HALF_EVEN))
        .extracting(m -> m.amount().toPlainString())
        .containsExactly("34", "66");
    assertThat(Money.parse("0.005", "USD").allocate(List.of(d("1"), d("1")), RoundingMode.HALF_UP))
        .extracting(m -> m.amount().toPlainString())
        .containsExactly("0.01", "0.00");
  }

  @Test
  void allocationRefusesUnusableRatios() {
    Money a = Money.parse("1", "ZAR");
    assertThat(code(() -> a.allocate(List.of(), RoundingMode.UP))).isEqualTo("ALLOCATION_INVALID");
    assertThat(code(() -> a.allocate(List.of(d("0"), d("0")), RoundingMode.UP))).isEqualTo("ALLOCATION_INVALID");
    assertThat(code(() -> a.allocate(List.of(d("1"), d("-1")), RoundingMode.UP))).isEqualTo("ALLOCATION_INVALID");
  }

  @Test
  void exchangeRateConvertsAndRoundsToTheTargetCurrency() {
    ExchangeRate rate = new ExchangeRate(Currency.of("USD"), Currency.of("ZAR"), d("18.3456"), Instant.parse("2026-11-03T00:00:00Z"));
    assertThat(rate.convert(Money.parse("100.00", "USD"), RoundingMode.HALF_EVEN)).isEqualTo(Money.parse("1834.56", "ZAR"));
    assertThat(code(() -> rate.convert(Money.parse("1", "ZAR"), RoundingMode.UP))).isEqualTo("CURRENCY_MISMATCH");
    assertThat(code(() -> new ExchangeRate(Currency.of("USD"), Currency.of("ZAR"), d("0"), Instant.EPOCH))).isEqualTo("RATE_INVALID");
  }
}
