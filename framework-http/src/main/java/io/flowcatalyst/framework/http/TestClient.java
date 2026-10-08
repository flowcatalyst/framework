package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.core.MonotonicClock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;

/**
 * Runs requests through the full pipeline in-process, with no socket. For the framework's own
 * tests and for applications' tests.
 *
 * <pre>{@code
 * TestClient client = TestClient.of(routes);
 * TestResponse r = client.get("/orders/7").header("X-Test", "1").send();
 * assertThat(r.status()).isEqualTo(200);
 * }</pre>
 */
public final class TestClient {

  private final Dispatcher dispatcher;
  private final long maxBodyBytes;

  private TestClient(Dispatcher dispatcher, long maxBodyBytes) {
    this.dispatcher = dispatcher;
    this.maxBodyBytes = maxBodyBytes;
  }

  /**
   * A client for the routes, with no context resolver. Seals the routes.
   *
   * @param routes the routes
   * @return the client
   * @throws io.flowcatalyst.framework.core.StartupException when the routes are invalid
   */
  public static TestClient of(Routes routes) {
    return of(routes, null);
  }

  /**
   * A client for the routes. Seals the routes.
   *
   * @param routes the routes
   * @param resolver the context resolver, or null
   * @return the client
   * @throws io.flowcatalyst.framework.core.StartupException when the routes are invalid
   */
  public static TestClient of(Routes routes, @Nullable ContextResolver resolver) {
    return new TestClient(routes.compile(resolver, MonotonicClock.system()), 1_048_576L);
  }

  /**
   * A client with another body limit.
   *
   * @param bytes the limit
   * @return a client sharing the same routes
   */
  public TestClient withMaxBodyBytes(long bytes) {
    return new TestClient(dispatcher, bytes);
  }

  /**
   * Starts a GET request.
   *
   * @param target the path, optionally with a query string
   * @return the request builder
   */
  public Call get(String target) {
    return request(Method.GET, target);
  }

  /**
   * Starts a POST request.
   *
   * @param target the path, optionally with a query string
   * @return the request builder
   */
  public Call post(String target) {
    return request(Method.POST, target);
  }

  /**
   * Starts a PUT request.
   *
   * @param target the path, optionally with a query string
   * @return the request builder
   */
  public Call put(String target) {
    return request(Method.PUT, target);
  }

  /**
   * Starts a PATCH request.
   *
   * @param target the path, optionally with a query string
   * @return the request builder
   */
  public Call patch(String target) {
    return request(Method.PATCH, target);
  }

  /**
   * Starts a DELETE request.
   *
   * @param target the path, optionally with a query string
   * @return the request builder
   */
  public Call delete(String target) {
    return request(Method.DELETE, target);
  }

  /**
   * Starts a request.
   *
   * @param method the method
   * @param target the path, optionally with a query string
   * @return the request builder
   */
  public Call request(Method method, String target) {
    return new Call(method, target);
  }

  /** A request being built. */
  public final class Call {
    private final Method method;
    private final String target;
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private byte[] body = new byte[0];
    private String protocol = "HTTP/1.1";
    private long declaredLength = -2;

    private Call(Method method, String target) {
      this.method = method;
      this.target = target;
    }

    /**
     * Adds a header.
     *
     * @param name the name
     * @param value the value
     * @return this call
     */
    public Call header(String name, String value) {
      headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
      return this;
    }

    /**
     * Sets a text body.
     *
     * @param text the body
     * @return this call
     */
    public Call body(String text) {
      this.body = text.getBytes(StandardCharsets.UTF_8);
      return this;
    }

    /**
     * Sets a JSON text body and {@code Content-Type: application/json}.
     *
     * @param json the JSON text
     * @return this call
     */
    public Call jsonBody(String json) {
      header("Content-Type", "application/json");
      return body(json);
    }

    /**
     * Encodes a value as the JSON body and sets {@code Content-Type: application/json}.
     *
     * @param value a record or map
     * @return this call
     */
    public Call jsonBody(Object value) {
      try {
        return jsonBody(Json.mapper().writeValueAsString(value));
      } catch (JacksonException e) {
        throw new IllegalArgumentException(e);
      }
    }

    /**
     * Sets the protocol the request claims.
     *
     * @param value for example {@code HTTP/1.0}
     * @return this call
     */
    public Call protocol(String value) {
      this.protocol = value;
      return this;
    }

    /**
     * Declares a {@code Content-Length} that differs from the body, for limit tests.
     *
     * @param length the declared length
     * @return this call
     */
    public Call declaredLength(long length) {
      this.declaredLength = length;
      return this;
    }

    /**
     * Sends the request and reads the whole response.
     *
     * @return the response
     */
    public TestResponse send() {
      int q = target.indexOf('?');
      String path = q < 0 ? target : target.substring(0, q);
      String query = q < 0 ? null : target.substring(q + 1);
      RawRequest raw =
          new RawRequest(
              method,
              path,
              query,
              headers,
              new ByteArrayInputStream(body),
              declaredLength == -2 ? body.length : declaredLength,
              maxBodyBytes,
              protocol);
      Response response = dispatcher.handle(Request.of(raw));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try {
        response.writeBody(out);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return new TestResponse(response.status(), response.headers(), out.toByteArray());
    }
  }
}
