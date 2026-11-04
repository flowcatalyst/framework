package io.flowcatalyst.framework.measure.units;

import io.flowcatalyst.framework.measure.Decimals;
import io.flowcatalyst.framework.measure.MeasureException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The levels of one product (measure contract section 3.3): {@code
 * Packaging.of(Unit.C62).level(Unit.XCT, 24).level(Unit.XPX, 40)} means a carton holds 24 each and
 * a pallet holds 40 cartons. The first unit, the product's smallest handling unit, is a count or
 * mass unit; every further level is a packaging unit and says how many of the level below it holds
 * (a positive integer). Immutable.
 *
 * @param units the level units from the smallest up; at least one
 * @param perBelow how many of the level below each level holds, aligned with {@code units}; the
 *     first entry is 1
 */
public record Packaging(List<Unit> units, List<BigInteger> perBelow) {

  /**
   * Checks the levels.
   *
   * @throws MeasureException {@code PACKAGING_INVALID} for no levels, a first unit that is not a
   *     count or mass unit, a later unit that is not a packaging unit, a unit used twice, or a
   *     count that is not positive
   */
  public Packaging {
    if (units.isEmpty() || units.size() != perBelow.size()) {
      throw new MeasureException(MeasureException.PACKAGING_INVALID, "a packaging needs a first unit");
    }
    Dimension first = units.getFirst().dimension();
    if (first != Dimension.COUNT && first != Dimension.MASS) {
      throw new MeasureException(MeasureException.PACKAGING_INVALID, "the first unit must be a count or mass unit");
    }
    for (int i = 0; i < units.size(); i++) {
      if (i > 0 && !units.get(i).isPackaging()) {
        throw new MeasureException(MeasureException.PACKAGING_INVALID, "a level above the first must be a packaging unit");
      }
      if (units.indexOf(units.get(i)) != i) {
        throw new MeasureException(MeasureException.PACKAGING_INVALID, "a unit is used twice");
      }
      if (perBelow.get(i).signum() <= 0) {
        throw new MeasureException(MeasureException.PACKAGING_INVALID, "a level holds a positive number of the level below");
      }
    }
    units = List.copyOf(units);
    perBelow = List.copyOf(perBelow);
  }

  /**
   * Starts a packaging at its smallest unit.
   *
   * @param first a count or mass unit
   * @return the packaging with one level
   * @throws MeasureException {@code PACKAGING_INVALID}
   */
  public static Packaging of(Unit first) {
    return new Packaging(List.of(first), List.of(BigInteger.ONE));
  }

  /**
   * Adds a level above the last.
   *
   * @param unit a packaging unit
   * @param count how many of the level below it holds, at least 1
   * @return a new packaging
   * @throws MeasureException {@code PACKAGING_INVALID}
   */
  public Packaging level(Unit unit, long count) {
    List<Unit> us = new ArrayList<>(units);
    List<BigInteger> ps = new ArrayList<>(perBelow);
    us.add(unit);
    ps.add(BigInteger.valueOf(count));
    return new Packaging(us, ps);
  }

  /** How many smallest units one of the level holds. */
  private BigDecimal size(int level) {
    BigInteger n = BigInteger.ONE;
    for (int i = 1; i <= level; i++) {
      n = n.multiply(perBelow.get(i));
    }
    return new BigDecimal(n);
  }

  private int indexOf(Unit unit) {
    int i = units.indexOf(unit);
    if (i < 0) {
      throw new MeasureException(MeasureException.UNIT_NOT_IN_PACKAGING, "the unit is not a level of the packaging");
    }
    return i;
  }

  private Fraction fraction(Quantity q, Unit target) {
    int from = indexOf(q.unit());
    int to = indexOf(target);
    return new Fraction(q.value().multiply(size(from)), size(to));
  }

  /**
   * Converts between two levels (going down multiplies, going up divides), rounded once.
   *
   * @param quantity a quantity in a level
   * @param unit the level to convert to
   * @param scale the scale of the result
   * @param mode how to round
   * @return the converted quantity
   * @throws MeasureException {@code UNIT_NOT_IN_PACKAGING}
   */
  public Quantity convert(Quantity quantity, Unit unit, int scale, RoundingMode mode) {
    return new Quantity(fraction(quantity, unit).round(scale, mode), unit);
  }

  /**
   * Converts between two levels without rounding; the result has the smallest scale (at least 0)
   * that holds it.
   *
   * @param quantity a quantity in a level
   * @param unit the level to convert to
   * @return the exact quantity
   * @throws MeasureException {@code UNIT_NOT_IN_PACKAGING}, or {@code CONVERSION_INEXACT} when the
   *     result does not terminate
   */
  public Quantity convertExact(Quantity quantity, Unit unit) {
    return new Quantity(fraction(quantity, unit).exact(), unit);
  }

  /**
   * The quantity as whole units from the largest level down, with any fractional rest at the
   * smallest level: 1000 each in {@code [C62, XCT 24, XPX 40]} is {@code [1 XPX, 1 XCT, 16 C62]}.
   * Every level appears, zero included, largest first. A negative quantity is broken down as its
   * absolute value and every part negated.
   *
   * @param quantity a quantity in a level
   * @return one quantity per level, largest level first
   * @throws MeasureException {@code UNIT_NOT_IN_PACKAGING}
   */
  public List<Quantity> breakdown(Quantity quantity) {
    int from = indexOf(quantity.unit());
    boolean negative = quantity.value().signum() < 0;
    BigDecimal rest = Decimals.nonNegativeScale(quantity.value().abs().multiply(size(from)));
    List<Quantity> out = new ArrayList<>();
    for (int level = units.size() - 1; level >= 1; level--) {
      BigDecimal whole = rest.divide(size(level), 0, RoundingMode.FLOOR);
      rest = rest.subtract(whole.multiply(size(level)));
      out.add(new Quantity(negative ? whole.negate() : whole, units.get(level)));
    }
    out.add(new Quantity(negative ? rest.negate() : rest, units.getFirst()));
    return List.copyOf(out);
  }
}
