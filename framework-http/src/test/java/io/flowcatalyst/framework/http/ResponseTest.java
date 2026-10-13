package io.flowcatalyst.framework.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponseTest {

  record Dto(String a, int b) {}

  @Test
  void jsonTakesRecordsMapsAndCollections() {
    assertThat(new String(((Response.Body.Bytes) Response.json(201, new Dto("x", 1)).body()).content()))
        .isEqualTo("{\"a\":\"x\",\"b\":1}");
    assertThat(Response.ok(Map.of("k", 1)).status()).isEqualTo(200);
    assertThat(Response.ok(List.of(new Dto("x", 1))).contentType()).isEqualTo("application/json");
  }

  @Test
  void jsonRejectsOtherTypes() {
    assertThatIllegalArgumentException().isThrownBy(() -> Response.json(200, "a string"));
    assertThatIllegalArgumentException().isThrownBy(() -> Response.json(200, new Object()));
    assertThatIllegalArgumentException().isThrownBy(() -> Response.json(200, 5));
  }

  @Test
  void datesAreIso() {
    Response r = Response.json(200, Map.of("d", java.time.LocalDate.of(2030, 5, 1)));
    assertThat(new String(((Response.Body.Bytes) r.body()).content())).isEqualTo("{\"d\":\"2030-05-01\"}");
  }

  @Test
  void headersWithCrOrLfFailAtConstruction() {
    Response r = Response.noContent();
    assertThatIllegalArgumentException().isThrownBy(() -> r.header("X-A", "v\r\nSet-Cookie: x=y"));
    assertThatIllegalArgumentException().isThrownBy(() -> r.header("X-A", "v\nx"));
    assertThatIllegalArgumentException().isThrownBy(() -> r.header("X-A\r\nB", "v"));
    assertThatIllegalArgumentException().isThrownBy(() -> r.addHeader("X-A", "a\rb"));
    assertThatIllegalArgumentException().isThrownBy(() -> r.header("", "v"));
    assertThatIllegalArgumentException().isThrownBy(() -> r.header("Bad Name", "v"));
    assertThatIllegalArgumentException().isThrownBy(() -> Response.bytes(200, "text/plain\r\nX: y", new byte[0]));
  }

  @Test
  void headerReplacesAndAddHeaderAppends() {
    Response r = Response.noContent().header("X-A", "1").header("x-a", "2").addHeader("Set-Cookie", "a").addHeader("Set-Cookie", "b");
    assertThat(r.headerValues("X-A")).containsExactly("2");
    assertThat(r.headerValues("set-cookie")).containsExactly("a", "b");
  }

  @Test
  void responsesAreImmutable() {
    Response base = Response.noContent();
    base.header("X-A", "1");
    assertThat(base.header("X-A")).isNull();
  }

  @Test
  void invalidStatusIsRejected() {
    assertThatIllegalArgumentException().isThrownBy(() -> Response.text(99, "x"));
    assertThatIllegalArgumentException().isThrownBy(() -> Response.text(600, "x"));
  }

  @Test
  void aStreamedBodyIsWrittenByTheCallback() {
    Routes routes = new Routes();
    routes.get(
        "/s",
        r ->
            Response.stream(
                200,
                "text/plain",
                out -> {
                  out.write("one\n".getBytes());
                  out.flush();
                  out.write("two\n".getBytes());
                }));
    TestResponse r = TestClient.of(routes).get("/s").send();
    assertThat(r.body()).isEqualTo("one\ntwo\n");
    assertThat(TestClient.of(routesCopy()).request(Method.HEAD, "/s").send().bytes()).isEmpty();
  }

  private static Routes routesCopy() {
    Routes routes = new Routes();
    routes.get("/s", r -> Response.stream(200, "text/plain", out -> out.write(1)));
    return routes;
  }

  @Test
  void aClientDisconnectDuringTheStreamEndsItQuietly() throws IOException {
    Response r =
        Response.stream(
            200,
            "text/plain",
            out -> {
              throw new ClientDisconnectedException(new IOException("broken pipe"));
            });
    assertThat(r.writeBody(new ByteArrayOutputStream())).isFalse();
  }

  @Test
  void aClientDisconnectThrownByAHandlerIsNotAnError() {
    Routes routes = new Routes();
    routes.get(
        "/d",
        r -> {
          throw new ClientDisconnectedException(new IOException("gone"));
        });
    TestResponse r = TestClient.of(routes).get("/d").send();
    assertThat(r.status()).isNotEqualTo(500);
    assertThat(r.bytes()).isEmpty();
  }

  @Test
  void otherStreamFailuresPropagateToTheServer() {
    Response r =
        Response.stream(
            200,
            "text/plain",
            out -> {
              throw new IOException("disk");
            });
    org.assertj.core.api.Assertions.assertThatIOException()
        .isThrownBy(() -> r.writeBody(new ByteArrayOutputStream()));
  }

  @Test
  void theBodyCanBeReadOnceAsAStreamAndBytesAreCached() throws IOException {
    java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream("hello".getBytes());
    Request req =
        Request.of(new RawRequest(Method.POST, "/", null, Map.of(), in, 5, 100, "HTTP/1.1"));
    assertThat(req.bodyBytes()).isEqualTo("hello".getBytes());
    assertThat(req.bodyBytes()).isSameAs(req.bodyBytes());
    assertThat(req.body().readAllBytes()).isEqualTo("hello".getBytes());

    Request req2 =
        Request.of(new RawRequest(Method.POST, "/", null, Map.of(), new java.io.ByteArrayInputStream("x".getBytes()), 1, 100, "HTTP/1.1"));
    req2.body();
    org.assertj.core.api.Assertions.assertThatIllegalStateException().isThrownBy(req2::body);
    org.assertj.core.api.Assertions.assertThatIllegalStateException().isThrownBy(req2::bodyBytes);
  }

  @Test
  void theLimitAppliesWhileReadingEvenWithoutAContentLength() {
    Request req =
        Request.of(new RawRequest(Method.POST, "/", null, Map.of(), new java.io.ByteArrayInputStream(new byte[50]), -1, 10, "HTTP/1.1"));
    org.assertj.core.api.Assertions.assertThatThrownBy(req::bodyBytes)
        .isInstanceOfSatisfying(HttpException.class, e -> assertThat(e.status()).isEqualTo(413));
  }

  @Test
  void aRedirectIsA302WithALocationAndNoBody() {
    Response r = Response.redirect("/auth/login?returnTo=%2Fx");
    assertThat(r.status()).isEqualTo(302);
    assertThat(r.header("Location")).isEqualTo("/auth/login?returnTo=%2Fx");
    assertThat(r.body()).isInstanceOf(Response.Body.Empty.class);
    assertThat(r.contentType()).isNull();
    assertThatIllegalArgumentException().isThrownBy(() -> Response.redirect("/x\r\nSet-Cookie: a=b"));
  }

  @Test
  void theRawQueryIsTheQueryAsSent() {
    Request with =
        Request.of(new RawRequest(Method.GET, "/a", "b=%2F&c", Map.of(), new java.io.ByteArrayInputStream(new byte[0]), 0, 10, "HTTP/1.1"));
    Request without =
        Request.of(new RawRequest(Method.GET, "/a", null, Map.of(), new java.io.ByteArrayInputStream(new byte[0]), 0, 10, "HTTP/1.1"));
    assertThat(with.rawQuery()).isEqualTo("b=%2F&c");
    assertThat(without.rawQuery()).isNull();
  }
}
