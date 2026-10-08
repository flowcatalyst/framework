package io.flowcatalyst.framework.http;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a server adapter knows about an incoming request, with no server types. Turned into a
 * {@link Request} by {@link Request#of}.
 *
 * @param method the method
 * @param rawPath the path as sent, still percent-encoded, without the query
 * @param rawQuery the query string as sent without the {@code ?}, or null
 * @param headers header names to values; names are lowercased on construction
 * @param body the body stream (empty stream when there is none)
 * @param contentLength the declared body length, or -1 when not declared
 * @param maxBodyBytes the largest body to accept
 * @param protocol the protocol, for example {@code HTTP/1.1}
 */
public record RawRequest(
    Method method,
    String rawPath,
    @Nullable String rawQuery,
    Map<String, List<String>> headers,
    InputStream body,
    long contentLength,
    long maxBodyBytes,
    String protocol) {

  /** Lowercases the header names and copies the map. */
  public RawRequest {
    Map<String, List<String>> copy = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> e : headers.entrySet()) {
      copy.merge(
          e.getKey().toLowerCase(Locale.ROOT),
          List.copyOf(e.getValue()),
          (a, b) -> {
            List<String> merged = new java.util.ArrayList<>(a);
            merged.addAll(b);
            return List.copyOf(merged);
          });
    }
    headers = Map.copyOf(copy);
  }
}
