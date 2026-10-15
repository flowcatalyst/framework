package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.conformance.JsonVectors;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.http.ratelimit.KeyResolvers;
import io.flowcatalyst.framework.http.ratelimit.MemoryRateLimiter;
import io.flowcatalyst.framework.http.ratelimit.RateLimit;
import io.flowcatalyst.framework.http.ratelimit.RateLimitPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A sign-in that cannot start, for a browser navigation or for an API call (sessions contract 4.1
 * step 2 and section 8). Writes and checks {@code conformance/auth/login-failure.json}.
 */
class LoginFailureNavigationTest {

  static final String FILE = "auth/login-failure.json";
  static final String PAGE = "/sign-in-failed";

  @TempDir Path dir;

  /** What the application's failure handler, a redirect to {@link #PAGE}, or the envelope gives. */
  record Case(String name, String condition, String method, String path, Map<String, String> headers, int status, String error, String location) {}

  static Map<String, String> h(String... kv) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

  static List<Case> cases() {
    List<Case> out = new ArrayList<>();
    for (String condition : List.of("RATE_LIMITED", "PLATFORM_UNAVAILABLE")) {
      int status = condition.equals("RATE_LIMITED") ? 429 : 502;
      String go = PAGE + "?error=" + condition;
      String c = condition + ": ";
      out.add(new Case(c + "navigate with Accept html", condition, "GET", "/auth/login", h("Sec-Fetch-Mode", "navigate", "Accept", "text/html,application/xhtml+xml"), 302, null, go));
      out.add(new Case(c + "navigate alone", condition, "GET", "/auth/login", h("Sec-Fetch-Mode", "navigate"), 302, null, go));
      out.add(new Case(c + "no Sec-Fetch-Mode, Accept html", condition, "GET", "/auth/login", h("Accept", "text/html"), 302, null, go));
      out.add(new Case(c + "navigate to a login with returnTo", condition, "GET", "/auth/login?returnTo=%2Forders", h("Sec-Fetch-Mode", "navigate"), 302, null, go));
      out.add(new Case(c + "fetch (cors) even when Accept html", condition, "GET", "/auth/login", h("Sec-Fetch-Mode", "cors", "Accept", "text/html"), status, condition, null));
      out.add(new Case(c + "same-origin fetch", condition, "GET", "/auth/login", h("Sec-Fetch-Mode", "same-origin"), status, condition, null));
      out.add(new Case(c + "no headers", condition, "GET", "/auth/login", h(), status, condition, null));
      out.add(new Case(c + "Accept json", condition, "GET", "/auth/login", h("Accept", "application/json"), status, condition, null));
      out.add(new Case(c + "Authorization present", condition, "GET", "/auth/login", h("Sec-Fetch-Mode", "navigate", "Authorization", "Bearer x"), status, condition, null));
    }
    out.add(new Case("RATE_LIMITED: callback navigation", "RATE_LIMITED", "GET", "/auth/callback?state=x", h("Sec-Fetch-Mode", "navigate"), 302, null, PAGE + "?error=RATE_LIMITED"));
    out.add(new Case("RATE_LIMITED: callback fetch", "RATE_LIMITED", "GET", "/auth/callback?state=x", h("Sec-Fetch-Mode", "cors"), 429, "RATE_LIMITED", null));
    return out;
  }

  private TestResponse run(Case c) {
    // a limiter of one request a minute: the first request below is the one that passes
    try (AuthApp t =
        AuthApp.start(
            dir,
            b ->
                b.loginFailure(LoginFailureHandler.redirectTo(PAGE))
                    .rateLimit(
                        RateLimit.by(
                                MemoryRateLimiter.create("auth", new RateLimitPolicy(1, Duration.ofMinutes(1), 1)),
                                KeyResolvers.clientAddress())
                            .build()),
            Map.of())) {
      Browser b = t.browser();
      if (c.condition().equals("RATE_LIMITED")) {
        assertThat(b.get("/auth/login").status()).as("the request that uses the budget").isEqualTo(302);
      } else {
        t.provider.down(true);
      }
      TestResponse r = b.get(c.path(), c.headers().entrySet().stream()
          .flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue())).toArray(String[]::new));
      if (c.location() != null || c.status() == 502) {
        assertThat(r.header("Cache-Control")).as(c.name()).isEqualTo("no-store");
      }
      assertThat(r.headerValues("Set-Cookie")).as(c.name() + ": no binding cookie on a refusal").noneMatch(v -> v.contains("fw_login_"));
      return r;
    }
  }

  @Test
  void aBrowserNavigationIsSentToTheFailurePageAndAnApiCallKeepsTheEnvelope() {
    for (Case c : cases()) {
      TestResponse r = run(c);
      assertThat(r.status()).as(c.name()).isEqualTo(c.status());
      if (c.location() != null) {
        assertThat(r.header("Location")).as(c.name()).isEqualTo(c.location());
      } else {
        assertThat(r.header("Location")).as(c.name()).isNull();
        assertThat(r.tree().path("error").asString()).as(c.name()).isEqualTo(c.error());
      }
    }
  }

  @Test
  void withTheDefaultHandlerANavigationStillGetsTheEnvelope() {
    try (AuthApp t = AuthApp.start(dir)) {
      t.provider.down(true);
      TestResponse r = t.browser().get("/auth/login", "Sec-Fetch-Mode", "navigate", "Accept", "text/html");
      assertThat(r.status()).isEqualTo(502);
      assertThat(r.tree().path("error").asString()).isEqualTo("PLATFORM_UNAVAILABLE");
    }
  }

  @Test
  void theVectorMatches() {
    ObjectNode doc = JsonVectors.mapper().createObjectNode();
    doc.put(
        "description",
        "A sign-in that cannot start (sessions contract 4.1 step 2 and section 8). Setup: the application's failure handler redirects to "
            + PAGE
            + " ('?error=CODE'); condition RATE_LIMITED = the client address has used its budget (the request then sent is refused with 429),"
            + " PLATFORM_UNAVAILABLE = discovery cannot be read (502). A browser navigation (GET or HEAD, no Authorization, Sec-Fetch-Mode"
            + " navigate, or without that header an Accept containing text/html) gets the handler's answer: here 302 to location, no body"
            + " checked; every other request gets the JSON envelope {error, message} with the status. The redirect and the 502 are Cache-Control: no-store (the limiter's own 429 envelope is unchanged and says"
            + " nothing of caching); no answer sets a login binding cookie. Header names compare case-insensitively.");
    doc.put("failurePage", PAGE);
    ArrayNode out = doc.putArray("cases");
    for (Case c : cases()) {
      ObjectNode item = out.addObject();
      item.put("name", c.name());
      item.put("condition", c.condition());
      ObjectNode req = item.putObject("request");
      req.put("method", c.method());
      req.put("path", c.path());
      ObjectNode rh = req.putObject("headers");
      c.headers().forEach(rh::put);
      ObjectNode expect = item.putObject("expect");
      expect.put("status", c.status());
      if (c.location() != null) {
        expect.put("location", c.location());
      } else {
        expect.put("error", c.error());
      }
    }
    JsonVectors.check(FILE, doc);
  }
}
