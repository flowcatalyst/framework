package io.flowcatalyst.framework.http.jetty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.Spa;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SPA over a real Jetty server: what the in-process tests cannot show (the bytes on the wire,
 * {@code HEAD}, {@code Range}, paths Jetty itself parses, a packaged jar in a deployed environment).
 */
class SpaOverJettyTest {

  static final String INDEX =
      "<!doctype html><html><head><script type=\"module\" src=\"/assets/index-abc123.js\"></script></head>"
          + "<body><div id=\"app\"></div></body></html>";
  static final String JS = "console.log(\"app\")";
  static final String SECRET = "TOP-SECRET-OUTSIDE-THE-ROOT";

  @TempDir Path tmp;
  Path config;
  Path dist;

  @BeforeEach
  void build() throws IOException {
    config = Files.createDirectories(tmp.resolve("config"));
    dist = Files.createDirectories(tmp.resolve("app/dist/assets"));
    dist = dist.getParent();
    Files.writeString(dist.resolve("index.html"), INDEX);
    Files.writeString(dist.resolve("assets/index-abc123.js"), JS);
    Files.writeString(tmp.resolve("app/secret.txt"), SECRET);
  }

  App start(Map<String, String> env, Spa spa) {
    return Apps.start(
        config,
        env,
        r -> {
          r.get("/api/v1/orders", req -> Response.ok(Map.of("orders", List.of())));
          r.spa(spa);
        });
  }

  @Test
  void aDeployedApplicationWithoutItsBuildDoesNotStart() {
    Throwable t =
        catchThrowable(() -> start(Apps.env("APP_ENV", "production"), Spa.directory(tmp.resolve("nope")).build()));
    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(((StartupException) t).problems().getFirst().message()).contains("SPA not built");
  }

  @Test
  void localWithoutTheBuildStartsAndSaysSo() throws Exception {
    try (App app = start(Apps.env(), Spa.directory(tmp.resolve("nope")).build());
        RawHttp http = new RawHttp(app.get(HttpServer.class).port())) {
      http.get("/");
      RawHttp.Reply r = http.read();
      assertThat(r.status()).isEqualTo(404);
      assertThat(r.body()).startsWith("Frontend not built");
      assertThat(r.headers()).containsEntry("cache-control", "no-store");
    }
  }

  @Test
  void aPackagedJarIsServedInADeployedEnvironment() throws Exception {
    Path jar = tmp.resolve("app.jar");
    try (OutputStream os = Files.newOutputStream(jar);
        JarOutputStream out = new JarOutputStream(os)) {
      for (String[] e : new String[][] {{"fw-spa/index.html", INDEX}, {"fw-spa/assets/index-abc123.js", JS}}) {
        out.putNextEntry(new JarEntry(e[0]));
        out.write(e[1].getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null);
        App app = start(Apps.env("APP_ENV", "production"), Spa.classpath("fw-spa", loader).build());
        RawHttp http = new RawHttp(app.get(HttpServer.class).port())) {
      http.get("/orders/7");
      RawHttp.Reply r = http.read();
      assertThat(r.status()).isEqualTo(200);
      assertThat(r.body()).isEqualTo(INDEX);
      assertThat(r.headers())
          .containsEntry("content-type", "text/html; charset=utf-8")
          .containsEntry("content-length", Integer.toString(INDEX.length()))
          .containsEntry("cache-control", "no-cache")
          .containsEntry("x-content-type-options", "nosniff")
          .containsEntry("x-frame-options", "DENY")
          .containsEntry("referrer-policy", "same-origin")
          .containsEntry(
              "content-security-policy",
              "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self';"
                  + " frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'")
          .doesNotContainKey("last-modified")
          .doesNotContainKey("accept-ranges");
      assertThat(r.headers().get("etag")).matches("\"[0-9a-f]{32}\"");
    }
  }

  @Test
  void headCarriesTheContentLengthOfGetAndNoBody() throws Exception {
    try (App app = start(Apps.env(), Spa.directory(dist).build());
        RawHttp http = new RawHttp(app.get(HttpServer.class).port())) {
      for (String path : List.of("/", "/assets/index-abc123.js", "/assets/missing.js", "/api/nope", "/api/v1/orders")) {
        http.get(path);
        RawHttp.Reply get = http.read();
        // pipelined: if HEAD sent a body, the GET after it would not parse
        http.send("HEAD " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\nGET /assets/index-abc123.js HTTP/1.1\r\nHost: localhost\r\n\r\n");
        RawHttp.Reply head = http.readHeaders();
        RawHttp.Reply after = http.read();
        assertThat(head.status()).as(path).isEqualTo(get.status());
        assertThat(head.headers().get("content-length")).as(path).isEqualTo(Integer.toString(get.body().getBytes(StandardCharsets.UTF_8).length));
        assertThat(head.headers().get("content-type")).as(path).isEqualTo(get.headers().get("content-type"));
        assertThat(head.headers().get("etag")).as(path).isEqualTo(get.headers().get("etag"));
        assertThat(after.status()).isEqualTo(200);
        assertThat(after.body()).isEqualTo(JS);
      }
    }
  }

  @Test
  void rangeIsIgnoredAndNotModifiedHasNoBody() throws Exception {
    try (App app = start(Apps.env(), Spa.directory(dist).build());
        RawHttp http = new RawHttp(app.get(HttpServer.class).port())) {
      http.send("GET /assets/index-abc123.js HTTP/1.1\r\nHost: localhost\r\nRange: bytes=0-3\r\n\r\n");
      RawHttp.Reply ranged = http.read();
      assertThat(ranged.status()).isEqualTo(200);
      assertThat(ranged.body()).isEqualTo(JS);
      assertThat(ranged.headers()).doesNotContainKey("content-range").doesNotContainKey("accept-ranges");
      assertThat(ranged.headers()).containsEntry("cache-control", "public, max-age=31536000, immutable");
      String etag = ranged.headers().get("etag");
      http.send("GET /assets/index-abc123.js HTTP/1.1\r\nHost: localhost\r\nIf-None-Match: W/" + etag + "\r\n\r\n");
      RawHttp.Reply notModified = http.readHeaders();
      assertThat(notModified.status()).isEqualTo(304);
      assertThat(notModified.headers()).doesNotContainKey("content-type").containsEntry("etag", etag);
      // the connection is still in step: a 304 sent no body
      http.get("/");
      assertThat(http.read().body()).isEqualTo(INDEX);
    }
  }

  @Test
  void pathsJettyParsesNeverLeaveTheIndex() throws Exception {
    try (App app = start(Apps.env(), Spa.directory(dist).build())) {
      int port = app.get(HttpServer.class).port();
      for (String path :
          List.of(
              "/../secret.txt",
              "/assets/../../secret.txt",
              "/%2e%2e/secret.txt",
              "/%2E%2E/%2e%2e/secret.txt",
              "/assets/..%2f..%2fsecret.txt",
              "/..%5csecret.txt",
              "/a\\..\\..\\secret.txt",
              "/%00",
              "/index.html%00.js",
              "/a%0d%0aX-Injected:%201",
              "/.%2e/secret.txt",
              "//secret.txt",
              "/%252e%252e/secret.txt",
              "/;/../secret.txt",
              "/assets;x=1/index-abc123.js",
              "/" + "a".repeat(20_000))) {
        try (RawHttp http = new RawHttp(port)) {
          http.get(path);
          RawHttp.Reply r = http.read();
          assertThat(r.body()).as(path).doesNotContain(SECRET);
          assertThat(r.headers()).as(path).doesNotContainKey("x-injected");
          if (r.status() == 200) {
            // only an extension-less client route may answer, and only with index.html
            assertThat(r.body()).as(path).isEqualTo(INDEX);
          } else {
            assertThat(r.status()).as(path).isIn(400, 404, 414, 431);
            assertThat(r.body()).as(path).doesNotContain("<div id=\"app\">");
          }
          assertThat(r.headers()).as(path).containsEntry("x-content-type-options", "nosniff");
        }
      }
    }
  }

  @Test
  void jettysOwnAnswersCarryNosniff() throws Exception {
    try (App app = start(Apps.env(), Spa.directory(dist).build())) {
      int port = app.get(HttpServer.class).port();
      try (RawHttp http = new RawHttp(port)) {
        http.send("BREW / HTTP/1.1\r\nHost: localhost\r\n\r\n");
        RawHttp.Reply r = http.read();
        assertThat(r.status()).isEqualTo(405);
        assertThat(r.headers()).containsEntry("x-content-type-options", "nosniff");
      }
      try (RawHttp http = new RawHttp(port)) {
        http.send("GET / HTTP/1.1\r\nHost: localhost\r\nBad Header Line\r\n\r\n");
        RawHttp.Reply r = http.read();
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.headers()).containsEntry("x-content-type-options", "nosniff");
      }
    }
  }
}
