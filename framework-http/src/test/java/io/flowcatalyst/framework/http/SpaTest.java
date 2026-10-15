package io.flowcatalyst.framework.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.MetricsSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Static assets and the SPA fallback (static-assets contract sections 4 to 7). Negative cases
 * first: what must never be served comes before what must be.
 */
class SpaTest {

  static final String BASELINE_CSP = "frame-ancestors 'none'; object-src 'none'; base-uri 'self'";

  /** The default policy since 2026-10-15 (contract section 7): strict, with no script hashes. */
  static final String STRICT_CSP =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self';"
          + " connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'";
  static final String IMMUTABLE = "public, max-age=31536000, immutable";

  @TempDir Path tmp;
  Path dist;

  @BeforeEach
  void build() {
    dist = SpaFixture.write(tmp);
  }

  /** The application's routes: a versioned API, a resource, a runtime endpoint, health. */
  static void appRoutes(Routes r) {
    r.get("/api/v1/orders", req -> Response.ok(Map.of("orders", List.of())));
    r.get("/orders/{id}", req -> Response.ok(Map.of("id", req.pathParam("id"))));
    r.post("/runtime/v1/poll", req -> Response.noContent());
    r.get("/health/ready", req -> Response.ok(Map.of("status", "UP")));
    r.get("/boom", req -> { throw new IllegalStateException("boom"); });
  }

  TestClient client() {
    return client(Spa.directory(dist).build(), SpaTest::appRoutes);
  }

  static TestClient client(Spa spa, Consumer<Routes> routes) {
    Routes r = new Routes("test");
    routes.accept(r.forModule("api"));
    r.forModule("web").spa(spa);
    return TestClient.of(r);
  }

  static boolean isIndex(TestResponse r) {
    return r.body().contains("<div id=\"app\">");
  }

  static void assertSpaHeaders(TestResponse r) {
    assertThat(r.header("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(r.header("Referrer-Policy")).isEqualTo("same-origin");
    assertThat(r.header("X-Frame-Options")).isEqualTo("DENY");
    assertThat(r.header("Content-Security-Policy")).isEqualTo(STRICT_CSP);
    assertThat(r.header("Cross-Origin-Opener-Policy")).isEqualTo("same-origin");
    assertThat(r.header("Permissions-Policy")).isEqualTo("camera=(), microphone=(), geolocation=()");
  }

  static void assertEnvelope404NoStore(TestResponse r, String what) {
    assertThat(r.status()).as(what).isEqualTo(404);
    assertThat(isIndex(r)).as(what + " must not be index.html").isFalse();
    assertThat(r.body()).as(what).doesNotContain(SpaFixture.SECRET);
    assertThat(r.header("Content-Type")).as(what).isEqualTo("application/json");
    assertThat(r.tree().get("error").asString()).as(what).isEqualTo("NOT_FOUND");
    assertThat(r.header("Cache-Control")).as(what).isEqualTo("no-store");
    assertThat(r.header("ETag")).as(what).isNull();
    assertSpaHeaders(r);
  }

  // ---- what must never be served ------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/nope",
        "/api",
        "/api/",
        "/api/v1/orders/7",
        "/api/v2",
        "/auth/login",
        "/auth/nope",
        "/auth",
        "/orders",
        "/orders/7/edit",
        "/health",
        "/health/live",
        "/runtime/v1/other",
        "/%61pi/x",
        "/api/x.js",
        "/api/..",
        "/api/%2e%2e/index.html"
      })
  void unknownApiPathsAreTheJsonEnvelopeNeverIndexForAnyMethod(String path) {
    TestClient client = client();
    for (Method m : List.of(Method.GET, Method.POST, Method.DELETE, Method.PUT, Method.OPTIONS)) {
      // none of these paths matches a route, so the API rule must come before the method rule
      TestResponse r = client.request(m, path).header("Accept", "text/html").send();
      assertEnvelope404NoStore(r, m + " " + path);
      assertThat(r.tree().get("message").asString()).isEqualTo("no route for this path");
    }
  }

  @Test
  void apiPrefixesAddedInCodeAreApiPaths() {
    TestClient client = client(Spa.directory(dist).apiPrefix("extra").apiPrefix("q").build(), SpaTest::appRoutes);
    assertEnvelope404NoStore(client.get("/extra/thing").send(), "/extra/thing");
    assertEnvelope404NoStore(client.get("/q").send(), "/q");
    assertThat(isIndex(client.get("/extras").send())).isTrue();
  }

  @Test
  void aClientRouteSharingAFirstSegmentWithAServerRouteIsAnApiPath() {
    // /orders/{id} is a server route, so /orders and /orders/7/edit can never be SPA pages
    TestClient client = client();
    assertEnvelope404NoStore(client.get("/orders").send(), "/orders");
    assertEnvelope404NoStore(client.get("/orders/7/edit").send(), "/orders/7/edit");
    assertThat(client.get("/orders/7").send().tree().get("id").asString()).isEqualTo("7");
  }

  @Test
  void aPathMatchingARouteWithAnotherMethodKeepsTheRoutersFourOhFive() {
    TestResponse r = client().post("/api/v1/orders").send();
    assertThat(r.status()).isEqualTo(405);
    assertThat(r.header("Allow")).isEqualTo("GET, HEAD, OPTIONS");
    assertThat(isIndex(r)).isFalse();
    TestResponse get = client().get("/runtime/v1/poll").send();
    assertThat(get.status()).isEqualTo(405);
    assertThat(get.header("Allow")).isEqualTo("OPTIONS, POST");
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
  void otherMethodsOnSpaPathsAreFourOhFiveWithAllowGetHead(String method) {
    TestClient client = client();
    for (String path : List.of("/dashboard", "/", "/index.html", "/assets/index-abc123.js", "/assets/missing.js")) {
      TestResponse r = client.request(Method.valueOf(method), path).send();
      assertThat(r.status()).as(method + " " + path).isEqualTo(405);
      assertThat(r.header("Allow")).as(method + " " + path).isEqualTo("GET, HEAD");
      assertThat(r.tree().get("error").asString()).isEqualTo("METHOD_NOT_ALLOWED");
      assertThat(r.header("Content-Type")).isEqualTo("application/json");
      assertThat(r.header("Cache-Control")).isEqualTo("no-store");
      assertThat(isIndex(r)).isFalse();
      assertSpaHeaders(r);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/assets/index-OLDHASH.js",
        "/assets/Page-OLD.css",
        "/assets",
        "/assets/",
        "/assets/no-extension",
        "/assets/nested",
        "/assets/nested/",
        "/favicon.ico",
        "/robots2.txt",
        "/some/deep/chunk-x.js",
        "/manifest2.webmanifest",
        "/logo.png",
        "/logo.PNG",
        "/Index.HTML",
        "/page.htm",
        "/x/y.JS",
        "/f.mjs",
        "/f.cjs",
        "/f.map",
        "/f.json",
        "/f.xml",
        "/f.wasm",
        "/f.jpg",
        "/f.jpeg",
        "/f.gif",
        "/f.webp",
        "/f.avif",
        "/f.bmp",
        "/f.woff",
        "/f.woff2",
        "/f.ttf",
        "/f.otf",
        "/f.eot",
        "/FAVICON.SVG"
      })
  void aMissingAssetIsAFourOhFourNoStoreNeverIndex(String path) {
    assertEnvelope404NoStore(client().get(path).send(), path);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/..",
        "/../secret.txt",
        "/../../secret.txt",
        "/assets/../index.html",
        "/assets/../../secret.txt",
        "/%2e%2e/secret.txt",
        "/%2E%2E/secret.txt",
        "/%2e%2e",
        "/assets/%2e%2e/%2e%2e/secret.txt",
        "/..%2fsecret.txt",
        "/..%2Fsecret.txt",
        "/assets%2findex-abc123.js",
        "/assets%2Findex-abc123.js",
        "/.",
        "/./index.html",
        "/%2e/index.html",
        "//index.html",
        "//",
        "/assets//index-abc123.js",
        "/dashboard/",
        "/dashboard//x",
        "/a\\b",
        "/..\\secret.txt",
        "/%5c..%5csecret.txt",
        "/x%5c..%5c..%5csecret.txt",
        "/%00",
        "/index.html%00",
        "/index.html%00.js",
        "/a%00b",
        "/a%0d%0aSet-Cookie:%20x=1",
        "/a%0aX-Injected:%201",
        "/a%09b",
        "/a%7fb",
        "/a%1bb",
        "/.vite/manifest.json",
        "/.vite",
        "/%2evite/manifest.json",
        "/.hidden",
        "/assets/.editor.js",
        "/.git/config",
        "/.env"
      })
  void anUncleanPathNeverLeavesTheIndexAndIsAFourOhFour(String path) {
    TestResponse r = client().get(path).send();
    assertEnvelope404NoStore(r, path);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/%252e%252e/secret.txt",
        "/%252e%252e/%252e%252e/secret.txt",
        "/assets/%252e%252e/secret.txt",
        "/%25252e%25252e/secret.txt",
        "/%252f..%252fsecret.txt",
        "/%255c..%255csecret.txt"
      })
  void aDoubleEncodedPathIsDecodedOnceAndNeverServesAFileOutsideTheIndex(String path) {
    TestResponse r = client().get(path).send();
    assertThat(r.body()).doesNotContain(SpaFixture.SECRET);
    // decoded once these are names with '%' in them: a missing .txt file or an asset path, so 404
    assertEnvelope404NoStore(r, path);
  }

  @Test
  void aDoubleEncodedClientRouteIsStillJustAClientRoute() {
    TestResponse r = client().get("/%252e%252e/x").send();
    assertThat(r.status()).isEqualTo(200);
    assertThat(isIndex(r)).isTrue();
  }

  @Test
  void aMalformedEscapeIsTheRoutersFourHundredBeforeTheSpa() {
    for (String path : List.of("/%zz", "/%", "/a%2", "/%c3%28")) {
      TestResponse r = client().get(path).send();
      assertThat(r.status()).as(path).isEqualTo(400);
      assertThat(r.tree().get("error").asString()).isEqualTo("VALIDATION");
      assertThat(isIndex(r)).isFalse();
    }
  }

  @Test
  void symbolicLinksAreNotFollowed() {
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.isSymbolicLink(dist.resolve("link.txt")), "no symbolic links here");
    TestClient client = client();
    for (String path : List.of("/link.txt", "/linkdir/x.js")) {
      TestResponse r = client.get(path).send();
      assertEnvelope404NoStore(r, path);
    }
  }

  @Test
  void dotFilesAreNotIndexedEvenWhenAskedForExactly() {
    TestClient client = client();
    assertEnvelope404NoStore(client.get("/.vite/manifest.json").send(), "vite manifest");
    assertEnvelope404NoStore(client.get("/.hidden").send(), ".hidden");
  }

  @Test
  void aPathIsNeverEchoedInHeadersOrBody() {
    String marker = "zz-marker-zz";
    for (String path :
        List.of(
            "/" + marker + "%0d%0aSet-Cookie:%20a=b",
            "/api/" + marker,
            "/assets/" + marker + ".js",
            "/" + marker + "/..",
            "/" + marker)) {
      TestResponse r = client().post(path).send();
      for (Response.Header h : r.headers()) {
        assertThat(h.value()).as(path).doesNotContain(marker).doesNotContain("\r").doesNotContain("\n");
      }
      assertThat(r.headerValues("Set-Cookie")).isEmpty();
      assertThat(r.body()).as(path).doesNotContain(marker);
      TestResponse g = client().get(path).send();
      for (Response.Header h : g.headers()) {
        assertThat(h.value()).as(path).doesNotContain(marker);
      }
      assertThat(g.body()).as(path).doesNotContain(marker);
    }
  }

  @Test
  void hugePathsAreAnsweredWithoutFailure() {
    TestClient client = client();
    TestResponse longRoute = client.get("/" + "a".repeat(200_000)).send();
    assertThat(longRoute.status()).isEqualTo(200);
    assertThat(isIndex(longRoute)).isTrue();
    TestResponse deep = client.get("/" + "a/".repeat(50_000) + "x.js").send();
    assertEnvelope404NoStore(deep, "deep asset");
    TestResponse climbing = client.get("/" + "../".repeat(50_000) + "secret.txt").send();
    assertEnvelope404NoStore(climbing, "climbing");
    TestResponse longExt = client.get("/x." + "j".repeat(100_000)).send();
    assertThat(longExt.status()).isEqualTo(200);
  }

  // ---- what must be served -------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/",
        "/index.html",
        "/dashboard",
        "/clients/clt_0ABC",
        "/ingestions/acme.orders",
        "/ingestions/acme.orders/runs",
        "/authorization/roles/orders:admin",
        "/apiary",
        "/authors",
        "/api-docs",
        "/healthz",
        "/ordersx",
        "/assetsx",
        "/x.unknownext",
        "/version.1"
      })
  void aClientRouteIsIndexHtmlNoCacheWithTheETag(String path) {
    TestResponse r = client().get(path).header("Accept", "application/json").send();
    assertThat(r.status()).as(path).isEqualTo(200);
    assertThat(r.bytes()).isEqualTo(SpaFixture.files().get("index.html"));
    assertThat(r.header("Content-Type")).isEqualTo("text/html; charset=utf-8");
    assertThat(r.header("Cache-Control")).isEqualTo("no-cache");
    assertThat(r.header("ETag")).isEqualTo(SpaFixture.etag("index.html"));
    assertThat(r.header("Last-Modified")).isNull();
    assertThat(r.header("Accept-Ranges")).isNull();
    assertSpaHeaders(r);
  }

  @Test
  void theETagIsTheFirstSixteenBytesOfSha256InLowercaseHexQuoted() {
    String tag = client().get("/").send().header("ETag");
    assertThat(tag).matches("\"[0-9a-f]{32}\"");
    // sha256("<!doctype ...") computed independently in the fixture
    assertThat(tag).isEqualTo(SpaFixture.etag(SpaFixture.INDEX.getBytes(StandardCharsets.UTF_8)));
    // a fixed vector: sha256("") = e3b0c44298fc1c149afbf4c8996fb924...
    assertThat(SpaFixture.etag(new byte[0])).isEqualTo("\"e3b0c44298fc1c149afbf4c8996fb924\"");
  }

  @Test
  void aHashedAssetIsImmutableWithItsContentTypeAndETag() {
    TestClient client = client();
    TestResponse js = client.get("/assets/index-abc123.js").send();
    assertThat(js.status()).isEqualTo(200);
    assertThat(js.bytes()).isEqualTo(SpaFixture.files().get("assets/index-abc123.js"));
    assertThat(js.header("Content-Type")).isEqualTo("text/javascript; charset=utf-8");
    assertThat(js.header("Cache-Control")).isEqualTo(IMMUTABLE);
    assertThat(js.header("ETag")).isEqualTo(SpaFixture.etag("assets/index-abc123.js"));
    assertSpaHeaders(js);
    TestResponse nested = client.get("/assets/nested/chunk-def456.mjs").send();
    assertThat(nested.header("Cache-Control")).isEqualTo(IMMUTABLE);
    assertThat(nested.header("Content-Type")).isEqualTo("text/javascript; charset=utf-8");
    assertThat(client.get("/assets/index-abc123.css").send().header("Content-Type")).isEqualTo("text/css; charset=utf-8");
    assertThat(client.get("/assets/inter-latin.woff2").send().header("Content-Type")).isEqualTo("font/woff2");
  }

  @Test
  void anUnhashedFileIsNoCacheWithItsETag() {
    TestClient client = client();
    TestResponse fav = client.get("/favicon.svg").send();
    assertThat(fav.status()).isEqualTo(200);
    assertThat(fav.header("Content-Type")).isEqualTo("image/svg+xml");
    assertThat(fav.header("Cache-Control")).isEqualTo("no-cache");
    assertThat(fav.header("ETag")).isEqualTo(SpaFixture.etag("favicon.svg"));
    TestResponse robots = client.get("/robots.txt").send();
    assertThat(robots.header("Content-Type")).isEqualTo("text/plain; charset=utf-8");
    assertThat(robots.header("Cache-Control")).isEqualTo("no-cache");
    assertThat(client.get("/manifest.webmanifest").send().header("Content-Type")).isEqualTo("application/manifest+json");
    assertThat(client.get("/noextension").send().header("Content-Type")).isEqualTo("application/octet-stream");
    assertThat(client.get("/data.bin").send().header("Content-Type")).isEqualTo("application/octet-stream");
  }

  @Test
  void contentTypesFollowTheFixedTableCaseInsensitively() throws Exception {
    Map<String, String> table =
        Map.ofEntries(
            Map.entry("a.html", "text/html; charset=utf-8"),
            Map.entry("a.htm", "text/html; charset=utf-8"),
            Map.entry("a.js", "text/javascript; charset=utf-8"),
            Map.entry("a.mjs", "text/javascript; charset=utf-8"),
            Map.entry("a.cjs", "text/javascript; charset=utf-8"),
            Map.entry("a.css", "text/css; charset=utf-8"),
            Map.entry("a.json", "application/json"),
            Map.entry("a.map", "application/json"),
            Map.entry("a.webmanifest", "application/manifest+json"),
            Map.entry("a.txt", "text/plain; charset=utf-8"),
            Map.entry("a.xml", "application/xml"),
            Map.entry("a.wasm", "application/wasm"),
            Map.entry("a.svg", "image/svg+xml"),
            Map.entry("a.png", "image/png"),
            Map.entry("a.jpg", "image/jpeg"),
            Map.entry("a.jpeg", "image/jpeg"),
            Map.entry("a.gif", "image/gif"),
            Map.entry("a.webp", "image/webp"),
            Map.entry("a.avif", "image/avif"),
            Map.entry("a.bmp", "image/bmp"),
            Map.entry("a.ico", "image/x-icon"),
            Map.entry("a.woff", "font/woff"),
            Map.entry("a.woff2", "font/woff2"),
            Map.entry("a.ttf", "font/ttf"),
            Map.entry("a.otf", "font/otf"),
            Map.entry("a.eot", "application/vnd.ms-fontobject"),
            Map.entry("upper.PNG", "image/png"),
            Map.entry("mixed.Js", "text/javascript; charset=utf-8"),
            Map.entry("a.pdf", "application/octet-stream"),
            Map.entry("a.", "application/octet-stream"));
    Path root = tmp.resolve("types");
    Files.createDirectories(root);
    Files.writeString(root.resolve("index.html"), SpaFixture.INDEX);
    for (String name : table.keySet()) {
      Files.writeString(root.resolve(name), name);
    }
    TestClient client = client(Spa.directory(root).build(), r -> {});
    for (Map.Entry<String, String> e : table.entrySet()) {
      TestResponse r = client.get("/" + e.getKey()).send();
      assertThat(r.status()).as(e.getKey()).isEqualTo(200);
      assertThat(r.header("Content-Type")).as(e.getKey()).isEqualTo(e.getValue());
    }
  }

  @Test
  void ifNoneMatchAnswersNotModifiedWithTheCachingHeadersAndNoBody() {
    TestClient client = client();
    String index = SpaFixture.etag("index.html");
    String weak = "W/" + index;
    for (String value :
        List.of(index, weak, "\"nope\", " + index, "\"a\",\"b\" ," + weak, "*", " " + index + " ")) {
      for (String path : List.of("/", "/dashboard", "/index.html")) {
        TestResponse r = client.get(path).header("If-None-Match", value).send();
        assertThat(r.status()).as(path + " If-None-Match: " + value).isEqualTo(304);
        assertThat(r.bytes()).isEmpty();
        assertThat(r.header("Content-Type")).isNull();
        assertThat(r.header("ETag")).isEqualTo(index);
        assertThat(r.header("Cache-Control")).isEqualTo("no-cache");
        assertSpaHeaders(r);
      }
    }
    TestResponse twoHeaders =
        client.get("/").header("If-None-Match", "\"x\"").header("If-None-Match", index).send();
    assertThat(twoHeaders.status()).isEqualTo(304);
    String asset = SpaFixture.etag("assets/index-abc123.js");
    TestResponse a = client.get("/assets/index-abc123.js").header("If-None-Match", asset).send();
    assertThat(a.status()).isEqualTo(304);
    assertThat(a.header("Cache-Control")).isEqualTo(IMMUTABLE);
  }

  @Test
  void aNonMatchingIfNoneMatchServesTheFile() {
    TestClient client = client();
    String index = SpaFixture.etag("index.html");
    for (String value :
        List.of(
            "\"nope\"",
            index.substring(0, 10) + "\"",
            index.toUpperCase(java.util.Locale.ROOT),
            index.substring(1, index.length() - 1),
            "W/" + index.substring(1),
            "",
            ",",
            SpaFixture.etag("assets/index-abc123.js"))) {
      TestResponse r = client.get("/").header("If-None-Match", value).send();
      assertThat(r.status()).as("If-None-Match: " + value).isEqualTo(200);
      assertThat(isIndex(r)).isTrue();
    }
  }

  @Test
  void ifNoneMatchDoesNotTurnAFourOhFourIntoANotModified() {
    TestResponse r = client().get("/assets/missing.js").header("If-None-Match", "*").send();
    assertThat(r.status()).isEqualTo(404);
  }

  @Test
  void ifModifiedSinceAndRangeAreIgnored() {
    TestClient client = client();
    TestResponse ims = client.get("/").header("If-Modified-Since", "Sun, 06 Nov 2094 08:49:37 GMT").send();
    assertThat(ims.status()).isEqualTo(200);
    TestResponse range = client.get("/assets/index-abc123.js").header("Range", "bytes=0-3").send();
    assertThat(range.status()).isEqualTo(200);
    assertThat(range.bytes()).isEqualTo(SpaFixture.files().get("assets/index-abc123.js"));
    assertThat(range.header("Content-Range")).isNull();
    assertThat(range.header("Accept-Ranges")).isNull();
    TestResponse ifRange =
        client.get("/").header("Range", "bytes=0-0").header("If-Range", SpaFixture.etag("index.html")).send();
    assertThat(ifRange.status()).isEqualTo(200);
    assertThat(isIndex(ifRange)).isTrue();
  }

  @Test
  void headIsGetWithTheSameHeadersIncludingContentLengthAndNoBody() {
    TestClient client = client();
    for (String path : List.of("/", "/dashboard", "/assets/index-abc123.js", "/favicon.svg", "/assets/missing.js", "/api/x", "/..")) {
      TestResponse get = client.get(path).send();
      TestResponse head = client.request(Method.HEAD, path).send();
      assertThat(head.status()).as(path).isEqualTo(get.status());
      assertThat(head.bytes()).as(path).isEmpty();
      assertThat(head.header("Content-Length")).as(path).isEqualTo(Integer.toString(get.bytes().length));
      for (String name : List.of("Content-Type", "Cache-Control", "ETag", "X-Content-Type-Options", "Referrer-Policy", "X-Frame-Options", "Content-Security-Policy")) {
        assertThat(head.header(name)).as(path + " " + name).isEqualTo(get.header(name));
      }
    }
    TestResponse notModified =
        client.request(Method.HEAD, "/").header("If-None-Match", SpaFixture.etag("index.html")).send();
    assertThat(notModified.status()).isEqualTo(304);
    assertThat(notModified.header("Content-Length")).isNull();
  }

  @Test
  void theSecurityHeadersAreOnEveryAnswerOfTheSpaAndTheDefaultPolicyIsTheStrictOne() {
    TestClient client = client();
    List<TestResponse> answers =
        List.of(
            client.get("/").send(),
            client.get("/dashboard").send(),
            client.get("/assets/index-abc123.js").send(),
            client.get("/").header("If-None-Match", "*").send(),
            client.get("/assets/missing.js").send(),
            client.get("/api/nope").send(),
            client.post("/dashboard").send(),
            client.get("/..").send());
    for (TestResponse r : answers) {
      assertSpaHeaders(r);
      String csp = r.header("Content-Security-Policy");
      assertThat(csp).startsWith("default-src 'self'; script-src 'self';").doesNotContain("unsafe-inline");
    }
    assertThat(Csp.strict().value()).isEqualTo(STRICT_CSP);
  }

  @Test
  void theBaselineIsTheExplicitOptInAndRestrictsNoScripts() {
    TestClient client =
        client(Spa.directory(dist).contentSecurityPolicy(Csp.baseline()).build(), SpaTest::appRoutes);
    for (String path : List.of("/", "/assets/index-abc123.js", "/assets/missing.js", "/api/x")) {
      TestResponse r = client.get(path).send();
      String csp = r.header("Content-Security-Policy");
      assertThat(csp).isEqualTo(BASELINE_CSP);
      for (String d : List.of("default-src", "script-src", "style-src", "connect-src")) {
        assertThat(csp).doesNotContain(d);
      }
      assertThat(r.header("Cross-Origin-Opener-Policy")).isNull();
      assertThat(r.header("Permissions-Policy")).isNull();
      assertThat(r.header("X-Frame-Options")).isEqualTo("DENY");
    }
    assertThat(Csp.baseline().value()).isEqualTo(BASELINE_CSP);
    assertThat(Csp.baseline().isStrict()).isFalse();
  }

  @Test
  void theStrictPolicyTakesTheApplicationsInlineScriptHashes() {
    TestClient client =
        client(
            Spa.directory(dist)
                .contentSecurityPolicy(Csp.strict("sha256-AbC+/09=", "sha384-xyz"))
                .build(),
            SpaTest::appRoutes);
    for (String path : List.of("/", "/assets/index-abc123.js", "/assets/missing.js", "/api/x")) {
      TestResponse r = client.get(path).send();
      assertThat(r.header("Content-Security-Policy"))
          .isEqualTo(
              "default-src 'self'; script-src 'self' 'sha256-AbC+/09=' 'sha384-xyz'; style-src 'self';"
                  + " img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none';"
                  + " base-uri 'none'; form-action 'self'; object-src 'none'");
      assertThat(r.header("Cross-Origin-Opener-Policy")).isEqualTo("same-origin");
      assertThat(r.header("Permissions-Policy")).isEqualTo("camera=(), microphone=(), geolocation=()");
      assertThat(r.header("X-Frame-Options")).isEqualTo("DENY");
    }
  }

  @Test
  void nosniffIsOnEveryFrameworkResponseApiAndErrorsIncluded() {
    TestClient client = client();
    assertThat(client.get("/api/v1/orders").send().header("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(client.get("/boom").send().header("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(client.post("/api/v1/orders").send().header("X-Content-Type-Options")).isEqualTo("nosniff");
    Routes plain = new Routes();
    plain.get("/x", req -> Response.text(200, "x"));
    TestClient noSpa = TestClient.of(plain);
    assertThat(noSpa.get("/x").send().header("X-Content-Type-Options")).isEqualTo("nosniff");
    TestResponse notFound = noSpa.get("/nope").send();
    assertThat(notFound.status()).isEqualTo(404);
    assertThat(notFound.header("X-Content-Type-Options")).isEqualTo("nosniff");
    // without an SPA nothing else changes: no caching or SPA headers on the router's 404
    assertThat(notFound.header("Cache-Control")).isNull();
    assertThat(notFound.header("Content-Security-Policy")).isNull();
  }

  @Test
  void aMatchedRouteIsNeverShadowedByTheSpa() {
    TestClient client = client();
    TestResponse r = client.get("/health/ready").send();
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().get("status").asString()).isEqualTo("UP");
    assertThat(r.header("Content-Security-Policy")).isNull();
  }

  @Test
  void mountedAtAPrefixTheSpaAnswersOnlyUnderIt() throws Exception {
    Routes r = new Routes("test");
    SpaTest.appRoutes(r.forModule("api"));
    r.forModule("app").get("/app/api/v1/things", req -> Response.ok(Map.of()));
    r.forModule("web").spa(Spa.directory(dist).prefix("/app").build());
    TestClient client = TestClient.of(r);
    for (String path : List.of("/app", "/app/", "/app/orders", "/app/index.html", "/app/health")) {
      TestResponse ok = client.get(path).send();
      assertThat(ok.status()).as(path).isEqualTo(200);
      assertThat(isIndex(ok)).as(path).isTrue();
    }
    assertThat(client.get("/app/assets/index-abc123.js").send().header("Cache-Control")).isEqualTo(IMMUTABLE);
    // derived under the prefix: /app/api/... is an API path; fixed ones are relative to the prefix too
    assertEnvelope404NoStore(client.get("/app/api/nope").send(), "/app/api/nope");
    assertEnvelope404NoStore(client.get("/app/auth/x").send(), "/app/auth/x");
    assertEnvelope404NoStore(client.get("/app/assets/missing.js").send(), "/app/assets/missing.js");
    // outside the prefix: the router's normal 404, without the SPA's headers
    for (String path : List.of("/", "/dashboard", "/appx", "/assets/index-abc123.js", "/index.html")) {
      TestResponse out = client.get(path).send();
      assertThat(out.status()).as(path).isEqualTo(404);
      assertThat(out.tree().get("error").asString()).isEqualTo("NOT_FOUND");
      assertThat(out.header("Cache-Control")).as(path).isNull();
      assertThat(out.header("Content-Security-Policy")).as(path).isNull();
      assertThat(isIndex(out)).isFalse();
    }
  }

  @Test
  void theAccessLogAndMetricsLabelSpaAnswersWithAFixedTemplate() {
    Metrics metrics = Metrics.memory(1000, MonotonicClock.system());
    Routes r = new Routes("test");
    appRoutes(r.forModule("api"));
    r.forModule("web").spa(Spa.directory(dist).build());
    TestClient client = TestClient.of(r, null, metrics);
    client.get("/dashboard").send();
    client.get("/clients/clt_1").send();
    client.get("/assets/index-abc123.js").send();
    client.get("/assets/missing.js").send();
    client.get("/api/nope").send();
    assertThat(value(metrics, List.of("GET", "(spa)", "200"))).isEqualTo(3.0);
    assertThat(value(metrics, List.of("GET", "(spa)", "404"))).isEqualTo(1.0);
    assertThat(value(metrics, List.of("GET", "<unmatched>", "404"))).isEqualTo(1.0);
    String text = io.flowcatalyst.framework.metrics.PrometheusText.write(metrics.snapshot());
    assertThat(text).doesNotContain("dashboard").doesNotContain("clt_1").doesNotContain("abc123");
  }

  static double value(Metrics metrics, List<String> labels) {
    for (MetricsSnapshot.Family f : metrics.snapshot().families()) {
      if (f.name().equals("fw_http_server_requests_total")) {
        for (MetricsSnapshot.Sample s : f.samples()) {
          if (s.labelValues().equals(labels)) {
            return s.value();
          }
        }
      }
    }
    return 0;
  }

  @Test
  void notBuiltInLocalOrTestAnswersThePlainFourOhFourButApiPathsKeepTheEnvelope() throws Exception {
    Path empty = tmp.resolve("empty");
    Files.createDirectories(empty);
    for (String env : List.of("local", "test")) {
      for (Spa spa : List.of(Spa.directory(empty).build(), Spa.directory(tmp.resolve("missing")).build())) {
        Routes r = new Routes(env);
        appRoutes(r.forModule("api"));
        r.forModule("web").spa(spa);
        TestClient client = TestClient.of(r);
        for (TestResponse nb :
            List.of(client.get("/").send(), client.get("/dashboard").send(), client.get("/assets/x.js").send(), client.post("/x").send())) {
          assertThat(nb.status()).isEqualTo(404);
          assertThat(nb.header("Content-Type")).isEqualTo("text/plain; charset=utf-8");
          assertThat(nb.body())
              .isEqualTo("Frontend not built into this application. Run the web build (pnpm --dir web build) and rebuild.\n");
          assertThat(nb.header("Cache-Control")).isEqualTo("no-store");
          assertSpaHeaders(nb);
        }
        assertEnvelope404NoStore(client.get("/api/nope").send(), "api while not built");
        assertThat(client.get("/api/v1/orders").send().status()).isEqualTo(200);
      }
    }
  }
}
