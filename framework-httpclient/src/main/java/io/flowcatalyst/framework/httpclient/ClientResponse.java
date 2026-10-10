package io.flowcatalyst.framework.httpclient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A response of any status; {@link ServiceClient} never throws for a status. */
public final class ClientResponse {

  private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");

  private final int status;
  private final Map<String, List<String>> headers;
  private final byte[] body;
  private final ObjectMapper mapper;
  private final String description;

  ClientResponse(
      int status, Map<String, List<String>> headers, byte[] body, ObjectMapper mapper, String description) {
    this.status = status;
    this.headers = headers;
    this.body = body;
    this.mapper = mapper;
    this.description = description;
  }

  /**
   * The status.
   *
   * @return the HTTP status
   */
  public int status() {
    return status;
  }

  /**
   * Whether the status is 2xx.
   *
   * @return true for 200 to 299
   */
  public boolean isSuccess() {
    return status >= 200 && status < 300;
  }

  /**
   * The first value of a header.
   *
   * @param name the name, any case
   * @return the value, or null
   */
  public @Nullable String header(String name) {
    List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  /**
   * Every value of a header.
   *
   * @param name the name, any case
   * @return the values, possibly empty
   */
  public List<String> headers(String name) {
    return List.copyOf(headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of()));
  }

  /**
   * The body.
   *
   * @return a copy of the bytes
   */
  public byte[] bodyBytes() {
    return body.clone();
  }

  /**
   * The body as UTF-8 text.
   *
   * @return the text
   */
  public String bodyText() {
    return new String(body, StandardCharsets.UTF_8);
  }

  /**
   * The body decoded as JSON.
   *
   * @param <T> the type
   * @param type a record, map or list type
   * @return the value
   * @throws HttpClientException of kind {@code DECODE} when it is not that JSON; the message never
   *     contains the body
   */
  public <T> T json(Class<T> type) {
    try {
      return mapper.readValue(body, type);
    } catch (JacksonException e) {
      throw decodeFailure(type.getSimpleName());
    }
  }

  /**
   * The body decoded as JSON of a generic type.
   *
   * @param <T> the type
   * @param type the type
   * @return the value
   * @throws HttpClientException of kind {@code DECODE}
   */
  public <T> T json(TypeReference<T> type) {
    try {
      return mapper.readValue(body, type);
    } catch (JacksonException e) {
      throw decodeFailure(type.getType().getTypeName());
    }
  }

  // The parser's own message can quote the body, so the cause is not attached.
  private HttpClientException decodeFailure(String what) {
    return new HttpClientException(
        HttpClientException.Kind.DECODE,
        description + ": the " + status + " response body is not JSON for " + what,
        null);
  }

  /** The {@code error} code when the body is the platform's error envelope. */
  @Nullable String errorCode() {
    try {
      JsonNode tree = mapper.readTree(body);
      JsonNode error = tree == null ? null : tree.get("error");
      if (error != null && error.isString() && ERROR_CODE.matcher(error.asString()).matches()) {
        return error.asString();
      }
    } catch (JacksonException e) {
      // not an envelope
    }
    return null;
  }

  @Override
  public String toString() {
    return "ClientResponse[" + status + ", " + body.length + " bytes]";
  }
}
