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

  private static Props.PickList pickList(Props.PickItem... items) {
    return new Props.PickList(
        "pick", "picked", "pickQ", "roles", "pk-", java.util.List.of(items), null, null, null, null,
        null, null, null);
  }

  @Test
  void aPickValueWithAKeyIsAQuotedLiteralInTheSignalAndJsonInTheMembers() {
    String value = "it's a\\b\r\n<c> \"d\"";
    String out =
        html(
            Kit.pickList(
                pickList(
                    new Props.PickItem(value, "N", "c", null, true, "k0", "g", null, false))));
    assertNoLiveMarkup(out);
    org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(out);
    assertThat(doc.select("form").attr("data-signals"))
        .isEqualTo("{picked: ['it\\'s a\\\\b\\r\\n\\x3cc> \"d\"'], pickQ: ''}");
    assertThat(doc.select("[data-members]").attr("data-members"))
        .isEqualTo(io.flowcatalyst.framework.http.Json.mapper().writeValueAsString(java.util.List.of(value)));
    assertThat(doc.select("input[type=checkbox]").attr("id")).isEqualTo("pk-k0");
    assertThat(doc.select("[data-value]").attr("data-value")).isEqualTo(value);
  }

  @Test
  void aPickValueWithoutAKeyMustBeIdSafe() {
    assertThatThrownBy(
            () -> html(Kit.pickList(pickList(new Props.PickItem("a:b", "N", "c", null, false)))))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aLockedPickIsSubmittedAndCountedEvenWhenNotMarkedChecked() {
    String out =
        html(
            Kit.pickList(
                pickList(
                    new Props.PickItem("r", "N", "c", null, false, null, null, null, true),
                    new Props.PickItem("s", "M", "d", null, false))));
    org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(out);
    org.jsoup.nodes.Element locked = doc.select("input#pk-r").first();
    assertThat(locked.hasAttr("checked")).isTrue();
    assertThat(locked.hasAttr("disabled")).isTrue();
    assertThat(locked.hasAttr("name")).isFalse();
    assertThat(doc.select("input[type=hidden]").attr("value")).isEqualTo("r");
    assertThat(doc.select("form").attr("data-signals")).isEqualTo("{picked: [''], pickQ: ''}");
    assertThat(doc.select("[data-text]").text()).isEqualTo("1");
    assertThat(doc.select("[data-text]").attr("data-text"))
        .isEqualTo("$picked.filter(Boolean).length + 1");
  }

  @Test
  void aMultiSelectWithoutChipsHasNoChipListAndNoChipHandler() {
    Props.MultiSelect p =
        new Props.MultiSelect(
            "m", "n", "L", java.util.List.of(new Props.Option("a", "A")), java.util.List.of("a"), null,
            0, null, false, null, null, false, null, null, null);
    String out = html(Kit.multiSelect(p));
    assertThat(out).doesNotContain("fc-multiselect-chip");
    assertThat(p.detailsAttrs().get("data-on:change")).isEqualTo(Props.MultiSelect.SUMMARY_EXPR);
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

  @Test
  void aPathRefusesTheProtocolRelativeForm() {
    assertThat(Markup.path("/orders/new")).isEqualTo("/orders/new");
    assertThatThrownBy(() -> Markup.path("//evil.example")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Markup.path("//")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Markup.path("/\\evil")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aLocalPathHasOneLeadingSlashAndNoControlCharactersOrBackslash() {
    for (String ok : new String[] {"/", "/orders?q=a%20b&page=2", "/a/b#frag", "/x?next=//y"}) {
      assertThat(Markup.isLocalPath(ok)).as(ok).isTrue();
    }
    for (String bad : new String[] {"", "orders", "//evil.example", "/\\evil", "/a\\b", "/a\nb", "/a\u007fb", "https://evil.example", "javascript:alert(1)"}) {
      assertThat(Markup.isLocalPath(bad)).as(bad).isFalse();
      assertThatThrownBy(() -> Markup.localPath(bad)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(Markup.isLocalPath(null)).isFalse();
  }

  // ---- the GU3 components -----------------------------------------------------------------------

  @Test
  void markdownIsSanitisedWhateverTheSource() {
    String out = html(Kit.markdown(new Props.Markdown(XSS + "\n\n[x](javascript:alert(1)) [y](data:text/html,x) <a href=\"/ok\" onclick=\"z()\">ok</a>\n\n<iframe src=\"/x\"></iframe><svg onload=alert(1)>\n", "extra")));
    org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(out);
    assertThat(doc.select("script, iframe, svg, style")).isEmpty();
    for (org.jsoup.nodes.Element e : doc.getAllElements()) {
      e.attributes().forEach(a -> assertThat(a.getKey()).doesNotStartWith("on"));
    }
    assertThat(doc.select("a[href]").eachAttr("href")).containsExactly("/ok");
    assertThat(doc.select("div.fc-markdown.extra")).hasSize(1);
  }

  @Test
  void markdownKeepsAnImageButNotItsHandlerAndHeadingIdsFollowTheText() {
    String out = html(Kit.markdown(new Props.Markdown("## Second factors\n\n<img src=x onerror=alert(1)>\n\n- [x] done\n- [ ] todo\n\n~~gone~~ https://example.com/auto\n", null)));
    org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(out);
    assertThat(doc.select("h2").attr("id")).isEqualTo("second-factors");
    assertThat(doc.select("img").attr("src")).isEqualTo("x");
    assertThat(doc.select("img").first().attributes().size()).isEqualTo(1);
    assertThat(doc.select("input[type=checkbox]")).hasSize(2);
    assertThat(doc.select("input[checked]")).hasSize(1);
    assertThat(doc.select("del").text()).isEqualTo("gone");
    assertThat(doc.select("a[href]").eachAttr("href")).containsExactly("https://example.com/auto");
  }

  @Test
  void markdownWithoutASourceIsEmpty() {
    assertThat(html(Kit.markdown(new Props.Markdown(null, null))).strip()).isEqualTo("<div class=\"fc-markdown\"></div>");
  }

  @Test
  void theCodeEditorWritesItsTextAsTheTextareaBodyOnly() {
    String out = html(Kit.codeEditor(new Props.CodeEditor("e", "n", XSS, "json", false, XSS, 0, false, null, null, null)));
    assertNoLiveMarkup(out);
    org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(out);
    assertThat(doc.select("textarea").first().wholeText()).isEqualTo(XSS);
    assertThat(doc.select("textarea").attr("aria-label")).isEqualTo(XSS);
    assertThat(doc.select("fc-code-editor script")).isEmpty();
  }

  @Test
  void aDiagramSourceIsElementTextAndItsBundleUrlIsChecked() {
    String out = html(Kit.diagram(new Props.Diagram(XSS, "javascript:alert(1)", null), Markup.text(XSS)));
    assertNoLiveMarkup(out);
    org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(out);
    assertThat(doc.select("fc-diagram").attr("data-bundle")).isEqualTo(Markup.BLOCKED_URL);
    assertThat(doc.select("pre code").first().wholeText()).isEqualTo(XSS);
    assertThat(doc.select("[data-ignore][hidden]")).hasSize(1);
  }

  @Test
  void thePermissionDeniedDialogNamesNoApplicationPath() {
    assertThatThrownBy(() -> html(Kit.permissionDeniedDialog(new Props.PermissionDeniedDialog("d", "denied", "m", null, null, "/login", false))))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> html(Kit.permissionDeniedDialog(new Props.PermissionDeniedDialog("d", null, "m", null, "", null, false))))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> html(Kit.permissionDeniedDialog(new Props.PermissionDeniedDialog("d", "session", "m", null, "/home", null, false))))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> html(Kit.permissionDeniedDialog(new Props.PermissionDeniedDialog("d", "other", "m", null, "/home", "/login", false))))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    // the other case's URL is not needed
    assertThat(html(Kit.permissionDeniedDialog(new Props.PermissionDeniedDialog("d", "session", "m", null, null, "/login", false)))).contains("href=\"/login\"");
    String out = html(Kit.permissionDeniedDialog(new Props.PermissionDeniedDialog("d", "denied", XSS, XSS, "javascript:alert(1)", null, false)));
    assertNoLiveMarkup(out);
    assertThat(org.jsoup.Jsoup.parseBodyFragment(out).select("a.fc-btn-primary").attr("href")).isEqualTo(Markup.BLOCKED_URL);
  }

  @Test
  void thePermissionDeniedKindFollowsTheMessageAsTheSpaDoes() {
    assertThat(Props.PermissionDeniedDialog.kindOf("Your session has expired")).isEqualTo("session");
    assertThat(Props.PermissionDeniedDialog.kindOf("User is not Authenticated")).isEqualTo("session");
    assertThat(Props.PermissionDeniedDialog.kindOf("Forbidden")).isEqualTo("denied");
  }

  @Test
  void theSignalsAndIdsOfThePasswordTabListAndSecretRefComponentsAreValidated() {
    String evil = "x');alert(1);//";
    assertThatThrownBy(() -> html(Kit.passwordInput(new Props.PasswordInput("p", null, evil, null, false, false, false, null, null, true, null))))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> html(Kit.passwordInput(new Props.PasswordInput(evil, null, "pw", null, false, false, false, null, null, false, null))))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> html(Kit.secretRefInput(new Props.SecretRefInput(evil, null, null, null, false, false, false, null, null))))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
    for (Props.TabList bad :
        new Props.TabList[] {
          new Props.TabList("t", "sig", null, java.util.List.of(new Props.TabItem(evil, "x")), null, null, null, null),
          new Props.TabList("t", "sig", null, java.util.List.of(new Props.TabItem("a", "x")), null, evil, null, null),
          new Props.TabList("t", evil, null, java.util.List.of(new Props.TabItem("a", "x")), null, null, null, null),
          new Props.TabList(evil, "sig", null, java.util.List.of(new Props.TabItem("a", "x")), null, null, null, null),
          new Props.TabList("t", "sig", null, java.util.List.of(new Props.TabItem("a", "x")), evil, null, null, null),
        }) {
      assertThatThrownBy(() -> html(Kit.tabList(bad, Map.of()))).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void aSecretReferenceValueAndTheSearchSelectTextAreEscaped() {
    String out = html(Kit.secretRefInput(new Props.SecretRefInput("s", "n", XSS, XSS, false, false, true, XSS, XSS)));
    assertNoLiveMarkup(out);
    assertThat(org.jsoup.Jsoup.parseBodyFragment(out).select("input").attr("value")).isEqualTo(XSS);
    String select =
        html(
            Kit.searchSelect(
                new Props.SearchSelect(
                    "app",
                    XSS,
                    java.util.List.of(new Props.Option(XSS, XSS)),
                    XSS,
                    v -> "javascript:alert(1)",
                    XSS,
                    "appSearch",
                    XSS,
                    null,
                    null)));
    assertNoLiveMarkup(select);
    org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(select);
    assertThat(doc.select("a[role=option]").attr("href")).isEqualTo(Markup.BLOCKED_URL);
    assertThat(doc.select("a[role=option]").text()).isEqualTo(XSS);
    assertThat(doc.select("a[role=option]").attr("data-filter-text")).isEqualTo(XSS.toLowerCase());
  }
}
