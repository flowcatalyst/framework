package io.flowcatalyst.framework.http.datastar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.flowcatalyst.framework.http.ClientDisconnectedException;
import io.flowcatalyst.framework.http.Method;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DatastarTest {

  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final ServerSentEventGenerator sse = new ServerSentEventGenerator(bytes);

  private String wire() {
    return bytes.toString(StandardCharsets.UTF_8);
  }

  // ---- patch elements -----------------------------------------------------------------------

  @Test
  void patchElementsDefault() {
    sse.patchElements("<div id=\"a\">hi</div>");
    assertThat(wire()).isEqualTo("event: datastar-patch-elements\ndata: elements <div id=\"a\">hi</div>\n\n");
  }

  @Test
  void multiLineHtmlGetsOneDataLinePerLine() {
    sse.patchElements("<div id=\"feed\">\n    <span>1</span>\r\n</div>");
    assertThat(wire())
        .isEqualTo(
            "event: datastar-patch-elements\n"
                + "data: elements <div id=\"feed\">\n"
                + "data: elements     <span>1</span>\n"
                + "data: elements </div>\n\n");
  }

  @Test
  void selectorIsWritten() {
    sse.patchElements("<p>x</p>", PatchElementsOptions.defaults().selector("#feed"));
    assertThat(wire()).isEqualTo("event: datastar-patch-elements\ndata: selector #feed\ndata: elements <p>x</p>\n\n");
  }

  @Test
  void everyNonDefaultModeIsWrittenAndOuterIsNot() {
    for (ElementPatchMode m : ElementPatchMode.values()) {
      if (m == ElementPatchMode.REMOVE) {
        continue;
      }
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      new ServerSentEventGenerator(b).patchElements("<i id=\"x\"></i>", PatchElementsOptions.defaults().mode(m));
      String expected =
          m == ElementPatchMode.OUTER
              ? "event: datastar-patch-elements\ndata: elements <i id=\"x\"></i>\n\n"
              : "event: datastar-patch-elements\ndata: mode " + m.name().toLowerCase() + "\ndata: elements <i id=\"x\"></i>\n\n";
      assertThat(b.toString(StandardCharsets.UTF_8)).as(m.name()).isEqualTo(expected);
    }
  }

  @Test
  void viewTransitionOnlyWhenTrue() {
    sse.patchElements("<p id=\"a\"></p>", PatchElementsOptions.defaults().useViewTransition(true));
    assertThat(wire()).isEqualTo("event: datastar-patch-elements\ndata: useViewTransition true\ndata: elements <p id=\"a\"></p>\n\n");
    bytes.reset();
    sse.patchElements("<p id=\"a\"></p>", PatchElementsOptions.defaults().useViewTransition(false));
    assertThat(wire()).doesNotContain("useViewTransition");
  }

  @Test
  void namespaceOnlyWhenNotHtml() {
    sse.patchElements("<circle id=\"c\"/>", PatchElementsOptions.defaults().namespace(Namespace.SVG));
    assertThat(wire()).isEqualTo("event: datastar-patch-elements\ndata: namespace svg\ndata: elements <circle id=\"c\"/>\n\n");
    bytes.reset();
    sse.patchElements("<m id=\"c\"/>", PatchElementsOptions.defaults().namespace(Namespace.MATHML));
    assertThat(wire()).contains("data: namespace mathml\n");
    bytes.reset();
    sse.patchElements("<p id=\"c\"/>", PatchElementsOptions.defaults().namespace(Namespace.HTML));
    assertThat(wire()).doesNotContain("namespace");
  }

  @Test
  void eventIdAndRetryComeBeforeTheDataLines() {
    sse.patchElements("<p id=\"a\"></p>", PatchElementsOptions.defaults().eventId("evt-1").retry(2500));
    assertThat(wire()).isEqualTo("event: datastar-patch-elements\nid: evt-1\nretry: 2500\ndata: elements <p id=\"a\"></p>\n\n");
  }

  @Test
  void retryOfTheDefaultIsOmitted() {
    sse.patchElements("<p id=\"a\"></p>", PatchElementsOptions.defaults().retry(1000));
    assertThat(wire()).doesNotContain("retry");
  }

  @Test
  void allOptionsTogetherInSpecOrder() {
    sse.patchElements(
        "<g id=\"a\"/>",
        PatchElementsOptions.defaults()
            .selector("#t")
            .mode(ElementPatchMode.INNER)
            .useViewTransition(true)
            .namespace(Namespace.SVG)
            .eventId("7")
            .retry(10));
    assertThat(wire())
        .isEqualTo(
            "event: datastar-patch-elements\nid: 7\nretry: 10\ndata: selector #t\ndata: mode inner\n"
                + "data: useViewTransition true\ndata: namespace svg\ndata: elements <g id=\"a\"/>\n\n");
  }

  @Test
  void removeIsAPatchWithModeRemoveAndNoElements() {
    sse.remove("#gone");
    assertThat(wire()).isEqualTo("event: datastar-patch-elements\ndata: selector #gone\ndata: mode remove\n\n");
  }

  @Test
  void removeByElementIds() {
    sse.patchElements("<div id=\"x\"></div>", PatchElementsOptions.defaults().mode(ElementPatchMode.REMOVE));
    assertThat(wire()).isEqualTo("event: datastar-patch-elements\ndata: mode remove\ndata: elements <div id=\"x\"></div>\n\n");
  }

  @Test
  void utf8IsWrittenAsUtf8() {
    sse.patchElements("<p id=\"a\">héllo ☃</p>");
    assertThat(bytes.toByteArray()).isEqualTo("event: datastar-patch-elements\ndata: elements <p id=\"a\">héllo ☃</p>\n\n".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void badInputsAreRejected() {
    assertThatIllegalArgumentException().isThrownBy(() -> sse.patchElements("  "));
    assertThatIllegalArgumentException().isThrownBy(() -> sse.remove(""));
    assertThatIllegalArgumentException().isThrownBy(() -> sse.patchElements("<p id=\"a\"/>", PatchElementsOptions.defaults().selector("a\nb")));
    assertThatIllegalArgumentException().isThrownBy(() -> sse.patchElements("<p id=\"a\"/>", PatchElementsOptions.defaults().eventId("a\r\nevent: x")));
    assertThatIllegalArgumentException().isThrownBy(() -> sse.patchElements("<p id=\"a\"/>", PatchElementsOptions.defaults().retry(-1)));
    assertThat(wire()).isEmpty();
  }

  // ---- patch signals ------------------------------------------------------------------------

  @Test
  void patchSignalsDefault() {
    sse.patchSignals("{\"output\":\"x\",\"show\":true}");
    assertThat(wire()).isEqualTo("event: datastar-patch-signals\ndata: signals {\"output\":\"x\",\"show\":true}\n\n");
  }

  @Test
  void patchSignalsOptions() {
    sse.patchSignals("{\"a\":1}", PatchSignalsOptions.defaults().onlyIfMissing(true).eventId("123").retry(2000));
    assertThat(wire())
        .isEqualTo("event: datastar-patch-signals\nid: 123\nretry: 2000\ndata: onlyIfMissing true\ndata: signals {\"a\":1}\n\n");
    bytes.reset();
    sse.patchSignals("{\"a\":1}", PatchSignalsOptions.defaults().onlyIfMissing(false));
    assertThat(wire()).doesNotContain("onlyIfMissing");
  }

  @Test
  void multiLineSignalsAreSplit() {
    sse.patchSignals("{\n  \"a\": null\n}");
    assertThat(wire()).isEqualTo("event: datastar-patch-signals\ndata: signals {\ndata: signals   \"a\": null\ndata: signals }\n\n");
  }

  @Test
  void invalidSignalsJsonIsRejected() {
    assertThatIllegalArgumentException().isThrownBy(() -> sse.patchSignals("{nope"));
    assertThat(wire()).isEmpty();
  }

  // ---- stream behaviour ---------------------------------------------------------------------

  @Test
  void eachEventIsFlushed() {
    AtomicInteger flushes = new AtomicInteger();
    OutputStream counting =
        new ByteArrayOutputStream() {
          @Override
          public void flush() {
            flushes.incrementAndGet();
          }
        };
    ServerSentEventGenerator g = new ServerSentEventGenerator(counting);
    g.patchElements("<p id=\"a\"/>");
    g.patchSignals("{}");
    g.remove("#x");
    assertThat(flushes).hasValue(3);
  }

  @Test
  void aWriteFailureIsAClientDisconnect() {
    OutputStream broken =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("broken pipe");
          }
        };
    ServerSentEventGenerator g = new ServerSentEventGenerator(broken);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> g.patchSignals("{}"))
        .isInstanceOf(ClientDisconnectedException.class)
        .hasCauseInstanceOf(IOException.class);
  }

  @Test
  void writingAfterCloseFails() {
    sse.close();
    assertThatIllegalStateException().isThrownBy(() -> sse.patchSignals("{}"));
  }

  // ---- over the router ----------------------------------------------------------------------

  record Sig(String name, int n) {}

  private static TestClient client() {
    Routes routes = new Routes();
    routes.get("/stream", r -> Datastar.stream(r, g -> {
      g.patchElements("<p id=\"a\">1</p>");
      g.patchSignals("{\"n\":2}");
    }));
    routes.add(Method.POST, "/stream", r -> Datastar.stream(r, g -> g.patchSignals("{\"echo\":" + Datastar.signals(r).get("name").toString() + "}")));
    routes.get("/sig", r -> Response.ok(Datastar.signals(r)));
    routes.get("/sig-typed", r -> Response.ok(Datastar.signals(r, Sig.class)));
    routes.post("/sig-typed", r -> Response.ok(Datastar.signals(r, Sig.class)));
    routes.get("/gone", r -> Datastar.stream(r, g -> {
      throw new ClientDisconnectedException(new IOException("closed"));
    }));
    return TestClient.of(routes);
  }

  @Test
  void streamResponseHeadersAndBody() {
    TestResponse r = client().get("/stream").send();
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.header("Content-Type")).isEqualTo("text/event-stream");
    assertThat(r.header("Cache-Control")).isEqualTo("no-cache");
    assertThat(r.header("Connection")).isEqualTo("keep-alive");
    assertThat(r.body())
        .isEqualTo(
            "event: datastar-patch-elements\ndata: elements <p id=\"a\">1</p>\n\n"
                + "event: datastar-patch-signals\ndata: signals {\"n\":2}\n\n");
  }

  @Test
  void keepAliveIsOnlyForHttp11() {
    TestResponse r = client().get("/stream").protocol("HTTP/1.0").send();
    assertThat(r.header("Connection")).isNull();
    assertThat(r.header("Content-Type")).isEqualTo("text/event-stream");
  }

  @Test
  void signalsFromTheQueryOnGet() {
    TestResponse r = client().get("/sig?datastar=%7B%22name%22%3A%22ada%22%2C%22n%22%3A3%7D").send();
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().get("name").asString()).isEqualTo("ada");
    TestResponse typed = client().get("/sig-typed?datastar=%7B%22name%22%3A%22ada%22%2C%22n%22%3A3%7D").send();
    assertThat(typed.json(Sig.class)).isEqualTo(new Sig("ada", 3));
  }

  @Test
  void signalsFromTheBodyOnPost() {
    TestResponse r = client().post("/stream").jsonBody("{\"name\":\"bob\"}").send();
    assertThat(r.body()).isEqualTo("event: datastar-patch-signals\ndata: signals {\"echo\":\"bob\"}\n\n");
    TestResponse typed = client().post("/sig-typed").jsonBody("{\"name\":\"bob\",\"n\":4}").send();
    assertThat(typed.json(Sig.class)).isEqualTo(new Sig("bob", 4));
  }

  @Test
  void missingOrInvalidSignalsAre400() {
    TestClient c = client();
    assertThat(c.get("/sig").send().status()).isEqualTo(400);
    assertThat(c.get("/sig?datastar=%7Bnope").send().status()).isEqualTo(400);
    assertThat(c.get("/sig-typed?datastar=%7B%22name%22%3A1%2C%22n%22%3A%22x%22%7D").send().status()).isEqualTo(400);
    assertThat(c.post("/sig-typed").jsonBody("not json").send().status()).isEqualTo(400);
    assertThat(c.post("/sig-typed").jsonBody("").send().status()).isEqualTo(400);
    assertThat(c.get("/sig?datastar=null").send().status()).isEqualTo(400);
  }

  @Test
  void aDisconnectInsideTheStreamIsNotAnError() {
    TestResponse r = client().get("/gone").send();
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.bytes()).isEmpty();
  }
}
