package io.flowcatalyst.framework.push;

/**
 * One push as the hub holds it: the log entry and its SSE event, encoded once when the hub reads it
 * and shared, read-only, by the memory of recent pushes and by every stream it goes to.
 *
 * @param entry the log entry
 * @param wire the push event bytes; never modified
 */
@SuppressWarnings("ArrayRecordComponent")
record Frame(Entry entry, byte[] wire) {

  static Frame of(Entry entry) {
    return new Frame(entry, Wire.pushEvent(entry));
  }

  long id() {
    return entry.id();
  }
}
