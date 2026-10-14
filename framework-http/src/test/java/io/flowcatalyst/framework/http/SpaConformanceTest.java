package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.conformance.JsonVectors;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes and checks {@code conformance/http/spa.json} (static-assets contract section 10): a
 * fixture build, a route table, the SPA's options, and for each request the status, the headers
 * and the body's SHA-256 the Java code answers. The Go implementation serves the same fixture and
 * compares.
 */
class SpaConformanceTest {

  static final String FILE = "http/spa.json";

  @TempDir Path tmp;

  record Case(String method, String path, Map<String, String> headers) {
    static Case get(String path) {
      return new Case("GET", path, Map.of());
    }

    static Case of(String method, String path) {
      return new Case(method, path, Map.of());
    }

    static Case get(String path, String header, String value) {
      return new Case("GET", path, Map.of(header, value));
    }
  }

  static Map<String, String> fixture() {
    Map<String, String> f = new LinkedHashMap<>();
    f.put("index.html", SpaFixture.INDEX);
    f.put("favicon.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
    f.put("robots.txt", "User-agent: *\n");
    f.put("manifest.webmanifest", "{\"name\":\"x\"}");
    f.put("noextension", "plain");
    f.put("assets/index-abc123.js", "console.log(\"app\")");
    f.put("assets/index-abc123.css", "body{}");
    f.put("assets/nested/chunk-def456.mjs", "export {}");
    f.put(".vite/manifest.json", "{\"vite\":true}");
    return f;
  }

  static List<Case> cases() {
    String index = Spa.etag(SpaFixture.INDEX.getBytes(StandardCharsets.UTF_8));
    List<Case> c = new ArrayList<>();
    // client routes and index.html
    for (String p : List.of("/", "/index.html", "/dashboard", "/ingestions/acme.orders", "/apiary", "/x.unknownext", "/%252e%252e/x")) {
      c.add(Case.get(p));
    }
    // files
    for (String p : List.of("/assets/index-abc123.js", "/assets/index-abc123.css", "/assets/nested/chunk-def456.mjs", "/favicon.svg", "/robots.txt", "/manifest.webmanifest", "/noextension")) {
      c.add(Case.get(p));
    }
    // missing assets
    for (String p : List.of("/assets/index-OLD.js", "/assets", "/assets/", "/assets/nested", "/favicon.ico", "/deep/chunk.JS", "/Index.HTML")) {
      c.add(Case.get(p));
    }
    // API prefixes: derived, fixed, added in code; whole segment only
    for (String p : List.of("/api/nope", "/api", "/auth/login", "/orders", "/orders/7/edit", "/health", "/runtime/x", "/%61pi/x")) {
      c.add(Case.get(p));
      c.add(Case.of("POST", p));
      c.add(Case.of("DELETE", p));
    }
    // the router's own answers come first
    c.add(Case.get("/orders/7"));
    c.add(Case.of("POST", "/api/v1/orders"));
    c.add(Case.get("/%zz"));
    // other methods on SPA paths
    for (String m : List.of("POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
      c.add(Case.of(m, "/dashboard"));
    }
    c.add(Case.of("POST", "/assets/index-abc123.js"));
    // unclean paths
    for (String p :
        List.of(
            "/..", "/../secret.txt", "/assets/../index.html", "/%2e%2e/secret.txt", "/..%2fsecret.txt", "/assets%2findex-abc123.js",
            "/.", "//index.html", "/dashboard/", "/a\\b", "/%5c..%5csecret.txt", "/%00", "/index.html%00.js", "/a%0d%0aX:%20y",
            "/a%7fb", "/.vite/manifest.json", "/%2evite/manifest.json", "/%252e%252e/secret.txt")) {
      c.add(Case.get(p));
    }
    // conditional requests, HEAD, Range
    c.add(Case.get("/", "If-None-Match", index));
    c.add(Case.get("/dashboard", "If-None-Match", "W/" + index));
    c.add(Case.get("/", "If-None-Match", "\"other\", " + index));
    c.add(Case.get("/", "If-None-Match", "*"));
    c.add(Case.get("/", "If-None-Match", "\"other\""));
    c.add(Case.get("/assets/index-abc123.js", "If-None-Match", Spa.etag("console.log(\"app\")".getBytes(StandardCharsets.UTF_8))));
    c.add(Case.get("/assets/missing.js", "If-None-Match", "*"));
    c.add(Case.get("/", "If-Modified-Since", "Sun, 06 Nov 2094 08:49:37 GMT"));
    c.add(Case.get("/assets/index-abc123.js", "Range", "bytes=0-3"));
    for (String p : List.of("/", "/assets/index-abc123.js", "/assets/missing.js", "/api/nope", "/..")) {
      c.add(Case.of("HEAD", p));
    }
    return c;
  }

  @Test
  void theVectorMatchesWhatTheJavaCodeAnswers() throws IOException {
    Map<String, String> files = fixture();
    Path root = tmp.resolve("dist");
    for (Map.Entry<String, String> e : files.entrySet()) {
      Path p = root.resolve(e.getKey());
      Files.createDirectories(p.getParent());
      Files.writeString(p, e.getValue());
    }
    List<String[]> routes =
        List.of(
            new String[] {"GET", "/api/v1/orders"},
            new String[] {"GET", "/orders/{id}"},
            new String[] {"POST", "/runtime/v1/poll"},
            new String[] {"GET", "/health/ready"});
    Routes table = new Routes("test");
    for (String[] r : routes) {
      table.forModule("api").add(Method.valueOf(r[0]), r[1], req -> Response.ok(Map.of("route", r[1])));
    }
    table.forModule("web").spa(Spa.directory(root).apiPrefix("q").build());
    TestClient client = TestClient.of(table);

    ObjectNode doc = JsonVectors.mapper().createObjectNode();
    doc.put(
        "description",
        "The SPA fallback (static-assets contract). fixture: the built files (UTF-8 text), served at prefix"
            + " with apiPrefixes added in code, behind routes (each answers 200 {\"route\": pattern}), APP_ENV test."
            + " Each case: the request (path as sent, before percent-decoding) and the answer: status, every header"
            + " except X-Correlation-Id and Content-Length, sorted by name, the body length and its SHA-256 in"
            + " lowercase hex (for HEAD, bodyLength is the Content-Length sent with no body). A server that refuses an unclean request line before the framework (Jetty: 400)"
            + " is outside the vector, which runs in process.");
    ObjectNode fixture = doc.putObject("fixture");
    files.forEach(fixture::put);
    ObjectNode spa = doc.putObject("spa");
    spa.put("prefix", "/");
    spa.putArray("apiPrefixes").add("q");
    ArrayNode rs = doc.putArray("routes");
    for (String[] r : routes) {
      rs.addObject().put("method", r[0]).put("pattern", r[1]);
    }
    ArrayNode out = doc.putArray("cases");
    for (Case c : cases()) {
      TestClient.Call call = client.request(Method.valueOf(c.method()), c.path());
      c.headers().forEach(call::header);
      TestResponse r = call.send();
      ObjectNode item = out.addObject();
      ObjectNode req = item.putObject("request");
      req.put("method", c.method());
      req.put("path", c.path());
      ObjectNode rh = req.putObject("headers");
      new TreeMap<>(c.headers()).forEach(rh::put);
      ObjectNode expect = item.putObject("expect");
      expect.put("status", r.status());
      ObjectNode h = expect.putObject("headers");
      Map<String, String> sorted = new TreeMap<>();
      for (Response.Header header : r.headers()) {
        if (!header.name().equalsIgnoreCase("X-Correlation-Id") && !header.name().equalsIgnoreCase("Content-Length")) {
          sorted.put(header.name(), header.value());
        }
      }
      sorted.forEach(h::put);
      byte[] body = r.bytes();
      String length = r.header("Content-Length");
      expect.put("bodyLength", c.method().equals("HEAD") && length != null ? Integer.parseInt(length) : body.length);
      expect.put("bodySha256", sha256(body));
    }
    JsonVectors.check(FILE, doc);
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
