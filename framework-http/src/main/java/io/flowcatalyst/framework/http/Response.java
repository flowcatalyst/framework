package io.flowcatalyst.framework.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * An HTTP response as a value: status, headers, body. Built with the static factories and the
 * fluent {@link #header}; immutable, each call returns a new response.
 */
public final class Response {

  private static final Logger LOG = LoggerFactory.getLogger(Response.class);

  private static final String JSON = "application/json";

  /**
   * A header line.
   *
   * @param name the header name
   * @param value the header value
   */
  public record Header(String name, String value) {}

  /** The body of a response. */
  public sealed interface Body {

    /** No body. */
    record Empty() implements Body {}

    /**
     * A body held in memory.
     *
     * @param content the bytes; do not modify
     */
    @SuppressWarnings("ArrayRecordComponent")
    record Bytes(byte[] content) implements Body {}

    /**
     * A body written by a callback when the response is sent.
     *
     * @param writer the callback
     */
    record Stream(StreamBody writer) implements Body {}
  }

  private final int status;
  private final List<Header> headers;
  private final Body body;

  private Response(int status, List<Header> headers, Body body) {
    if (status < 100 || status > 599) {
      throw new IllegalArgumentException("invalid HTTP status " + status);
    }
    this.status = status;
    this.headers = headers;
    this.body = body;
  }

  private static Response of(int status, @Nullable String contentType, Body body) {
    List<Header> headers = contentType == null ? List.of() : List.of(checked("Content-Type", contentType));
    return new Response(status, headers, body);
  }

  // ---- factories ---------------------------------------------------------------------------

  /**
   * A 200 response with a JSON body. A {@code String} is encoded as a JSON string; use
   * {@link #text} for plain text.
   *
   * @param body a record, map, collection or {@code JsonNode}
   * @return the response
   * @throws IllegalArgumentException when the body is not one of those
   */
  public static Response ok(Object body) {
    return json(200, body);
  }

  /**
   * A response with a JSON body ({@code application/json}).
   *
   * @param status the status
   * @param body a record, map, collection or {@code JsonNode}
   * @return the response
   * @throws IllegalArgumentException when the body is not one of those, or cannot be encoded
   */
  public static Response json(int status, Object body) {
    if (!(body instanceof Record || body instanceof Map || body instanceof Collection || body instanceof JsonNode)) {
      throw new IllegalArgumentException(
          "Response.json takes records, maps, collections or JsonNode, not " + body.getClass().getName());
    }
    try {
      return of(status, JSON, new Body.Bytes(Json.mapper().writeValueAsBytes(body)));
    } catch (JacksonException e) {
      throw new IllegalArgumentException("cannot encode the response body as JSON", e);
    }
  }

  /**
   * A plain text response ({@code text/plain; charset=utf-8}).
   *
   * @param status the status
   * @param text the text
   * @return the response
   */
  public static Response text(int status, String text) {
    return of(status, "text/plain; charset=utf-8", new Body.Bytes(text.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * An HTML response ({@code text/html; charset=utf-8}).
   *
   * @param status the status
   * @param html the markup
   * @return the response
   */
  public static Response html(int status, String html) {
    return of(status, "text/html; charset=utf-8", new Body.Bytes(html.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * A {@code 302 Found} with a {@code Location} header and no body.
   *
   * @param location the target, for example {@code /auth/login?returnTo=%2F} or an absolute URL
   * @return the response
   * @throws IllegalArgumentException when the location contains CR or LF
   */
  public static Response redirect(String location) {
    return of(302, null, new Body.Empty()).header("Location", location);
  }

  /**
   * A 204 response with no body.
   *
   * @return the response
   */
  public static Response noContent() {
    return of(204, null, new Body.Empty());
  }

  /**
   * A response with raw bytes.
   *
   * @param status the status
   * @param contentType the content type
   * @param bytes the body
   * @return the response
   */
  public static Response bytes(int status, String contentType, byte[] bytes) {
    return of(status, contentType, new Body.Bytes(bytes.clone()));
  }

  /** Raw bytes the caller will never modify (the SPA's index); not copied. */
  static Response shared(int status, String contentType, byte[] bytes) {
    return of(status, contentType, new Body.Bytes(bytes));
  }

  /** A response with no body and no content type, such as {@code 304 Not Modified}. */
  static Response empty(int status) {
    return of(status, null, new Body.Empty());
  }

  /**
   * A response whose body is written by a callback, for example a server-sent event stream.
   *
   * @param status the status
   * @param contentType the content type
   * @param body the callback
   * @return the response
   */
  public static Response stream(int status, String contentType, StreamBody body) {
    return of(status, contentType, new Body.Stream(body));
  }

  // ---- reading -----------------------------------------------------------------------------

  /**
   * The status.
   *
   * @return the HTTP status
   */
  public int status() {
    return status;
  }

  /**
   * All header lines.
   *
   * @return the headers in the order added
   */
  public List<Header> headers() {
    return headers;
  }

  /**
   * The first value of a header.
   *
   * @param name the name, case-insensitive
   * @return the value, or null
   */
  public @Nullable String header(String name) {
    for (Header h : headers) {
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
   * @return the values, possibly empty
   */
  public List<String> headerValues(String name) {
    List<String> out = new ArrayList<>();
    for (Header h : headers) {
      if (h.name().equalsIgnoreCase(name)) {
        out.add(h.value());
      }
    }
    return out;
  }

  /**
   * The content type.
   *
   * @return the {@code Content-Type} value, or null when there is none
   */
  public @Nullable String contentType() {
    return header("Content-Type");
  }

  /**
   * The body.
   *
   * @return the body
   */
  public Body body() {
    return body;
  }

  // ---- building ----------------------------------------------------------------------------

  /**
   * Sets a header, replacing any header of that name.
   *
   * @param name the name
   * @param value the value
   * @return a copy with the header set
   * @throws IllegalArgumentException when the name or value contains CR or LF, or the name is
   *     empty
   */
  public Response header(String name, String value) {
    Header h = checked(name, value);
    List<Header> list = new ArrayList<>(headers.size() + 1);
    for (Header existing : headers) {
      if (!existing.name().equalsIgnoreCase(name)) {
        list.add(existing);
      }
    }
    list.add(h);
    return new Response(status, List.copyOf(list), body);
  }

  /**
   * Adds a header line, keeping any of that name (for example {@code Set-Cookie}).
   *
   * @param name the name
   * @param value the value
   * @return a copy with the header added
   * @throws IllegalArgumentException when the name or value contains CR or LF, or the name is
   *     empty
   */
  public Response addHeader(String name, String value) {
    List<Header> list = new ArrayList<>(headers);
    list.add(checked(name, value));
    return new Response(status, List.copyOf(list), body);
  }

  /**
   * A copy without a body, for answering HEAD: the same headers, and the {@code Content-Length}
   * the body would have had when it is held in memory (a streamed body has no known length).
   *
   * @return the response with an empty body
   */
  Response withoutBody() {
    Response out = new Response(status, headers, new Body.Empty());
    if (body instanceof Body.Bytes b && header("Content-Length") == null) {
      out = out.header("Content-Length", Integer.toString(b.content().length));
    }
    return out;
  }

  private static Header checked(String name, String value) {
    if (name.isEmpty()) {
      throw new IllegalArgumentException("header name must not be empty");
    }
    for (String s : new String[] {name, value}) {
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '\r' || c == '\n') {
          throw new IllegalArgumentException("header names and values must not contain CR or LF");
        }
      }
    }
    if (name.chars().anyMatch(c -> c <= ' ' || c == ':' || c > 126)) {
      throw new IllegalArgumentException("invalid header name");
    }
    return new Header(name, value);
  }

  // ---- sending -----------------------------------------------------------------------------

  /**
   * Writes the body to the connection. For a stream body this runs the callback; a
   * {@link ClientDisconnectedException} is logged at DEBUG and ends the body, and the method
   * returns false.
   *
   * @param out the connection's output stream
   * @return false when the client disconnected, true otherwise
   * @throws IOException on a write failure
   */
  public boolean writeBody(OutputStream out) throws IOException {
    switch (body) {
      case Body.Empty e -> {
        return true;
      }
      case Body.Bytes b -> {
        out.write(b.content());
        return true;
      }
      case Body.Stream s -> {
        try {
          s.writer().writeTo(out);
          return true;
        } catch (ClientDisconnectedException e) {
          LOG.debug("client disconnected during a streamed response");
          return false;
        }
      }
    }
  }

  @Override
  public String toString() {
    return "Response[" + status + " " + (contentType() == null ? "" : contentType().toLowerCase(Locale.ROOT)) + "]";
  }
}
