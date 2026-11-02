package io.flowcatalyst.framework.http.datastar;

import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Json;
import io.flowcatalyst.framework.http.Method;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.ValidationException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Datastar 1.0 server adapter: reads the client's signals and starts event streams.
 *
 * <pre>{@code
 * routes.get("/clock", request -> Datastar.stream(request, sse -> {
 *   while (true) {
 *     sse.patchElements("<div id=\"t\">" + Instant.now() + "</div>");
 *     Thread.sleep(1000);
 *   }
 * }));
 * }</pre>
 */
public final class Datastar {

  private Datastar() {}

  /** The largest signals body read: 1 MiB (http contract section 10); a larger one is refused 413. */
  public static final int MAX_SIGNAL_BYTES = 1 << 20;

  /** Produces the events of one stream. */
  @FunctionalInterface
  public interface Events {

    /**
     * Writes events. When it returns, the stream ends. A
     * {@link io.flowcatalyst.framework.http.ClientDisconnectedException} may propagate.
     *
     * @param sse the generator
     * @throws Exception any failure; it is logged and ends the stream
     */
    void run(ServerSentEventGenerator sse) throws Exception;
  }

  private static boolean signalsInQuery(Method method) {
    // Datastar 1.0.4 sends a body only for the other methods (!["GET","DELETE"].includes(method)).
    return method == Method.GET || method == Method.HEAD || method == Method.DELETE;
  }

  /**
   * The client's signals: for {@code GET}, {@code HEAD} and {@code DELETE} the JSON in the {@code
   * datastar} query parameter, otherwise the JSON request body (at most {@link #MAX_SIGNAL_BYTES}).
   *
   * @param request the request
   * @return the signals tree
   * @throws ValidationException when absent or not valid JSON
   * @throws HttpException 413 {@code PAYLOAD_TOO_LARGE} when the body is above 1 MiB
   */
  public static JsonNode signals(Request request) {
    String field;
    JsonNode node;
    try {
      if (signalsInQuery(request.method())) {
        field = "datastar";
        String raw = request.query("datastar");
        if (raw == null) {
          throw ValidationException.of(field, "REQUIRED", "the datastar query parameter is required");
        }
        node = Json.mapper().readTree(raw);
      } else {
        field = "body";
        if (request.contentLength() > MAX_SIGNAL_BYTES) {
          throw tooLarge();
        }
        byte[] body = request.bodyBytes();
        if (body.length > MAX_SIGNAL_BYTES) {
          throw tooLarge();
        }
        node = Json.mapper().readTree(body);
      }
    } catch (JacksonException e) {
      throw ValidationException.of(
          signalsInQuery(request.method()) ? "datastar" : "body",
          "INVALID_JSON",
          "signals are not valid JSON");
    }
    if (node == null || node.isMissingNode() || node.isNull()) {
      throw ValidationException.of(field, "INVALID_JSON", "signals are required");
    }
    return node;
  }

  private static HttpException tooLarge() {
    return new HttpException(413, "PAYLOAD_TOO_LARGE", "signals are larger than 1 MiB");
  }

  /**
   * The client's signals decoded into a record.
   *
   * @param <T> the record type
   * @param request the request
   * @param type the record class
   * @return the decoded signals
   * @throws ValidationException when absent, not valid JSON, or not decodable into the type
   * @throws HttpException 413 {@code PAYLOAD_TOO_LARGE} when the body is above 1 MiB
   */
  public static <T> T signals(Request request, Class<T> type) {
    JsonNode node = signals(request);
    try {
      return Json.mapper().treeToValue(node, type);
    } catch (JacksonException e) {
      throw ValidationException.of("signals", "INVALID_JSON", "signals do not match the expected shape");
    }
  }

  /**
   * Starts an event stream: a {@code text/event-stream} response whose body is produced by
   * {@code events} when the server sends it. Headers: {@code Cache-Control: no-cache}, and
   * {@code Connection: keep-alive} on HTTP/1.1 only.
   *
   * @param request the request (for its protocol)
   * @param events writes the events
   * @return the streamed response
   */
  public static Response stream(Request request, Events events) {
    Response response = Response.stream(200, "text/event-stream", out -> {
      ServerSentEventGenerator sse = new ServerSentEventGenerator(out);
      try {
        events.run(sse);
      } catch (RuntimeException | java.io.IOException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException("datastar stream failed", e);
      } finally {
        sse.close();
      }
    });
    response = response.header("Cache-Control", "no-cache");
    if (request.protocol().equals("HTTP/1.1")) {
      response = response.header("Connection", "keep-alive");
    }
    return response;
  }
}
