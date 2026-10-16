package io.flowcatalyst.framework.examples.ordersjte;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.ui.UiAssets;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.InstantSource;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrdersUiTest {

  private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
  private static final String XSS = "<script>alert(1)</script><img src=x onerror=alert(2)>";

  private OrderStore store;
  private TestClient client;

  @BeforeEach
  void setUp() {
    store = OrderStore.seeded(NOW);
    UiAssets assets = UiAssets.load();
    Routes routes = new Routes("test");
    new OrdersUi(OrdersUiModule.views(assets), store, InstantSource.fixed(NOW)).register(routes.forModule("orders-ui"));
    assets.register(routes.forModule("ui"));
    client = TestClient.of(routes);
  }

  private static Document doc(TestResponse r) {
    return Jsoup.parse(r.body());
  }

  private static String form(String... pairs) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < pairs.length; i += 2) {
      if (i > 0) {
        b.append('&');
      }
      b.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
    }
    return b.toString();
  }

  private TestResponse post(String path, String body, boolean datastar) {
    var req = client.post(path).header("Content-Type", "application/x-www-form-urlencoded");
    if (datastar) {
      req = req.header("Datastar-Request", "true");
    }
    return req.body(body).send();
  }

  @Test
  void theListIsAFullPageWithToolbarTableAndPager() {
    TestResponse r = client.get("/orders").send();
    assertThat(r.status()).isEqualTo(200);
    Document d = doc(r);
    assertThat(d.title()).isEqualTo("Orders - Orders demo");
    assertThat(d.select("h1.fc-page-title").text()).isEqualTo("Orders");
    assertThat(d.select("form#orders-list input[type=search][name=q]")).hasSize(1);
    assertThat(d.select("tbody#orders-rows tr")).hasSize(10);
    assertThat(d.select("th[aria-sort=descending]").text()).contains("Placed");
    assertThat(d.select("nav.fc-paginator .fc-page-report").text()).isEqualTo("Showing 1 to 10 of 23 orders");
    assertThat(d.select("aside#fc-sidebar")).hasSize(1);
    assertThat(d.select("dialog#new-order")).hasSize(1);
    assertThat(d.select("dialog#cancel-order")).hasSize(1);
    assertThat(d.select("aside#order-drawer[role=complementary]")).hasSize(1);
  }

  @Test
  void searchStatusSortAndPagingComeFromTheQueryAndSurviveInEveryLink() {
    Document d = doc(client.get("/orders?q=a&status=PAID&sort=total&dir=asc&rows=5").send());
    assertThat(d.select("tbody#orders-rows tr td:first-child a")).isNotEmpty().allSatisfy(a -> assertThat(a.text()).containsIgnoringCase("a"));
    assertThat(d.select("tbody#orders-rows tr td:nth-child(2)")).allSatisfy(c -> assertThat(c.text()).isEqualTo("Paid"));
    assertThat(d.select("th[aria-sort=ascending]").text()).contains("Total");
    assertThat(d.select("select#filter-status option[selected]").attr("value")).isEqualTo("PAID");
    assertThat(d.select("input[name=q]").attr("value")).isEqualTo("a");
    String sortLink = d.select("th a").first().attr("href");
    assertThat(sortLink).contains("q=a").contains("status=PAID").contains("rows=5");
    assertThat(d.select("a.fc-btn-text").text()).contains("Clear All");

    Document page2 = doc(client.get("/orders?rows=5&page=2").send());
    assertThat(page2.select("nav.fc-paginator [aria-current=page]").text()).isEqualTo("2");
    assertThat(page2.select("tbody#orders-rows tr")).hasSize(5);
    assertThat(doc(client.get("/orders?page=999&rows=5").send()).select("nav.fc-paginator [aria-current=page]").text()).isEqualTo("5");
  }

  @Test
  void anEmptyResultShowsTheEmptyStateInsideTheTable() {
    Document d = doc(client.get("/orders?q=nobody-at-all").send());
    assertThat(d.select("tbody#orders-rows .fc-empty").text()).contains("No orders match.");
    assertThat(d.select("tbody#orders-rows .fc-empty a").attr("href")).isEqualTo("/orders");
  }

  @Test
  void unknownQueryValuesFallBackToDefaultsAndNeverBreakThePage() {
    TestResponse r = client.get("/orders?sort=%27%3B+DROP&dir=x&status=<b>&rows=7&page=abc").send();
    assertThat(r.status()).isEqualTo(200);
    assertThat(doc(r).select("tbody#orders-rows tr")).hasSize(10);
  }

  @Test
  void theDrawerIsLoadedByTheServerAsAPatchAndAsAFullPageWithoutDatastar() {
    String sse = client.get("/orders/ord-0001").header("Datastar-Request", "true").send().body();
    assertThat(sse).contains("event: datastar-patch-signals").contains("selOrder").contains("ord-0001");
    assertThat(sse).contains("data: selector #order-drawer").contains("data: mode inner").contains("fc-drawer-title");
    assertThat(sse).contains("Ada Lovelace");

    TestResponse full = client.get("/orders/ord-0001").send();
    assertThat(doc(full).select("h1.fc-page-title").text()).isEqualTo("Ada Lovelace");
    assertThat(doc(full).select("nav[aria-label=breadcrumb] [aria-current=page]").text()).isEqualTo("ord-0001");

    assertThat(client.get("/orders/ord-9999").send().status()).isEqualTo(404);
    String missing = client.get("/orders/ord-9999").header("Datastar-Request", "true").send().body();
    assertThat(missing).contains("fc-flash-error");
  }

  @Test
  void aValidationFailureAnswersWithTheFormAndItsErrorsNotAnErrorStatus() {
    String sse = post("/orders", form("customer", "A", "amount", "-3", "status", "PENDING"), true).body();
    assertThat(sse).contains("data: elements <dialog id=\"new-order\"");
    Document d = Jsoup.parseBodyFragment(sse.lines().filter(l -> l.startsWith("data: elements ")).map(l -> l.substring(15)).reduce("", String::concat));
    assertThat(d.select("#new-order-customer[aria-invalid=true][aria-describedby=new-order-customer-err]")).hasSize(1);
    assertThat(d.select("#new-order-customer-err[role=alert]").text()).contains("at least 2");
    assertThat(d.select("#new-order-amount-err").text()).contains("greater than zero");
    assertThat(d.select("dialog#new-order[open]")).hasSize(1);
    assertThat(d.select("#new-order-customer").attr("value")).isEqualTo("A");
    assertThat(store.list(null, null, "created", true, 1, 100).total()).isEqualTo(23);
  }

  @Test
  void aNativePostWithErrorsRendersThePageAgainWithStatus422() {
    TestResponse r = post("/orders", form("customer", "", "amount", "x", "status", "PENDING"), false);
    assertThat(r.status()).isEqualTo(422);
    Document d = doc(r);
    assertThat(d.select("dialog#new-order[open]")).hasSize(1);
    assertThat(d.select("#new-order-customer-err").text()).isNotEmpty();
    assertThat(d.select("h1.fc-page-title").text()).isEqualTo("Orders");
  }

  @Test
  void aCreateThroughDatastarReplacesTheDialogReloadsTheListAndFlashes() {
    String sse = post("/orders", form("customer", "Linus Torvalds", "amount", "1234.50", "status", "PAID"), true).body();
    assertThat(store.list("linus", null, "created", true, 1, 5).total()).isEqualTo(1);
    assertThat(sse).contains("data: elements <div id=\"orders-region\">").contains("Linus Torvalds").contains("1,234.50");
    // the dialog is replaced (leaves the top layer), never morphed
    assertThat(sse).contains("data: selector #new-order").contains("data: mode replace");
    assertThat(sse).contains("data: selector #flash-stack").contains("Order created.");
    assertThat(sse).contains("document.getElementById(&#39;new-order-open&#39;)?.focus()");
  }

  @Test
  void aNativeCreateRedirectsAndTheFlashTravelsAsAFixedKey() {
    TestResponse r = post("/orders", form("customer", "Linus Torvalds", "amount", "10", "status", "PENDING"), false);
    assertThat(r.status()).isEqualTo(303);
    assertThat(r.header("Location")).isEqualTo("/orders?flash=created");
    assertThat(doc(client.get("/orders?flash=created").send()).select("#flash-stack > .fc-flash .fc-flash-summary").text()).isEqualTo("Order created.");
    // text from a request is never a flash: an unknown key shows nothing
    assertThat(doc(client.get("/orders?flash=" + URLEncoder.encode(XSS, StandardCharsets.UTF_8)).send()).select("#flash-stack > .fc-flash")).isEmpty();
  }

  @Test
  void cancelAsksForConfirmationAndPatchesTheRowOnlyWhenAllowed() {
    Document list = doc(client.get("/orders").send());
    assertThat(list.select("#row-ord-0001-act-menu [data-action$='/orders/ord-0001/cancel']")).hasSize(1);
    TestResponse ok = post("/orders/ord-0001/cancel", "", true);
    assertThat(ok.body()).contains("data: elements <tr id=\"row-ord-0001\"").contains("Cancelled").contains("Order cancelled.");
    assertThat(store.find("ord-0001").orElseThrow().status()).isEqualTo("CANCELLED");
    String again = post("/orders/ord-0001/cancel", "", true).body();
    assertThat(again).contains("fc-flash-error").doesNotContain("<tr");
    assertThat(post("/orders/ord-0002/cancel", "", false).header("Location")).isEqualTo("/orders?flash=cancelled");
    assertThat(post("/orders/ord-0001/cancel", "", false).header("Location")).isEqualTo("/orders?flash=not-cancellable");
  }

  @Test
  void aCrossSitePostIsRefused() {
    var req = client.post("/orders").header("Content-Type", "application/x-www-form-urlencoded").header("Sec-Fetch-Site", "cross-site");
    assertThat(req.body(form("customer", "Mallory", "amount", "5", "status", "PAID")).send().status()).isEqualTo(403);
    var byOrigin = client.post("/orders").header("Content-Type", "application/x-www-form-urlencoded").header("Origin", "https://evil.example").header("Host", "app.example");
    assertThat(byOrigin.body(form("customer", "Mallory", "amount", "5", "status", "PAID")).send().status()).isEqualTo(403);
    assertThat(store.list("mallory", null, "created", true, 1, 5).total()).isZero();
    var same = client.post("/orders").header("Content-Type", "application/x-www-form-urlencoded").header("Sec-Fetch-Site", "same-origin");
    assertThat(same.body(form("customer", "Mallory", "amount", "5", "status", "PAID")).send().status()).isEqualTo(303);
  }

  @Test
  void aCustomerNameFromTheUserIsEscapedInTheListTheDrawerAndTheStream() {
    Order o = store.add(XSS, "PENDING", 500, NOW);
    Document list = doc(client.get("/orders?q=script").send());
    assertThat(list.select("tbody#orders-rows script, tbody#orders-rows img")).isEmpty();
    assertThat(list.select("tbody#orders-rows td:first-child a").text()).isEqualTo(XSS);
    assertThat(list.select("[data-action]").eachAttr("data-action")).allSatisfy(a -> assertThat(a).startsWith("/orders/ord-"));
    String drawer = client.get("/orders/" + o.id()).header("Datastar-Request", "true").send().body();
    assertThat(drawer).doesNotContain("<script>").doesNotContain("<img src=x");
    assertThat(doc(client.get("/orders/" + o.id()).send()).select("h1").text()).isEqualTo(XSS);
    String created = post("/orders", form("customer", XSS + " Inc", "amount", "9", "status", "PAID"), true).body();
    assertThat(created).doesNotContain("<script>").doesNotContain("<img src=x");
    TestResponse failed = post("/orders", form("customer", XSS, "amount", "0", "status", "PAID"), false);
    assertThat(doc(failed).select("script:not([src]), img")).isEmpty();
    assertThat(doc(failed).select("#new-order-customer").attr("value")).isEqualTo(XSS);
  }

  @Test
  void theRowLinksUseAPlainHrefWithTheIdInADataAttributeNotInTheExpression() {
    Document d = doc(client.get("/orders").send());
    var link = d.select("tbody#orders-rows td:first-child a").first();
    assertThat(link.attr("href")).isEqualTo("/orders/ord-0001");
    assertThat(link.attr("data-id")).isEqualTo("ord-0001");
    assertThat(link.attr("data-on:click__prevent")).isEqualTo(OrdersUi.OPEN_ORDER).doesNotContain("ord-0001");
  }
}
