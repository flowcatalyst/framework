package io.flowcatalyst.framework.measure.money;

import io.flowcatalyst.framework.measure.Decimals;
import io.flowcatalyst.framework.measure.MeasureException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * An amount in a currency (measure contract section 2.2). Immutable. Precision is kept: no
 * operation rounds unless asked ({@link #round(RoundingMode)}). Arithmetic is within one currency;
 * mixing currencies is an error, never a conversion. Equality is numeric ({@code 10.0 ZAR} equals
 * {@code 10.00 ZAR}). The wire form is {@code {"amount": "3132.48", "currency": "ZAR"}}.
 *
 * @param amount the amount, any scale
 * @param currency the currency
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

  /**
   * Creates money from text.
   *
   * @param amount plain decimal text
   * @param currency an ISO 4217 code
   * @return the money
   * @throws MeasureException {@code DECIMAL_INVALID} or {@code CURRENCY_UNKNOWN}
   */
  public static Money parse(String amount, String currency) {
    return new Money(Decimals.parse(amount), Currency.of(currency));
  }

  /**
   * Zero in a currency, at the currency's minor-unit scale.
   *
   * @param currency the currency
   * @return zero
   */
  public static Money zero(Currency currency) {
    return new Money(BigDecimal.ZERO.setScale(currency.minorUnits()), currency);
  }

  /**
   * The sum of a non-empty list in one currency.
   *
   * @param values the amounts
   * @return their sum
   * @throws MeasureException {@code SUM_EMPTY} for an empty list, {@code CURRENCY_MISMATCH} for more than one currency
   */
  public static Money sum(List<Money> values) {
    if (values.isEmpty()) {
      throw new MeasureException(MeasureException.SUM_EMPTY, "the sum of no amounts has no currency");
    }
    Money total = values.getFirst();
    for (int i = 1; i < values.size(); i++) {
      total = total.plus(values.get(i));
    }
    return total;
  }

  /**
   * Adds.
   *
   * @param other the same currency
   * @return the sum, at the larger scale
   * @throws MeasureException {@code CURRENCY_MISMATCH}
   */
  public Money plus(Money other) {
    same(other);
    return new Money(amount.add(other.amount), currency);
  }

  /**
   * Subtracts.
   *
   * @param other the same currency
   * @return the difference, at the larger scale
   * @throws MeasureException {@code CURRENCY_MISMATCH}
   */
  public Money minus(Money other) {
    same(other);
    return new Money(amount.subtract(other.amount), currency);
  }

  /**
   * Negates.
   *
   * @return the negated amount
   */
  public Money negate() {
    return new Money(amount.negate(), currency);
  }

  /**
   * The absolute value.
   *
   * @return the absolute amount
   */
  public Money abs() {
    return new Money(amount.abs(), currency);
  }

  /**
   * Multiplies by a decimal; the scales add.
   *
   * @param factor the factor
   * @return the product, unrounded
   */
  public Money times(BigDecimal factor) {
    return new Money(amount.multiply(factor), currency);
  }

  /**
   * Whether the amount is zero.
   *
   * @return true for zero at any scale
   */
  public boolean isZero() {
    return amount.signum() == 0;
  }

  /**
   * The sign.
   *
   * @return -1, 0 or 1
   */
  public int signum() {
    return amount.signum();
  }

  /**
   * Rounds to the currency's minor units.
   *
   * @param mode how to round
   * @return the amount at the minor-unit scale
   */
  public Money round(RoundingMode mode) {
    return round(currency.minorUnits(), mode);
  }

  /**
   * Rounds to any scale.
   *
   * @param scale the scale of the result
   * @param mode how to round
   * @return the rounded money
   */
  public Money round(int scale, RoundingMode mode) {
    return new Money(amount.setScale(scale, mode), currency);
  }

  /**
   * Splits the amount into parts proportional to the ratios. The amount is first rounded to the
   * currency's minor units with {@code mode}; each part is the floor of {@code amount * ratio /
   * total} in minor units; the leftover minor units go one each to the parts with a non-zero ratio,
   * in order. The parts add up exactly to the rounded amount. A negative amount is allocated as its
   * absolute value and every part negated. A part with a zero ratio is always zero.
   *
   * @param ratios non-negative, at least one non-zero
   * @param mode how to round the amount first
   * @return one part per ratio, at the minor-unit scale
   * @throws MeasureException {@code ALLOCATION_INVALID} for no ratios, a negative ratio, or all zero
   */
  public List<Money> allocate(List<BigDecimal> ratios, RoundingMode mode) {
    if (ratios.isEmpty()) {
      throw new MeasureException(MeasureException.ALLOCATION_INVALID, "no ratios");
    }
    BigDecimal total = BigDecimal.ZERO;
    for (BigDecimal r : ratios) {
      if (r.signum() < 0) {
        throw new MeasureException(MeasureException.ALLOCATION_INVALID, "a ratio is negative");
      }
      total = total.add(r);
    }
    if (total.signum() == 0) {
      throw new MeasureException(MeasureException.ALLOCATION_INVALID, "every ratio is zero");
    }
    int scale = currency.minorUnits();
    BigDecimal rounded = amount.setScale(scale, mode);
    boolean negative = rounded.signum() < 0;
    BigInteger units = rounded.unscaledValue().abs();
    BigInteger[] parts = new BigInteger[ratios.size()];
    BigInteger given = BigInteger.ZERO;
    for (int i = 0; i < parts.length; i++) {
      BigDecimal part =
          new BigDecimal(units).multiply(ratios.get(i)).divide(total, 0, RoundingMode.FLOOR);
      parts[i] = part.toBigIntegerExact();
      given = given.add(parts[i]);
    }
    BigInteger leftover = units.subtract(given);
    for (int i = 0; i < parts.length && leftover.signum() > 0; i++) {
      if (ratios.get(i).signum() > 0) {
        parts[i] = parts[i].add(BigInteger.ONE);
        leftover = leftover.subtract(BigInteger.ONE);
      }
    }
    List<Money> out = new ArrayList<>(parts.length);
    for (BigInteger p : parts) {
      BigDecimal v = new BigDecimal(p, scale);
      out.add(new Money(negative ? v.negate() : v, currency));
    }
    return List.copyOf(out);
  }

  /**
   * Compares amounts of one currency.
   *
   * @param other the same currency
   * @return negative, zero or positive
   * @throws MeasureException {@code CURRENCY_MISMATCH}
   */
  @Override
  public int compareTo(Money other) {
    same(other);
    return amount.compareTo(other.amount);
  }

  private void same(Money other) {
    if (!currency.equals(other.currency)) {
      throw new MeasureException(MeasureException.CURRENCY_MISMATCH, "the currencies differ");
    }
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Money m && currency.equals(m.currency) && amount.compareTo(m.amount) == 0;
  }

  @Override
  public int hashCode() {
    return 31 * Decimals.hash(amount) + currency.hashCode();
  }

  @Override
  public String toString() {
    return Decimals.format(amount) + " " + currency.code();
  }
}
