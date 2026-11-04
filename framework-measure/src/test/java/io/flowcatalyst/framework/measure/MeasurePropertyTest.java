package io.flowcatalyst.framework.measure;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.measure.money.Currency;
import io.flowcatalyst.framework.measure.money.Money;
import io.flowcatalyst.framework.measure.units.Dimension;
import io.flowcatalyst.framework.measure.units.Quantity;
import io.flowcatalyst.framework.measure.units.Unit;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Seeded property loops (a failure repeats): allocation sums, and conversion there and back. */
class MeasurePropertyTest {

  @Test
  void allocatedPartsAlwaysSumToTheRoundedWhole() {
    Random rng = new Random(20261103L);
    String[] currencies = {"ZAR", "JPY", "BHD", "USD"};
    for (int i = 0; i < 3000; i++) {
      Currency cur = Currency.of(currencies[rng.nextInt(currencies.length)]);
      BigDecimal amount = new BigDecimal(BigInteger.valueOf(rng.nextLong(2_000_000_000L) - 1_000_000_000L), rng.nextInt(6));
      int n = 1 + rng.nextInt(7);
      List<BigDecimal> ratios = new ArrayList<>();
      boolean positive = false;
      for (int j = 0; j < n; j++) {
        BigDecimal r = rng.nextInt(4) == 0 ? BigDecimal.ZERO : new BigDecimal(BigInteger.valueOf(rng.nextLong(50)), rng.nextInt(3));
        positive = positive || r.signum() > 0;
        ratios.add(r);
      }
      if (!positive) {
        ratios.set(0, BigDecimal.ONE);
      }
      RoundingMode mode = RoundingMode.values()[rng.nextInt(7)];
      Money m = new Money(amount, cur);
      List<Money> parts = m.allocate(ratios, mode);
      Money whole = m.round(mode);
      assertThat(Money.sum(parts)).as("#%d %s %s %s", i, m, ratios, mode).isEqualTo(whole);
      for (int j = 0; j < parts.size(); j++) {
        assertThat(parts.get(j).amount().scale()).isEqualTo(cur.minorUnits());
        if (ratios.get(j).signum() == 0) {
          assertThat(parts.get(j).isZero()).isTrue();
        }
        assertThat(parts.get(j).signum() * whole.signum()).isGreaterThanOrEqualTo(0);
      }
    }
  }

  @Test
  void conversionThereAndBackIsWithinTheRoundingError() {
    Random rng = new Random(20261103L + 1);
    List<Unit> all = new ArrayList<>();
    for (String c :
        List.of(
            "MGM", "GRM", "KGM", "TNE", "LBR", "ONZ", "STN", "LTN", "MMT", "CMT", "MTR", "KMT", "INH", "FOT", "YRD", "SMI", "NMI", "MTK", "FTK",
            "CMQ", "MLT", "LTR", "MTQ", "INQ", "FTQ", "GLL", "GLI", "C62", "EA", "H87", "DZN")) {
      all.add(Unit.of(c));
    }
    BigDecimal ulp = BigDecimal.ONE.movePointLeft(12);
    int checked = 0;
    for (Unit from : all) {
      for (Unit to : all) {
        if (from.dimension() != to.dimension() || from.dimension() == Dimension.TEMPERATURE) {
          continue;
        }
        BigDecimal ratio = new Quantity(BigDecimal.ONE, to).to(from, 0, RoundingMode.UP).value();
        BigDecimal tolerance = ratio.add(BigDecimal.TWO).multiply(ulp);
        for (int k = 0; k < 40; k++) {
          BigDecimal v = new BigDecimal(BigInteger.valueOf(rng.nextLong(2_000_000_000L) - 1_000_000_000L), rng.nextInt(5));
          Quantity there = new Quantity(v, from).to(to, 12, RoundingMode.HALF_EVEN);
          Quantity back = there.to(from, 12, RoundingMode.HALF_EVEN);
          assertThat(back.value().subtract(v).abs()).as("%s %s -> %s", v, from, to).isLessThanOrEqualTo(tolerance);
          checked++;
        }
      }
    }
    assertThat(checked).isGreaterThan(5000);
  }
}
