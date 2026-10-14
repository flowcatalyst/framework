package io.flowcatalyst.framework.http;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The check every application with an SPA runs against its own build (static-assets contract
 * section 8): {@code index.html}, one hashed asset it references, a missing asset and an unknown
 * API path, with the caching and security headers of the contract. Throws {@link AssertionError}
 * naming what was wrong, so it works with any test framework.
 *
 * <pre>{@code
 * SpaTesting.assertServesBuild(TestClient.of(routes));
 * }</pre>
 */
public final class SpaTesting {

  private static final Pattern ETAG = Pattern.compile("\"[0-9a-f]{32}\"");

  private SpaTesting() {}

  /**
   * Checks an SPA mounted at {@code /} whose hashed files are under {@code assets/}.
   *
   * @param client a client for the application's routes
   * @throws AssertionError when an answer is not what the contract says
   */
  public static void assertServesBuild(TestClient client) {
    assertServesBuild(client, "/");
  }

  /**
   * Checks an SPA mounted at a prefix whose hashed files are under {@code assets/}.
   *
   * @param client a client for the application's routes
   * @param prefix the SPA's prefix, for example {@code /app}
   * @throws AssertionError when an answer is not what the contract says
   */
  public static void assertServesBuild(TestClient client, String prefix) {
    String base = prefix.endsWith("/") ? prefix : prefix + "/";

    TestResponse index = client.get(base).send();
    expect(index.status() == 200, "GET " + base + " answered " + index.status() + ", not 200 with index.html");
    header(index, base, "Content-Type", "text/html; charset=utf-8");
    header(index, base, "Cache-Control", "no-cache");
    etag(index, base);
    security(index, base);

    String html = index.body();
    Matcher m = Pattern.compile("(?:src|href)=\"(" + Pattern.quote(base + "assets/") + "[^\"]+)\"").matcher(html);
    expect(m.find(), "index.html at " + base + " references no file under " + base + "assets/; is this the built index.html?");
    String assetPath = m.group(1);
    TestResponse asset = client.get(assetPath).send();
    expect(asset.status() == 200, "GET " + assetPath + " (referenced by index.html) answered " + asset.status() + ", not 200");
    header(asset, assetPath, "Cache-Control", "public, max-age=31536000, immutable");
    etag(asset, assetPath);
    security(asset, assetPath);

    String missing = base + "assets/fw-spa-testing-missing-0.js";
    TestResponse gone = client.get(missing).send();
    expect(gone.status() == 404, "GET " + missing + " answered " + gone.status() + ", not 404");
    header(gone, missing, "Cache-Control", "no-store");
    header(gone, missing, "Content-Type", "application/json");
    security(gone, missing);

    String api = base + "api/fw-spa-testing-missing";
    TestResponse unknown = client.get(api).header("Accept", "text/html").send();
    expect(unknown.status() == 404, "GET " + api + " answered " + unknown.status() + ", not the JSON 404");
    header(unknown, api, "Content-Type", "application/json");
    expect(!unknown.body().equals(html), "GET " + api + " answered index.html: an unknown API path must never get the SPA");
  }

  private static void etag(TestResponse r, String path) {
    String tag = r.header("ETag");
    expect(tag != null && ETAG.matcher(tag).matches(), "GET " + path + " has ETag " + tag + ", not a quoted 32-character hex digest");
  }

  private static void security(TestResponse r, String path) {
    header(r, path, "X-Content-Type-Options", "nosniff");
    header(r, path, "Referrer-Policy", "same-origin");
    header(r, path, "X-Frame-Options", "DENY");
    String csp = r.header("Content-Security-Policy");
    expect(csp != null && csp.contains("frame-ancestors 'none'"), "GET " + path + " has Content-Security-Policy " + csp);
  }

  private static void header(TestResponse r, String path, String name, String expected) {
    String actual = r.header(name);
    expect(expected.equals(actual), "GET " + path + ": " + name + " is " + actual + ", expected " + expected);
  }

  private static void expect(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
