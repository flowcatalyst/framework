package io.flowcatalyst.framework.ui;

import java.util.regex.Pattern;

/**
 * The {@code Content-Security-Policy} of a server-rendered page.
 *
 * <p>It is the strict policy of {@code Csp.strict} (static-assets contract section 7) with two
 * differences that Datastar and the kit force, and nothing else relaxed:
 *
 * <ul>
 *   <li>{@code script-src} adds {@code 'unsafe-eval'}: Datastar compiles every {@code data-*}
 *       expression with the {@code Function} constructor (its 1.0.4 bundle does; there is no
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
 */
public final class ViewCsp {

  private ViewCsp() {}

  private static final Pattern HASH = Pattern.compile("sha(256|384|512)-[A-Za-z0-9+/]+={0,2}");

  /**
   * The policy.
   *
   * @param scriptHashes hashes of the application's own inline scripts ({@code sha256-...}, no
   *     quotes); normally none
   * @return the header value
   * @throws IllegalArgumentException when a hash is malformed
   */
  public static String policy(String... scriptHashes) {
    StringBuilder script = new StringBuilder("script-src 'self' 'unsafe-eval'");
    for (String hash : scriptHashes) {
      if (!HASH.matcher(hash).matches()) {
        throw new IllegalArgumentException(
            "a script hash must be sha256-, sha384- or sha512- followed by base64, without quotes");
      }
      script.append(" '").append(hash).append('\'');
    }
    return "default-src 'self'; "
        + script
        + "; style-src 'self'; style-src-attr 'unsafe-inline'; img-src 'self' data:; font-src 'self';"
        + " connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self';"
        + " object-src 'none'";
  }
}
