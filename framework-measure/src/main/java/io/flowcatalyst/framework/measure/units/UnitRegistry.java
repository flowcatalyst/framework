package io.flowcatalyst.framework.measure.units;

import io.flowcatalyst.framework.measure.MeasureException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns text into a {@link Unit}: the code (case-sensitive), a built-in alias, or an alias an
 * application registered for a partner's own codes. Aliases are case-insensitive. Immutable: {@link
 * #with} returns a new registry; the built-in table cannot be changed.
 */
public final class UnitRegistry {

  private static final UnitRegistry BUILTIN = new UnitRegistry(Map.of());

  private final Map<String, Unit> extra;

  private UnitRegistry(Map<String, Unit> extra) {
    this.extra = extra;
  }

  /**
   * The registry of the built-in codes and aliases.
   *
   * @return the registry
   */
  public static UnitRegistry builtin() {
    return BUILTIN;
  }

  /**
   * A registry with one more alias.
   *
   * @param alias the partner's spelling, matched case-insensitively
   * @param unit the unit it means
   * @return a new registry
   * @throws MeasureException {@code UNIT_UNKNOWN} when the alias is blank, or is a code or built-in
   *     alias already
   */
  public UnitRegistry with(String alias, Unit unit) {
    String key = alias.toLowerCase(Locale.ROOT);
    if (alias.isBlank() || UnitTable.BY_CODE.containsKey(alias) || UnitTable.ALIASES.containsKey(key)) {
      throw new MeasureException(MeasureException.UNIT_UNKNOWN, "the alias is empty or already a built-in code or alias");
    }
    Map<String, Unit> m = new HashMap<>(extra);
    m.put(key, unit);
    return new UnitRegistry(Map.copyOf(m));
  }

  /**
   * Parses a code or an alias: the code exactly, then the built-in aliases, then this registry's
   * own, both ignoring case.
   *
   * @param text the text
   * @return the unit
   * @throws MeasureException {@code UNIT_UNKNOWN}
   */
  public Unit parse(String text) {
    if (UnitTable.BY_CODE.containsKey(text)) {
      return new Unit(text);
    }
    String key = text.toLowerCase(Locale.ROOT);
    String code = UnitTable.ALIASES.get(key);
    if (code != null) {
      return new Unit(code);
    }
    Unit unit = extra.get(key);
    if (unit != null) {
      return unit;
    }
    throw new MeasureException(MeasureException.UNIT_UNKNOWN, "unknown unit");
  }
}
