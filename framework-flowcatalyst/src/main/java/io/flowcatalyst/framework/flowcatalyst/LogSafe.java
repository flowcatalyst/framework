package io.flowcatalyst.framework.flowcatalyst;

/**
 * Makes text that came from outside (a header, a handler's reason) safe to put in a log line or a
 * response: no line breaks or other control and format characters, bounded length.
 */
final class LogSafe {

  private LogSafe() {}

  /**
   * Replaces every control, format, line-separator and paragraph-separator character with a space
   * and cuts the text to at most {@code max} characters (never inside a surrogate pair).
   *
   * @param text the text
   * @param max the largest length kept
   * @return the cleaned text
   */
  static String clean(String text, int max) {
    int end = Math.min(text.length(), max);
    if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
      end--;
    }
    StringBuilder out = new StringBuilder(end);
    for (int i = 0; i < end; i++) {
      char c = text.charAt(i);
      out.append(unsafe(c) ? ' ' : c);
    }
    return out.toString();
  }

  private static boolean unsafe(char c) {
    return switch (Character.getType(c)) {
      case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true;
      default -> false;
    };
  }
}
