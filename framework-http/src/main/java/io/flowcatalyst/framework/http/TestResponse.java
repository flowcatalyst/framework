package io.flowcatalyst.framework.http;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * A response as the {@link TestClient} saw it.
 *
 * @param status the status
 * @param headers the header lines
 * @param bytes the body (a streamed body is read to its end)
 */
public record TestResponse(int status, List<Response.Header> headers, byte[] bytes) {

  /**
   * The first value of a header.
   *
   * @param name the name, case-insensitive
   * @return the value, or null
   */
  public @Nullable String header(String name) {
    for (Response.Header h : headers) {
      if (h.name().equalsIgnoreCase(name)) {
        return h.value();
      }
    }
    return null;
  }

  /**
   * Every value of a header.
   *
   * @param name the name, case-insensitive
   * @return the values
   */
  public List<String> headerValues(String name) {
    return headers.stream().filter(h -> h.name().equalsIgnoreCase(name)).map(Response.Header::value).toList();
  }

  /**
   * The body as UTF-8 text.
   *
   * @return the text
   */
  public String body() {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  /**
   * Decodes the body as JSON.
   *
   * @param <T> the type
   * @param type a record class, or {@code JsonNode}, or {@code Map}
   * @return the value
   * @throws IllegalStateException when the body is not valid JSON for the type
   */
  public <T> T json(Class<T> type) {
    try {
      return Json.mapper().readValue(bytes, type);
    } catch (JacksonException e) {
      throw new IllegalStateException("response body is not the expected JSON: " + body(), e);
    }
  }

  /**
   * Parses the body as a JSON tree.
   *
   * @return the tree
   */
  public JsonNode tree() {
    return json(JsonNode.class);
  }
}
