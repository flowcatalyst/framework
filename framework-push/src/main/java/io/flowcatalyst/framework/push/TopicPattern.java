package io.flowcatalyst.framework.push;

import io.flowcatalyst.framework.usecase.Push;
import java.util.List;

/**
 * A topic pattern (push contract, 6.3): segments that are literals or placeholders
 * ({@code {principal}}, {@code {client}}, any other {@code {name}}). The first segment is a literal
 * application code.
 *
 * @param text the pattern as written
 * @param segments its segments; a placeholder keeps its braces
 */
record TopicPattern(String text, List<String> segments) {

  /** Checks a pattern: a topic whose segments after the first may be {name} placeholders. */
  static TopicPattern parse(String text) {
    if (text.length() > Push.MAX_TOPIC_LENGTH) {
      throw new IllegalArgumentException("a topic pattern is at most " + Push.MAX_TOPIC_LENGTH + " characters");
    }
    String[] segs = text.split(":", -1);
    if (segs.length < 2 || !Push.isApplication(segs[0])) {
      throw new IllegalArgumentException("a topic pattern is {application}:{segment}(:{segment})* with a literal application: " + text);
    }
    for (int i = 1; i < segs.length; i++) {
      if (!Push.isSegment(segs[i]) && !isPlaceholder(segs[i])) {
        throw new IllegalArgumentException("a pattern segment is [0-9A-Za-z_.-]+ or {name}: " + text);
      }
    }
    return new TopicPattern(text, List.of(segs));
  }

  /** {@code {name}} with name {@code [a-z][A-Za-z0-9]*}. */
  static boolean isPlaceholder(String s) {
    if (s.length() < 3 || s.charAt(0) != '{' || s.charAt(s.length() - 1) != '}') {
      return false;
    }
    String name = s.substring(1, s.length() - 1);
    char first = name.charAt(0);
    if (first < 'a' || first > 'z') {
      return false;
    }
    for (int i = 1; i < name.length(); i++) {
      char c = name.charAt(i);
      if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c < '0' || c > '9')) {
        return false;
      }
    }
    return true;
  }

  String application() {
    return segments.getFirst();
  }

  boolean has(String placeholder) {
    return segments.contains(placeholder);
  }

  /**
   * Whether the topic has the pattern's shape, every placeholder matching any one segment (a
   * ticket's patterns, 6.4).
   */
  boolean matchesAny(List<String> topic) {
    if (topic.size() != segments.size()) {
      return false;
    }
    for (int i = 0; i < segments.size(); i++) {
      String s = segments.get(i);
      if (!isPlaceholder(s) && !s.equals(topic.get(i))) {
        return false;
      }
    }
    return true;
  }
}
