package io.flowcatalyst.framework.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The {@code Content-Security-Policy} of a server-rendered page.
 *
 * <p>It is the strict policy of {@code Csp.strict} (static-assets contract section 7) with two
 * differences that Datastar and the kit force, and nothing else relaxed:
 *
 * <ul>
 *   <li>{@code script-src} adds {@code 'unsafe-eval'}: Datastar compiles every {@code data-*}
 *       expression with the {@code Function} constructor (its 1.0.5 bundle does; there is no
 *       CSP-safe build). The scripts that run are still only the module's own {@code datastar.js}
 *       ({@code 'self'}) and the hashes an application lists; there is no inline script, so no
 *       nonce is needed, and an injected {@code <script>} is still refused.
 *   <li>{@code style-src-attr 'unsafe-inline'}: the kit's markup carries a few {@code style="..."}
 *       attributes (a hidden panel before Datastar runs, the select's check-mark custom property,
 *       the sidebar logo size). {@code <style>} elements and external styles stay limited to {@code
 *       'self'}.
 * </ul>
 *
 * <p>Because of the first difference this policy is weaker against an XSS that can write attribute
 * values than the Nuxt UI package's strict policy, which has no {@code 'unsafe-eval'}. The module
 * escapes every dynamic value (see {@code Markup}) so that such a hole is not expected to exist;
 * the policy is the second line, not the first. That trade-off is the main reason to prefer the Nuxt
 * UI package for an application that handles untrusted content.
 *
 * <p>An application may name extra image hosts, for example a map's tile server (contract
 * server-rendered-ui.md section 6 rule 1, amended 2026-11-12): they follow {@code img-src 'self'
 * data:} and nothing else in the policy changes.
 */
public final class ViewCsp {

  private ViewCsp() {}

  private static final Pattern HASH = Pattern.compile("sha(256|384|512)-[A-Za-z0-9+/]+={0,2}");

  /**
   * An extra image source, matched after ASCII upper case is lowered: an https origin, one
   * optional leading wildcard label, host labels of letters, digits and hyphens, an optional port,
   * nothing after.
   */
  private static final Pattern IMAGE_SOURCE =
      Pattern.compile("https://(\\*\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)*(:[1-9][0-9]{0,4})?");

  /**
   * The policy.
   *
   * @param scriptHashes hashes of the application's own inline scripts ({@code sha256-...}, no
   *     quotes); normally none
   * @return the header value
   * @throws IllegalArgumentException when a hash is malformed
   */
  public static String policy(String... scriptHashes) {
    return policy(List.of(scriptHashes), List.of());
  }

  /**
   * The policy with extra image sources after {@code img-src 'self' data:}. Each source is an
   * {@code https} origin, {@code https://host} or {@code https://host:port}, optionally with one
   * leading wildcard label ({@code https://*.example.org}): host labels of letters, digits and
   * hyphens, a port of 1 to 65535, nothing after the authority (no path, query or fragment, not
   * even a trailing slash), no user-info. ASCII upper case is lowered; duplicates collapse to the
   * first; the given order is kept. With no image sources the policy is exactly {@link
   * #policy(String...)}'s.
   *
   * @param scriptHashes hashes of the application's own inline scripts ({@code sha256-...}, no
   *     quotes); normally none
   * @param imageSources extra image hosts, for example {@code https://tile.openstreetmap.org};
   *     normally none
   * @return the header value
   * @throws IllegalArgumentException when a hash or an image source is malformed (a bare {@code
   *     *}, a bare scheme, {@code data:}, {@code blob:}, {@code http}, whitespace, quotes, commas,
   *     semicolons, a path ...)
   */
  public static String policy(List<String> scriptHashes, List<String> imageSources) {
    StringBuilder script = new StringBuilder("script-src 'self' 'unsafe-eval'");
    for (String hash : scriptHashes) {
      if (!HASH.matcher(hash).matches()) {
        throw new IllegalArgumentException(
            "a script hash must be sha256-, sha384- or sha512- followed by base64, without quotes");
      }
      script.append(" '").append(hash).append('\'');
    }
    StringBuilder img = new StringBuilder("img-src 'self' data:");
    List<String> seen = new ArrayList<>();
    for (String source : imageSources) {
      String src = imageSource(source);
      if (!seen.contains(src)) {
        seen.add(src);
        img.append(' ').append(src);
      }
    }
    return "default-src 'self'; "
        + script
        + "; style-src 'self'; style-src-attr 'unsafe-inline'; "
        + img
        + "; font-src 'self';"
        + " connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self';"
        + " object-src 'none'";
  }

  /**
   * One extra image source, checked and lower-cased. Only ASCII letters are lowered ({@code
   * toLowerCase} would turn the Kelvin sign into {@code k}); anything else outside the rule's
   * characters fails the pattern.
   */
  private static String imageSource(String source) {
    boolean ok = source != null;
    String src = "";
    if (ok) {
      StringBuilder lower = new StringBuilder(source.length());
      for (int i = 0; i < source.length(); i++) {
        char c = source.charAt(i);
        lower.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
      }
      src = lower.toString();
      ok = IMAGE_SOURCE.matcher(src).matches();
      int colon = src.lastIndexOf(':');
      if (ok && colon > "https".length()) {
        ok = Integer.parseInt(src.substring(colon + 1)) <= 65535;
      }
    }
    if (!ok) {
      throw new IllegalArgumentException(
          "image source "
              + (source == null ? "null" : '"' + source + '"')
              + " must be an https origin, https://host or https://host:port, optionally with one"
              + " leading wildcard label (https://*.example.org), with nothing after the host or"
              + " port");
    }
    return src;
  }
}
