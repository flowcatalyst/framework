package io.flowcatalyst.framework.ui.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class I18nTest {

  @Test
  void parseRefusesWhatTheContractRefuses() {
    for (String src :
        List.of(
            "[\"a\"]",
            "{\"hello\": \"x\"}",
            "{\"kit.a b\": \"x\"}",
            "{\"Kit.a\": \"x\"}",
            "{\"kit.a\": 1}",
            "{\"kit.a\": {\"one\": \"x\"}}",
            "{\"kit.a\": {\"few2\": \"x\", \"other\": \"y\"}}",
            "{\"kit.a\": {\"other\": 1}}",
            "not json")) {
      assertThatThrownBy(() -> Catalog.parse(src)).as(src).isInstanceOf(IllegalArgumentException.class);
    }
    Catalog c = Catalog.parse("{\"kit.a\": \"A\", \"validation.TOO_SHORT\": \"x\", \"app.x-y_z.k\": {\"one\": \"1\", \"other\": \"n\"}}");
    assertThat(c.messages()).hasSize(3);
  }

  private static Catalog cat(String json) {
    return Catalog.parse(json);
  }

  @Test
  void lookupFallsBackFromTagToLanguageToEnglish() {
    Bundle b =
        Bundle.of(
            Map.of(
                "en", cat("{\"t.a\": \"en a\", \"t.b\": \"en b\", \"t.c\": \"en c\"}"),
                "af", cat("{\"t.a\": \"af a\", \"t.b\": \"af b\"}"),
                "af-ZA", cat("{\"t.a\": \"af-ZA a\"}")));
    assertThat(b.message("af-ZA", "t.a", Map.of())).isEqualTo("af-ZA a");
    assertThat(b.message("af-ZA", "t.b", Map.of())).isEqualTo("af b"); // the language step
    assertThat(b.message("af-ZA", "t.c", Map.of())).isEqualTo("en c"); // English
    assertThat(b.message("af-za", "t.a", Map.of())).isEqualTo("af-ZA a");
    assertThat(b.message("af-NA", "t.a", Map.of())).isEqualTo("af a");
    assertThat(b.message("zu", "t.a", Map.of())).isEqualTo("en a");
    assertThat(b.message("", "t.a", Map.of())).isEqualTo("en a");
    assertThat(b.message("af", "t.missing", Map.of())).isEqualTo("t.missing"); // the key itself
  }

  @Test
  void anApplicationLayerOverridesTheKitButNotItsOtherLanguages() {
    Bundle b = Bundle.kit().with(Map.of("en", cat("{\"kit.dialog.cancel\": \"Never mind\"}")));
    assertThat(b.messages("en").t("kit.dialog.cancel")).isEqualTo("Never mind");
    assertThat(b.messages("af").t("kit.dialog.cancel")).isEqualTo("Kanselleer");
    assertThat(Bundle.kit().messages("en").t("kit.dialog.cancel")).isEqualTo("Cancel"); // With did not change the kit
  }

  @Test
  void aMissingKeyIsReported() {
    List<String> got = new ArrayList<>();
    Bundle b = Bundle.kit().onMissing((l, k) -> got.add(l + ":" + k));
    b.messages("af").t("kit.nope.nothing");
    b.messages("af").t("kit.dialog.cancel");
    assertThat(got).containsExactly("af:kit.nope.nothing");
  }

  @Test
  void messagesDefaultToTheKitInEnglishAndCanBeBound() throws Exception {
    assertThat(Messages.current().locale()).isEqualTo("en");
    assertThat(Messages.current().t("kit.dialog.close")).isEqualTo("Close");
    Bundle.kit().messages("af-za");
    String bound = Messages.where(Bundle.kit().messages("af-za"), () -> Messages.current().t("kit.dialog.close"));
    assertThat(bound).isEqualTo("Maak toe");
    assertThat(Messages.current().t("kit.dialog.close")).isEqualTo("Close"); // the binding ended
    assertThat(Bundle.kit().messages("af").t("kit.paginator.showing", Map.of("from", 1, "to", 10, "total", 55, "noun", "rye")))
        .isEqualTo("Wys 1 tot 10 van 55 rye");
  }

  @Test
  void theResolverMatchesTheFullTagThenTheLanguage() {
    LocaleResolver r = LocaleResolver.builder().supported("af-ZA", "zu").build();
    assertThat(r.match("af-ZA")).isEqualTo("af-ZA");
    assertThat(r.match("af")).isEqualTo("af-ZA");
    assertThat(r.match("af-NA")).isEqualTo("af-ZA");
    assertThat(r.match("zu-ZA")).isEqualTo("zu");
    assertThat(r.match("en-GB")).isEqualTo("en");
    assertThat(r.match("xx")).isNull();
    assertThat(r.match("")).isNull();
    assertThat(r.supported()).containsExactly("af-ZA", "zu", "en"); // en is always added
  }

  @Test
  void theResolverReadsUserTenantAndAcceptLanguageInThatOrder() {
    LocaleResolver r =
        LocaleResolver.builder()
            .supported("af")
            .user(req -> req.header("X-User-Locale"))
            .tenant(req -> req.header("X-Tenant-Locale"))
            .build();
    assertThat(r.resolve(null, null, null)).isEqualTo("en");
    assertThat(r.resolve(null, null, "af-ZA, en;q=0.5")).isEqualTo("af");
    assertThat(r.resolve(null, "af", "en")).isEqualTo("af");
    assertThat(r.resolve("en", "af", "af")).isEqualTo("en");
    assertThat(r.resolve("fr", "xx", "af;q=0.2, fr")).isEqualTo("af");
  }

  @Test
  void validationFallsBackToTheErrorsOwnMessage() {
    Messages af = Bundle.kit().messages("af");
    assertThat(af.validation("TOO_SHORT", Map.of("min", 8), "must be at least 8 characters")).isEqualTo("moet ten minste 8 karakters lank wees");
    assertThat(af.validation("TOO_SHORT", Map.of(), "must be at least 8 characters")).isEqualTo("must be at least 8 characters");
    assertThat(af.validation("MY_RULE", Map.of(), "own text")).isEqualTo("own text");
    assertThat(Messages.english().validation("NOT_NULL", Map.of(), "x")).isEqualTo("is required");
  }

  // ---- the checks of contract section 6, on the kit's catalogs

  private static Catalog kitCatalog(String tag) throws Exception {
    return Catalog.parse(Files.readString(Path.of("../ui-kit/messages/" + tag + ".json")));
  }

  private static TreeSet<String> placeholdersOf(Message m) {
    TreeSet<String> out = new TreeSet<>();
    switch (m) {
      case Message.Text t -> out.addAll(Bundle.placeholders(t.text()));
      case Message.Plural p -> p.forms().values().forEach(v -> out.addAll(Bundle.placeholders(v)));
    }
    return out;
  }

  @Test
  void kitCatalogsAreConsistent() throws Exception {
    Catalog en = kitCatalog("en");
    assertThat(en.messages()).isNotEmpty();
    for (String tag : Bundle.KIT_LANGUAGES) {
      Catalog c = kitCatalog(tag);
      c.messages()
          .forEach(
              (key, m) -> {
                assertThat(en.messages()).as(tag + ": " + key + " is in en").containsKey(key);
                Message e = en.messages().get(key);
                assertThat(m instanceof Message.Plural).as(tag + ": " + key + " plural like en").isEqualTo(e instanceof Message.Plural);
                assertThat(placeholdersOf(m)).as(tag + ": placeholders of " + key).isEqualTo(placeholdersOf(e));
              });
    }
  }

  @Test
  void theKitShipsExactlyTheLanguagesItDeclares() throws Exception {
    assertThat(Bundle.kit().languages()).isEqualTo(Bundle.KIT_LANGUAGES.stream().sorted().toList());
    try (var files = Files.list(Path.of("../ui-kit/messages"))) {
      assertThat(files.map(p -> p.getFileName().toString().replace(".json", "")).sorted().toList())
          .isEqualTo(Bundle.KIT_LANGUAGES.stream().sorted().toList());
    }
  }

  @Test
  void everyValidationCodeHasAMessage() throws Exception {
    for (String tag : Bundle.KIT_LANGUAGES) {
      for (String code : List.of("NOT_NULL", "NOT_BLANK", "TOO_SHORT", "TOO_LONG", "PATTERN", "OUT_OF_RANGE", "SIZE", "NOT_ONE_OF")) {
        assertThat(kitCatalog(tag).messages()).as(tag).containsKey("validation." + code);
      }
    }
  }

  /** The report of contract section 6: the English keys each other catalog has not translated (printed, never a failure). */
  @Test
  void untranslatedReport() throws Exception {
    Catalog en = kitCatalog("en");
    Map<String, List<String>> report = new LinkedHashMap<>();
    for (String tag : Bundle.KIT_LANGUAGES) {
      if (tag.equals("en")) {
        continue;
      }
      Catalog c = kitCatalog(tag);
      List<String> missing = en.messages().keySet().stream().filter(k -> !c.messages().containsKey(k)).sorted().toList();
      report.put(tag, missing);
      System.out.println("i18n report: " + tag + ": " + (en.messages().size() - missing.size()) + " of " + en.messages().size() + " keys translated; untranslated: " + missing);
    }
    assertThat(report).isNotEmpty();
  }
}
