package io.flowcatalyst.framework.http;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which browser origins may call the application across origins (CORS), installed with {@link
 * Routes#cors}. The HTTP module builds one from {@code http.cors.*} when {@code
 * http.cors.allowed-origins} is set; CORS is off otherwise.
 *
 * <p>There are never credentials: {@code Access-Control-Allow-Credentials} is never sent, so the
 * session cookie is never usable across origins (sessions contract section 7 relies on it). A page
 * on an allowed origin calls with a bearer token it holds.
 *
 * @param allowedOrigins exact origins such as {@code https://app.example.com}, or the single entry
 *     {@code *} for any origin; not empty
 * @param allowedMethods the methods a preflight may ask for
 * @param allowedHeaders the request headers a preflight may ask for, compared case-insensitively
 * @param exposedHeaders the response headers the browser may let the page read
 * @param maxAge how long a browser may cache a preflight's answer
 */
public record CorsPolicy(
    List<String> allowedOrigins,
    List<Method> allowedMethods,
    List<String> allowedHeaders,
    List<String> exposedHeaders,
    Duration maxAge) {

  /** The methods allowed by default. */
  public static final List<Method> DEFAULT_METHODS =
      List.of(Method.GET, Method.HEAD, Method.POST, Method.PUT, Method.PATCH, Method.DELETE);

  /** The request headers allowed by default ({@code DPoP} for field workers' proof-bound tokens). */
  public static final List<String> DEFAULT_HEADERS =
      List.of("Content-Type", "Authorization", "DPoP", "X-Correlation-Id", "Idempotency-Key", "Last-Event-ID");

  /** The response headers exposed by default. */
  public static final List<String> DEFAULT_EXPOSED = List.of("X-Correlation-Id", "Idempotency-Replayed");

  private static final Pattern ORIGIN = Pattern.compile("https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?");
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]+");

  /** Checks and copies the lists. */
  public CorsPolicy {
    allowedOrigins = List.copyOf(allowedOrigins);
    allowedMethods = List.copyOf(allowedMethods);
    allowedHeaders = List.copyOf(allowedHeaders);
    exposedHeaders = List.copyOf(exposedHeaders);
    if (allowedOrigins.isEmpty()) {
      throw new IllegalArgumentException("a CORS policy needs at least one allowed origin");
    }
    boolean any = allowedOrigins.contains("*");
    if (any && allowedOrigins.size() > 1) {
      throw new IllegalArgumentException("'*' allows every origin and is the only entry when used");
    }
    for (String o : allowedOrigins) {
      if (!any && !ORIGIN.matcher(o).matches()) {
        throw new IllegalArgumentException(
            "a CORS origin is scheme, host and optional port with no path, such as https://app.example.com: '" + o + "'");
      }
    }
    for (String h : allowedHeaders) {
      if (!TOKEN.matcher(h).matches()) {
        throw new IllegalArgumentException("not a header name: '" + h + "'");
      }
    }
    for (String h : exposedHeaders) {
      if (!TOKEN.matcher(h).matches()) {
        throw new IllegalArgumentException("not a header name: '" + h + "'");
      }
    }
    if (maxAge.isNegative()) {
      throw new IllegalArgumentException("the preflight max age must not be negative");
    }
  }

  /**
   * A policy for some origins with the default methods and headers and a ten-minute preflight cache.
   *
   * @param origins the allowed origins
   * @return the policy
   */
  public static CorsPolicy allowing(String... origins) {
    return new CorsPolicy(List.of(origins), DEFAULT_METHODS, DEFAULT_HEADERS, DEFAULT_EXPOSED, Duration.ofMinutes(10));
  }

  boolean allowsOrigin(String origin) {
    return allowedOrigins.contains("*") || allowedOrigins.contains(origin);
  }

  boolean allowsHeader(String header) {
    String h = header.trim().toLowerCase(Locale.ROOT);
    for (String allowed : allowedHeaders) {
      if (allowed.toLowerCase(Locale.ROOT).equals(h)) {
        return true;
      }
    }
    return false;
  }
}
