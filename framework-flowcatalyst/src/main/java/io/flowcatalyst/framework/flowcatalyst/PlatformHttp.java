package io.flowcatalyst.framework.flowcatalyst;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The plain HTTP path to the platform for the admin reads and writes whose SDK methods are not in
 * the SDK version this framework pins (interim, see admin contract section 12): one request with
 * the service account's bearer token, JSON in and out, one retry after a {@code 401} with a fresh
 * token. It logs nothing: a request or response body can carry an invitation link.
 */
final class PlatformHttp {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** What the platform says about a client it will not invite into (a 403 with a reason, not a missing permission). */
  static final String CLIENT_REFUSAL = "client is not available to this application";

  private final HttpClient http;
  private final String base;
  private final Duration timeout;
  private final Supplier<String> token;
  private final Runnable clearToken;

  PlatformHttp(URI baseUrl, Duration timeout, Supplier<String> token, Runnable clearToken) {
    String text = baseUrl.toString();
    this.base = text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    this.timeout = timeout;
    this.token = token;
    this.clearToken = clearToken;
    this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  /** Percent-encodes one path segment. */
  static String enc(String segment) {
    return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /**
   * Calls the platform.
   *
   * @param method the HTTP method
   * @param path the path (segments already encoded)
   * @param query query parameters; null values are left out
   * @param body a JSON body, or null for none
   * @return the parsed JSON answer, or a missing node for an empty one
   * @throws PlatformException when the platform refuses or cannot be reached
   */
  JsonNode call(String method, String path, Map<String, @Nullable String> query, @Nullable Object body) {
    return call(method, path, query, body, Map.of());
  }

  /**
   * Calls the platform with extra request headers.
   *
   * @param headers extra headers (already safe to send); never {@code Authorization}
   * @see #call(String, String, Map, Object)
   */
  JsonNode call(String method, String path, Map<String, @Nullable String> query, @Nullable Object body, Map<String, String> headers) {
    for (int attempt = 0; ; attempt++) {
      HttpResponse<String> response = send(method, path, query, body, headers);
      if (response.statusCode() == 401 && attempt == 0) {
        clearToken.run();
        continue;
      }
      return interpret(response);
    }
  }

  private HttpResponse<String> send(
      String method, String path, Map<String, @Nullable String> query, @Nullable Object body, Map<String, String> headers) {
    StringBuilder uri = new StringBuilder(base).append(path);
    char sep = '?';
    for (Map.Entry<String, @Nullable String> e : query.entrySet()) {
      if (e.getValue() != null) {
        uri.append(sep).append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        sep = '&';
      }
    }
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(uri.toString())).timeout(timeout).header("Authorization", "Bearer " + token.get()).header("Accept", "application/json");
    for (Map.Entry<String, String> h : headers.entrySet()) {
      request.header(h.getKey(), h.getValue());
    }
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    }
    try {
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      // The message of an I/O failure can name hosts; it is the exception's, never a body.
      throw new PlatformException("the platform could not be reached", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PlatformException("the call to the platform was interrupted", e);
    }
  }

  private static JsonNode interpret(HttpResponse<String> response) {
    int status = response.statusCode();
    String text = response.body() == null ? "" : response.body();
    if (status >= 200 && status < 300) {
      if (text.isBlank()) {
        return JSON.missingNode();
      }
      try {
        return JSON.readTree(text);
      } catch (RuntimeException e) {
        throw new PlatformException("the platform answered " + status + " with something that is not JSON", e);
      }
    }
    String code = null;
    String message = null;
    try {
      JsonNode tree = JSON.readTree(text);
      JsonNode error = tree.get("error");
      if (error != null && error.isString()) {
        code = LogSafe.clean(error.asString(), 80);
      } else if (error != null && error.isObject() && error.has("code") && error.get("code").isString()) {
        code = LogSafe.clean(error.get("code").asString(), 80);
      }
      JsonNode m = tree.get("message");
      if (m != null && m.isString()) {
        message = LogSafe.clean(m.asString(), 300);
      }
    } catch (RuntimeException ignored) {
      // not JSON: the status alone decides
    }
    throw new PlatformException(kindOf(status, code, message), status, code, message);
  }

  /** What an answer means (see {@link PlatformException.Kind}). */
  static PlatformException.Kind kindOf(int status, @Nullable String code, @Nullable String message) {
    if (status == 404) {
      return PlatformException.Kind.NOT_FOUND;
    }
    if (status == 403) {
      boolean ownReason =
          (code != null && !code.equals("FORBIDDEN")) || (message != null && message.equalsIgnoreCase(CLIENT_REFUSAL));
      return ownReason ? PlatformException.Kind.REJECTED : PlatformException.Kind.FORBIDDEN;
    }
    if (status == 400 || status == 409 || status == 422) {
      return PlatformException.Kind.REJECTED;
    }
    if (status == 500 && "INVITE_DELIVERY_FAILED".equals(code)) {
      return PlatformException.Kind.REJECTED;
    }
    return PlatformException.Kind.OTHER;
  }
}
