package io.flowcatalyst.framework.ui.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.ui.i18n.Bundle;
import io.flowcatalyst.framework.ui.i18n.Catalog;
import io.flowcatalyst.framework.ui.i18n.Messages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The kit's user-visible text is the catalog's (docs/contracts/i18n.md sections 4 and 6): every
 * fixture renders with no message missing, English output shows no raw key, a label prop wins, and
 * every key the sources use is in English and every English kit key is used.
 */
class KitI18nTest {

  private static <T> T in(Messages m, java.util.concurrent.Callable<T> body) {
    try {
      return Messages.where(m, body);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Renders every fixture of every component in the messages' language. */
  private static Map<String, String> renderAll(Messages m) throws IOException {
    Map<String, String> out = new LinkedHashMap<>();
    try (Stream<Path> dirs = Files.list(FixtureConformanceTest.fixtures())) {
      for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
        String component = dir.getFileName().toString();
        if (!Renderers.ALL.containsKey(component)) {
          continue;
        }
        try (Stream<Path> files = Files.list(dir)) {
          for (Path json : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
            out.put(component + "/" + json.getFileName(), in(m, () -> FixtureConformanceTest.renderFixture(component, json)));
          }
        }
      }
    }
    assertThat(out).isNotEmpty();
    return out;
  }

  private static Messages recording(String locale, List<String> missing) {
    return Bundle.kit().onMissing((l, k) -> missing.add(l + " " + k)).messages(locale);
  }

  @Test
  void everyFixtureRendersWithoutAMissingMessage() throws IOException {
    for (String locale : List.of("en", "af", "af-ZA", "zu", "xx")) {
      List<String> missing = new ArrayList<>();
      renderAll(recording(locale, missing));
      assertThat(missing).as("messages missing in " + locale + " (a raw key would be rendered)").isEmpty();
    }
  }

  @Test
  void englishRenderingNeverShowsAKey() throws IOException {
    Pattern key = Pattern.compile("\\bkit\\.[a-z0-9_]+\\.[a-z0-9_.]+");
    renderAll(Messages.english()).forEach((name, html) -> assertThat(key.matcher(html).find()).as(name + " shows a raw key").isFalse());
  }

  @Test
  void afrikaansRendersTheAfrikaansCatalog() throws IOException {
    Map<String, String> en = renderAll(Messages.english());
    Map<String, String> af = renderAll(Bundle.kit().messages("af"));
    long differs = en.keySet().stream().filter(k -> !en.get(k).equals(af.get(k))).count();
    assertThat(differs).as("fixtures that render differently in Afrikaans").isGreaterThanOrEqualTo(10);
    String paginator =
        in(
            Bundle.kit().messages("af"),
            () -> Markup.render(Kit.paginator(new Props.Paginator(null, 2, 5, 50, 10, null, "rye", null, null, null))));
    assertThat(paginator).contains("aria-label=\"Eerste\"", "aria-label=\"Laaste\"", "Wys 11 tot 20 van 50 rye");
  }

  @Test
  void aPropThatSetsALabelWinsOverTheCatalog() throws Exception {
    Messages af = Bundle.kit().messages("af");
    String custom =
        in(af, () -> Markup.render(Kit.alertDialog(new Props.AlertDialog("d", "T", "M", "Nope", "Yes", null, "/x", null, null))));
    assertThat(custom).contains("Nope", "Yes").doesNotContain("Kanselleer");
    String defaults =
        in(af, () -> Markup.render(Kit.alertDialog(new Props.AlertDialog("d", "T", "M", null, null, null, "/x", null, null))));
    assertThat(defaults).contains("Kanselleer", "Bevestig");
  }

  @Test
  void messagesInExpressionsAreQuotedForJavaScript() {
    assertThat(Kit.jsq("it's a \\ \"test\"")).isEqualTo("'it\\'s a \\\\ \"test\"'");
    assertThat(Kit.jsMessage("kit.multiselect.remove", "label", "t.textContent")).isEqualTo("'Remove ' + t.textContent");
    Messages af = Bundle.kit().messages("af");
    String expr = in(af, () -> new Props.PasswordInput("p", null, "pw", null, false, false, false, null, null, true, null).strengthText());
    assertThat(expr).contains("'Swak'", "'Sterk'");
  }

  @Test
  void theDocumentLangFollowsTheMessages() {
    Props.Document doc = new Props.Document("T", null, "/a.css", "/a.js", null);
    assertThat(in(Messages.english(), () -> Markup.render(Kit.document(doc, Markup.text("x"))))).contains("<html lang=\"en\"");
    assertThat(in(Bundle.kit().messages("af-za"), () -> Markup.render(Kit.document(doc, Markup.text("x"))))).contains("<html lang=\"af-ZA\"");
    Props.Document explicit = new Props.Document("T", null, "/a.css", "/a.js", null, null, List.of(), "zu");
    assertThat(in(Messages.english(), () -> Markup.render(Kit.document(explicit, Markup.text("x"))))).contains("<html lang=\"zu\"");
  }

  @Test
  void localTimeFormatsWithTheDocumentLanguage() {
    String out = Markup.render(Kit.localTime(new Props.LocalTime(java.time.Instant.parse("2026-10-06T08:30:00Z"), false, null)));
    assertThat(out).contains("toLocaleString(document.documentElement.lang || undefined)");
  }

  @Test
  void localNumberMoneyAndQuantityRefuseWhatIsNotAPlainValue() {
    for (Props.LocalNumber p : List.of(new Props.LocalNumber("12,5", null), new Props.LocalNumber("1e3", null), new Props.LocalNumber("1\"><script>", null))) {
      refused(() -> Markup.render(Kit.localNumber(p)));
    }
    refused(() -> Markup.render(Kit.localMoney(new Props.LocalMoney("x", "ZAR", null, null))));
    refused(() -> Markup.render(Kit.localMoney(new Props.LocalMoney("1", "zar", null, null))));
    refused(() -> Markup.render(Kit.localMoney(new Props.LocalMoney("1", "ZAR", 99, null))));
    refused(() -> Markup.render(Kit.localQuantity(new Props.LocalQuantity("-", "kg", null))));
  }

  private static void refused(Runnable render) {
    assertThatThrownBy(render::run)
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("kit: ");
  }

  @Test
  void localNumberKeepsThePlainValueAsText() {
    String out = Markup.render(Kit.localNumber(new Props.LocalNumber("1234.50", null)));
    assertThat(out).contains(">1234.50</data>", "value=\"1234.50\"", "minimumFractionDigits: 2, maximumFractionDigits: 2");
  }

  // ---- the sources and the catalog

  private static final Pattern LITERAL_KEY = Pattern.compile("\"((?:kit\\.[a-z0-9_]+\\.|validation\\.|flash\\.)[A-Za-z0-9_.-]+)\"");

  /** The catalog keys named in the kit's templates and Java sources. */
  private static TreeSet<String> sourceKeys() throws IOException {
    TreeSet<String> keys = new TreeSet<>();
    List<Path> files = new ArrayList<>();
    for (String dir : List.of("src/main/jte", "src/main/java")) {
      try (Stream<Path> s = Files.walk(Path.of(dir))) {
        s.filter(p -> p.toString().endsWith(".jte") || p.toString().endsWith(".java")).forEach(files::add);
      }
    }
    for (Path f : files) {
      for (String line : Files.readAllLines(f)) {
        String t = line.strip();
        if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) {
          continue; // documentation names keys as examples
        }
        Matcher m = LITERAL_KEY.matcher(line);
        while (m.find()) {
          keys.add(m.group(1));
        }
      }
    }
    return keys;
  }

  private static Catalog catalog(String tag) throws IOException {
    return Catalog.parse(Files.readString(Path.of("../ui-kit/messages/" + tag + ".json")));
  }

  @Test
  void everyKeyTheSourcesUseIsInEnglish() throws IOException {
    Catalog en = catalog("en");
    for (String key : sourceKeys()) {
      if (key.endsWith(".") || key.equals("validation.") || key.startsWith("validation.")) {
        continue;
      }
      assertThat(en.messages()).as("the sources use " + key).containsKey(key);
    }
  }

  /** Keys of components only the Go kit has (the Java sidebar has no collapsible groups). */
  private static final java.util.Set<String> GO_ONLY = java.util.Set.of("kit.appshell.contains_current");

  @Test
  void everyEnglishKitKeyIsUsedBySources() throws IOException {
    TreeSet<String> used = sourceKeys();
    List<String> unused = new ArrayList<>();
    for (String key : catalog("en").messages().keySet()) {
      // keys built from parts: "kit.flash_fallback." + name + ".summary"
      if (key.startsWith("kit.") && !used.contains(key) && !key.startsWith("kit.flash_fallback.") && !GO_ONLY.contains(key)) {
        unused.add(key);
      }
    }
    assertThat(unused).as("English kit keys no source uses (dead text, or a literal left in a template)").isEmpty();
  }
}
