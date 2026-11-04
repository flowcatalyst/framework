package io.flowcatalyst.framework.measure.units;

import io.flowcatalyst.framework.measure.Decimals;
import io.flowcatalyst.framework.measure.MeasureException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A value in a unit (measure contract section 3.2). Immutable. Conversions within a dimension are
 * computed exactly and rounded once. Equality is numeric value and unit: {@code 1000 GRM} does not
 * equal {@code 1 KGM}, though {@link #compareTo} says they are the same amount. The wire form is
 * {@code {"value": "7831.2", "unit": "KGM"}}.
 *
 * @param value the value, any scale
 * @param unit the unit
 */
public record Quantity(BigDecimal value, Unit unit) implements Comparable<Quantity> {

  /**
   * Creates a quantity from text.
   *
   * @param value plain decimal text
   * @param unit a unit code or alias
   * @return the quantity
   * @throws MeasureException {@code DECIMAL_INVALID} or {@code UNIT_UNKNOWN}
   */
  public static Quantity parse(String value, String unit) {
    return new Quantity(Decimals.parse(value), Unit.of(unit));
  }

  /**
   * Converts within the dimension: computed exactly, divided to {@code scale} with {@code mode}
   * once.
   *
   * @param target the unit to convert to
   * @param scale the scale of the result
   * @param mode how to round
   * @return the converted quantity
   * @throws MeasureException {@code NO_CONVERSION} for a packaging unit, {@code
   *     DIMENSION_MISMATCH} across dimensions
   */
  public Quantity to(Unit target, int scale, RoundingMode mode) {
    return new Quantity(fractionIn(target).round(scale, mode), target);
  }

  /**
   * Converts without rounding. The result has the smallest scale (at least 0) that holds it.
   *
   * @param target the unit to convert to
   * @return the exact quantity
   * @throws MeasureException {@code CONVERSION_INEXACT} when the result does not terminate (kg to
   *     lb), {@code NO_CONVERSION}, {@code DIMENSION_MISMATCH}
   */
  public Quantity toExact(Unit target) {
    return new Quantity(fractionIn(target).exact(), target);
  }

  /**
   * The exact value in another unit as a ratio: {@code ((v + oF) * pF * qT - oT * qF * pT) / (qF *
   * pT)}.
   */
  Fraction fractionIn(Unit target) {
    checkConvertible(unit, target);
    BigDecimal pF = unit.numerator();
    BigDecimal qF = unit.denominator();
    BigDecimal pT = target.numerator();
    BigDecimal qT = target.denominator();
    BigDecimal numerator =
        value.add(unit.offset()).multiply(pF).multiply(qT).subtract(target.offset().multiply(qF).multiply(pT));
    return new Fraction(numerator, qF.multiply(pT));
  }

  private static void checkConvertible(Unit from, Unit to) {
    if (from.isPackaging() || to.isPackaging()) {
      throw new MeasureException(
          MeasureException.NO_CONVERSION, "a packaging unit converts only through a Packaging");
    }
    if (from.dimension() != to.dimension()) {
      throw new MeasureException(MeasureException.DIMENSION_MISMATCH, "the units are of different dimensions");
    }
  }

  /**
   * Adds; the other quantity is converted to this unit exactly first.
   *
   * @param other a quantity of the same dimension
   * @return the sum in this unit
   * @throws MeasureException {@code TEMPERATURE_NOT_ADDITIVE}, {@code CONVERSION_INEXACT}, {@code
   *     DIMENSION_MISMATCH}, {@code NO_CONVERSION}
   */
  public Quantity plus(Quantity other) {
    return new Quantity(value.add(addend(other)), unit);
  }

  /**
   * Subtracts; the other quantity is converted to this unit exactly first.
   *
   * @param other a quantity of the same dimension
   * @return the difference in this unit
   * @throws MeasureException as for {@link #plus}
   */
  public Quantity minus(Quantity other) {
    return new Quantity(value.subtract(addend(other)), unit);
  }

  private BigDecimal addend(Quantity other) {
    if (unit.dimension() == Dimension.TEMPERATURE || other.unit.dimension() == Dimension.TEMPERATURE) {
      throw new MeasureException(MeasureException.TEMPERATURE_NOT_ADDITIVE, "temperatures are not added or subtracted");
    }
    if (unit.equals(other.unit)) {
      return other.value;
    }
    return other.toExact(unit).value;
  }

  /**
   * Multiplies the value; the unit stays.
   *
   * @param factor the factor
   * @return the product, unrounded
   * @throws MeasureException {@code TEMPERATURE_NOT_ADDITIVE} for a temperature
   */
  public Quantity times(BigDecimal factor) {
    if (unit.dimension() == Dimension.TEMPERATURE) {
      throw new MeasureException(MeasureException.TEMPERATURE_NOT_ADDITIVE, "a temperature is not multiplied");
    }
    return new Quantity(value.multiply(factor), unit);
  }

  /**
   * Compares the amounts exactly, across units of one dimension.
   *
   * @param other a quantity of the same dimension
   * @return negative, zero or positive
   * @throws MeasureException {@code DIMENSION_MISMATCH}, or {@code NO_CONVERSION} for two different
   *     packaging units
   */
  @Override
  public int compareTo(Quantity other) {
    if (unit.equals(other.unit)) {
      return value.compareTo(other.value);
    }
    checkConvertible(unit, other.unit);
    return base().compareTo(other.base());
  }

  /** The value in the dimension's base unit as a ratio: {@code (v + o) * p / q}. */
  private Fraction base() {
    return new Fraction(value.add(unit.offset()).multiply(unit.numerator()), unit.denominator());
  }

  /**
   * Whether the value is zero.
   *
   * @return true for zero at any scale
   */
  public boolean isZero() {
    return value.signum() == 0;
  }

  /**
   * The sign of the value.
   *
   * @return -1, 0 or 1
   */
  public int signum() {
    return value.signum();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Quantity q && unit.equals(q.unit) && value.compareTo(q.value) == 0;
  }

  @Override
  public int hashCode() {
    return 31 * Decimals.hash(value) + unit.hashCode();
  }

  @Override
  public String toString() {
    return Decimals.format(value) + " " + unit.code();
  }
}
