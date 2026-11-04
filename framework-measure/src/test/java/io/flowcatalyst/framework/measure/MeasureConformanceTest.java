package io.flowcatalyst.framework.measure;

import io.flowcatalyst.framework.conformance.JsonVectors;
import io.flowcatalyst.framework.measure.money.ExchangeRate;
import io.flowcatalyst.framework.measure.money.Money;
import io.flowcatalyst.framework.measure.units.ChargeableWeight;
import io.flowcatalyst.framework.measure.units.DensityFactor;
import io.flowcatalyst.framework.measure.units.Dimension;
import io.flowcatalyst.framework.measure.units.Packaging;
import io.flowcatalyst.framework.measure.units.Quantity;
import io.flowcatalyst.framework.measure.units.Unit;
import io.flowcatalyst.framework.measure.units.UnitPrice;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes and checks the vectors of {@code conformance/measure/} (measure contract section 7). The
 * Java code computes every expected value; run with {@code -Dconformance.update=true} to rewrite
 * the files. Go ({@code go/internal/conformance}, {@code measure_test.go}) compares every case as
 * text. A case holds the inputs as text, and either {@code expect} (a string, a money or quantity
 * object, or an array of them) or {@code error} (an error code of the contract).
 */
class MeasureConformanceTest {

  private static final RoundingMode[] MODES = {
    RoundingMode.UP,
    RoundingMode.DOWN,
    RoundingMode.CEILING,
    RoundingMode.FLOOR,
    RoundingMode.HALF_UP,
    RoundingMode.HALF_DOWN,
    RoundingMode.HALF_EVEN
  };

  private static ObjectNode doc(String description, String howToRun) {
    ObjectNode o = JsonVectors.mapper().createObjectNode();
    o.put("description", description);
    o.put("howToRun", howToRun);
    return o;
  }

  private static ObjectNode obj() {
    return JsonVectors.mapper().createObjectNode();
  }

  private static ObjectNode money(Money m) {
    ObjectNode o = obj();
    o.put("amount", Decimals.format(m.amount()));
    o.put("currency", m.currency().code());
    return o;
  }

  private static ObjectNode money(String amount, String currency) {
    ObjectNode o = obj();
    o.put("amount", amount);
    o.put("currency", currency);
    return o;
  }

  private static ObjectNode quantity(Quantity q) {
    ObjectNode o = obj();
    o.put("value", Decimals.format(q.value()));
    o.put("unit", q.unit().code());
    return o;
  }

  private static ObjectNode quantity(String value, String unit) {
    ObjectNode o = obj();
    o.put("value", value);
    o.put("unit", unit);
    return o;
  }

  private static Money m(JsonNode n) {
    return Money.parse(n.get("amount").asString(), n.get("currency").asString());
  }

  private static Quantity q(JsonNode n) {
    return Quantity.parse(n.get("value").asString(), n.get("unit").asString());
  }

  /** Runs {@code f}; its node goes under {@code expect}, or a contract failure under {@code error}. */
  private static void expect(ObjectNode c, Supplier<JsonNode> f) {
    try {
      c.set("expect", f.get());
    } catch (MeasureException e) {
      c.put("error", e.code());
    }
  }

  private static JsonNode text(String s) {
    return JsonVectors.mapper().getNodeFactory().stringNode(s);
  }

  private static JsonNode number(int n) {
    return JsonVectors.mapper().getNodeFactory().numberNode(n);
  }

  private static BigDecimal d(String s) {
    return Decimals.parse(s);
  }

  // ---- decimal.json ---------------------------------------------------------------------------

  @Test
  void decimal() {
    ObjectNode doc =
        doc(
            "Decimal text and arithmetic (measure contract section 1). parse: the input is plain"
                + " decimal text or a DECIMAL_INVALID error; expect is the value formatted back (the scale is"
                + " kept, leading zeros dropped) and scale the number of fraction digits. arithmetic: a and b"
                + " parsed, op applied, expect the formatted result (add and subtract take the larger scale,"
                + " multiply adds the scales). compare: expect is -1, 0 or 1, numerically.",
            "For each case parse the inputs with the type's text parser, apply the operation, and compare the"
                + " result's plain text with expect as a string, or the error code with error.");
    ArrayNode parse = doc.putArray("parse");
    for (String s :
        List.of(
            "3132.40", "0.0", "0", "007.5", "-0.0", "-0", "12", "-12.50", "0.000001", "123456789012345678901234567890.123456789",
            "-0.40", "00", "1.", ".5", "1e3", "1E-2", "+1", " 1", "1 ", "", "-", "--1", "1,000", "1.2.3", "NaN", "Infinity",
            "0x10", "1_000", "٣", "1.٣", "-.5", "- 1", "1.0e0")) {
      ObjectNode c = obj();
      c.put("input", s);
      try {
        BigDecimal v = Decimals.parse(s);
        c.put("expect", Decimals.format(v));
        c.put("scale", v.scale());
      } catch (MeasureException e) {
        c.put("error", e.code());
      }
      parse.add(c);
    }
    ArrayNode arithmetic = doc.putArray("arithmetic");
    String[] values = {"0", "1", "-1", "1.5", "-1.50", "0.10", "0.0", "100", "3132.48", "0.000001", "7.8312", "-0.005"};
    for (String a : values) {
      for (String b : values) {
        for (String op : List.of("add", "subtract", "multiply")) {
          ObjectNode c = obj();
          c.put("op", op);
          c.put("a", a);
          c.put("b", b);
          BigDecimal x = d(a);
          BigDecimal y = d(b);
          BigDecimal r =
              switch (op) {
                case "add" -> x.add(y);
                case "subtract" -> x.subtract(y);
                default -> x.multiply(y);
              };
          c.put("expect", Decimals.format(r));
          arithmetic.add(c);
        }
      }
    }
    ArrayNode compare = doc.putArray("compare");
    for (String[] p :
        new String[][] {
          {"2.0", "2.00"}, {"1", "2"}, {"2", "1"}, {"-1", "1"}, {"0", "0.000"}, {"-0.0", "0"}, {"10", "9.99"},
          {"1.0000001", "1"}, {"-3", "-2.9"}
        }) {
      ObjectNode c = obj();
      c.put("a", p[0]);
      c.put("b", p[1]);
      c.put("expect", d(p[0]).compareTo(d(p[1])));
      compare.add(c);
    }
    JsonVectors.check("measure/decimal.json", doc);
  }

  // ---- rounding.json --------------------------------------------------------------------------

  @Test
  void rounding() {
    ObjectNode doc =
        doc(
            "Rounding and division (measure contract section 1). round: value setScale(scale, mode), the"
                + " seven modes at and around the half-way points, negatives included; expect is the plain text"
                + " of the result (raising the scale appends zeros). divide: a / b to scale with mode;"
                + " DIVISION_BY_ZERO for a zero divisor.",
            "Parse value, a and b as decimals; round or divide with the named mode; compare the plain text"
                + " with expect, or the error code with error.");
    ArrayNode round = doc.putArray("round");
    String[] values = {
      "0", "0.00", "0.4", "0.5", "0.6", "1.5", "2.5", "3.5", "-0.4", "-0.5", "-0.6", "-1.5", "-2.5", "-3.5", "1.25", "1.35",
      "1.45", "-1.25", "-1.35", "1.2500", "1.2501", "1.2499", "-1.2501", "-1.2499", "0.05", "0.15", "0.25", "0.35", "5", "-5", "10",
      "123.456", "-123.456", "0.0049", "0.0051", "-0.0050", "99.995", "-99.995", "1E3"
    };
    for (String v : values) {
      if (v.contains("E")) {
        continue;
      }
      for (int scale : new int[] {0, 1, 2, 3}) {
        for (RoundingMode mode : MODES) {
          ObjectNode c = obj();
          c.put("value", v);
          c.put("scale", scale);
          c.put("mode", mode.name());
          c.put("expect", Decimals.format(d(v).setScale(scale, mode)));
          round.add(c);
        }
      }
    }
    ArrayNode divide = doc.putArray("divide");
    for (String a : List.of("1", "2", "10", "-10", "7", "0.5", "5", "-5", "0", "2.50")) {
      for (String b : List.of("3", "8", "-3", "0.3", "2", "0", "0.00")) {
        for (int scale : new int[] {0, 2}) {
          for (RoundingMode mode : MODES) {
            ObjectNode c = obj();
            c.put("a", a);
            c.put("b", b);
            c.put("scale", scale);
            c.put("mode", mode.name());
            expect(c, () -> text(Decimals.format(Decimals.divide(d(a), d(b), scale, mode))));
            divide.add(c);
          }
        }
      }
    }
    JsonVectors.check("measure/rounding.json", doc);
  }

  // ---- money.json -----------------------------------------------------------------------------

  @Test
  void moneyVectors() {
    ObjectNode doc =
        doc(
            "Currency, money arithmetic, allocation and exchange (measure contract section 2). currency: the"
                + " code, expect its minor units or error CURRENCY_UNKNOWN. plus, minus, times, round, compare,"
                + " sum, allocate and exchange: inputs as {amount, currency} objects with string amounts;"
                + " expect is a money object (amount keeps its scale), a number for compare, an array for sum"
                + " and allocate; round has scale null for the currency's minor units.",
            "Build the money values from the strings, apply the operation, compare amounts as plain text and"
                + " currencies as codes with expect, or the error code with error. Allocation parts are in order.");
    ArrayNode currency = doc.putArray("currency");
    for (String code : List.of("ZAR", "USD", "EUR", "JPY", "BHD", "KWD", "CLP", "GBP", "XXA", "zar", "ZA", "ZARR", "", "ZA1", "XAU")) {
      ObjectNode c = obj();
      c.put("code", code);
      expect(c, () -> number(io.flowcatalyst.framework.measure.money.Currency.of(code).minorUnits()));
      currency.add(c);
    }
    String[][] pairs = {
      {"10.00", "ZAR", "2.55", "ZAR"}, {"10", "ZAR", "2.555", "ZAR"}, {"-5.5", "USD", "5.50", "USD"},
      {"0.1", "EUR", "0.2", "EUR"}, {"10", "ZAR", "1", "USD"}, {"100", "JPY", "0.5", "JPY"}, {"1.234", "BHD", "0.0006", "BHD"}
    };
    ArrayNode plus = doc.putArray("plus");
    ArrayNode minus = doc.putArray("minus");
    ArrayNode compare = doc.putArray("compare");
    for (String[] p : pairs) {
      for (var op : List.of(plus, minus, compare)) {
        ObjectNode c = obj();
        c.set("a", money(p[0], p[1]));
        c.set("b", money(p[2], p[3]));
        if (op == plus) {
          expect(c, () -> money(m(c.get("a")).plus(m(c.get("b")))));
        } else if (op == minus) {
          expect(c, () -> money(m(c.get("a")).minus(m(c.get("b")))));
        } else {
          expect(c, () -> number(m(c.get("a")).compareTo(m(c.get("b")))));
        }
        op.add(c);
      }
    }
    ArrayNode times = doc.putArray("times");
    for (String[] p : new String[][] {{"0.40", "ZAR", "7831.2"}, {"10.00", "ZAR", "0.5"}, {"3", "JPY", "-2.5"}, {"1.005", "USD", "100"}, {"0", "ZAR", "9.99"}}) {
      ObjectNode c = obj();
      c.set("a", money(p[0], p[1]));
      c.put("factor", p[2]);
      expect(c, () -> money(m(c.get("a")).times(d(p[2]))));
      times.add(c);
    }
    ArrayNode round = doc.putArray("round");
    for (String[] p :
        new String[][] {
          {"10.005", "ZAR"}, {"10.015", "ZAR"}, {"-10.005", "ZAR"}, {"2.5", "JPY"}, {"3.5", "JPY"}, {"-2.5", "JPY"},
          {"1.2345", "BHD"}, {"1.2355", "BHD"}, {"3132.480", "ZAR"}, {"0.004", "ZAR"}, {"-0.004", "ZAR"}
        }) {
      for (RoundingMode mode : MODES) {
        for (Integer scale : new Integer[] {null, 0, 4}) {
          ObjectNode c = obj();
          c.set("a", money(p[0], p[1]));
          c.put("mode", mode.name());
          if (scale == null) {
            c.putNull("scale");
          } else {
            c.put("scale", scale);
          }
          Money x = m(c.get("a"));
          expect(c, () -> money(scale == null ? x.round(mode) : x.round(scale, mode)));
          round.add(c);
        }
      }
    }
    ArrayNode sum = doc.putArray("sum");
    for (List<Money> list :
        List.of(
            List.of(Money.parse("1.10", "ZAR"), Money.parse("2.2", "ZAR"), Money.parse("0.005", "ZAR")),
            List.of(Money.parse("5", "USD")),
            List.<Money>of(),
            List.of(Money.parse("1", "ZAR"), Money.parse("1", "USD")))) {
      ObjectNode c = obj();
      ArrayNode in = c.putArray("values");
      list.forEach(x -> in.add(money(x)));
      expect(c, () -> {
        ArrayNode out = JsonVectors.mapper().createArrayNode();
        out.add(money(Money.sum(list)));
        return out;
      });
      sum.add(c);
    }
    ArrayNode allocate = doc.putArray("allocate");
    String[][] ratioSets = {
      {"1", "1", "1"}, {"1", "2", "3"}, {"70", "30"}, {"1"}, {"0", "1", "1"}, {"1", "0", "1"}, {"1", "1", "0"}, {"0.5", "0.25", "0.25"},
      {"1", "1", "1", "1", "1", "1", "1"}, {"3", "0", "7", "0", "5"}, {"0", "0"}, {"1", "-1"}, {}, {"0.1", "0.2", "0.3"}
    };
    String[][] amounts = {
      {"100.00", "ZAR"}, {"0.05", "ZAR"}, {"10", "ZAR"}, {"-100.00", "ZAR"}, {"-0.05", "ZAR"}, {"0", "ZAR"}, {"100", "JPY"},
      {"1", "JPY"}, {"-7", "JPY"}, {"1.234", "BHD"}, {"99.999", "ZAR"}, {"-99.995", "ZAR"}, {"0.01", "USD"}
    };
    for (String[] a : amounts) {
      for (String[] ratios : ratioSets) {
        for (RoundingMode mode : new RoundingMode[] {RoundingMode.HALF_EVEN, RoundingMode.DOWN, RoundingMode.HALF_UP, RoundingMode.CEILING}) {
          ObjectNode c = obj();
          c.set("amount", money(a[0], a[1]));
          ArrayNode r = c.putArray("ratios");
          for (String s : ratios) {
            r.add(s);
          }
          c.put("mode", mode.name());
          List<BigDecimal> list = new ArrayList<>();
          for (String s : ratios) {
            list.add(d(s));
          }
          Money x = m(c.get("amount"));
          expect(c, () -> {
            ArrayNode out = JsonVectors.mapper().createArrayNode();
            x.allocate(list, mode).forEach(p -> out.add(money(p)));
            return out;
          });
          allocate.add(c);
        }
      }
    }
    ArrayNode exchange = doc.putArray("exchange");
    String[][] rates = {
      {"USD", "ZAR", "18.3456", "100.00", "USD"}, {"USD", "ZAR", "18.3456", "0.01", "USD"}, {"ZAR", "USD", "0.054513", "1234.56", "ZAR"},
      {"USD", "JPY", "149.5", "10.01", "USD"}, {"EUR", "BHD", "0.41", "2.5", "EUR"}, {"USD", "ZAR", "18", "1", "EUR"},
      {"USD", "ZAR", "0", "1", "USD"}, {"USD", "ZAR", "-1", "1", "USD"}, {"USD", "ZAR", "18.3456", "-100.00", "USD"}
    };
    for (String[] r : rates) {
      for (RoundingMode mode : new RoundingMode[] {RoundingMode.HALF_EVEN, RoundingMode.DOWN, RoundingMode.UP}) {
        ObjectNode c = obj();
        c.put("from", r[0]);
        c.put("to", r[1]);
        c.put("rate", r[2]);
        c.set("amount", money(r[3], r[4]));
        c.put("mode", mode.name());
        expect(c, () -> money(new ExchangeRate(io.flowcatalyst.framework.measure.money.Currency.of(r[0]), io.flowcatalyst.framework.measure.money.Currency.of(r[1]), d(r[2]), Instant.parse("2026-11-03T00:00:00Z")).convert(m(c.get("amount")), mode)));
        exchange.add(c);
      }
    }
    JsonVectors.check("measure/money.json", doc);
  }

  // ---- units.json: conversions ----------------------------------------------------------------

  @Test
  void conversions() {
    ObjectNode doc =
        doc(
            "Unit parsing and quantity conversion (measure contract section 3.1 and 3.2). parse: the text, expect"
                + " the unit code or UNIT_UNKNOWN. convert: every ordered pair of units within each dimension for"
                + " several values; round12 is value.to(unit, 12, HALF_EVEN) as plain text, exact is toExact (an"
                + " error CONVERSION_INEXACT where the result does not terminate). arithmetic: plus, minus and"
                + " compare between quantities; expect a quantity object or a number for compare.",
            "Parse value and unit codes; compute to(to, 12, HALF_EVEN) and toExact(to); compare plain text with"
                + " round12 and exact (or the error code with exactError).");
    ArrayNode parse = doc.putArray("parse");
    List<String> inputs = new ArrayList<>(List.of("KGM", "kg", "KG", "kgm", "Kg", "kgs", "lbs", "LBS", "L", "l", "ltr", "pcs", "ea", "each", "EA", "EACH",
        "m2", "M2", "m3", "cm3", "in3", "ft2", "ft3", "degC", "degc", "DEGF", "PX", "px", "CT", "CS", "BX", "PK", "BG", "CR", "DR", "short ton",
        "SHORT TON", "US gal", "us gal", "imp gal", "pallet", "carton", "nmi", "mi", "MI", "dozen", "", " ", "KGM ", "xyz", "m²", "°C", "mtr"));
    for (String code : allCodes()) {
      inputs.add(code);
    }
    for (String s : inputs) {
      ObjectNode c = obj();
      c.put("input", s);
      expect(c, () -> text(Unit.of(s).code()));
      parse.add(c);
    }
    ArrayNode convert = doc.putArray("convert");
    for (Dimension dim : List.of(Dimension.MASS, Dimension.LENGTH, Dimension.AREA, Dimension.VOLUME, Dimension.COUNT, Dimension.TEMPERATURE)) {
      String[] values =
          dim == Dimension.TEMPERATURE ? new String[] {"0", "-40", "100.5", "36.6"} : new String[] {"1", "7831.2", "-3.5", "0.001"};
      for (Unit from : allUnits()) {
        if (from.dimension() != dim) {
          continue;
        }
        for (Unit to : allUnits()) {
          if (to.dimension() != dim) {
            continue;
          }
          for (String v : values) {
            ObjectNode c = obj();
            c.put("value", v);
            c.put("from", from.code());
            c.put("to", to.code());
            Quantity qty = new Quantity(d(v), from);
            c.put("round12", Decimals.format(qty.to(to, 12, RoundingMode.HALF_EVEN).value()));
            try {
              c.put("exact", Decimals.format(qty.toExact(to).value()));
            } catch (MeasureException e) {
              c.put("exactError", e.code());
            }
            convert.add(c);
          }
        }
      }
    }
    ArrayNode arithmetic = doc.putArray("arithmetic");
    String[][] pairs = {
      {"1", "KGM", "500", "GRM"}, {"1.5", "KGM", "2", "LBR"}, {"2", "TNE", "1000", "KGM"}, {"1", "MTR", "3", "FOT"}, {"1", "KGM", "1", "MTR"},
      {"10", "CEL", "5", "CEL"}, {"10", "CEL", "5", "KEL"}, {"20", "CEL", "68", "FAH"}, {"1", "XPX", "1", "XPX"}, {"1", "XPX", "1", "XCT"},
      {"1", "C62", "1", "DZN"}, {"1000", "GRM", "1", "KGM"}, {"-1", "KGM", "-1000", "GRM"}, {"1.50", "KGM", "1.5", "KGM"}, {"1", "LTR", "1000", "CMQ"}
    };
    for (String[] p : pairs) {
      for (String op : List.of("plus", "minus", "compare")) {
        ObjectNode c = obj();
        c.put("op", op);
        c.set("a", quantity(p[0], p[1]));
        c.set("b", quantity(p[2], p[3]));
        Quantity a = q(c.get("a"));
        Quantity b = q(c.get("b"));
        expect(
            c,
            () ->
                switch (op) {
                  case "plus" -> quantity(a.plus(b));
                  case "minus" -> quantity(a.minus(b));
                  default -> number(a.compareTo(b));
                });
        arithmetic.add(c);
      }
    }
    ArrayNode times = doc.putArray("times");
    for (String[] p : new String[][] {{"2.5", "KGM", "4"}, {"10", "CEL", "2"}, {"3", "XPX", "1.5"}, {"1.10", "MTR", "0.10"}}) {
      ObjectNode c = obj();
      c.set("a", quantity(p[0], p[1]));
      c.put("factor", p[2]);
      expect(c, () -> quantity(q(c.get("a")).times(d(p[2]))));
      times.add(c);
    }
    ArrayNode cross = doc.putArray("to");
    for (String[] p : new String[][] {{"1", "KGM", "MTR"}, {"1", "XPX", "XCT"}, {"1", "XPX", "KGM"}, {"1", "KGM", "XPX"}, {"1", "CEL", "KGM"}, {"1", "MTQ", "MTK"}}) {
      ObjectNode c = obj();
      c.set("a", quantity(p[0], p[1]));
      c.put("to", p[2]);
      expect(c, () -> quantity(q(c.get("a")).to(Unit.of(p[2]), 6, RoundingMode.HALF_EVEN)));
      cross.add(c);
    }
    JsonVectors.check("measure/conversions.json", doc);
  }

  private static List<String> allCodes() {
    return allUnits().stream().map(Unit::code).toList();
  }

  private static List<Unit> allUnits() {
    List<Unit> out = new ArrayList<>();
    for (String code :
        List.of(
            "MGM", "GRM", "KGM", "TNE", "LBR", "ONZ", "STN", "LTN", "MMT", "CMT", "MTR", "KMT", "INH", "FOT", "YRD", "SMI", "NMI", "MTK", "FTK",
            "CMQ", "MLT", "LTR", "MTQ", "INQ", "FTQ", "GLL", "GLI", "C62", "EA", "H87", "DZN", "KEL", "CEL", "FAH", "XPX", "XCT", "XCS", "XBX",
            "XPK", "XBG", "XCR", "XDR")) {
      out.add(Unit.of(code));
    }
    return out;
  }

  // ---- packaging.json -------------------------------------------------------------------------

  @Test
  void packaging() {
    ObjectNode doc =
        doc(
            "Packaging (measure contract section 3.3). Each case has levels: [unit, ...] and counts: [n, ...]"
                + " (counts[0] is 1, counts[i] says how many of level i-1 level i holds). new: the packaging is"
                + " built or fails PACKAGING_INVALID. convert: quantity converted to a unit at scale 6 HALF_EVEN"
                + " and exact. breakdown: the quantity as whole units from the largest level down (expect an array of"
                + " quantity objects).",
            "Build the packaging from the levels, then run each operation; compare quantities as plain text and"
                + " unit code, or the error code with error.");
    ArrayNode news = doc.putArray("new");
    String[][][] defs = {
      {{"C62", "XCT", "XPX"}, {"1", "24", "40"}},
      {{"KGM", "XBG", "XPX"}, {"1", "25", "48"}},
      {{"C62"}, {"1"}},
      {{"EA", "XCS", "XCT", "XPX"}, {"1", "6", "4", "10"}},
      {{"XPX"}, {"1"}},
      {{"C62", "KGM"}, {"1", "2"}},
      {{"C62", "XCT", "XCT"}, {"1", "24", "2"}},
      {{"C62", "XCT"}, {"1", "0"}},
      {{"C62", "XCT"}, {"1", "-3"}},
      {{"MTR"}, {"1"}},
      {{"LTR", "XDR"}, {"1", "200"}},
      {{"DZN", "XBX"}, {"1", "10"}}
    };
    for (String[][] def : defs) {
      ObjectNode c = packagingInput(def);
      expect(c, () -> {
        build(def);
        return text("ok");
      });
      news.add(c);
    }
    ArrayNode convert = doc.putArray("convert");
    ArrayNode breakdown = doc.putArray("breakdown");
    String[][][] good = {defs[0], defs[1], defs[3], defs[11], defs[2]};
    String[] values = {"1", "1000", "24", "0.5", "1.5", "960", "-1000", "0", "2.75", "10000.25"};
    for (String[][] def : good) {
      Packaging p = build(def);
      for (String fromUnit : def[0]) {
        for (String toUnit : def[0]) {
          for (String v : values) {
            ObjectNode c = packagingInput(def);
            c.set("quantity", quantity(v, fromUnit));
            c.put("to", toUnit);
            Quantity in = q(c.get("quantity"));
            Unit to = Unit.of(toUnit);
            c.put("round6", Decimals.format(p.convert(in, to, 6, RoundingMode.HALF_EVEN).value()));
            try {
              c.put("exact", Decimals.format(p.convertExact(in, to).value()));
            } catch (MeasureException e) {
              c.put("exactError", e.code());
            }
            convert.add(c);
          }
        }
        for (String v : values) {
          ObjectNode c = packagingInput(def);
          c.set("quantity", quantity(v, fromUnit));
          Quantity in = q(c.get("quantity"));
          expect(c, () -> {
            ArrayNode out = JsonVectors.mapper().createArrayNode();
            p.breakdown(in).forEach(x -> out.add(quantity(x)));
            return out;
          });
          breakdown.add(c);
        }
      }
    }
    // a unit that is not a level
    ObjectNode notLevel = packagingInput(defs[0]);
    notLevel.set("quantity", quantity("1", "XBX"));
    notLevel.put("to", "C62");
    notLevel.put("error", "UNIT_NOT_IN_PACKAGING");
    convert.add(notLevel);
    ObjectNode notLevel2 = packagingInput(defs[0]);
    notLevel2.set("quantity", quantity("1", "KGM"));
    expect(notLevel2, () -> {
      ArrayNode out = JsonVectors.mapper().createArrayNode();
      build(defs[0]).breakdown(q(notLevel2.get("quantity"))).forEach(x -> out.add(quantity(x)));
      return out;
    });
    breakdown.add(notLevel2);
    JsonVectors.check("measure/packaging.json", doc);
  }

  private static ObjectNode packagingInput(String[][] def) {
    ObjectNode c = obj();
    ArrayNode levels = c.putArray("levels");
    for (String u : def[0]) {
      levels.add(u);
    }
    ArrayNode counts = c.putArray("counts");
    for (String n : def[1]) {
      counts.add(n);
    }
    return c;
  }

  private static Packaging build(String[][] def) {
    Packaging p = Packaging.of(Unit.of(def[0][0]));
    for (int i = 1; i < def[0].length; i++) {
      p = p.level(Unit.of(def[0][i]), Long.parseLong(def[1][i]));
    }
    return p;
  }

  // ---- pricing.json ---------------------------------------------------------------------------

  @Test
  void pricing() {
    ObjectNode doc =
        doc(
            "Unit prices and chargeable weight (measure contract section 4). unitPrice: price per unit times a"
                + " quantity, expect a money object at scale (times) or unrounded (exact); the conversion is"
                + " computed exactly and rounded once, so a price per lb times kg is defined for times and"
                + " CONVERSION_INEXACT for exact. chargeable: actual and volume quantities, factor"
                + " {kind: kgPerCubicMetre|divisor, value}, result in KGM at scale with mode.",
            "Build the values, compute, compare plain text and codes (money: amount text and currency; quantity: value text"
                + " and unit code), or the error code with error.");
    ArrayNode up = doc.putArray("unitPrice");
    String[][] prices = {
      {"0.40", "ZAR", "KGM", "7.8312", "TNE"}, {"0.40", "ZAR", "KGM", "7831.2", "KGM"}, {"0.40", "ZAR", "KGM", "100", "GRM"},
      {"12.50", "USD", "LBR", "10", "KGM"}, {"12.50", "USD", "LBR", "10", "LBR"}, {"3", "ZAR", "MTR", "2.5", "KMT"},
      {"3", "ZAR", "MTR", "1", "KGM"}, {"5", "EUR", "MTQ", "250", "LTR"}, {"5", "EUR", "MTQ", "1.5", "FTQ"}, {"2", "ZAR", "C62", "3", "DZN"},
      {"2", "ZAR", "C62", "3", "XPX"}, {"2", "ZAR", "XPX", "3", "XPX"}, {"2", "ZAR", "CEL", "3", "CEL"}, {"1.5", "ZAR", "KGM", "-2.5", "KGM"}
    };
    for (String[] p : prices) {
      for (RoundingMode mode : new RoundingMode[] {RoundingMode.HALF_EVEN, RoundingMode.DOWN, RoundingMode.UP}) {
        for (int scale : new int[] {2, 3}) {
          ObjectNode c = obj();
          c.set("price", money(p[0], p[1]));
          c.put("per", p[2]);
          c.set("quantity", quantity(p[3], p[4]));
          c.put("scale", scale);
          c.put("mode", mode.name());
          expect(
              c,
              () ->
                  money(
                      new UnitPrice(m(c.get("price")), Unit.of(p[2])).times(q(c.get("quantity")), scale, mode)));
          try {
            c.set("exact", money(new UnitPrice(m(c.get("price")), Unit.of(p[2])).timesExact(q(c.get("quantity")))));
          } catch (MeasureException e) {
            c.put("exactError", e.code());
          }
          up.add(c);
        }
      }
    }
    ArrayNode chargeable = doc.putArray("chargeable");
    String[][] cases = {
      {"100", "KGM", "1", "MTQ", "kgPerCubicMetre", "167"}, {"100", "KGM", "0.5", "MTQ", "kgPerCubicMetre", "167"},
      {"500", "KGM", "3", "MTQ", "kgPerCubicMetre", "333"}, {"1", "TNE", "3", "MTQ", "kgPerCubicMetre", "333"},
      {"100", "KGM", "1", "MTQ", "divisor", "6000"}, {"100", "KGM", "1000000", "CMQ", "divisor", "6000"}, {"50", "KGM", "100000", "CMQ", "divisor", "6000"},
      {"220.462", "LBR", "35.3147", "FTQ", "kgPerCubicMetre", "167"}, {"10", "KGM", "1", "MTQ", "kgPerCubicMetre", "1000"},
      {"333.33", "KGM", "2", "MTQ", "divisor", "6000"}, {"0", "KGM", "0", "MTQ", "kgPerCubicMetre", "167"}, {"100", "MTR", "1", "MTQ", "divisor", "6000"},
      {"100", "KGM", "1", "MTK", "divisor", "6000"}, {"100", "KGM", "1", "MTQ", "divisor", "0"}, {"100", "KGM", "1", "MTQ", "kgPerCubicMetre", "-1"}
    };
    for (String[] k : cases) {
      for (RoundingMode mode : new RoundingMode[] {RoundingMode.HALF_EVEN, RoundingMode.UP}) {
        for (int scale : new int[] {0, 3}) {
          ObjectNode c = obj();
          c.set("actual", quantity(k[0], k[1]));
          c.set("volume", quantity(k[2], k[3]));
          ObjectNode f = obj();
          f.put("kind", k[4]);
          f.put("value", k[5]);
          c.set("factor", f);
          c.put("scale", scale);
          c.put("mode", mode.name());
          expect(
              c,
              () -> {
                DensityFactor df =
                    k[4].equals("divisor") ? DensityFactor.divisor(d(k[5])) : DensityFactor.kgPerCubicMetre(d(k[5]));
                return quantity(ChargeableWeight.of(q(c.get("actual")), q(c.get("volume")), df, scale, mode));
              });
          chargeable.add(c);
        }
      }
    }
    JsonVectors.check("measure/pricing.json", doc);
  }

  // ---- errors.json ----------------------------------------------------------------------------

  @Test
  void errors() {
    ObjectNode doc =
        doc(
            "One case per error code of the measure contract (section 6), by operation. op names the call and"
                + " the other members its inputs as text; every case fails with error. Operations: decimal.parse"
                + " {input}, decimal.divide {a, b}, currency.of {code}, money.plus {a, b}, money.sum {values},"
                + " money.allocate {amount, ratios}, exchange.new {from, to, rate}, unit.of {input}, quantity.to"
                + " {quantity, to}, quantity.toExact {quantity, to}, quantity.plus {a, b}, quantity.times"
                + " {quantity, factor}, packaging.new {levels, counts}, packaging.convert {levels, counts,"
                + " quantity, to}, unitPrice.new {price, per}.",
            "Run the operation with the inputs; it must fail with a validation error whose code is error.");
    ArrayNode cases = doc.putArray("cases");
    cases.add(err("decimal.parse", "DECIMAL_INVALID", o -> o.put("input", "1e3"), () -> Decimals.parse("1e3")));
    cases.add(err("decimal.divide", "DIVISION_BY_ZERO", o -> o.put("a", "1").put("b", "0.0"), () -> Decimals.divide(d("1"), d("0.0"), 2, RoundingMode.UP)));
    cases.add(err("currency.of", "CURRENCY_UNKNOWN", o -> o.put("code", "ZZZ"), () -> io.flowcatalyst.framework.measure.money.Currency.of("ZZZ")));
    cases.add(err("money.plus", "CURRENCY_MISMATCH", o -> {
      o.set("a", money("1", "ZAR"));
      o.set("b", money("1", "USD"));
    }, () -> Money.parse("1", "ZAR").plus(Money.parse("1", "USD"))));
    cases.add(err("money.allocate", "ALLOCATION_INVALID", o -> {
      o.set("amount", money("1", "ZAR"));
      o.putArray("ratios").add("0").add("0");
    }, () -> Money.parse("1", "ZAR").allocate(List.of(d("0"), d("0")), RoundingMode.HALF_EVEN)));
    cases.add(err("unit.of", "UNIT_UNKNOWN", o -> o.put("input", "furlong"), () -> Unit.of("furlong")));
    cases.add(err("quantity.to", "DIMENSION_MISMATCH", o -> {
      o.set("quantity", quantity("1", "KGM"));
      o.put("to", "MTR");
    }, () -> Quantity.parse("1", "KGM").to(Unit.MTR, 2, RoundingMode.UP)));
    cases.add(err("quantity.to", "NO_CONVERSION", o -> {
      o.set("quantity", quantity("1", "XPX"));
      o.put("to", "XCT");
    }, () -> Quantity.parse("1", "XPX").to(Unit.XCT, 2, RoundingMode.UP)));
    cases.add(err("quantity.toExact", "CONVERSION_INEXACT", o -> {
      o.set("quantity", quantity("1", "KGM"));
      o.put("to", "LBR");
    }, () -> Quantity.parse("1", "KGM").toExact(Unit.LBR)));
    cases.add(err("quantity.plus", "TEMPERATURE_NOT_ADDITIVE", o -> {
      o.set("a", quantity("1", "CEL"));
      o.set("b", quantity("1", "CEL"));
    }, () -> Quantity.parse("1", "CEL").plus(Quantity.parse("1", "CEL"))));
    cases.add(err("packaging.convert", "UNIT_NOT_IN_PACKAGING", o -> {
      ArrayNode l = o.putArray("levels");
      l.add("C62").add("XCT");
      ArrayNode n = o.putArray("counts");
      n.add("1").add("24");
      o.set("quantity", quantity("1", "XPX"));
      o.put("to", "C62");
    }, () -> Packaging.of(Unit.C62).level(Unit.XCT, 24).convert(Quantity.parse("1", "XPX"), Unit.C62, 2, RoundingMode.UP)));
    cases.add(err("packaging.new", "PACKAGING_INVALID", o -> {
      ArrayNode l = o.putArray("levels");
      l.add("C62").add("XCT");
      ArrayNode n = o.putArray("counts");
      n.add("1").add("0");
    }, () -> Packaging.of(Unit.C62).level(Unit.XCT, 0)));
    cases.add(err("exchange.new", "RATE_INVALID", o -> {
      o.put("from", "USD");
      o.put("to", "ZAR");
      o.put("rate", "0");
    }, () -> new ExchangeRate(io.flowcatalyst.framework.measure.money.Currency.of("USD"), io.flowcatalyst.framework.measure.money.Currency.of("ZAR"), d("0"), Instant.EPOCH)));
    cases.add(err("money.sum", "SUM_EMPTY", o -> o.putArray("values"), () -> Money.sum(List.of())));
    cases.add(err("quantity.times", "TEMPERATURE_NOT_ADDITIVE", o -> {
      o.set("quantity", quantity("1", "CEL"));
      o.put("factor", "2");
    }, () -> Quantity.parse("1", "CEL").times(d("2"))));
    cases.add(err("unitPrice.new", "NO_CONVERSION", o -> {
      o.set("price", money("1", "ZAR"));
      o.put("per", "XPX");
    }, () -> new UnitPrice(Money.parse("1", "ZAR"), Unit.XPX)));
    cases.add(err("unitPrice.new", "TEMPERATURE_NOT_ADDITIVE", o -> {
      o.set("price", money("1", "ZAR"));
      o.put("per", "CEL");
    }, () -> new UnitPrice(Money.parse("1", "ZAR"), Unit.CEL)));
    JsonVectors.check("measure/errors.json", doc);
  }

  private static ObjectNode err(String op, String code, java.util.function.Consumer<ObjectNode> inputs, Runnable call) {
    ObjectNode c = obj();
    c.put("op", op);
    inputs.accept(c);
    try {
      call.run();
      throw new AssertionError(op + " did not fail");
    } catch (MeasureException e) {
      if (!e.code().equals(code)) {
        throw new AssertionError(op + ": expected " + code + " but got " + e.code());
      }
    }
    c.put("error", code);
    return c;
  }
}
