package io.flowcatalyst.framework.measure.units;

import io.flowcatalyst.framework.measure.Data;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The built-in units, read once from the embedded {@code units.json}. */
final class UnitTable {

  /** One unit's definition; {@code p}, {@code q} and {@code offset} are null for packaging. */
  record Def(
      String code,
      String symbol,
      Dimension dimension,
      @Nullable BigDecimal p,
      @Nullable BigDecimal q,
      @Nullable BigDecimal offset,
      List<String> aliases) {}

  static final Map<String, Def> BY_CODE;

  /** Lower-cased alias to code. */
  static final Map<String, String> ALIASES;

  static {
    Map<String, Def> codes = new LinkedHashMap<>();
    Map<String, String> aliases = new HashMap<>();
    for (Object o : Data.array(Data.object(Data.read("units.json")).get("units"))) {
      Map<?, ?> u = Data.object(o);
      String code = Data.string(u, "code");
      BigDecimal p = null;
      BigDecimal q = null;
      Object f = u.get("factor");
      if (f != null) {
        Map<?, ?> fm = Data.object(f);
        p = new BigDecimal(Data.string(fm, "p"));
        q = new BigDecimal(Data.string(fm, "q"));
      }
      BigDecimal offset = u.get("offset") == null ? null : new BigDecimal(Data.string(u, "offset"));
      Dimension dim = Dimension.valueOf(Data.string(u, "dimension").toUpperCase(Locale.ROOT));
      List<String> al = new ArrayList<>();
      for (Object a : Data.array(u.get("aliases"))) {
        String alias = (String) a;
        al.add(alias);
        String previous = aliases.put(alias.toLowerCase(Locale.ROOT), code);
        if (previous != null) {
          throw new IllegalStateException("units.json: the alias " + alias + " is used twice");
        }
      }
      codes.put(code, new Def(code, Data.string(u, "symbol"), dim, p, q, offset, List.copyOf(al)));
    }
    BY_CODE = Map.copyOf(codes);
    ALIASES = Map.copyOf(aliases);
  }

  private UnitTable() {}
}
