package io.flowcatalyst.framework.ui.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.conformance.Conformance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes (or, without {@code -Dconformance.update=true}, compares) {@code conformance/i18n/cases.json}:
 * lookup and fallback over layered catalogs, placeholders, plural categories, locale resolution and
 * validation rendering (contract i18n.md sections 2 to 4). The Go kit's {@code i18n} package compares.
 */
class I18nConformanceTest {

  static final String DESCRIPTION =
      "docs/contracts/i18n.md sections 2 to 4. 'layers' are two layers of catalogs (language tag to the catalog file's"
          + " JSON), the base first; 'lookup' cases give the bundle's text for (locale, key, args); 'format' cases are"
          + " the placeholder rules alone; 'placeholders' the names a text contains; 'plurals' the CLDR category of a count"
          + " (plain decimal text) for a language; 'resolve' the locale chosen for a supported list, a user preference, a"
          + " tenant default and an Accept-Language header; 'validation' the text of a field error.";

  static final String HOW_TO_RUN =
      "Parse each layer's catalogs (messages are strings or plural objects with 'other'), build a bundle of the layers (later"
          + " layers win within one tag; per key the tag, then its language, then en). For each case compute the 'expect'"
          + " and compare it exactly. Args are JSON strings or numbers; a number is the text it was written with. A key no"
          + " catalog has renders as the key. Locale tags are normalised (af-za is af-ZA).";

  static final Map<String, Object> BASE = new LinkedHashMap<>();
  static final Map<String, Object> APP = new LinkedHashMap<>();

  static {
    Map<String, Object> en = new LinkedHashMap<>();
    en.put("t.hello", "Hello");
    en.put("t.greet", "Hello {name}");
    en.put("t.braces", "Use {{name}} for {name}");
    en.put("t.rows", args("one", "{count} row", "other", "{count} rows"));
    en.put("t.items", args("zero", "No items", "one", "{count} item", "other", "{count} items"));
    en.put("t.only_en", "Only English");
    en.put("t.two", "{a} then {b}");
    en.put("t.override", "Kit text");
    en.put("validation.TOO_SHORT", "must be at least {min} characters");
    en.put("validation.NOT_NULL", "is required");
    Map<String, Object> af = new LinkedHashMap<>();
    af.put("t.hello", "Hallo");
    af.put("t.greet", "Hallo {name}");
    af.put("t.rows", args("one", "{count} ry", "other", "{count} rye"));
    af.put("t.override", "Kit-teks");
    af.put("validation.TOO_SHORT", "moet ten minste {min} karakters wees");
    Map<String, Object> afZa = new LinkedHashMap<>();
    afZa.put("t.hello", "Hallo (ZA)");
    Map<String, Object> zu = new LinkedHashMap<>();
    zu.put("t.hello", "Sawubona");
    zu.put("t.rows", args("one", "{count} umugqa", "other", "{count} imigqa"));
    BASE.put("en", en);
    BASE.put("af", af);
    BASE.put("af-ZA", afZa);
    BASE.put("zu", zu);
    Map<String, Object> appEn = new LinkedHashMap<>();
    appEn.put("t.override", "App text");
    appEn.put("app.title", "My app");
    Map<String, Object> appAf = new LinkedHashMap<>();
    appAf.put("t.override", "Toepassing-teks");
    appAf.put("t.greet", "Goeie dag {name}");
    APP.put("en", appEn);
    APP.put("af", appAf);
  }

  record Lookup(String locale, String key, Map<String, Object> args) {}

  static Map<String, Object> args(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  static final List<Lookup> LOOKUPS =
      List.of(
          new Lookup("en", "t.hello", args()),
          new Lookup("af", "t.hello", args()),
          new Lookup("af-ZA", "t.hello", args()),
          new Lookup("af-za", "t.hello", args()),
          new Lookup("af-NA", "t.hello", args()),
          new Lookup("zu", "t.hello", args()),
          new Lookup("xx", "t.hello", args()),
          new Lookup("", "t.hello", args()),
          new Lookup("not a tag", "t.hello", args()),
          new Lookup("fr-CA", "t.greet", args("name", "Anna")),
          new Lookup("af", "t.greet", args("name", "Anna")),
          new Lookup("af", "t.greet", args()),
          new Lookup("af", "t.only_en", args()),
          new Lookup("af-ZA", "t.only_en", args()),
          new Lookup("af", "t.missing", args()),
          new Lookup("en", "t.override", args()),
          new Lookup("af", "t.override", args()),
          new Lookup("zu", "t.override", args()),
          new Lookup("en", "app.title", args()),
          new Lookup("af", "app.title", args()),
          new Lookup("en", "t.braces", args("name", "X")),
          new Lookup("en", "t.braces", args()),
          new Lookup("en", "t.two", args("b", 2, "a", "one")),
          new Lookup("en", "t.rows", args("count", 1)),
          new Lookup("en", "t.rows", args("count", 2)),
          new Lookup("en", "t.rows", args("count", "1.0")),
          new Lookup("en", "t.rows", args()),
          new Lookup("af", "t.rows", args("count", 1)),
          new Lookup("af", "t.rows", args("count", "1.0")),
          new Lookup("af", "t.rows", args("count", 0)),
          new Lookup("af-ZA", "t.rows", args("count", 1)),
          new Lookup("zu", "t.rows", args("count", 0)),
          new Lookup("zu", "t.rows", args("count", "0.5")),
          new Lookup("zu", "t.rows", args("count", 1)),
          new Lookup("zu", "t.rows", args("count", 2)),
          new Lookup("en", "t.items", args("count", 0)),
          new Lookup("en", "t.items", args("count", 1)),
          new Lookup("en", "t.items", args("count", 5)),
          new Lookup("xx", "t.rows", args("count", 1)));

  static final List<String> FORMATS =
      List.of(
          "plain",
          "{name}",
          "Hello {name}!",
          "{name}{name}",
          "{{name}}",
          "{{",
          "}}",
          "{{{name}}}",
          "a { b",
          "a } b",
          "{}",
          "{ name }",
          "{1a}",
          "{a1}",
          "{a-b}",
          "{unclosed",
          "{name",
          "{count} of {total}",
          "é{name}é");

  static final Map<String, Object> FORMAT_ARGS = args("name", "Anna", "count", 3, "total", "10", "a1", 0);

  static final List<String> PLURAL_COUNTS =
      List.of("0", "1", "2", "5", "1.0", "1.5", "0.5", "0.0", "00", "01", "1.00", "2.0", "-1", "+1", "1000000", "abc", "", "1e3", " 1 ");

  record Resolve(List<String> supported, String user, String tenant, String accept) {}

  static final List<Resolve> RESOLVES =
      List.of(
          new Resolve(List.of("en", "af"), null, null, null),
          new Resolve(List.of("en", "af"), "af", null, null),
          new Resolve(List.of("en", "af"), "af-ZA", null, null),
          new Resolve(List.of("en", "af"), "AF-za", null, null),
          new Resolve(List.of("en", "af"), "fr", "af", "en"),
          new Resolve(List.of("en", "af"), "", "af", "en"),
          new Resolve(List.of("en", "af"), null, "xx", "af"),
          new Resolve(List.of("en", "af"), null, null, "af-ZA,af;q=0.9,en;q=0.8"),
          new Resolve(List.of("en", "af"), null, null, "fr-CA, af;q=0.5, en;q=0.4"),
          new Resolve(List.of("en", "af"), null, null, "en;q=0.4, af;q=0.8"),
          new Resolve(List.of("en", "af"), null, null, "af;q=0, en;q=0.5"),
          new Resolve(List.of("en", "af"), null, null, "af;q=0"),
          new Resolve(List.of("en", "af"), null, null, "*"),
          new Resolve(List.of("en", "af"), null, null, "zu, af;q=0.1"),
          new Resolve(List.of("en", "af"), null, null, "af;q=abc, en"),
          new Resolve(List.of("en", "af"), null, null, "af;q=2, en"),
          new Resolve(List.of("en", "af", "zu"), null, null, "zu-ZA"),
          new Resolve(List.of("en", "af-ZA"), null, null, "af"),
          new Resolve(List.of("en", "af-ZA"), null, null, "af-NA"),
          new Resolve(List.of("af"), null, null, "xx"),
          new Resolve(List.of("en", "af"), null, null, "not a tag, af"),
          new Resolve(List.of("en", "af"), null, null, "af;q=0.5;level=1, en;q=0.5"),
          new Resolve(List.of("en", "af"), "en", "af", "af"));

  record Validation(String locale, String code, Map<String, Object> params, String message) {}

  static final List<Validation> VALIDATIONS =
      List.of(
          new Validation("en", "TOO_SHORT", args("min", 3), "must be at least 3 characters"),
          new Validation("af", "TOO_SHORT", args("min", 3), "must be at least 3 characters"),
          new Validation("af-ZA", "TOO_SHORT", args("min", 12), "own"),
          new Validation("af", "TOO_SHORT", args(), "own message"),
          new Validation("af", "NOT_NULL", args(), "is required"),
          new Validation("af", "UNKNOWN_CODE", args("x", 1), "an unknown rule"),
          new Validation("fr", "TOO_SHORT", args("min", 2), "own"));

  @Test
  void theI18nVectorIsCurrent() throws Exception {
    JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    Map<String, Catalog> base = catalogs(mapper, BASE);
    Map<String, Catalog> app = catalogs(mapper, APP);
    Bundle bundle = Bundle.of(base).with(app);

    Map<String, Object> vector = new LinkedHashMap<>();
    vector.put("description", DESCRIPTION);
    vector.put("howToRun", HOW_TO_RUN);
    vector.put("layers", List.of(BASE, APP));

    List<Map<String, Object>> lookup = new ArrayList<>();
    for (Lookup l : LOOKUPS) {
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("locale", l.locale());
      e.put("key", l.key());
      e.put("args", l.args());
      e.put("expect", bundle.message(l.locale(), l.key(), l.args()));
      lookup.add(e);
    }
    vector.put("lookup", lookup);

    List<Map<String, Object>> format = new ArrayList<>();
    for (String text : FORMATS) {
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("text", text);
      e.put("args", FORMAT_ARGS);
      e.put("expect", Bundle.format(text, FORMAT_ARGS));
      format.add(e);
    }
    vector.put("format", format);

    List<Map<String, Object>> placeholders = new ArrayList<>();
    for (String text : FORMATS) {
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("text", text);
      e.put("expect", Bundle.placeholders(text));
      placeholders.add(e);
    }
    vector.put("placeholders", placeholders);

    List<Map<String, Object>> plurals = new ArrayList<>();
    for (String lang : List.of("en", "af", "zu", "xx")) {
      for (String count : PLURAL_COUNTS) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("lang", lang);
        e.put("count", count);
        e.put("expect", Plurals.category(lang, count));
        plurals.add(e);
      }
    }
    vector.put("plurals", plurals);

    List<Map<String, Object>> resolves = new ArrayList<>();
    for (Resolve r : RESOLVES) {
      LocaleResolver resolver = LocaleResolver.builder().supported(r.supported().toArray(String[]::new)).build();
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("supported", r.supported());
      e.put("user", r.user());
      e.put("tenant", r.tenant());
      e.put("acceptLanguage", r.accept());
      e.put("expect", resolver.resolve(r.user(), r.tenant(), r.accept()));
      resolves.add(e);
    }
    vector.put("resolve", resolves);

    List<Map<String, Object>> validations = new ArrayList<>();
    for (Validation v : VALIDATIONS) {
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("locale", v.locale());
      e.put("code", v.code());
      e.put("params", v.params());
      e.put("message", v.message());
      e.put("expect", bundle.validation(v.locale(), v.code(), v.params(), v.message()));
      validations.add(e);
    }
    vector.put("validation", validations);

    Conformance.check("i18n/cases.json", mapper.writeValueAsString(vector) + "\n");
  }

  /** Independent expectations, so the vector is not only what the code does. */
  @Test
  void theRulesHoldByHand() {
    Bundle bundle = Bundle.of(catalogs(JsonMapper.builder().build(), BASE)).with(catalogs(JsonMapper.builder().build(), APP));
    assertThat(bundle.message("af-ZA", "t.hello", Map.of())).isEqualTo("Hallo (ZA)");
    assertThat(bundle.message("af-NA", "t.hello", Map.of())).isEqualTo("Hallo");
    assertThat(bundle.message("fr", "t.hello", Map.of())).isEqualTo("Hello");
    assertThat(bundle.message("af", "t.only_en", Map.of())).isEqualTo("Only English");
    assertThat(bundle.message("af", "t.nothing", Map.of())).isEqualTo("t.nothing");
    // the application's layer wins within a tag; an English application text never beats the kit's Afrikaans
    assertThat(bundle.message("en", "t.override", Map.of())).isEqualTo("App text");
    assertThat(bundle.message("af", "t.override", Map.of())).isEqualTo("Toepassing-teks");
    assertThat(bundle.message("zu", "t.override", Map.of())).isEqualTo("App text");
    assertThat(bundle.message("en", "t.rows", Map.of("count", 1))).isEqualTo("1 row");
    assertThat(bundle.message("en", "t.rows", Map.of("count", 2))).isEqualTo("2 rows");
    assertThat(bundle.message("af", "t.rows", Map.of("count", 1))).isEqualTo("1 ry");
    assertThat(bundle.message("zu", "t.rows", Map.of("count", 0))).isEqualTo("0 umugqa");
    assertThat(bundle.message("en", "t.items", Map.of("count", 0))).isEqualTo("0 items"); // en has no zero form
    assertThat(Bundle.format("Use {{name}} for {name}", Map.of("name", "X"))).isEqualTo("Use {name} for X");
    assertThat(Bundle.format("{a} {b}", Map.of("a", 1))).isEqualTo("1 {b}");
    assertThat(Plurals.category("en", "1.0")).isEqualTo("other");
    assertThat(Plurals.category("af", "1.0")).isEqualTo("one");
    assertThat(Plurals.category("zu", "0.5")).isEqualTo("one");
    assertThat(Plurals.category("xx", "1")).isEqualTo("one");
  }

  private static Map<String, Catalog> catalogs(JsonMapper mapper, Map<String, Object> layer) {
    Map<String, Catalog> out = new LinkedHashMap<>();
    layer.forEach((tag, messages) -> out.put(tag, Catalog.parse(mapper.writeValueAsString(messages))));
    return out;
  }
}
