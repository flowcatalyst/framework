package io.flowcatalyst.framework.partitions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The module's log lines: a message followed by {@code key=value} pairs (a value with a space, a quote or
 * an equals sign is quoted), so a line reads the same in any SLF4J backend and a test can read it.
 */
final class Log {

  private static final Logger LOG = LoggerFactory.getLogger(Partitions.class);

  private Log() {}

  static void info(String message, Object... pairs) {
    if (LOG.isInfoEnabled()) {
      LOG.info(line(message, pairs));
    }
  }

  static void warn(String message, Object... pairs) {
    if (LOG.isWarnEnabled()) {
      LOG.warn(line(message, pairs));
    }
  }

  static void error(String message, Object... pairs) {
    if (LOG.isErrorEnabled()) {
      LOG.error(line(message, pairs));
    }
  }

  /** The message of a failure, or its class name when it has none. */
  static String describe(Throwable t) {
    String m = t.getMessage();
    return m == null ? t.getClass().getSimpleName() : m;
  }

  static String line(String message, Object... pairs) {
    StringBuilder sb = new StringBuilder(message);
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      sb.append(' ').append(pairs[i]).append('=').append(quote(String.valueOf(pairs[i + 1])));
    }
    return sb.toString();
  }

  private static String quote(String v) {
    if (!v.isEmpty() && v.chars().noneMatch(c -> c <= ' ' || c == '"' || c == '=')) {
      return v;
    }
    return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }
}
