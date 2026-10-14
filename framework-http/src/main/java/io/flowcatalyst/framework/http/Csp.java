package io.flowcatalyst.framework.http;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The {@code Content-Security-Policy} an SPA is served with (static-assets contract section 7).
 *
 * <p>{@link #baseline()} is the default: it restricts framing, plugins and {@code <base>} only, as
 * the platform does. {@link #strict(String...)} also restricts scripts, styles, images, fonts and
 * connections to the application's own origin and adds {@code Cross-Origin-Opener-Policy} and
 * {@code Permissions-Policy}; it is opt-in until the shared UI package has been shown to run under it.
 */
public final class Csp {

  private static final String BASELINE = "frame-ancestors 'none'; object-src 'none'; base-uri 'self'";

  private static final Pattern HASH = Pattern.compile("sha(256|384|512)-[A-Za-z0-9+/]+={0,2}");

  private final String value;
  private final boolean strict;

  private Csp(String value, boolean strict) {
    this.value = value;
    this.strict = strict;
  }

  /**
   * The platform's policy: {@code frame-ancestors 'none'; object-src 'none'; base-uri 'self'}.
   *
   * @return the baseline policy
   */
  public static Csp baseline() {
    return new Csp(BASELINE, false);
  }

  /**
   * The strict policy: {@code default-src 'self'}, scripts from the origin plus the given hashes of
   * the application's inline snippets, styles, images ({@code data:} too), fonts and connections
   * from the origin, no framing, {@code base-uri 'none'}, {@code form-action 'self'}, no plugins.
   *
   * @param scriptHashes hashes such as {@code sha256-<base64>} (sha256, sha384 or sha512), without
   *     quotes
   * @return the strict policy
   * @throws IllegalArgumentException when a hash is not of that form
   */
  public static Csp strict(String... scriptHashes) {
    StringBuilder script = new StringBuilder("script-src 'self'");
    for (String hash : List.of(scriptHashes)) {
      if (!HASH.matcher(hash).matches()) {
        throw new IllegalArgumentException(
            "a script hash must be sha256-, sha384- or sha512- followed by base64, without quotes");
      }
      script.append(" '").append(hash).append('\'');
    }
    return new Csp(
        "default-src 'self'; "
            + script
            + "; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self';"
            + " frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'",
        true);
  }

  /**
   * The header value.
   *
   * @return the policy text
   */
  public String value() {
    return value;
  }

  /**
   * Whether this is the strict policy, which brings two companion headers.
   *
   * @return true for {@link #strict(String...)}
   */
  public boolean isStrict() {
    return strict;
  }

  @Override
  public String toString() {
    return value;
  }
}
