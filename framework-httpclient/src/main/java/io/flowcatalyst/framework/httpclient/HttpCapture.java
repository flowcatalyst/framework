package io.flowcatalyst.framework.httpclient;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A per-call capture scope for outbound requests. Work that wants to see what its HTTP calls sent and
 * received binds {@link #CURRENT} around that work; every attempt a {@link ServiceClient} makes while it
 * is bound is handed to the sink. A caller that does not bind it pays one {@code isBound()} check per
 * attempt and nothing else.
 *
 * <pre>{@code
 * ScopedValue.where(HttpCapture.CURRENT, exchange -> record(exchange))
 *     .run(() -> client.get("/x").send());
 * }</pre>
 *
 * <p>The sink runs on the calling thread after the attempt has finished. It must be quick and must not
 * call the same client. Anything it throws is logged and swallowed: capturing never changes a request,
 * its retries or its response.
 */
public final class HttpCapture {

  /** Bound around work whose outbound calls should be captured. */
  public static final ScopedValue<Sink> CURRENT = ScopedValue.newInstance();

  /** The value a sensitive header's value is replaced with. */
  public static final String REDACTED = "[redacted]";

  /** Lower-case fragments that make a header name sensitive. */
  private static final List<String> SENSITIVE =
      List.of("auth", "key", "token", "secret", "password", "signature", "cookie", "session");

  private HttpCapture() {}

  /** Receives each captured attempt. */
  @FunctionalInterface
  public interface Sink {
    /**
     * One attempt has finished.
     *
     * @param exchange what was sent and what came back
     */
    void captured(Exchange exchange);
  }

  /**
   * One attempt: what was sent and what came back. Header values that a sensitive name carries are
   * already {@code "[redacted]"}. The header maps are never null and cannot be modified. For a transport
   * failure (no response) {@code status} is 0, {@code responseHeaders} is empty, {@code responseBody} is
   * null and {@code failure} says why. Response header names are lower case; request header names are
   * as sent. The byte arrays are copies owned by the exchange; do not modify them.
   *
   * @param service the client's name
   * @param method the method
   * @param url the full target, query included
   * @param requestHeaders the headers as sent, auth included, sensitive values redacted
   * @param requestBody the body sent, null when there was none
   * @param status the response status, 0 when there was no response
   * @param responseHeaders the response headers, sensitive values redacted
   * @param responseBody the response body as received, null when there was no response
   * @param failure why there was no response, null when there was one
   * @param nanos how long the attempt took
   * @param attempt 1 for the first attempt, 2 for the first retry, and so on
   */
  public record Exchange(
      String service,
      RequestMethod method,
      URI url,
      Map<String, List<String>> requestHeaders,
      byte @Nullable [] requestBody,
      int status,
      Map<String, List<String>> responseHeaders,
      byte @Nullable [] responseBody,
      HttpClientException.@Nullable Kind failure,
      long nanos,
      int attempt) {}

  /**
   * Whether a header name is sensitive: it contains, ignoring case, {@code auth}, {@code key},
   * {@code token}, {@code secret}, {@code password}, {@code signature}, {@code cookie} or
   * {@code session}.
   *
   * @param name the header name
   * @return true when its value must not be kept
   */
  public static boolean isSensitiveHeader(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    for (String fragment : SENSITIVE) {
      if (lower.contains(fragment)) {
        return true;
      }
    }
    return false;
  }

  /**
   * A copy of the headers with every value of a sensitive name (see {@link #isSensitiveHeader}) replaced
   * by {@code "[redacted]"} (one entry per original value, so the count survives). Public so that code
   * which handles headers it did not get from a {@link ServiceClient} applies the same rule.
   *
   * @param headers names to values; not modified
   * @return an unmodifiable copy in the same order, empty for empty input
   */
  public static Map<String, List<String>> redactHeaders(Map<String, List<String>> headers) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    headers.forEach(
        (name, values) ->
            out.put(
                name,
                isSensitiveHeader(name)
                    ? values.stream().map(v -> REDACTED).toList()
                    : List.copyOf(values)));
    return java.util.Collections.unmodifiableMap(out);
  }
}
