package io.flowcatalyst.framework.measure.money;

import io.flowcatalyst.framework.measure.Data;
import io.flowcatalyst.framework.measure.MeasureException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An ISO 4217 alphabetic code and its minor units (ZAR 2, JPY 0, BHD 3). The table is
 * {@code conformance/measure/iso4217.json}, embedded; both languages read the same file. The wire
 * form is the code as a string.
 *
 * @param code three upper-case letters, in the table
 */
public record Currency(String code) {

  private static final Pattern SHAPE = Pattern.compile("[A-Z]{3}");

  /** The embedded table: code to minor units. */
  private static final Map<String, Integer> TABLE = load();

  /**
   * Checks the code against the table.
   *
   * @throws MeasureException {@code CURRENCY_UNKNOWN} when the code is not in the table
   */
  public Currency {
    if (!SHAPE.matcher(code).matches() || !TABLE.containsKey(code)) {
      throw new MeasureException(MeasureException.CURRENCY_UNKNOWN, "unknown currency code");
    }
  }

  /**
   * The currency with a code.
   *
   * @param code for example {@code ZAR}
   * @return the currency
   * @throws MeasureException {@code CURRENCY_UNKNOWN}
   */
  public static Currency of(String code) {
    return new Currency(code);
  }

  /**
   * The number of minor-unit digits: ZAR 2, JPY 0, BHD 3.
   *
   * @return the digits after the decimal point of the smallest unit
   */
  public int minorUnits() {
    return Objects.requireNonNull(TABLE.get(code));
  }

  /**
   * The JDK's currency, for interop.
   *
   * @return the {@link java.util.Currency} with the same code
   */
  public java.util.Currency toJdk() {
    return java.util.Currency.getInstance(code);
  }

  @Override
  public String toString() {
    return code;
  }

  private static Map<String, Integer> load() {
    Map<String, Integer> m = new HashMap<>();
    for (Object o : Data.array(Data.object(Data.read("iso4217.json")).get("currencies"))) {
      Map<?, ?> c = Data.object(o);
      m.put(Data.string(c, "code"), Data.integer(c, "minorUnits"));
    }
    return Map.copyOf(m);
  }
}
