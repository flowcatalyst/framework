package io.flowcatalyst.framework.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouterTest {

  private static Handler says(String text) {
    return r -> Response.text(200, text);
  }

  @Test
  void literalBeatsParameterAtTheSamePosition() {
    Routes routes = new Routes();
    routes.get("/orders/{id}", says("param"));
    routes.get("/orders/new", says("literal"));
    TestClient c = TestClient.of(routes);
    assertThat(c.get("/orders/new").send().body()).isEqualTo("literal");
    assertThat(c.get("/orders/7").send().body()).isEqualTo("param");
  }

  @Test
  void theFirstDifferingPositionDecides() {
    Routes routes = new Routes();
    routes.get("/{a}/x", says("param-first"));
    routes.get("/a/{b}", says("literal-first"));
    TestClient c = TestClient.of(routes);
    assertThat(c.get("/a/x").send().body()).isEqualTo("literal-first");
    assertThat(c.get("/b/x").send().body()).isEqualTo("param-first");
  }

  @Test
  void aLiteralBranchThatDoesNotFitFallsBackToTheParameterBranch() {
    Routes routes = new Routes();
    routes.get("/orders/new", says("literal"));
    routes.get("/orders/{id}/lines", says("lines"));
    TestClient c = TestClient.of(routes);
    assertThat(c.get("/orders/new/lines").send().body()).isEqualTo("lines");
  }

  @Test
  void parametersAreDecodedPerSegmentAfterSplitting() {
    Routes routes = new Routes();
    routes.get("/files/{name}", r -> Response.text(200, r.pathParam("name")));
    TestClient c = TestClient.of(routes);
    assertThat(c.get("/files/a%20b").send().body()).isEqualTo("a b");
    assertThat(c.get("/files/a%2Fb").send().body()).isEqualTo("a/b");
    assertThat(c.get("/files/a+b").send().body()).isEqualTo("a+b");
    assertThat(c.get("/files/%C3%A9").send().body()).isEqualTo("é");
  }

  @Test
  void aMalformedEscapeIsAValidationError() {
    Routes routes = new Routes();
    routes.get("/files/{name}", says("x"));
    TestClient c = TestClient.of(routes);
    assertThat(c.get("/files/%zz").send().status()).isEqualTo(400);
    assertThat(c.get("/files/%2").send().status()).isEqualTo(400);
    assertThat(c.get("/files/%ff").send().status()).isEqualTo(400);
  }

  @Test
  void aParameterMatchesOneNonEmptySegment() {
    Routes routes = new Routes();
    routes.get("/orders/{id}", says("x"));
    TestClient c = TestClient.of(routes);
    assertThat(c.get("/orders/").send().status()).isEqualTo(404);
    assertThat(c.get("/orders/1/2").send().status()).isEqualTo(404);
    assertThat(c.get("/orders").send().status()).isEqualTo(404);
  }

  @Test
  void aTrailingSlashIsADifferentPath() {
    Routes routes = new Routes();
    routes.get("/a", says("no-slash"));
    routes.get("/b/", says("slash"));
    TestClient c = TestClient.of(routes);
    assertThat(c.get("/a").send().body()).isEqualTo("no-slash");
    assertThat(c.get("/a/").send().status()).isEqualTo(404);
    assertThat(c.get("/b/").send().body()).isEqualTo("slash");
    assertThat(c.get("/b").send().status()).isEqualTo(404);
  }

  @Test
  void theRootPathWorks() {
    Routes routes = new Routes();
    routes.get("/", says("root"));
    assertThat(TestClient.of(routes).get("/").send().body()).isEqualTo("root");
  }

  @Test
  void pathParamOfAnUndeclaredNameIsAnError() {
    Routes routes = new Routes();
    routes.get("/o/{id}", r -> Response.text(200, r.pathParam("other")));
    TestClient.Call call = TestClient.of(routes).get("/o/1");
    // the handler's IllegalArgumentException is a programming error: 500
    assertThat(call.send().status()).isEqualTo(500);
  }

  @Test
  void duplicateRoutesFailStartUpNamingBothRegistrants() {
    Routes routes = new Routes();
    routes.forModule("orders").get("/a/{x}", says("1"));
    routes.forModule("billing").get("/a/{y}", says("2"));
    routes.forModule("billing").post("/a/{y}", says("3")); // different method: fine
    Throwable t = catchThrowable(() -> TestClient.of(routes));
    assertThat(t).isInstanceOf(StartupException.class);
    List<Problem> problems = ((StartupException) t).problems();
    assertThat(problems).hasSize(1);
    assertThat(problems.getFirst().module()).isEqualTo("billing");
    assertThat(problems.getFirst().message())
        .contains("GET /a/{y}", "GET /a/{x}", "'billing'", "'orders'");
  }

  @Test
  void everyDuplicateIsReportedAtOnce() {
    Routes routes = new Routes();
    routes.get("/a", says("1"));
    routes.get("/a", says("2"));
    routes.get("/b/{id}", says("1"));
    routes.get("/b/{other}", says("2"));
    StartupException e = (StartupException) catchThrowable(() -> TestClient.of(routes));
    assertThat(e.problems()).hasSize(2);
  }

  @Test
  void unknownPathIs404AndKnownPathWrongMethodIs405WithAllow() {
    Routes routes = new Routes();
    routes.get("/orders", says("list"));
    routes.post("/orders", says("create"));
    routes.delete("/orders/{id}", says("delete"));
    TestClient c = TestClient.of(routes);

    assertThat(c.get("/nope").send().status()).isEqualTo(404);

    TestResponse put = c.put("/orders").send();
    assertThat(put.status()).isEqualTo(405);
    assertThat(put.header("Allow")).isEqualTo("GET, HEAD, OPTIONS, POST");
    assertThat(put.tree().get("error").asString()).isEqualTo("METHOD_NOT_ALLOWED");

    TestResponse get = c.get("/orders/5").send();
    assertThat(get.status()).isEqualTo(405);
    assertThat(get.header("Allow")).isEqualTo("DELETE, OPTIONS");
  }

  @Test
  void headIsAnsweredFromGetWithTheBodyDropped() {
    Routes routes = new Routes();
    routes.get("/x", r -> Response.text(200, "hello").header("X-Seen", "yes"));
    TestResponse r = TestClient.of(routes).request(Method.HEAD, "/x").send();
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.header("X-Seen")).isEqualTo("yes");
    assertThat(r.bytes()).isEmpty();
  }

  @Test
  void aHeadRouteWinsOverGet() {
    Routes routes = new Routes();
    routes.get("/x", says("get"));
    routes.add(Method.HEAD, "/x", r -> Response.noContent().header("X-From", "head"));
    TestResponse r = TestClient.of(routes).request(Method.HEAD, "/x").send();
    assertThat(r.status()).isEqualTo(204);
    assertThat(r.header("X-From")).isEqualTo("head");
  }

  @Test
  void headOnAPathWithoutGetIs405() {
    Routes routes = new Routes();
    routes.post("/x", says("post"));
    TestResponse r = TestClient.of(routes).request(Method.HEAD, "/x").send();
    assertThat(r.status()).isEqualTo(405);
    assertThat(r.header("Allow")).isEqualTo("OPTIONS, POST");
  }

  @Test
  void optionsOnAKnownPathIs204WithAllow() {
    Routes routes = new Routes();
    routes.get("/x", says("get"));
    routes.put("/x", says("put"));
    TestResponse r = TestClient.of(routes).request(Method.OPTIONS, "/x").send();
    assertThat(r.status()).isEqualTo(204);
    assertThat(r.header("Allow")).isEqualTo("GET, HEAD, OPTIONS, PUT");
    assertThat(r.bytes()).isEmpty();
  }

  @Test
  void optionsOnAnUnknownPathIs404() {
    Routes routes = new Routes();
    routes.get("/x", says("get"));
    assertThat(TestClient.of(routes).request(Method.OPTIONS, "/y").send().status()).isEqualTo(404);
  }

  @Test
  void anExplicitOptionsRouteIsUsed() {
    Routes routes = new Routes();
    routes.add(Method.OPTIONS, "/x", r -> Response.text(200, "custom"));
    assertThat(TestClient.of(routes).request(Method.OPTIONS, "/x").send().body()).isEqualTo("custom");
  }

  @Test
  void noCorsHeadersAreAdded() {
    Routes routes = new Routes();
    routes.get("/x", says("x"));
    TestResponse r = TestClient.of(routes).get("/x").header("Origin", "https://example.com").send();
    assertThat(r.header("Access-Control-Allow-Origin")).isNull();
  }

  @Test
  void registrationAfterCompileFails() {
    Routes routes = new Routes();
    routes.get("/x", says("x"));
    TestClient.of(routes);
    assertThatIllegalStateException().isThrownBy(() -> routes.get("/y", says("y")));
    assertThatIllegalStateException().isThrownBy(() -> routes.use((r, n) -> n.handle(r)));
  }

  @Test
  void malformedPatternsAreRejectedAtRegistration() {
    Routes routes = new Routes();
    assertThatIllegalArgumentException().isThrownBy(() -> routes.get("x", says("")));
    assertThatIllegalArgumentException().isThrownBy(() -> routes.get("/a//b", says("")));
    assertThatIllegalArgumentException().isThrownBy(() -> routes.get("/a/{Id}", says("")));
    assertThatIllegalArgumentException().isThrownBy(() -> routes.get("/a/x{id}", says("")));
    assertThatIllegalArgumentException().isThrownBy(() -> routes.get("/a/{id}/{id}", says("")));
  }

  @Test
  void wildcardsAreLiterals() {
    Routes routes = new Routes();
    routes.get("/a/*", says("star"));
    TestClient c = TestClient.of(routes);
    assertThat(c.get("/a/*").send().body()).isEqualTo("star");
    assertThat(c.get("/a/b").send().status()).isEqualTo(404);
  }

  @Test
  void routesAreListedForTheStartUpLog() {
    Routes routes = new Routes();
    routes.forModule("orders").get("/orders/{id}", says("x"));
    Dispatcher d = routes.compile(null, () -> 0L);
    assertThat(d.describeRoutes()).containsExactly("GET /orders/{id} (orders)");
  }
}
