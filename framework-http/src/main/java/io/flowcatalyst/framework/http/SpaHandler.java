package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.core.Problem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mounted SPA: the rules of static-assets contract section 4, in their order, with the API
 * prefixes worked out from the route table. Built by {@link Routes#compile}; the dispatcher calls
 * {@link #answer} for a path no route matched.
 */
final class SpaHandler {

  private static final Logger LOG = LoggerFactory.getLogger(SpaHandler.class);

  static final String IMMUTABLE = "public, max-age=31536000, immutable";
  static final String NOT_BUILT =
      "Frontend not built into this application. Run the web build (pnpm --dir web build) and rebuild.\n";
  private static final Set<String> ALWAYS_API = Set.of("api", "auth");

  private final Spa spa;
  private final Set<String> apiPrefixes;
  private final boolean notBuilt;
  private final List<Response.Header> securityHeaders;

  private SpaHandler(Spa spa, Set<String> apiPrefixes, boolean notBuilt) {
    this.spa = spa;
    this.apiPrefixes = Set.copyOf(apiPrefixes);
    this.notBuilt = notBuilt;
    List<Response.Header> h = new ArrayList<>();
    h.add(new Response.Header("X-Content-Type-Options", "nosniff"));
    h.add(new Response.Header("Referrer-Policy", "same-origin"));
    h.add(new Response.Header("X-Frame-Options", "DENY"));
    h.add(new Response.Header("Content-Security-Policy", spa.csp().value()));
    if (spa.csp().isStrict()) {
      h.add(new Response.Header("Cross-Origin-Opener-Policy", "same-origin"));
      h.add(new Response.Header("Permissions-Policy", "camera=(), microphone=(), geolocation=()"));
    }
    this.securityHeaders = List.copyOf(h);
  }

  /**
   * Checks the SPA against the route table and mounts it (contract sections 2, 3 and 5).
   *
   * @param registrant the module that mounted it
   * @param spa the SPA
   * @param routes every compiled route
   * @param environment {@code APP_ENV}, or null when unknown (treated as a deployed environment)
   * @param problems where start-up problems go
   * @param lines the start-up log lines, to which the SPA's is added
   * @return the handler, or null when there were problems
   */
  static @Nullable SpaHandler mount(
      String registrant,
      Spa spa,
      List<Router.Compiled> routes,
      @Nullable String environment,
      List<Problem> problems,
      List<String> lines) {
    int before = problems.size();
    for (String p : spa.problems()) {
      problems.add(Problem.of(registrant, "spa", p));
    }
    boolean notBuilt = false;
    if (spa.problems().isEmpty() && spa.index() == null) {
      if ("local".equals(environment) || "test".equals(environment)) {
        LOG.warn(
            "SPA not built: run the web build; serving 404 (no index.html in {}, APP_ENV {})",
            spa.source(),
            environment);
        notBuilt = true;
      } else {
        problems.add(
            Problem.of(
                registrant,
                "spa",
                "SPA not built: no index.html in "
                    + spa.source()
                    + " (APP_ENV "
                    + (environment == null ? "unknown" : environment)
                    + "); run the web build before packaging. Only APP_ENV local or test start without it"));
      }
    }

    // where each API prefix comes from, for the problems below
    Map<String, String> origin = new LinkedHashMap<>();
    for (String fixed : ALWAYS_API) {
      origin.put(fixed, "always an API prefix");
    }
    for (String added : spa.apiPrefixes()) {
      origin.put(added, "Spa.Builder.apiPrefix (module '" + registrant + "')");
    }
    List<String> prefix = spa.prefix();
    int k = prefix.size();
    for (Router.Compiled r : routes) {
      RoutePattern p = r.pattern();
      List<String> segs = p.segments();
      if (segs.size() < k || !covers(p, prefix)) {
        continue;
      }
      String route = r.method() + " " + p.text() + " (module '" + r.registrant() + "')";
      boolean atPrefix = segs.size() == k || (segs.size() == k + 1 && segs.get(k).isEmpty());
      if (atPrefix) {
        problems.add(
            Problem.of(
                r.registrant(),
                r.method() + " " + p.text(),
                "route " + route + " shadows the SPA's index.html at " + spa.prefixText()
                    + " (module '" + registrant + "'); move the route under an API prefix"));
      } else if (p.isParam(k)) {
        problems.add(
            Problem.of(
                r.registrant(),
                r.method() + " " + p.text(),
                "route " + route + " has a parameter as its first segment under the SPA's prefix "
                    + spa.prefixText() + ": every first segment would be an API prefix"));
      } else {
        origin.putIfAbsent(segs.get(k), "route " + route);
      }
    }
    Set<String> firstSegments = new TreeSet<>();
    for (String name : spa.files().keySet()) {
      int slash = name.indexOf('/');
      firstSegments.add(slash < 0 ? name : name.substring(0, slash));
    }
    for (Map.Entry<String, String> e : origin.entrySet()) {
      if (firstSegments.contains(e.getKey())) {
        problems.add(
            Problem.of(
                registrant,
                "spa",
                "API prefix '" + e.getKey() + "' (" + e.getValue() + ") hides the SPA's files under "
                    + (spa.prefixText().equals("/") ? "/" : spa.prefixText() + "/") + e.getKey()
                    + ": every one of them would answer the JSON 404"));
      }
    }
    if (problems.size() > before) {
      return null;
    }
    Set<String> api = new TreeSet<>(origin.keySet());
    lines.add(
        "SPA " + spa.prefixText() + " (" + registrant + "): "
            + (notBuilt ? "not built" : spa.files().size() + " files")
            + ", API prefixes " + String.join(", ", api));
    return new SpaHandler(spa, api, notBuilt);
  }

  /** Whether the route's first segments can match the SPA's prefix (literal equal or a parameter). */
  private static boolean covers(RoutePattern p, List<String> prefix) {
    for (int i = 0; i < prefix.size(); i++) {
      if (!p.isParam(i) && !p.segments().get(i).equals(prefix.get(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Answers a request the router did not match.
   *
   * @param request the request
   * @param segments its path, split and percent-decoded per segment as the router does
   * @return the response
   * @throws HttpException the router's own 404, for a path outside the SPA's prefix
   */
  Response answer(Request request, List<String> segments) {
    List<String> prefix = spa.prefix();
    // 1. outside the prefix: the router's 404
    if (segments.size() < prefix.size() || !segments.subList(0, prefix.size()).equals(prefix)) {
      throw new HttpException(404, "NOT_FOUND", "no route for this path");
    }
    List<String> rest = segments.subList(prefix.size(), segments.size());
    boolean root = rest.isEmpty() || (rest.size() == 1 && rest.getFirst().isEmpty());
    // 2. an API prefix: the router's 404, never index.html, for any method
    if (!root && apiPrefixes.contains(rest.getFirst())) {
      return secure(noStore(HttpErrors.error(404, "NOT_FOUND", "no route for this path")));
    }
    request.bind("(spa)", Map.of());
    if (notBuilt) {
      return secure(noStore(Response.text(404, NOT_BUILT)));
    }
    // 3. only GET and HEAD
    if (request.method() != Method.GET && request.method() != Method.HEAD) {
      return secure(
          noStore(HttpErrors.error(405, "METHOD_NOT_ALLOWED", "method not allowed for this path"))
              .header("Allow", "GET, HEAD"));
    }
    // 4. unclean paths never reach the index
    if (!root && !clean(rest)) {
      return notFound();
    }
    Spa.File index = spa.index();
    if (index == null) {
      throw new IllegalStateException("a mounted SPA without index.html must be in not-built mode");
    }
    // 5. the prefix itself, or index.html
    if (root || (rest.size() == 1 && rest.getFirst().equals("index.html"))) {
      return serve(request, index, "no-cache");
    }
    // 6. an indexed file
    Spa.File file = spa.files().get(String.join("/", rest));
    if (file != null) {
      boolean hashed = rest.size() > 1 && rest.getFirst().equals(spa.assetsDirectory());
      return serve(request, file, hashed ? IMMUTABLE : "no-cache");
    }
    // 7. a missing asset: a 404, never index.html loaded as a script
    if (rest.getFirst().equals(spa.assetsDirectory()) || Spa.isAssetExtension(rest.getLast())) {
      return notFound();
    }
    // 8. a client route
    return serve(request, index, "no-cache");
  }

  /** Contract section 4.1, per decoded segment. */
  static boolean clean(List<String> segments) {
    for (String s : segments) {
      if (s.isEmpty() || s.charAt(0) == '.') {
        return false; // also '.' and '..'
      }
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '\\' || c == '/' || c < 0x20 || c == 0x7f) {
          return false;
        }
      }
    }
    return true;
  }

  private Response notFound() {
    return secure(noStore(HttpErrors.error(404, "NOT_FOUND", "no route for this path")));
  }

  private Response serve(Request request, Spa.File file, String cacheControl) {
    Response r =
        matches(request.headers("If-None-Match"), file.etag())
            ? Response.empty(304)
            : Response.shared(200, file.contentType(), file.bytes());
    return secure(r.header("Cache-Control", cacheControl).header("ETag", file.etag()));
  }

  /** {@code If-None-Match} with the weak comparison: a list of tags, {@code W/} ignored, or {@code *}. */
  static boolean matches(List<String> headerValues, String etag) {
    for (String value : headerValues) {
      for (String part : value.split(",", -1)) {
        String tag = part.strip();
        if (tag.equals("*")) {
          return true;
        }
        if (tag.startsWith("W/")) {
          tag = tag.substring(2);
        }
        if (tag.equals(etag)) {
          return true;
        }
      }
    }
    return false;
  }

  private static Response noStore(Response r) {
    return r.header("Cache-Control", "no-store");
  }

  private Response secure(Response r) {
    Response out = r;
    for (Response.Header h : securityHeaders) {
      out = out.header(h.name(), h.value());
    }
    return out;
  }
}
