package io.flowcatalyst.framework.measure.units;

import java.util.Locale;

/** The dimension of a unit: units convert only within one dimension (measure contract section 3.1). */
public enum Dimension {
  /** Mass; base unit {@code KGM}. */
  MASS,
  /** Length; base unit {@code MTR}. */
  LENGTH,
  /** Area; base unit {@code MTK}. */
  AREA,
  /** Volume; base unit {@code MTQ}. */
  VOLUME,
  /** Counts; base unit {@code C62}. */
  COUNT,
  /** Temperature, affine: converts but does not add; base unit {@code KEL}. */
  TEMPERATURE,
  /** Packaging units (pallet, carton): no base unit, converted only through a {@link Packaging}. */
  PACKAGING;

  /**
   * The name as the data file writes it.
   *
   * @return lower case, for example {@code mass}
   */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}
