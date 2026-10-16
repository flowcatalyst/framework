package io.flowcatalyst.framework.ui.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every dynamic value is HTML-escaped; values that end up in Datastar expressions are validated.
 * Each test fails if the escaping it exercises is disabled (see the mutation record in
 * docs/guide/ui-jte.md).
 */
class EscapingTest {

  private static final String XSS = "<script>alert(1)</script>\"'&<img src=x onerror=alert(2)>";

  private static String html(gg.jte.Content c) {
    return Markup.render(c);
  }

  /** The output parses to no script or image element and no event-handler attribute. */
  private static void assertNoLiveMarkup(String out) {
    org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(out);
    assertThat(doc.select("script, img")).isEmpty();
    for (org.jsoup.nodes.Element e : doc.getAllElements()) {
      e.attributes().forEach(a -> assertThat(a.getKey()).doesNotStartWith("on"));
    }
  }

  @Test
  void textInSlotsAndPropsIsEscaped() {
    String out =
        html(
            Kit.alert(
                new Props.Alert("destructive", "alert", null, XSS, XSS, null, null),
                Markup.text(XSS)));
    assertNoLiveMarkup(out);
    assertThat(out).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(out);
    assertThat(doc.select("p").first().text()).isEqualTo(XSS);
    assertThat(doc.text()).contains(XSS);
  }

  @Test
  void attributeValuesAreEscapedAndCannotBreakOut() {
    String out = html(Kit.input(new Props.Input("a", "n", null, XSS, XSS, false, false, false, null, XSS, null)));
    assertNoLiveMarkup(out);
    // the value is still the literal text, not markup: one input element, no children
    org.jsoup.nodes.Element input = org.jsoup.Jsoup.parseBodyFragment(out).select("input").first();
    assertThat(input.attr("value")).isEqualTo(XSS);
    assertThat(input.attr("placeholder")).isEqualTo(XSS);
    assertThat(input.attr("class")).contains(XSS);
    assertThat(org.jsoup.Jsoup.parseBodyFragment(out).body().children()).hasSize(1);
  }

  @Test
  void textareaAndSelectAndTableTextAreEscaped() {
    assertNoLiveMarkup(html(Kit.textarea(new Props.Textarea("t", "t", XSS, null, 0, false, false, false, null, null, null))));
    assertNoLiveMarkup(
        html(
            Kit.select(
                new Props.Select(
                    "s", "s", java.util.List.of(new Props.Option(XSS, XSS)), XSS, XSS, false, false, false, null, null, null))));
    assertNoLiveMarkup(html(Kit.tableCell(new Props.TD(0, XSS, null), Markup.text(XSS))));
  }

  @Test
  void attrsMapValuesAreEscaped() {
    String out = Markup.attrs(Map.of("data-x", XSS));
    assertThat(out).doesNotContain("<").startsWith(" data-x=\"").endsWith("\"");
    assertThat(org.jsoup.Jsoup.parseBodyFragment("<i" + out + ">").select("i").first().attr("data-x"))
        .isEqualTo(XSS);
  }

  @Test
  void attrsRefuseEventHandlersAndMalformedNames() {
    assertThatThrownBy(() -> Markup.attrs(Map.of("onclick", "alert(1)")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Markup.attrs(Map.of("ONmouseover", "x"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Markup.attrs(Map.of("a b", "x"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Markup.attrs(Map.of("a\"=x", "x"))).isInstanceOf(IllegalArgumentException.class);
    assertThat(Markup.attrs(Map.of("data-on:click", "go()"))).isEqualTo(" data-on:click=\"go()\"");
  }

  @Test
  void idsSignalsAndPathsThatReachExpressionsAreValidated() {
    String evil = "x');alert(1);//";
    assertThatThrownBy(() -> Markup.id(evil)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Markup.signal(evil)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Markup.path(evil)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Markup.showModal(evil)).isInstanceOf(IllegalArgumentException.class);
    // and the templates use them: a dialog, a drawer signal, a tab value, an autocomplete source
    assertThatThrownBy(() -> html(Kit.dialog(new Props.Dialog(evil, "t", null, null, null, false, false, null, null), null, null)))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> html(Kit.drawer(new Props.Drawer(null, evil, null, null, null, null, null, null, null), null, null, null)))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                html(
                    Kit.tabs(
                        new Props.Tabs("t", java.util.List.of(new Props.TabItem(evil, "x")), null, null, null),
                        Map.of())))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                html(
                    Kit.autocomplete(
                        new Props.Autocomplete("a", null, null, null, evil, null, false, false, false, null, null, null))))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void signalAttrIsKebabCase() {
    assertThat(Markup.signalAttr("qPools")).isEqualTo("q-pools");
    assertThat(Markup.signalAttr("tabsDemo")).isEqualTo("tabs-demo");
  }

  @Test
  void unknownVariantsAreRefusedNotSilentlyDefaulted() {
    assertThatThrownBy(() -> html(Kit.badge(new Props.Badge("<b>", null, null, null), Markup.text("x"))))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void trustedRawSlotIsTheOnlyUnescapedPath() {
    String out = html(Kit.badge(new Props.Badge(null, null, null, null), Markup.raw("<b>ok</b>")));
    assertThat(out).contains("<b>ok</b>");
  }

  @Test
  void escapeCoversTheFiveCharacters() {
    assertThat(Markup.escape("<>&\"'")).isEqualTo("&lt;&gt;&amp;&quot;&#39;");
    assertThat(Markup.text("<b>").getClass()).isNotNull();
    assertThat(html(Markup.text("<b>"))).isEqualTo("&lt;b&gt;");
  }

  @Test
  void unsafeUrlSchemesAreBlocked() {
    for (String bad : new String[] {"javascript:alert(1)", "  JaVaScript:alert(1)", "data:text/html,<script>1</script>"}) {
      String a = html(Kit.badge(new Props.Badge(null, bad, null, null), Markup.text("x")));
      String b = html(Kit.button(new Props.Button(null, null, null, bad, false, null, null, null), Markup.text("x")));
      for (String out : new String[] {a, b}) {
        assertThat(org.jsoup.Jsoup.parseBodyFragment(out).select("a").first().attr("href"))
            .isEqualTo(Markup.BLOCKED_URL);
      }
    }
    assertThat(Markup.url("/orders?x=1#a")).isEqualTo("/orders?x=1#a");
    assertThat(Markup.url("https://example.com/a:b")).isEqualTo("https://example.com/a:b");
    assertThat(Markup.url("mailto:a@b.c")).isEqualTo("mailto:a@b.c");
    assertThat(Markup.url("orders/a:b")).isEqualTo("orders/a:b");
    assertThat(Markup.url("jav\tascript:alert(1)")).isEqualTo(Markup.BLOCKED_URL);
    assertThat(Markup.url("vbscript:x")).isEqualTo(Markup.BLOCKED_URL);
    assertThat(Markup.attrs(Map.of("href", "data:text/html,x"))).isEqualTo(" href=\"" + Markup.BLOCKED_URL + "\"");
    String form = html(Kit.confirmDialog(new Props.ConfirmDialog("c", "javascript:alert(1)", "t", "m", "ok", null, null, null)));
    assertThat(org.jsoup.Jsoup.parseBodyFragment(form).select("form").first().attr("action")).isEqualTo(Markup.BLOCKED_URL);
  }
}
