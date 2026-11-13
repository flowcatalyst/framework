package io.flowcatalyst.framework.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.jte.Content;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.ui.kit.Kit;
import io.flowcatalyst.framework.ui.kit.Markup;
import io.flowcatalyst.framework.ui.kit.Props;
import io.flowcatalyst.framework.http.datastar.ServerSentEventGenerator;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
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

  // ---- extra image sources (contract section 6 rule 1, amended 2026-11-12) ----------------------
  // The same cases are written out in Go's views_test.go (imageSourcesAccepted, imageSourcesRefused).

  /** The policy of contract section 6 rule 1, byte for byte. */
  private static final String DEFAULT_CSP =
      "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self'; style-src-attr 'unsafe-inline'; "
          + "img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; "
          + "form-action 'self'; object-src 'none'";

  /** The sources given, and the img-src directive they give. */
  private record Accepted(List<String> sources, String imgSrc) {}

  private static final List<Accepted> IMAGE_SOURCES_ACCEPTED =
      List.of(
          new Accepted(List.of(), "img-src 'self' data:"),
          new Accepted(List.of("https://tile.openstreetmap.org"), "img-src 'self' data: https://tile.openstreetmap.org"),
          new Accepted(
              List.of("https://tile.openstreetmap.org", "https://tiles.example.org", "https://cdn.example.net"),
              "img-src 'self' data: https://tile.openstreetmap.org https://tiles.example.org https://cdn.example.net"),
          new Accepted(List.of("https://*.tile.openstreetmap.org"), "img-src 'self' data: https://*.tile.openstreetmap.org"),
          new Accepted(List.of("https://tiles.example.org:8443"), "img-src 'self' data: https://tiles.example.org:8443"),
          new Accepted(List.of("https://*.example.org:443"), "img-src 'self' data: https://*.example.org:443"),
          new Accepted(
              List.of("https://localhost:65535", "https://192.168.0.10:1"),
              "img-src 'self' data: https://localhost:65535 https://192.168.0.10:1"),
          new Accepted(List.of("https://tile-1.example.org"), "img-src 'self' data: https://tile-1.example.org"),
          new Accepted(List.of("HTTPS://Tile.OpenStreetMap.ORG"), "img-src 'self' data: https://tile.openstreetmap.org"),
          new Accepted(
              List.of("https://b.example.org", "https://a.example.org", "https://b.example.org", "HTTPS://A.example.org"),
              "img-src 'self' data: https://b.example.org https://a.example.org"));

  /** Each is refused on its own. */
  private static final List<String> IMAGE_SOURCES_REFUSED =
      List.of(
          "", "*", "https:", "https://", "https:/tile.example.org", "data:", "blob:", "'self'", "'none'",
          "http://tile.example.org", "wss://tile.example.org", "tile.example.org", "//tile.example.org",
          "https://tile.example.org/", "https://tile.example.org/tiles/{z}/{x}/{y}.png", "https://tile.example.org?x=1",
          "https://tile.example.org#x", "https://user@tile.example.org", "https://user:pw@tile.example.org",
          " https://tile.example.org", "https://tile.example.org ", "https://tile.example .org", "https://tile.example.org\t",
          "https://tile.example.org\n", "'https://tile.example.org'", "\"https://tile.example.org\"",
          "https://a.example.org,https://b.example.org", "https://a.example.org;", "https://a.example.org; script-src *",
          "https://*", "https://*.", "https://*.*.example.org", "https://a.*.example.org", "https://*example.org",
          "https://tile.example.org:", "https://tile.example.org:0", "https://tile.example.org:080",
          "https://tile.example.org:65536", "https://tile.example.org:99999", "https://tile.example.org:*",
          "https://tile..example.org", "https://.example.org", "https://example.org.", "https://tile_1.example.org",
          "https://[::1]", "https://tile.Kexample.org", "https://tilé.example.org");

  @Test
  void imageSourcesWidenOnlyImgSrc() {
    assertThat(ViewCsp.policy()).isEqualTo(DEFAULT_CSP);
    assertThat(ViewCsp.policy(List.of(), List.of())).isEqualTo(DEFAULT_CSP);
    for (Accepted c : IMAGE_SOURCES_ACCEPTED) {
      assertThat(ViewCsp.policy(List.of(), c.sources()))
          .as("%s", c.sources())
          .isEqualTo(DEFAULT_CSP.replace("img-src 'self' data:", c.imgSrc()));
    }
    assertThat(ViewCsp.policy(List.of("sha256-abc="), List.of("https://tile.openstreetmap.org")))
        .contains("'unsafe-eval' 'sha256-abc='; style-src")
        .contains("; img-src 'self' data: https://tile.openstreetmap.org; font-src 'self';");

    // documents send it; without the call the header is unchanged; a Datastar answer has none
    Routes routes = new Routes("test");
    Views plain = Views.builder(ASSETS).build();
    Views map =
        Views.builder(ASSETS)
            .imageSource("https://tile.openstreetmap.org")
            .imageSource("https://*.example.org:8443")
            .imageSource("https://tile.openstreetmap.org")
            .build();
    routes.get("/plain", r -> plain.page(r, "Plain", content(r)));
    routes.get("/map", r -> map.page(r, "Map", content(r)));
    ASSETS.register(routes.forModule("ui"));
    TestClient c = TestClient.of(routes);
    assertThat(c.get("/plain").send().header("Content-Security-Policy")).isEqualTo(DEFAULT_CSP);
    assertThat(c.get("/map").send().header("Content-Security-Policy"))
        .isEqualTo(
            DEFAULT_CSP.replace(
                "img-src 'self' data:", "img-src 'self' data: https://tile.openstreetmap.org https://*.example.org:8443"));
    assertThat(c.get("/map").header("Datastar-Request", "true").send().header("Content-Security-Policy")).isNull();
  }

  @Test
  void imageSourcesOutsideTheRuleAreRefused() {
    for (String bad : IMAGE_SOURCES_REFUSED) {
      assertThatThrownBy(() -> ViewCsp.policy(List.of(), List.of("https://ok.example.org", bad)))
          .as("%s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("image source")
          .hasMessageContaining("https origin");
      assertThatThrownBy(() -> Views.builder(ASSETS).imageSource(bad).build())
          .as("builder %s", bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> Views.builder(ASSETS).imageSource(null).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- returnTo (contract section 8 rule 1) -----------------------------------------------------

  @Test
  void theSessionExpiredListenerSendsReturnToByDefault() {
    Document doc = Jsoup.parse(client(false).get("/orders").send().body());
    String expr = doc.select("body").attr("data-on:datastar-fetch");
    assertThat(expr).contains("window.location.assign('/auth/login?returnTo=' + encodeURIComponent(location.pathname + location.search))");
    assertThat(expr).doesNotContain("redirect=");
  }

  @Test
  void anApplicationMayNameTheReturnParameterAndBadOnesAreRefused() {
    Routes routes = new Routes("test");
    Views views = Views.builder(ASSETS).sessionExpiredLogin("/signin", "next_page").build();
    routes.get("/p", r -> views.page(r, "P", Markup.text("x")));
    String expr = Jsoup.parse(TestClient.of(routes).get("/p").send().body()).select("body").attr("data-on:datastar-fetch");
    assertThat(expr).contains("assign('/signin?next_page='");
    for (String bad : new String[] {"", "1a", "a-b", "a b", "x');alert(1);//"}) {
      assertThatThrownBy(() -> Views.builder(ASSETS).sessionExpiredLogin("/signin", bad)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> Views.builder(ASSETS).sessionExpiredLogin("//evil.example")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Views.builder(ASSETS).sessionExpiredLogin("/a b")).isInstanceOf(IllegalArgumentException.class);
  }

  // ---- island scripts (contract section 6 rule 5) ------------------------------------------------

  private static TestClient scriptsClient() {
    Routes routes = new Routes("test");
    Views views = Views.builder(ASSETS).build();
    routes.get(
        "/editor",
        r ->
            views.page(
                r,
                200,
                "Editor",
                Markup.text("x"),
                List.of(),
                List.of("fc-code-editor.js", "datastar.js", "fc-diagram.js", "fc-code-editor.js")));
    routes.get("/bad", r -> views.page(r, 200, "Bad", Markup.text("x"), List.of(), List.of(r.query("s"))));
    ASSETS.register(routes.forModule("ui"));
    return TestClient.of(routes);
  }

  @Test
  void aPageListsItsIslandScriptsAsModulesAfterDatastarOnceEach() {
    Document doc = Jsoup.parse(scriptsClient().get("/editor").send().body());
    List<String> srcs = doc.select("head script[type=module]").eachAttr("src");
    assertThat(srcs).hasSize(3);
    assertThat(srcs.get(0)).startsWith("/fw-ui/datastar.js?v=");
    assertThat(srcs.get(1)).isEqualTo(ASSETS.url("fc-code-editor.js"));
    assertThat(srcs.get(2)).isEqualTo(ASSETS.url("fc-diagram.js"));
    assertThat(doc.select("script:not([src])")).isEmpty();
  }

  @Test
  void onlyJavaScriptAssetsCanBeNamedAsPageScripts() {
    TestClient c = scriptsClient();
    // a refusal fails the request (500) before anything is written
    for (String bad : new String[] {"kit.css", "lucide-sprite.svg", "LICENSE-mermaid.md", "nope.js", "..%2Fx.js", "%22%3E%3Cscript%3Ealert(1)%3C%2Fscript%3E.js"}) {
      TestResponse r = c.get("/bad?s=" + bad).send();
      assertThat(r.status()).as(bad).isEqualTo(500);
      assertThat(r.body()).as(bad).doesNotContain("<script>");
    }
    assertThat(c.get("/bad?s=fc-diagram.js").send().status()).isEqualTo(200);
  }

  @Test
  void aDatastarAnswerIgnoresThePageScripts() {
    String body = scriptsClient().get("/editor").header("Datastar-Request", "true").send().body();
    assertThat(body).doesNotContain("fc-code-editor").contains("data: elements <div id=\"fw-page\">");
  }

  // ---- the flash cookie (contract section 9) ----------------------------------------------------

  private static final Props.FlashMessage SAVED = new Props.FlashMessage("success", "Saved.", "The order was saved.", false, null);

  private static Views flashViews(boolean plain) {
    Views.Builder b = Views.builder(ASSETS).flash("saved", SAVED).flash("order-deleted", new Props.FlashMessage("warn", "Deleted.", null, false, null));
    return (plain ? b.plainHttpCookies() : b).build();
  }

  private static TestClient flashClient(Views views) {
    Routes routes = new Routes("test");
    routes.get("/orders", r -> views.page(r, "Orders", Markup.text("list")));
    routes.get("/orders/page-flash", r -> views.page(r, 200, "Orders", Markup.text("list"), List.of(new Props.FlashMessage("info", "Careful", null, false, null))));
    routes.post("/orders", r -> views.seeOther(r, "/orders?x=1", "saved"));
    routes.post("/orders/bare", r -> views.seeOther(r, "/orders", null));
    routes.post("/orders/bad-path", r -> views.seeOther(r, "//evil.example", "saved"));
    routes.post("/orders/bad-key", r -> views.seeOther(r, "/orders", "typo"));
    return TestClient.of(routes);
  }

  @Test
  void aFlashCookieRendersOnTheNextDocumentAndIsCleared() {
    TestClient c = flashClient(flashViews(true));
    TestResponse r = c.get("/orders").header("Cookie", "fw_flash=saved").send();
    Document doc = Jsoup.parse(r.body());
    assertThat(doc.select("#flash-stack").text()).contains("Saved.").contains("The order was saved.");
    assertThat(r.headerValues("Set-Cookie")).containsExactly("fw_flash=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
    // the cookie's message comes before the page's own
    Document both = Jsoup.parse(c.get("/orders/page-flash").header("Cookie", "fw_flash=saved").send().body());
    assertThat(both.select("#flash-stack > .fc-flash .fc-flash-summary").eachText()).containsExactly("Saved.", "Careful");
    // an unknown or malformed key renders nothing and is cleared too
    for (String key : new String[] {"nope", "Bad_Key", "a".repeat(65), ""}) {
      TestResponse x = c.get("/orders").header("Cookie", "fw_flash=" + key).send();
      assertThat(Jsoup.parse(x.body()).select("#flash-stack").text()).as(key).doesNotContain("Saved");
      assertThat(x.headerValues("Set-Cookie")).as(key).hasSize(1);
    }
    // no cookie: nothing set
    assertThat(c.get("/orders").send().headerValues("Set-Cookie")).isEmpty();
    // a Datastar answer ignores the cookie and leaves it for the next document
    TestResponse d = c.get("/orders").header("Cookie", "fw_flash=saved").header("Datastar-Request", "true").send();
    assertThat(d.body()).doesNotContain("Saved.");
    assertThat(d.headerValues("Set-Cookie")).isEmpty();
  }

  @Test
  void theFlashCookieIsSecureUnlessThePlainHttpOptionWasGiven() {
    String strict = flashClient(flashViews(false)).post("/orders").send().header("Set-Cookie");
    assertThat(strict).isEqualTo("fw_flash=saved; Path=/; Max-Age=60; HttpOnly; SameSite=Lax; Secure");
    String plain = flashClient(flashViews(true)).post("/orders").send().header("Set-Cookie");
    assertThat(plain).isEqualTo("fw_flash=saved; Path=/; Max-Age=60; HttpOnly; SameSite=Lax");
    // the clearing cookie follows the same rule
    assertThat(flashClient(flashViews(false)).get("/orders").header("Cookie", "fw_flash=saved").send().header("Set-Cookie"))
        .isEqualTo("fw_flash=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax; Secure");
  }

  @Test
  void setFlashRefusesAKeyThatIsNotRegisteredOrNotLowerCaseAlphanumericOrDash() {
    Views v = flashViews(false);
    Response ok = Response.noContent();
    for (String key : new String[] {"", "Saved", "a_b", "a b", "a;b", "a\r\nSet-Cookie: x=y", "a".repeat(65), "é", "typo"}) {
      assertThatThrownBy(() -> v.setFlash(ok, key)).as(key).isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(v.setFlash(ok, "order-deleted").header("Set-Cookie")).startsWith("fw_flash=order-deleted;");
    // registration is checked too
    assertThatThrownBy(() -> Views.builder(ASSETS).flash("Bad_Key", SAVED)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Views.builder(ASSETS).flash("ok", new Props.FlashMessage("loud", "x", null, false, null))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void seeOtherAnswers303WithTheFlashCookieAndADatastarRequestGetsARedirectPatch() {
    TestClient c = flashClient(flashViews(false));
    TestResponse r = c.post("/orders").send();
    assertThat(r.status()).isEqualTo(303);
    assertThat(r.header("Location")).isEqualTo("/orders?x=1");
    assertThat(r.header("Set-Cookie")).startsWith("fw_flash=saved;");
    TestResponse bare = c.post("/orders/bare").send();
    assertThat(bare.status()).isEqualTo(303);
    assertThat(bare.headerValues("Set-Cookie")).isEmpty();
    // a Datastar request cannot follow a 303: 200, the cookie, and a redirect patch
    TestResponse d = c.post("/orders").header("Datastar-Request", "true").send();
    assertThat(d.status()).isEqualTo(200);
    assertThat(d.header("Content-Type")).startsWith("text/event-stream");
    assertThat(d.header("Set-Cookie")).startsWith("fw_flash=saved;");
    assertThat(d.body()).contains("data: selector body").contains("data: mode append")
        .contains("<span hidden data-init=\"window.location.assign(&quot;/orders?x=1&quot;)\"></span>");
    assertThat(d.header("Location")).isNull();
  }

  @Test
  void seeOtherRefusesAPathThatIsNotLocalAndAKeyThatIsNotRegistered() {
    TestClient c = flashClient(flashViews(false));
    // a refusal fails the request (500): no Location, no cookie
    for (String path : new String[] {"/orders/bad-path", "/orders/bad-key"}) {
      TestResponse r = c.post(path).send();
      assertThat(r.status()).as(path).isEqualTo(500);
      assertThat(r.header("Location")).as(path).isNull();
      assertThat(r.headerValues("Set-Cookie")).as(path).isEmpty();
    }
  }

  // ---- redirect and replaceURL (contract section 6 rule 3) --------------------------------------

  private static String patches(java.util.function.Consumer<Patches> fn) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ServerSentEventGenerator sse = new ServerSentEventGenerator(out);
    fn.accept(new Patches(sse, Views.FLASH_STACK_ID));
    sse.close();
    return out.toString(java.nio.charset.StandardCharsets.UTF_8);
  }

  @Test
  void redirectAndReplaceUrlAppendOneHiddenDataInitElementToTheBody() {
    String redirect = patches(p -> p.redirect("/orders?q=a&b=\"c\""));
    assertThat(redirect)
        .isEqualTo(
            "event: datastar-patch-elements\ndata: selector body\ndata: mode append\n"
                + "data: elements <span hidden data-init=\"window.location.assign(&quot;/orders?q=a&amp;b=\\&quot;c\\&quot;&quot;)\"></span>\n\n");
    String replace = patches(p -> p.replaceURL("/orders?sort=name"));
    assertThat(replace)
        .contains("data: selector body")
        .contains("data: mode append")
        .contains("<span hidden data-init=\"history.replaceState(history.state, &#39;&#39;, &quot;/orders?sort=name&quot;); el.remove()\"></span>");
    assertThat(replace.lines().filter(l -> l.startsWith("event:")).count()).isEqualTo(1);
  }

  @Test
  void redirectAndReplaceUrlRefuseAnythingButALocalPath() {
    for (String bad : new String[] {"//evil.example", "https://evil.example", "javascript:alert(1)", "/a\\b", "orders", "", "/a\nb"}) {
      assertThatThrownBy(() -> patches(p -> p.redirect(bad))).as(bad).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> patches(p -> p.replaceURL(bad))).as(bad).isInstanceOf(IllegalArgumentException.class);
    }
    // a path that tries to leave the attribute or the string is data, not code
    String hostile = patches(p -> p.redirect("/x\"');alert(1);//"));
    assertThat(hostile).doesNotContain("\"');alert").contains("&quot;/x\\&quot;");
  }

  // ---- the islands and licences (contract section 4) --------------------------------------------

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  @Test
  void theVendoredFilesAreExactlyThePinnedOnes() throws Exception {
    TestClient c = client(false);
    assertThat(sha256(c.get("/fw-ui/datastar.js").send().bytes())).isEqualTo(UiAssets.DATASTAR_SHA256);
    assertThat(UiAssets.DATASTAR_SHA256).isEqualTo("727844adfc825ee651fb93c544a2a739986f9a21820a94524b35f0cac470cf91");
    assertThat(sha256(c.get(ASSETS.url("mermaid.min.js")).send().bytes())).isEqualTo(UiAssets.MERMAID_SHA256);
    assertThat(sha256(c.get(ASSETS.url("fc-code-editor.js")).send().bytes())).isEqualTo(UiAssets.CODE_EDITOR_SHA256);
    assertThat(sha256(c.get(ASSETS.url("fc-diagram.js")).send().bytes())).isEqualTo(UiAssets.DIAGRAM_SHA256);
  }

  @Test
  void theIslandsAndEveryThirdPartyLicenceAreServedWithTheirTypes() {
    TestClient c = client(false);
    assertThat(ASSETS.names())
        .containsExactlyInAnyOrder(
            "KIT-THIRD-PARTY-NOTICES.md", "LICENSE-codemirror.md", "LICENSE-datastar.md", "LICENSE-lucide.txt",
            "LICENSE-mermaid.md", "datastar.js", "fc-code-editor.js", "fc-diagram.js", "kit.css",
            "lucide-sprite.svg", "mermaid.min.js");
    for (String name : ASSETS.names()) {
      TestResponse r = c.get(ASSETS.url(name)).send();
      assertThat(r.status()).as(name).isEqualTo(200);
      assertThat(r.header("Cache-Control")).as(name).contains("immutable");
      assertThat(c.get(ASSETS.url(name)).header("If-None-Match", r.header("ETag")).send().status()).as(name).isEqualTo(304);
      String type = r.header("Content-Type");
      assertThat(type).as(name).isEqualTo(
          name.endsWith(".js") ? "text/javascript; charset=utf-8"
              : name.endsWith(".css") ? "text/css; charset=utf-8"
              : name.endsWith(".svg") ? "image/svg+xml" : "text/plain; charset=utf-8");
    }
    assertThat(c.get("/fw-ui/fc-webauthn.js").send().status()).isEqualTo(404);
  }
}
