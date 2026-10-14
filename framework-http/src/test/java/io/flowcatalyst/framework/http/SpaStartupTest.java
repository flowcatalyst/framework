package io.flowcatalyst.framework.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The SPA's start-up rules and its file index (static-assets contract sections 2 and 3). */
class SpaStartupTest {

  @TempDir Path tmp;
  Path dist;

  @BeforeEach
  void build() {
    dist = SpaFixture.write(tmp);
  }

  static StartupException startupFailure(String env, Consumer<Routes> registrar) {
    Routes routes = env == null ? new Routes() : new Routes(env);
    registrar.accept(routes);
    return catchThrowableOfType(StartupException.class, () -> TestClient.of(routes));
  }

  static String messages(StartupException e) {
    return e.problems().stream().map(Problem::toString).collect(Collectors.joining("\n"));
  }

  // ---- not built ----------------------------------------------------------------------------

  @Test
  void noIndexHtmlOutsideLocalAndTestFailsStartUp() throws IOException {
    Path empty = tmp.resolve("empty");
    Files.createDirectories(empty);
    Files.writeString(empty.resolve("favicon.svg"), "<svg/>");
    for (String env : new String[] {"production", "staging", "prod", "LOCAL", "Test", null}) {
      for (Path root : List.of(empty, tmp.resolve("missing"))) {
        StartupException e = startupFailure(env, r -> r.forModule("web").spa(Spa.directory(root).build()));
        assertThat(e).as("APP_ENV " + env + ", root " + root).isNotNull();
        assertThat(messages(e)).contains("web").contains("SPA not built").contains("index.html");
      }
    }
  }

  @Test
  void noIndexHtmlInLocalOrTestStarts() throws IOException {
    for (String env : List.of("local", "test")) {
      Routes routes = new Routes(env);
      routes.forModule("web").spa(Spa.directory(tmp.resolve("missing")).build());
      assertThat(TestClient.of(routes).get("/").send().status()).isEqualTo(404);
    }
  }

  @Test
  void aRootThatIsAFileIsAStartUpProblemEvenInTest() throws IOException {
    Path file = tmp.resolve("file");
    Files.writeString(file, "x");
    StartupException e = startupFailure("test", r -> r.forModule("web").spa(Spa.directory(file).build()));
    assertThat(e).isNotNull();
    assertThat(messages(e)).contains("not a directory");
  }

  // ---- the route table ------------------------------------------------------------------------

  @Test
  void twoSpasAreAStartUpProblemNamingBothRegistrants() {
    StartupException e =
        startupFailure(
            "test",
            r -> {
              r.forModule("first").spa(Spa.directory(dist).build());
              r.forModule("second").spa(Spa.directory(dist).prefix("/app").build());
            });
    assertThat(e).isNotNull();
    assertThat(messages(e)).contains("first").contains("second").contains("second SPA");
  }

  @Test
  void aRouteAtTheSpasPrefixShadowsIndexHtmlForAnyMethod() {
    for (Method m : List.of(Method.GET, Method.POST, Method.HEAD, Method.DELETE)) {
      StartupException e =
          startupFailure(
              "test",
              r -> {
                r.forModule("api").add(m, "/", req -> Response.noContent());
                r.forModule("web").spa(Spa.directory(dist).build());
              });
      assertThat(e).as(m.name()).isNotNull();
      assertThat(messages(e)).contains(m + " /").contains("shadows the SPA's index.html").contains("api");
    }
    for (String pattern : List.of("/app", "/app/")) {
      StartupException e =
          startupFailure(
              "test",
              r -> {
                r.forModule("api").get(pattern, req -> Response.noContent());
                r.forModule("web").spa(Spa.directory(dist).prefix("/app").build());
              });
      assertThat(e).as(pattern).isNotNull();
      assertThat(messages(e)).contains("shadows the SPA's index.html");
    }
  }

  @Test
  void aParameterAsTheFirstSegmentAfterThePrefixIsAStartUpProblem() {
    StartupException root =
        startupFailure(
            "test",
            r -> {
              r.forModule("api").get("/{tenant}/orders", req -> Response.noContent());
              r.forModule("web").spa(Spa.directory(dist).build());
            });
    assertThat(root).isNotNull();
    assertThat(messages(root)).contains("/{tenant}/orders").contains("parameter");
    StartupException prefixed =
        startupFailure(
            "test",
            r -> {
              r.forModule("api").get("/app/{tenant}", req -> Response.noContent());
              r.forModule("web").spa(Spa.directory(dist).prefix("/app").build());
            });
    assertThat(prefixed).isNotNull();
    // outside the prefix a parameter route is fine
    Routes ok = new Routes("test");
    ok.forModule("api").get("/{tenant}/orders", req -> Response.noContent());
    ok.forModule("web").spa(Spa.directory(dist).prefix("/app").build());
    assertThat(TestClient.of(ok).get("/app/x").send().status()).isEqualTo(200);
  }

  @Test
  void anApiPrefixThatHidesIndexedFilesIsAStartUpProblem() {
    StartupException fromRoute =
        startupFailure(
            "test",
            r -> {
              r.forModule("api").get("/assets/upload", req -> Response.noContent());
              r.forModule("web").spa(Spa.directory(dist).build());
            });
    assertThat(fromRoute).isNotNull();
    assertThat(messages(fromRoute)).contains("'assets'").contains("hides");
    StartupException fromCode =
        startupFailure("test", r -> r.forModule("web").spa(Spa.directory(dist).apiPrefix("favicon.svg").build()));
    assertThat(fromCode).isNotNull();
    assertThat(messages(fromCode)).contains("'favicon.svg'");
  }

  @Test
  void everyProblemIsListedTogether() {
    StartupException e =
        startupFailure(
            "test",
            r -> {
              r.forModule("api").get("/", req -> Response.noContent());
              r.forModule("api").get("/{x}/y", req -> Response.noContent());
              r.forModule("web").spa(Spa.directory(dist).apiPrefix("assets").build());
              r.forModule("other").spa(Spa.directory(dist).build());
            });
    assertThat(e).isNotNull();
    assertThat(e.problems()).hasSizeGreaterThanOrEqualTo(4);
  }

  @Test
  void registeringAnSpaAfterTheTableIsSealedFails() {
    Routes routes = new Routes("test");
    TestClient.of(routes);
    assertThatIllegalStateException().isThrownBy(() -> routes.spa(Spa.directory(dist).build()));
  }

  @Test
  void theStartUpLogListsTheSpaAndItsApiPrefixes() {
    Routes routes = new Routes("test");
    routes.forModule("api").get("/api/v1/x", req -> Response.noContent());
    routes.forModule("api").get("/health/ready", req -> Response.noContent());
    routes.forModule("web").spa(Spa.directory(dist).apiPrefix("runtime").build());
    Dispatcher d = routes.compile(null, io.flowcatalyst.framework.core.MonotonicClock.system());
    assertThat(d.describeRoutes())
        .contains("SPA / (web): 10 files, API prefixes api, auth, health, runtime");
  }

  // ---- the index --------------------------------------------------------------------------------

  @Test
  void theLimitsAreStartUpProblems() {
    StartupException files =
        startupFailure("test", r -> r.forModule("web").spa(Spa.directory(dist).maxFiles(3).build()));
    assertThat(files).isNotNull();
    assertThat(messages(files)).contains("more than 3 files");
    StartupException bytes =
        startupFailure("test", r -> r.forModule("web").spa(Spa.directory(dist).maxBytes(100).build()));
    assertThat(bytes).isNotNull();
    assertThat(messages(bytes)).contains("more than 100 bytes");
    Routes exact = new Routes("test");
    exact.spa(Spa.directory(dist).maxFiles(10).build());
    assertThat(TestClient.of(exact).get("/").send().status()).isEqualTo(200);
  }

  @Test
  void theDefaultLimitsAreTenThousandFilesAndSixtyFourMebibytes() {
    assertThat(Spa.DEFAULT_MAX_FILES).isEqualTo(10_000);
    assertThat(Spa.DEFAULT_MAX_BYTES).isEqualTo(64L * 1024 * 1024);
  }

  @Test
  void theIndexIsBuiltOnceFilesChangedLaterAreNotServed() throws IOException {
    Spa spa = Spa.directory(dist).build();
    Files.writeString(dist.resolve("late.js"), "late");
    Files.writeString(dist.resolve("index.html"), "changed");
    Routes routes = new Routes("test");
    routes.spa(spa);
    TestClient client = TestClient.of(routes);
    assertThat(client.get("/late.js").send().status()).isEqualTo(404);
    assertThat(client.get("/").send().body()).isEqualTo(SpaFixture.INDEX);
  }

  @Test
  void aClasspathRootInAFileDirectoryIsIndexed() throws Exception {
    try (URLClassLoader loader = new URLClassLoader(new URL[] {tmp.toUri().toURL()}, null)) {
      Routes routes = new Routes("production");
      routes.spa(Spa.classpath("dist", loader).build());
      TestClient client = TestClient.of(routes);
      assertThat(client.get("/").send().body()).isEqualTo(SpaFixture.INDEX);
      assertThat(client.get("/assets/index-abc123.js").send().header("Cache-Control"))
          .isEqualTo("public, max-age=31536000, immutable");
      assertThat(client.get("/.vite/manifest.json").send().status()).isEqualTo(404);
      assertThat(client.get("/../secret.txt").send().body()).doesNotContain(SpaFixture.SECRET);
    }
  }

  @Test
  void aClasspathRootInsideAJarIsIndexedWithOrWithoutDirectoryEntries() throws Exception {
    for (boolean dirs : List.of(true, false)) {
      Path jarDir = tmp.resolve("jar-" + dirs);
      Files.createDirectories(jarDir);
      Path jar = SpaFixture.jar(jarDir, "fw-spa", dirs);
      try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
        Routes routes = new Routes("production");
        routes.spa(Spa.classpath("fw-spa", loader).build());
        TestClient client = TestClient.of(routes);
        TestResponse index = client.get("/").send();
        assertThat(index.body()).as("directory entries " + dirs).isEqualTo(SpaFixture.INDEX);
        assertThat(index.header("ETag")).isEqualTo(SpaFixture.etag("index.html"));
        TestResponse nested = client.get("/assets/nested/chunk-def456.mjs").send();
        assertThat(nested.status()).isEqualTo(200);
        assertThat(nested.body()).isEqualTo("export {}");
        assertThat(client.get("/.vite/manifest.json").send().status()).isEqualTo(404);
        assertThat(client.get("/assets/.editor.js").send().status()).isEqualTo(404);
        assertThat(client.get("/other/readme.txt").send().body()).doesNotContain(SpaFixture.SECRET);
        assertThat(client.get("/../other/readme.txt").send().body()).doesNotContain(SpaFixture.SECRET);
      }
    }
  }

  @Test
  void aClasspathRootThatIsNotFoundIsNotBuilt() throws Exception {
    try (URLClassLoader loader = new URLClassLoader(new URL[] {tmp.toUri().toURL()}, null)) {
      StartupException e = startupFailure("production", r -> r.spa(Spa.classpath("nope", loader).build()));
      assertThat(e).isNotNull();
      assertThat(messages(e)).contains("SPA not built").contains("classpath:nope");
      Routes local = new Routes("local");
      local.spa(Spa.classpath("nope", loader).build());
      assertThat(TestClient.of(local).get("/").send().body()).startsWith("Frontend not built");
    }
  }

  @Test
  void aClasspathRootUnderAnotherUrlSchemeIsAStartUpProblem() throws Exception {
    ClassLoader odd =
        new ClassLoader(null) {
          @Override
          public URL getResource(String name) {
            try {
              return java.net.URI.create("http://example.invalid/" + name).toURL();
            } catch (java.net.MalformedURLException e) {
              throw new IllegalStateException(e);
            }
          }
        };
    StartupException e = startupFailure("test", r -> r.spa(Spa.classpath("fw-spa", odd).build()));
    assertThat(e).isNotNull();
    assertThat(messages(e)).contains("file: directory or a directory inside a jar");
  }

  // ---- builder misuse ---------------------------------------------------------------------------

  @Test
  void builderMisuseIsAnIllegalArgument() {
    for (String prefix : List.of("", "app", "/app/", "/a//b", "/../x", "/./x", "/.x", "/a b", "/a\\b", "/a%2fb", "/{x}")) {
      assertThatIllegalArgumentException().as(prefix).isThrownBy(() -> Spa.directory(dist).prefix(prefix));
    }
    for (String segment : List.of("", "a/b", ".", "..", "a b", "a\nb", "{x}", "a%2f")) {
      assertThatIllegalArgumentException().as(segment).isThrownBy(() -> Spa.directory(dist).apiPrefix(segment));
    }
    for (String segment : List.of("", ".assets", "a/b", "..")) {
      assertThatIllegalArgumentException().as(segment).isThrownBy(() -> Spa.directory(dist).assetsDirectory(segment));
    }
    assertThatIllegalArgumentException().isThrownBy(() -> Spa.directory(dist).maxFiles(0));
    assertThatIllegalArgumentException().isThrownBy(() -> Spa.directory(dist).maxBytes(0));
    for (String root : List.of("", "/fw-spa", "fw-spa/", "../x", "a//b", "a\\b")) {
      assertThatIllegalArgumentException().as(root).isThrownBy(() -> Spa.classpath(root));
    }
    for (String hash : List.of("abc", "sha1-abc", "sha256-", "sha256-a'b", "sha256-a;b", "sha256-a b", "'sha256-abc'", "sha256-a\r\nX: y")) {
      assertThatIllegalArgumentException().as(hash).isThrownBy(() -> Csp.strict(hash));
    }
  }

  @Test
  void theHashedDirectoryCanBeChangedToMatchTheBuild() throws IOException {
    Path root = tmp.resolve("custom");
    Files.createDirectories(root.resolve("static"));
    Files.writeString(root.resolve("index.html"), SpaFixture.INDEX);
    Files.writeString(root.resolve("static/app-1.js"), "x");
    Routes routes = new Routes("test");
    routes.spa(Spa.directory(root).assetsDirectory("static").build());
    TestClient client = TestClient.of(routes);
    assertThat(client.get("/static/app-1.js").send().header("Cache-Control")).isEqualTo("public, max-age=31536000, immutable");
    assertThat(client.get("/static/missing").send().status()).isEqualTo(404);
    assertThat(client.get("/assets/missing").send().status()).isEqualTo(200);
    assertThat(new String(client.get("/assets/missing").send().bytes(), StandardCharsets.UTF_8)).isEqualTo(SpaFixture.INDEX);
  }
}
