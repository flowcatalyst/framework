package io.flowcatalyst.framework.ui;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.ui.i18n.Bundle;
import io.flowcatalyst.framework.ui.i18n.Catalog;
import io.flowcatalyst.framework.ui.i18n.LocaleResolver;
import io.flowcatalyst.framework.ui.kit.Kit;
import io.flowcatalyst.framework.ui.kit.Props;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ViewsI18nTest {

  private static final UiAssets ASSETS = UiAssets.load();

  private static Props.AppShell shell(Request r) {
    return new Props.AppShell(
        "/",
        List.of(new Props.NavGroup("Main", List.of(new Props.NavItem("Orders", "/orders", "inbox", true, false, null)))),
        new Props.ShellUser("Ada", "ada@example.com", "A"),
        "1.0",
        "/profile",
        null,
        "/auth/logout",
        null,
        null);
  }

  private static TestClient client(boolean withLocales) {
    Routes routes = new Routes("test");
    Views.Builder b =
        Views.builder(ASSETS)
            .shell(ViewsI18nTest::shell)
            .flash("saved", new Props.FlashMessage("success", "Saved.", "The order was saved.", false, null))
            .flash("gone", new Props.FlashMessage("error", "Deleted.", null, false, null));
    if (withLocales) {
      b.locales(LocaleResolver.builder().supported("en", "af").user(r -> r.header("X-User-Locale")).build())
          .catalogs(
              Bundle.kit()
                  .with(
                      Map.of(
                          "af", Catalog.parse("{\"flash.saved\": \"Gestoor.\", \"flash.saved.detail\": \"Die bestelling is gestoor.\"}"),
                          "en", Catalog.parse("{\"flash.gone\": \"Removed.\"}"))));
    }
    Views views = b.build();
    routes.get("/orders", r -> views.page(r, "Orders", Kit.alert(new Props.Alert(null, null, null, "Searched", "", null, null), null)));
    routes.get("/orders/stream", r -> views.stream(r, p -> p.flash("success", "Saved.", null)));
    ASSETS.register(routes.forModule("ui"));
    return TestClient.of(routes);
  }

  @Test
  void theDocumentLangIsTheResolvedLocale() {
    TestClient c = client(true);
    assertThat(c.get("/orders").send().body()).contains("<html lang=\"en\"");
    assertThat(c.get("/orders").header("Accept-Language", "af-ZA,af;q=0.9,en;q=0.5").send().body()).contains("<html lang=\"af\"");
    assertThat(c.get("/orders").header("Accept-Language", "fr").send().body()).contains("<html lang=\"en\"");
    assertThat(c.get("/orders").header("X-User-Locale", "af").header("Accept-Language", "en").send().body()).contains("<html lang=\"af\"");
  }

  @Test
  void aPageWithoutLocalesIsEnglish() {
    String body = client(false).get("/orders").header("Accept-Language", "af").send().body();
    assertThat(body).contains("<html lang=\"en\"").contains("Sign Out");
  }

  @Test
  void theShellSpeaksTheResolvedLanguage() {
    String af = client(true).get("/orders").header("Accept-Language", "af").send().body();
    assertThat(af).contains("Teken uit", "Profiel", "Weergawe", "aria-label=\"Maak navigasie oop\"").doesNotContain("Sign Out");
  }

  @Test
  void aFlashComesFromTheCatalogWhenItHasTheKey() {
    TestClient c = client(true);
    String af = c.get("/orders").header("Accept-Language", "af").header("Cookie", "fw_flash=saved").send().body();
    assertThat(af).contains("Gestoor.", "Die bestelling is gestoor.").doesNotContain("The order was saved.");
    String en = c.get("/orders").header("Cookie", "fw_flash=saved").send().body();
    assertThat(en).contains("Saved.", "The order was saved.");
    // flash.gone exists in en: the fallback chain reaches it for an Afrikaans user too
    assertThat(c.get("/orders").header("Accept-Language", "af").header("Cookie", "fw_flash=gone").send().body()).contains("Removed.");
    // the registered message when no catalog has flash.<key>
    assertThat(client(false).get("/orders").header("Cookie", "fw_flash=gone").send().body()).contains("Deleted.");
  }

  @Test
  void aDatastarPatchIsRenderedInTheResolvedLanguage() {
    TestResponse r = client(true).get("/orders/stream").header("Datastar-Request", "true").header("Accept-Language", "af").send();
    assertThat(r.body()).contains("Maak kennisgewing toe");
  }
}
