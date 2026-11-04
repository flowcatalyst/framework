package io.flowcatalyst.framework.measure.units;

import io.flowcatalyst.framework.measure.Decimals;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An exact ratio of two decimals with a positive denominator: the unrounded result of a conversion
 * or a multiplication, rounded once at the end.
 *
 * @param numerator the numerator
 * @param denominator the denominator, greater than zero
 */
record Fraction(BigDecimal numerator, BigDecimal denominator) {

  Fraction times(BigDecimal factor) {
    return new Fraction(numerator.multiply(factor), denominator);
  }

  Fraction dividedBy(BigDecimal divisor) {
    return new Fraction(numerator, denominator.multiply(divisor));
  }

  BigDecimal round(int scale, RoundingMode mode) {
    return Decimals.divide(numerator, denominator, scale, mode);
  }

  BigDecimal exact() {
    return Decimals.divideExact(numerator, denominator);
  }

  int compareTo(Fraction other) {
    return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
  }
}
