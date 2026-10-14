package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.PrometheusText;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The Prometheus scrape endpoint (background-and-metrics contract, section 3): the registry's
 * snapshot in the text exposition format. The Jetty module mounts it when {@code
 * http.metrics.enabled} is true; other servers can register {@link #handler} themselves.
 */
public final class MetricsEndpoint {

  private static final String BEARER = "Bearer ";

  private MetricsEndpoint() {}

  /**
   * The handler.
   *
   * @param metrics the registry to export
   * @param bearerToken when not null, requests must carry {@code Authorization: Bearer <token>};
   *     compared as SHA-256 digests in constant time
   * @return the handler: 200 with the exposition text, or 401 {@code UNAUTHENTICATED}
   */
  public static Handler handler(Metrics metrics, @Nullable Secret bearerToken) {
    byte[] expected = bearerToken == null ? null : sha256(bearerToken.reveal());
    return request -> {
      if (expected != null && !authorized(request, expected)) {
        return HttpErrors.unauthenticated("a bearer token is required");
      }
      String body = PrometheusText.write(metrics.snapshot());
      return Response.bytes(200, PrometheusText.CONTENT_TYPE, body.getBytes(StandardCharsets.UTF_8));
    };
  }

  private static boolean authorized(Request request, byte[] expected) {
    List<String> values = request.headers("Authorization");
    if (values.size() != 1) {
      return false;
    }
    String value = values.get(0);
    if (!value.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return false;
    }
    return MessageDigest.isEqual(expected, sha256(value.substring(BEARER.length())));
  }

  private static byte[] sha256(String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
