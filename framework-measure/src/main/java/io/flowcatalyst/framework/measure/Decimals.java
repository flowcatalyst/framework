package io.flowcatalyst.framework.measure;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The decimal rules of the measure contract (section 1) over {@link BigDecimal}: plain text only,
 * division always with a scale and a rounding mode.
 */
public final class Decimals {

  private Decimals() {}

  /**
   * Parses plain decimal text: {@code -?[0-9]+(\.[0-9]+)?}. No exponent, grouping, {@code +},
   * whitespace or locale. The scale is the number of fraction digits ({@code 3132.40} has scale 2);
   * leading zeros are dropped.
   *
   * @param text the text
   * @return the value
   * @throws MeasureException {@code DECIMAL_INVALID} when the text is not plain decimal
   */
  public static BigDecimal parse(String text) {
    int n = text.length();
    int i = 0;
    if (n > 0 && text.charAt(0) == '-') {
      i = 1;
    }
    int digits = 0;
    while (i < n && isDigit(text.charAt(i))) {
      i++;
      digits++;
    }
    boolean ok = digits > 0;
    if (ok && i < n) {
      ok = text.charAt(i) == '.';
      i++;
      int fraction = 0;
      while (ok && i < n && isDigit(text.charAt(i))) {
        i++;
        fraction++;
      }
      ok = ok && fraction > 0 && i == n;
    }
    if (!ok) {
      throw new MeasureException(MeasureException.DECIMAL_INVALID, "not a plain decimal number");
    }
    return new BigDecimal(text);
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  /**
   * Plain text of a decimal, keeping its scale: {@code 3132.40} prints {@code 3132.40}. A negative
   * scale prints as a whole number.
   *
   * @param value the value
   * @return the text
   */
  public static String format(BigDecimal value) {
    return value.toPlainString();
  }

  /**
   * Divides to a scale with a rounding mode: the only division the contract has.
   *
   * @param a the dividend
   * @param b the divisor
   * @param scale the scale of the result
   * @param mode how to round
   * @return {@code a / b} at {@code scale}
   * @throws MeasureException {@code DIVISION_BY_ZERO} when {@code b} is zero
   */
  public static BigDecimal divide(BigDecimal a, BigDecimal b, int scale, RoundingMode mode) {
    if (b.signum() == 0) {
      throw new MeasureException(MeasureException.DIVISION_BY_ZERO, "division by zero");
    }
    return a.divide(b, scale, mode);
  }

  /**
   * The number as a plain-scale value with no negative scale, so formatting never needs an exponent
   * and a whole number has scale 0.
   *
   * @param value the value
   * @return the value with a scale of at least 0
   */
  public static BigDecimal nonNegativeScale(BigDecimal value) {
    return value.scale() < 0 ? value.setScale(0) : value;
  }

  /**
   * Divides exactly: the quotient with the smallest scale (at least 0) that holds it.
   *
   * @param a the dividend
   * @param b the divisor, not zero
   * @return the exact quotient
   * @throws MeasureException {@code DIVISION_BY_ZERO}, or {@code CONVERSION_INEXACT} when the
   *     quotient does not terminate
   */
  public static BigDecimal divideExact(BigDecimal a, BigDecimal b) {
    if (b.signum() == 0) {
      throw new MeasureException(MeasureException.DIVISION_BY_ZERO, "division by zero");
    }
    BigDecimal q;
    try {
      q = a.divide(b);
    } catch (ArithmeticException e) {
      throw new MeasureException(MeasureException.CONVERSION_INEXACT, "the exact result does not terminate");
    }
    return nonNegativeScale(q.signum() == 0 ? BigDecimal.ZERO.setScale(0) : q.stripTrailingZeros());
  }

  /**
   * A hash that agrees with numeric equality ({@code 2.0} and {@code 2.00} hash alike).
   *
   * @param value the value
   * @return the hash
   */
  public static int hash(BigDecimal value) {
    return value.signum() == 0 ? 0 : value.stripTrailingZeros().hashCode();
  }
}
