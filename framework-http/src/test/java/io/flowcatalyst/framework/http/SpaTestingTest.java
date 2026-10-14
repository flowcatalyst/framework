package io.flowcatalyst.framework.http;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The assertion helper applications run against their own build. */
class SpaTestingTest {

  @TempDir Path tmp;

  @Test
  void aBrokenSetupFailsWithWhatWasWrong() throws IOException {
    Path dist = SpaFixture.write(tmp);
    // an API route that answers index.html for anything under /api
    Routes catchAll = new Routes("test");
    byte[] html = Files.readAllBytes(dist.resolve("index.html"));
    catchAll.get("/api/{rest}", r -> Response.bytes(404, "application/json", html));
    catchAll.spa(Spa.directory(dist).build());
    assertThatThrownBy(() -> SpaTesting.assertServesBuild(TestClient.of(catchAll)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("api/fw-spa-testing-missing")
        .hasMessageContaining("must never get the SPA");

    // an index.html that references no hashed file
    Path other = tmp.resolve("other");
    Files.createDirectories(other);
    Files.writeString(other.resolve("index.html"), "<!doctype html><div id=\"app\"></div>");
    Routes noAsset = new Routes("test");
    noAsset.spa(Spa.directory(other).build());
    assertThatThrownBy(() -> SpaTesting.assertServesBuild(TestClient.of(noAsset)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("references no file under /assets/");

    // not built
    Routes notBuilt = new Routes("test");
    notBuilt.spa(Spa.directory(tmp.resolve("missing")).build());
    assertThatThrownBy(() -> SpaTesting.assertServesBuild(TestClient.of(notBuilt)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("answered 404");

    // checked at the wrong prefix
    Routes root = new Routes("test");
    root.spa(Spa.directory(dist).build());
    assertThatThrownBy(() -> SpaTesting.assertServesBuild(TestClient.of(root), "/app"))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void aCorrectBuildPassesAtTheRootAndAtAPrefix() throws IOException {
    Path dist = SpaFixture.write(tmp);
    Routes routes = new Routes("test");
    routes.forModule("api").get("/api/v1/orders", r -> Response.noContent());
    routes.forModule("web").spa(Spa.directory(dist).build());
    assertThatCode(() -> SpaTesting.assertServesBuild(TestClient.of(routes))).doesNotThrowAnyException();

    Path app = tmp.resolve("app-dist");
    Files.createDirectories(app.resolve("assets"));
    Files.writeString(app.resolve("index.html"), SpaFixture.INDEX.replace("/assets/", "/app/assets/"), StandardCharsets.UTF_8);
    Files.writeString(app.resolve("assets/index-abc123.js"), "x");
    Routes prefixed = new Routes("test");
    prefixed.spa(Spa.directory(app).prefix("/app").build());
    assertThatCode(() -> SpaTesting.assertServesBuild(TestClient.of(prefixed), "/app")).doesNotThrowAnyException();
  }
}
