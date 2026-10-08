package io.flowcatalyst.framework.http.datastar;

import io.flowcatalyst.framework.http.ClientDisconnectedException;
import io.flowcatalyst.framework.http.Json;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;

/**
 * Writes Datastar events to a stream, flushing after each one. Not thread-safe, except
 * {@link #close()}.
 *
 * <p>Wire format per event: {@code event:}, then {@code id:} (if given), then {@code retry:}
 * (if not the default), then the {@code data:} lines, then a blank line.
 */
public final class ServerSentEventGenerator {

  /** The SSE default reconnect delay; {@code retry:} is written only for other values. */
  public static final long DEFAULT_RETRY_MILLIS = 1000;

  private final OutputStream out;
  private volatile boolean closed;

  /**
   * Wraps a stream. Normally obtained through {@link Datastar#stream}.
   *
   * @param out where events are written
   */
  public ServerSentEventGenerator(OutputStream out) {
    this.out = out;
  }

  /**
   * Patches elements with default options. The elements must carry ids.
   *
   * @param html the markup
   * @throws ClientDisconnectedException when the client has gone
   */
  public void patchElements(String html) {
    patchElements(html, PatchElementsOptions.defaults());
  }

  /**
   * Patches elements.
   *
   * @param html the markup; may be empty only with {@link ElementPatchMode#REMOVE}
   * @param options the options
   * @throws IllegalArgumentException when a value contains a line break where none is allowed, or
   *     the markup is blank for a mode other than remove
   * @throws ClientDisconnectedException when the client has gone
   */
  public void patchElements(String html, PatchElementsOptions options) {
    boolean remove = options.mode() == ElementPatchMode.REMOVE;
    if (options.selector() != null && options.selector().isBlank()) {
      throw new IllegalArgumentException("selector must not be blank");
    }
    if (remove && options.selector() == null && html.isBlank()) {
      throw new IllegalArgumentException("removing needs a selector or the elements to remove");
    }
    if (!remove && html.isBlank()) {
      throw new IllegalArgumentException("elements must not be blank");
    }
    StringBuilder sb = begin("datastar-patch-elements", options.eventId(), options.retryMillis());
    String selector = options.selector();
    if (selector != null) {
      singleLine("selector", selector);
      sb.append("data: selector ").append(selector).append('\n');
    }
    if (options.mode() != ElementPatchMode.OUTER) {
      sb.append("data: mode ").append(options.mode().wire()).append('\n');
    }
    if (options.useViewTransition()) {
      sb.append("data: useViewTransition true\n");
    }
    if (options.namespace() != Namespace.HTML) {
      sb.append("data: namespace ").append(options.namespace().wire()).append('\n');
    }
    if (!html.isBlank()) {
      for (String line : html.split("\r\n|\r|\n", -1)) {
        sb.append("data: elements ").append(line).append('\n');
      }
    }
    send(sb);
  }

  /**
   * Removes the elements matching the selector: a patch with mode {@code remove}.
   *
   * @param selector a CSS selector
   * @throws ClientDisconnectedException when the client has gone
   */
  public void remove(String selector) {
    patchElements("", PatchElementsOptions.defaults().selector(selector).mode(ElementPatchMode.REMOVE));
  }

  /**
   * Patches signals with default options.
   *
   * @param json a JSON object, applied as a JSON merge patch (RFC 7386)
   * @throws IllegalArgumentException when it is not valid JSON
   * @throws ClientDisconnectedException when the client has gone
   */
  public void patchSignals(String json) {
    patchSignals(json, PatchSignalsOptions.defaults());
  }

  /**
   * Patches signals.
   *
   * @param json a JSON object, applied as a JSON merge patch (RFC 7386)
   * @param options the options
   * @throws IllegalArgumentException when it is not valid JSON
   * @throws ClientDisconnectedException when the client has gone
   */
  public void patchSignals(String json, PatchSignalsOptions options) {
    try {
      Json.mapper().readTree(json);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("signals must be valid JSON", e);
    }
    StringBuilder sb = begin("datastar-patch-signals", options.eventId(), options.retryMillis());
    if (options.onlyIfMissing()) {
      sb.append("data: onlyIfMissing true\n");
    }
    for (String line : json.split("\r\n|\r|\n", -1)) {
      sb.append("data: signals ").append(line).append('\n');
    }
    send(sb);
  }

  /** Ends the stream; later writes throw {@link IllegalStateException}. Safe from any thread. */
  public void close() {
    closed = true;
  }

  private StringBuilder begin(String event, @Nullable String eventId, long retryMillis) {
    if (closed) {
      throw new IllegalStateException("the stream is closed");
    }
    StringBuilder sb = new StringBuilder(128).append("event: ").append(event).append('\n');
    if (eventId != null) {
      singleLine("eventId", eventId);
      sb.append("id: ").append(eventId).append('\n');
    }
    if (retryMillis < 0) {
      throw new IllegalArgumentException("retry must not be negative");
    }
    if (retryMillis != DEFAULT_RETRY_MILLIS) {
      sb.append("retry: ").append(retryMillis).append('\n');
    }
    return sb;
  }

  private static void singleLine(String what, String value) {
    if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
      throw new IllegalArgumentException(what + " must not contain a line break");
    }
  }

  private void send(StringBuilder event) {
    event.append('\n');
    try {
      out.write(event.toString().getBytes(StandardCharsets.UTF_8));
      out.flush();
    } catch (IOException e) {
      throw new ClientDisconnectedException(e);
    }
  }
}
