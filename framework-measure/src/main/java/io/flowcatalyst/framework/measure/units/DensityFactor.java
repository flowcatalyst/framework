package io.flowcatalyst.framework.measure.units;

import io.flowcatalyst.framework.measure.MeasureException;
import java.math.BigDecimal;

/**
 * The volumetric factor of a carrier, in one of the two forms the trade uses: kilograms per cubic
 * metre ({@code 167} for air, {@code 333} for road) or the divisor {@code 6000} cubic centimetres
 * per kilogram. Held as an exact ratio of kilograms per cubic metre.
 *
 * @param kgNumerator numerator of the kilograms per cubic metre, greater than zero
 * @param kgDenominator denominator of the kilograms per cubic metre, greater than zero
 */
public record DensityFactor(BigDecimal kgNumerator, BigDecimal kgDenominator) {

  /**
   * Checks the ratio.
   *
   * @throws MeasureException {@code RATE_INVALID} when either part is not greater than zero
   */
  public DensityFactor {
    if (kgNumerator.signum() <= 0 || kgDenominator.signum() <= 0) {
      throw new MeasureException(MeasureException.RATE_INVALID, "the density factor must be greater than zero");
    }
  }

  /**
   * Kilograms per cubic metre: {@code 167} means 167 kg for each m³.
   *
   * @param kg kilograms per cubic metre
   * @return the factor
   */
  public static DensityFactor kgPerCubicMetre(long kg) {
    return new DensityFactor(BigDecimal.valueOf(kg), BigDecimal.ONE);
  }

  /**
   * Kilograms per cubic metre as a decimal.
   *
   * @param kg kilograms per cubic metre
   * @return the factor
   */
  public static DensityFactor kgPerCubicMetre(BigDecimal kg) {
    return new DensityFactor(kg, BigDecimal.ONE);
  }

  /**
   * The divisor form: {@code 6000} means one kilogram for each 6000 cm³ (166.666... kg per m³).
   *
   * @param cubicCentimetresPerKg the divisor
   * @return the factor
   */
  public static DensityFactor divisor(long cubicCentimetresPerKg) {
    return divisor(BigDecimal.valueOf(cubicCentimetresPerKg));
  }

  /**
   * The divisor form as a decimal.
   *
   * @param cubicCentimetresPerKg the divisor
   * @return the factor
   */
  public static DensityFactor divisor(BigDecimal cubicCentimetresPerKg) {
    return new DensityFactor(BigDecimal.valueOf(1_000_000), cubicCentimetresPerKg);
  }
}
