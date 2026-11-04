package io.flowcatalyst.framework.measure.units;

import io.flowcatalyst.framework.measure.MeasureException;
import java.math.RoundingMode;

/**
 * The chargeable weight of a consignment (measure contract section 4): the larger of the actual
 * weight and the volume weight.
 */
public final class ChargeableWeight {

  private ChargeableWeight() {}

  /**
   * Computes the chargeable weight in kilograms, exactly, rounded once.
   *
   * @param actual the actual weight, a mass
   * @param volume the volume, a volume quantity
   * @param factor the carrier's density factor
   * @param scale the scale of the result
   * @param mode how to round
   * @return the larger of {@code actual} and {@code volume * factor}, in {@code KGM}
   * @throws MeasureException {@code DIMENSION_MISMATCH} when {@code actual} is not a mass or {@code
   *     volume} is not a volume
   */
  public static Quantity of(Quantity actual, Quantity volume, DensityFactor factor, int scale, RoundingMode mode) {
    if (actual.unit().dimension() != Dimension.MASS) {
      throw new MeasureException(MeasureException.DIMENSION_MISMATCH, "the actual weight must be a mass");
    }
    if (volume.unit().dimension() != Dimension.VOLUME) {
      throw new MeasureException(MeasureException.DIMENSION_MISMATCH, "the volume must be a volume");
    }
    Fraction actualKg = actual.fractionIn(Unit.KGM);
    Fraction cubicMetres = volume.fractionIn(Unit.MTQ);
    Fraction volumeKg = cubicMetres.times(factor.kgNumerator()).dividedBy(factor.kgDenominator());
    Fraction larger = actualKg.compareTo(volumeKg) >= 0 ? actualKg : volumeKg;
    return new Quantity(larger.round(scale, mode), Unit.KGM);
  }
}
