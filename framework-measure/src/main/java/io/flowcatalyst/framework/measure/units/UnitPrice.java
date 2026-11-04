package io.flowcatalyst.framework.measure.units;

import io.flowcatalyst.framework.measure.MeasureException;
import io.flowcatalyst.framework.measure.money.Money;
import java.math.RoundingMode;

/**
 * A price per unit, a rate (measure contract section 4): {@code 0.40 ZAR} per {@code KGM}. The unit
 * is not a packaging or temperature unit. The wire form is {@code {"price": {"amount": "0.40",
 * "currency": "ZAR"}, "per": "KGM"}}.
 *
 * @param price the price of one {@code per}
 * @param per the unit the price is for
 */
public record UnitPrice(Money price, Unit per) {

  /**
   * Checks the unit.
   *
   * @throws MeasureException {@code NO_CONVERSION} for a packaging unit, {@code
   *     TEMPERATURE_NOT_ADDITIVE} for a temperature
   */
  public UnitPrice {
    if (per.isPackaging()) {
      throw new MeasureException(MeasureException.NO_CONVERSION, "a price per packaging unit has no conversion");
    }
    if (per.dimension() == Dimension.TEMPERATURE) {
      throw new MeasureException(MeasureException.TEMPERATURE_NOT_ADDITIVE, "a price per temperature unit is meaningless");
    }
  }

  /**
   * Prices a quantity: the quantity is converted to {@code per} and multiplied by the price, in one
   * exact computation rounded once to {@code scale}. {@code 0.40 ZAR/KGM x 7.8312 TNE} is {@code
   * 3132.480 ZAR} at scale 3.
   *
   * @param quantity a quantity of the same dimension as {@code per}
   * @param scale the scale of the money
   * @param mode how to round
   * @return the money, in the price's currency
   * @throws MeasureException {@code DIMENSION_MISMATCH} or {@code NO_CONVERSION}
   */
  public Money times(Quantity quantity, int scale, RoundingMode mode) {
    Fraction converted = quantity.fractionIn(per);
    return new Money(converted.times(price.amount()).round(scale, mode), price.currency());
  }

  /**
   * Prices a quantity without rounding: the quantity is converted to {@code per} exactly, and
   * multiplied by the price (the scales add).
   *
   * @param quantity a quantity of the same dimension as {@code per}
   * @return the money, unrounded
   * @throws MeasureException {@code CONVERSION_INEXACT} when the conversion does not terminate,
   *     {@code DIMENSION_MISMATCH}, {@code NO_CONVERSION}
   */
  public Money timesExact(Quantity quantity) {
    return price.times(quantity.toExact(per).value());
  }
}
