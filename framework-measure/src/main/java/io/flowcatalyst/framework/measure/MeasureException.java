package io.flowcatalyst.framework.measure;

/**
 * A failure of the measure contract (section 6): an {@link IllegalArgumentException} carrying one of
 * the contract's error codes. The message never contains secrets and is safe to show a caller.
 */
public final class MeasureException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  /** Text is not a plain decimal number. */
  public static final String DECIMAL_INVALID = "DECIMAL_INVALID";

  /** Division by zero. */
  public static final String DIVISION_BY_ZERO = "DIVISION_BY_ZERO";

  /** Not an ISO 4217 code in the embedded table. */
  public static final String CURRENCY_UNKNOWN = "CURRENCY_UNKNOWN";

  /** Two money values, or a rate and a money value, in different currencies. */
  public static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";

  /** The sum of an empty list of money has no currency. */
  public static final String SUM_EMPTY = "SUM_EMPTY";

  /** Allocation ratios that cannot be used. */
  public static final String ALLOCATION_INVALID = "ALLOCATION_INVALID";

  /** Not a unit code or alias. */
  public static final String UNIT_UNKNOWN = "UNIT_UNKNOWN";

  /** Units of two dimensions. */
  public static final String DIMENSION_MISMATCH = "DIMENSION_MISMATCH";

  /** A packaging unit has no conversion outside a packaging. */
  public static final String NO_CONVERSION = "NO_CONVERSION";

  /** The exact result does not terminate. */
  public static final String CONVERSION_INEXACT = "CONVERSION_INEXACT";

  /** Temperatures are not added, subtracted or multiplied. */
  public static final String TEMPERATURE_NOT_ADDITIVE = "TEMPERATURE_NOT_ADDITIVE";

  /** The quantity's unit is not a level of the packaging. */
  public static final String UNIT_NOT_IN_PACKAGING = "UNIT_NOT_IN_PACKAGING";

  /** A packaging that cannot be built. */
  public static final String PACKAGING_INVALID = "PACKAGING_INVALID";

  /** An exchange rate that is not positive. */
  public static final String RATE_INVALID = "RATE_INVALID";

  private final String code;

  /**
   * Creates the exception.
   *
   * @param code one of the codes of this class
   * @param message what went wrong, for a caller
   */
  public MeasureException(String code, String message) {
    super(message);
    this.code = code;
  }

  /**
   * The contract's error code.
   *
   * @return for example {@code CURRENCY_MISMATCH}
   */
  public String code() {
    return code;
  }
}
