package io.flowcatalyst.framework.http.sse;

/**
 * One server-sent event of an {@link EventStreams} stream.
 *
 * <p>Ids are the stream's cursor: positive and increasing in the order events are published (a log
 * table's sequence is the usual source), so a client that reconnects with {@code Last-Event-ID} is
 * sent what came after it, and a stream never sends an id twice or goes backwards.
 *
 * @param id the event id, at least 1; written as {@code id:}
 * @param type the event type, written as {@code event:}: one line, not blank
 * @param data the payload, usually JSON; each line becomes one {@code data:} line
 */
public record SseEvent(long id, String type, String data) {

  /** Checks the id and the type. */
  public SseEvent {
    if (id < 1) {
      throw new IllegalArgumentException("an event id is at least 1");
    }
    if (type.isBlank() || type.indexOf('\n') >= 0 || type.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("an event type is one line and not blank");
    }
  }
}
