package io.flowcatalyst.framework.ui;

import static org.assertj.core.api.Assertions.assertThat;

import gg.jte.Content;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.ui.kit.Kit;
import io.flowcatalyst.framework.ui.kit.Markup;
import io.flowcatalyst.framework.ui.kit.Props;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class ViewsTest {

  private static final UiAssets ASSETS = UiAssets.load();
  private static final String XSS = "<script>alert(1)</script>";

  private static Props.AppShell shell(Request r) {
    return new Props.AppShell(
        "/",
        List.of(
            new Props.NavGroup(
                "Main",
                List.of(new Props.NavItem("Orders", "/orders", "inbox", true, false, null)))),
        new Props.ShellUser("Ada", "ada@example.com", "A"),
        "1.0",
        "/profile",
        null,
        "/auth/logout",
        null,
        null);
  }

  private static Content content(Request r) {
    String q = r.query("q") == null ? "" : r.query("q");
    return Kit.alert(new Props.Alert(null, null, null, "Searched", q, null, null), null);
  }

  private static TestClient client(boolean withShell) {
    Routes routes = new Routes("test");
    Views.Builder b = Views.builder(ASSETS).titleSuffix(" - Demo").sessionExpiredLogin("/auth/login");
    if (withShell) {
      b.shell(ViewsTest::shell);
    }
    Views views = b.build();
    routes.get("/orders", r -> views.page(r, "Orders", content(r)));
    routes.get("/orders/stream", r -> views.stream(r, p -> {
      p.inner("#list", Markup.text("<b>" + XSS));
      p.flash("success", "Saved.", null);
      p.remove("#row-1");
      p.replace("#dlg", Markup.raw("<dialog id=\"dlg\"></dialog>"));
      p.focus("opener");
      p.closeDialog("dlg");
    }));
    ASSETS.register(routes.forModule("ui"));
    return TestClient.of(routes);
  }

  @Test
  void aNavigationGetsTheFullDocumentWithThePagePolicy() {
    TestResponse r = client(true).get("/orders").send();
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.header("Content-Type")).startsWith("text/html");
    assertThat(r.body()).startsWith("<!DOCTYPE html>");
    Document doc = Jsoup.parse(r.body());
    assertThat(doc.title()).isEqualTo("Orders - Demo");
    assertThat(doc.select("link[rel=stylesheet]").attr("href")).startsWith("/fw-ui/kit.css?v=");
    assertThat(doc.select("script[type=module]").attr("src")).startsWith("/fw-ui/datastar.js?v=");
    assertThat(doc.select("#fw-page .col-start-2")).isNotEmpty();
    assertThat(doc.select("#flash-stack")).hasSize(1);
    assertThat(doc.select("aside#fc-sidebar nav a[aria-current=page]").text()).isEqualTo("Orders");
    String csp = r.header("Content-Security-Policy");
    assertThat(csp).contains("script-src 'self' 'unsafe-eval';").contains("style-src 'self'; style-src-attr 'unsafe-inline'")
        .contains("frame-ancestors 'none'").doesNotContain("'unsafe-inline'; style").doesNotContain("script-src 'self' 'unsafe-inline'");
    assertThat(r.header("Cross-Origin-Opener-Policy")).isEqualTo("same-origin");
    assertThat(r.header("X-Content-Type-Options")).isEqualTo("nosniff");
  }

  @Test
  void theDocumentHasNoInlineScriptOrStyleElementOrEventHandlerAttribute() {
    Document doc = Jsoup.parse(client(true).get("/orders?q=" + "x").send().body());
    assertThat(doc.select("script:not([src])")).isEmpty();
    assertThat(doc.select("style")).isEmpty();
    doc.getAllElements().forEach(e -> e.attributes().forEach(a -> assertThat(a.getKey()).doesNotStartWith("on")));
  }

  @Test
  void aDatastarRequestGetsOnlyAPatchOfThePageContent() {
    TestResponse r = client(true).get("/orders?q=abc").header("Datastar-Request", "true").send();
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.header("Content-Type")).startsWith("text/event-stream");
    assertThat(r.body()).startsWith("event: datastar-patch-elements\n");
    assertThat(r.body()).contains("data: elements <div id=\"fw-page\">").doesNotContain("<html").doesNotContain("<head");
    assertThat(r.body()).contains("abc");
    assertThat(r.header("Content-Security-Policy")).isNull();
  }

  @Test
  void theSameTemplateAnswersBothWays() {
    String full = client(false).get("/orders?q=same").send().body();
    String sse = client(false).get("/orders?q=same").header("Datastar-Request", "true").send().body();
    String fragment = Jsoup.parse(full).select("#fw-page").outerHtml().replaceAll("\\s+", " ");
    String patched = sse.lines().filter(l -> l.startsWith("data: elements ")).map(l -> l.substring("data: elements ".length())).reduce("", (a, b) -> a + b);
    assertThat(Jsoup.parseBodyFragment(patched).select("#fw-page").outerHtml().replaceAll("\\s+", " ")).isEqualTo(fragment);
  }

  @Test
  void aPageWithoutAShellHasNoSidebarButTheFlashStack() {
    Document doc = Jsoup.parse(client(false).get("/orders").send().body());
    assertThat(doc.select("aside")).isEmpty();
    assertThat(doc.select("main #flash-stack")).hasSize(1);
  }

  @Test
  void requestDataInAPageIsEscapedInBothAnswers() {
    String q = java.net.URLEncoder.encode(XSS, java.nio.charset.StandardCharsets.UTF_8);
    TestClient c = client(true);
    Document doc = Jsoup.parse(c.get("/orders?q=" + q).send().body());
    assertThat(doc.select("script:not([src])")).isEmpty();
    assertThat(doc.select("#fw-page").text()).contains(XSS);
    String sse = c.get("/orders?q=" + q).header("Datastar-Request", "true").send().body();
    assertThat(sse).doesNotContain("<script>").contains("&lt;script&gt;");
  }

  @Test
  void streamHelpersWriteInnerAppendAndRemovePatches() {
    String body = client(false).get("/orders/stream").header("Datastar-Request", "true").send().body();
    assertThat(body).contains("data: selector #list").contains("data: mode inner").contains("&lt;script&gt;");
    assertThat(body).doesNotContain("<script>");
    assertThat(body).contains("data: selector #flash-stack").contains("data: mode append").contains("fc-flash-success");
    assertThat(body).contains("data: selector #row-1").contains("data: mode remove");
    assertThat(body).contains("data: selector #dlg").contains("data: mode replace");
    assertThat(body).contains("document.getElementById(&#39;opener&#39;)?.focus()");
    assertThat(body).contains("document.getElementById(&#39;dlg&#39;).close()");
  }

  @Test
  void themeCookiesRenderTheDarkClassOnHtml() {
    TestClient c = client(true);
    assertThat(Jsoup.parse(c.get("/orders").header("Cookie", "fc_theme=dark").send().body()).select("html").hasClass("dark")).isTrue();
    assertThat(Jsoup.parse(c.get("/orders").header("Cookie", "fc_scheme=dark").send().body()).select("html").hasClass("dark")).isTrue();
    assertThat(Jsoup.parse(c.get("/orders").header("Cookie", "fc_theme=light; fc_scheme=dark").send().body()).select("html").hasClass("dark")).isFalse();
    assertThat(Jsoup.parse(c.get("/orders").header("Cookie", "fc_sidebar=collapsed").send().body()).select("html").attr("data-signals:sidebar-collapsed")).isEqualTo("true");
  }

  @Test
  void assetsAreServedHashedCachedAndConditional() {
    TestClient c = client(false);
    String url = ASSETS.url("kit.css");
    TestResponse css = c.get(url).send();
    assertThat(css.status()).isEqualTo(200);
    assertThat(css.header("Content-Type")).startsWith("text/css");
    assertThat(css.header("Cache-Control")).contains("immutable");
    assertThat(css.body()).contains("--background");
    TestResponse again = c.get(url).header("If-None-Match", css.header("ETag")).send();
    assertThat(again.status()).isEqualTo(304);
    assertThat(c.get("/fw-ui/datastar.js").send().body()).startsWith("// Datastar v1.0.4");
    assertThat(c.get("/icons/lucide-sprite.svg").send().header("Content-Type")).startsWith("image/svg+xml");
    assertThat(c.get("/fw-ui/other.js").send().status()).isEqualTo(404);
  }

  @Test
  void aContentBlockFromATemplateRendersThroughMarkupRender() {
    gg.jte.html.HtmlContent block = out -> out.writeUserContent("<i>&");
    assertThat(Markup.render(block)).isEqualTo("&lt;i&gt;&amp;");
    gg.jte.Content body = block;
    assertThat(Markup.render(Kit.detailField(new Props.DetailField("L", null, false), body))).contains("&lt;i&gt;&amp;");
  }

  @Test
  void policyRefusesAMalformedHash() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ViewCsp.policy("not a hash"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ViewCsp.policy("sha256-abc=")).contains("'sha256-abc='");
  }
}
