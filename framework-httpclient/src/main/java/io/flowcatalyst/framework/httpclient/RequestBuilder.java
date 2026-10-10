package io.flowcatalyst.framework.httpclient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;

/** One request being described; {@link #send()} runs it. Not thread-safe; use once. */
public final class RequestBuilder {

  final ServiceClient client;
  final RequestMethod method;
  final PathTemplate path;
  final Map<String, String> pathParams = new LinkedHashMap<>();
  final List<String[]> query = new ArrayList<>();
  final Map<String, List<String>> headers = new LinkedHashMap<>();
  byte @Nullable [] body;
  @Nullable Boolean retryable;
  @Nullable Duration timeout;

  RequestBuilder(ServiceClient client, RequestMethod method, String path) {
    this.client = client;
    this.method = method;
    this.path = new PathTemplate(path);
  }

  /**
   * Fills a {@code {name}} placeholder; the value is percent-encoded as one segment.
   *
   * @param name the placeholder
   * @param value not empty, not {@code .} or {@code ..}
   * @return this builder
   */
  public RequestBuilder pathParam(String name, String value) {
    if (!path.names().contains(name)) {
      throw new IllegalArgumentException("the path " + path.text() + " has no placeholder {" + name + "}");
    }
    PathTemplate.encodeSegment(value);
    pathParams.put(name, value);
    return this;
  }

  /**
   * Adds a query parameter (repeatable); name and value are percent-encoded.
   *
   * @param name the name
   * @param value the value
   * @return this builder
   */
  public RequestBuilder query(String name, String value) {
    if (name.isEmpty()) {
      throw new IllegalArgumentException("a query parameter needs a name");
    }
    query.add(new String[] {name, value});
    return this;
  }

  /**
   * Adds a header.
   *
   * @param name a token; not Host, Content-Length, Transfer-Encoding or Connection
   * @param value without CR, LF or NUL
   * @return this builder
   */
  public RequestBuilder header(String name, String value) {
    Headers.check(name, value);
    headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    return this;
  }

  /**
   * Sends a JSON body ({@code Content-Type: application/json}) written with the service's mapper.
   *
   * @param value a record, map, list or scalar
   * @return this builder
   */
  public RequestBuilder json(Object value) {
    this.body = client.options().mapper().writeValueAsBytes(value);
    return contentType("application/json");
  }

  /**
   * Sends raw bytes.
   *
   * @param bytes the body
   * @param contentType its content type
   * @return this builder
   */
  public RequestBuilder body(byte[] bytes, String contentType) {
    this.body = bytes.clone();
    return contentType(contentType);
  }

  /**
   * Sends a form body ({@code application/x-www-form-urlencoded}).
   *
   * @param fields names to values, in order
   * @return this builder
   */
  public RequestBuilder form(Map<String, String> fields) {
    this.body = formBody(fields).getBytes(StandardCharsets.UTF_8);
    return contentType("application/x-www-form-urlencoded");
  }

  static String formBody(Map<String, String> fields) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : fields.entrySet()) {
      if (sb.length() > 0) {
        sb.append('&');
      }
      sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
    }
    return sb.toString();
  }

  private RequestBuilder contentType(String value) {
    Headers.checkValue("Content-Type", value);
    headers.put("Content-Type", new ArrayList<>(List.of(value)));
    return this;
  }

  /**
   * Sets {@code Idempotency-Key}, which also makes the request retryable.
   *
   * @param key 1 to 255 printable characters
   * @return this builder
   */
  public RequestBuilder idempotencyKey(String key) {
    if (key.isBlank() || key.length() > 255) {
      throw new IllegalArgumentException("an idempotency key is 1 to 255 characters");
    }
    Headers.checkValue("Idempotency-Key", key);
    headers.put("Idempotency-Key", new ArrayList<>(List.of(key)));
    return this;
  }

  /**
   * Overrides whether the request may be retried: true for a non-idempotent request you know is
   * safe to repeat, false to send an idempotent one only once.
   *
   * @param value whether it may be retried
   * @return this builder
   */
  public RequestBuilder retryable(boolean value) {
    this.retryable = value;
    return this;
  }

  /**
   * Overrides the service's per-attempt timeout for this request.
   *
   * @param value positive
   * @return this builder
   */
  public RequestBuilder timeout(Duration value) {
    if (!value.isPositive()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    this.timeout = value;
    return this;
  }

  /**
   * Sends the request, with retries and auth as configured.
   *
   * @return the response, whatever its status
   * @throws HttpClientException when there is no response
   */
  public ClientResponse send() {
    return client.execute(this);
  }

  /**
   * Sends the request and decodes a 2xx body.
   *
   * @param <T> the type
   * @param type the type
   * @return the decoded body
   * @throws UnexpectedStatusException for a status that is not 2xx
   * @throws HttpClientException when there is no response or the body is not that JSON
   */
  public <T> T sendJson(Class<T> type) {
    return success(send()).json(type);
  }

  /**
   * Sends the request and decodes a 2xx body of a generic type.
   *
   * @param <T> the type
   * @param type the type
   * @return the decoded body
   * @throws UnexpectedStatusException for a status that is not 2xx
   */
  public <T> T sendJson(TypeReference<T> type) {
    return success(send()).json(type);
  }

  private ClientResponse success(ClientResponse response) {
    if (!response.isSuccess()) {
      throw new UnexpectedStatusException(response.status(), response.errorCode(), description());
    }
    return response;
  }

  String description() {
    return client.name() + " " + method + " " + path.text();
  }
}
