package io.flowcatalyst.framework.push;

import java.nio.charset.StandardCharsets;

/** The bytes of the stream (push contract, 6.2 and section 7; {@code conformance/push/wire.json} pins them). */
final class Wire {

  /** The reconnect delay the stream asks clients for, in milliseconds. */
  static final long RETRY_MILLIS = 3000;

  /** The reason of a {@code reset} for a cursor older than the log keeps. */
  static final String RESET_RETENTION = "retention";

  /** The reason of a {@code reset} for a cursor the log never had. */
  static final String RESET_UNKNOWN_ID = "unknown-id";

  private Wire() {}

  static String retryField() {
    return "retry: " + RETRY_MILLIS + "\n\n";
  }

  /** The push event of an entry. */
  static byte[] pushEvent(Entry e) {
    return ("id: " + e.id() + "\nevent: " + e.type() + "\ndata: {\"topic\":" + jsonString(e.topic()) + ",\"data\":" + e.data() + "}\n\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  static String subscribedEvent(java.util.List<String> granted, long latest) {
    StringBuilder list = new StringBuilder("[");
    for (int i = 0; i < granted.size(); i++) {
      if (i > 0) {
        list.append(',');
      }
      list.append(jsonString(granted.get(i)));
    }
    list.append(']');
    return "event: subscribed\ndata: {\"granted\":" + list + ",\"latest\":" + latest + "}\n\n";
  }

  static String resetEvent(String reason, long latest) {
    return "id: " + latest + "\nevent: reset\ndata: {\"reason\":" + jsonString(reason) + ",\"latest\":" + latest + "}\n\n";
  }

  static String authExpiredEvent() {
    return "event: auth-expired\ndata: {}\n\n";
  }

  static final String HEARTBEAT = ": ping\n\n";

  /** A JSON string: quotes, backslash and control characters escaped, everything else as is. */
  static String jsonString(String s) {
    StringBuilder out = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}
