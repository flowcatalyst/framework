package io.flowcatalyst.framework.measure.units;

import io.flowcatalyst.framework.measure.MeasureException;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * A built-in unit of measure (measure contract section 3.1), identified by its UN/ECE
 * Recommendation 20 code, which is also its wire form. Its definition against the dimension's base
 * unit is {@code base = (value + offset) * factor}, with the factor an exact fraction
 * {@code numerator / denominator} and the offset zero except for temperature. The table is
 * {@code conformance/measure/units.json}, embedded, and cannot be changed; an application's own
 * spellings go in a {@link UnitRegistry}.
 *
 * @param code a code of the table, matched exactly
 */
public record Unit(String code) {

  /** Milligram. */
  public static final Unit MGM = new Unit("MGM");

  /** Gram. */
  public static final Unit GRM = new Unit("GRM");

  /** Kilogram, the base unit of mass. */
  public static final Unit KGM = new Unit("KGM");

  /** Tonne. */
  public static final Unit TNE = new Unit("TNE");

  /** Pound. */
  public static final Unit LBR = new Unit("LBR");

  /** Ounce. */
  public static final Unit ONZ = new Unit("ONZ");

  /** Short ton. */
  public static final Unit STN = new Unit("STN");

  /** Long ton. */
  public static final Unit LTN = new Unit("LTN");

  /** Millimetre. */
  public static final Unit MMT = new Unit("MMT");

  /** Centimetre. */
  public static final Unit CMT = new Unit("CMT");

  /** Metre, the base unit of length. */
  public static final Unit MTR = new Unit("MTR");

  /** Kilometre. */
  public static final Unit KMT = new Unit("KMT");

  /** Inch. */
  public static final Unit INH = new Unit("INH");

  /** Foot. */
  public static final Unit FOT = new Unit("FOT");

  /** Yard. */
  public static final Unit YRD = new Unit("YRD");

  /** Statute mile. */
  public static final Unit SMI = new Unit("SMI");

  /** Nautical mile. */
  public static final Unit NMI = new Unit("NMI");

  /** Square metre, the base unit of area. */
  public static final Unit MTK = new Unit("MTK");

  /** Square foot. */
  public static final Unit FTK = new Unit("FTK");

  /** Cubic centimetre. */
  public static final Unit CMQ = new Unit("CMQ");

  /** Millilitre. */
  public static final Unit MLT = new Unit("MLT");

  /** Litre. */
  public static final Unit LTR = new Unit("LTR");

  /** Cubic metre, the base unit of volume. */
  public static final Unit MTQ = new Unit("MTQ");

  /** Cubic inch. */
  public static final Unit INQ = new Unit("INQ");

  /** Cubic foot. */
  public static final Unit FTQ = new Unit("FTQ");

  /** US gallon. */
  public static final Unit GLL = new Unit("GLL");

  /** Imperial gallon. */
  public static final Unit GLI = new Unit("GLI");

  /** One (each), the base unit of count. */
  public static final Unit C62 = new Unit("C62");

  /** Each. */
  public static final Unit EA = new Unit("EA");

  /** Piece. */
  public static final Unit H87 = new Unit("H87");

  /** Dozen. */
  public static final Unit DZN = new Unit("DZN");

  /** Kelvin, the base unit of temperature. */
  public static final Unit KEL = new Unit("KEL");

  /** Degree Celsius. */
  public static final Unit CEL = new Unit("CEL");

  /** Degree Fahrenheit. */
  public static final Unit FAH = new Unit("FAH");

  /** Pallet. */
  public static final Unit XPX = new Unit("XPX");

  /** Carton. */
  public static final Unit XCT = new Unit("XCT");

  /** Case. */
  public static final Unit XCS = new Unit("XCS");

  /** Box. */
  public static final Unit XBX = new Unit("XBX");

  /** Pack. */
  public static final Unit XPK = new Unit("XPK");

  /** Bag. */
  public static final Unit XBG = new Unit("XBG");

  /** Crate. */
  public static final Unit XCR = new Unit("XCR");

  /** Drum. */
  public static final Unit XDR = new Unit("XDR");

  /**
   * Checks the code against the table.
   *
   * @throws MeasureException {@code UNIT_UNKNOWN} when the code is not in the table
   */
  public Unit {
    if (!UnitTable.BY_CODE.containsKey(code)) {
      throw new MeasureException(MeasureException.UNIT_UNKNOWN, "unknown unit");
    }
  }

  /**
   * Parses a code (case-sensitive) or a built-in alias (case-insensitive).
   *
   * @param text for example {@code KGM}, {@code kg}, {@code m3}
   * @return the unit
   * @throws MeasureException {@code UNIT_UNKNOWN}
   */
  public static Unit of(String text) {
    return UnitRegistry.builtin().parse(text);
  }

  private UnitTable.Def def() {
    return Objects.requireNonNull(UnitTable.BY_CODE.get(code));
  }

  /**
   * The symbol, for display.
   *
   * @return for example {@code kg}, {@code m³}
   */
  public String symbol() {
    return def().symbol();
  }

  /**
   * The dimension.
   *
   * @return the dimension
   */
  public Dimension dimension() {
    return def().dimension();
  }

  /**
   * Whether this is a packaging unit (pallet, carton, ...).
   *
   * @return true for the packaging dimension
   */
  public boolean isPackaging() {
    return dimension() == Dimension.PACKAGING;
  }

  /**
   * The factor's numerator {@code p} of {@code base = (value + offset) * p / q}.
   *
   * @return {@code p}
   * @throws MeasureException {@code NO_CONVERSION} for a packaging unit
   */
  public BigDecimal numerator() {
    BigDecimal p = def().p();
    if (p == null) {
      throw new MeasureException(MeasureException.NO_CONVERSION, "a packaging unit has no conversion");
    }
    return p;
  }

  /**
   * The factor's denominator {@code q}.
   *
   * @return {@code q}
   * @throws MeasureException {@code NO_CONVERSION} for a packaging unit
   */
  public BigDecimal denominator() {
    BigDecimal q = def().q();
    if (q == null) {
      throw new MeasureException(MeasureException.NO_CONVERSION, "a packaging unit has no conversion");
    }
    return q;
  }

  /**
   * The offset added to a value before the factor is applied.
   *
   * @return zero except for temperature
   * @throws MeasureException {@code NO_CONVERSION} for a packaging unit
   */
  public BigDecimal offset() {
    BigDecimal o = def().offset();
    if (o == null) {
      throw new MeasureException(MeasureException.NO_CONVERSION, "a packaging unit has no conversion");
    }
    return o;
  }

  @Override
  public String toString() {
    return code;
  }
}
