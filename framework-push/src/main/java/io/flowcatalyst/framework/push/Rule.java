package io.flowcatalyst.framework.push;

import io.flowcatalyst.framework.context.Context;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Says who may subscribe to the topics of a pattern without a ticket (push contract, 6.3). It is
 * data, not a callback, so a gateway can apply it too. {@code {principal}} must equal the context's
 * principal id, {@code {client}} the context's client, any other {@code {name}} matches one
 * segment, and every required permission must be held. A rule with neither {@code {principal}} nor
 * a required permission is refused ({@link #of}): a topic open to anyone is declared on purpose,
 * with {@link #open}.
 */
public final class Rule {

  private final TopicPattern pattern;
  private final List<String> requires;
  private final boolean open;

  private Rule(TopicPattern pattern, List<String> requires, boolean open) {
    this.pattern = pattern;
    this.requires = requires;
    this.open = open;
  }

  /**
   * A rule on a pattern.
   *
   * @param pattern the topic pattern, for example {@code fulfil:order:{id}}
   * @param requires the permissions the subscriber must hold, all of them
   * @return the rule
   * @throws IllegalArgumentException when the pattern is not one, or the rule has neither a
   *     {@code {principal}} placeholder nor a required permission (an open rule, which is declared
   *     with {@link #open})
   */
  public static Rule of(String pattern, String... requires) {
    TopicPattern parsed = TopicPattern.parse(pattern);
    List<String> needs = List.of(requires);
    for (String permission : needs) {
      if (permission.isBlank() || permission.chars().anyMatch(Character::isWhitespace)) {
        throw new IllegalArgumentException("push rule " + pattern + ": not a permission: '" + permission + "'");
      }
    }
    if (needs.isEmpty() && !parsed.has("{principal}")) {
      throw new IllegalArgumentException(
          "push rule "
              + pattern
              + ": a push rule needs a {principal} placeholder or a required permission; declare a deliberately open topic with PushModule.Builder.publicTopic");
    }
    return new Rule(parsed, needs, false);
  }

  /**
   * A deliberately open rule: anyone may subscribe to its topics, with or without credentials. Its
   * placeholders still apply ({@code {principal}} needs that principal).
   *
   * @param pattern the topic pattern
   * @return the rule
   * @throws IllegalArgumentException when the pattern is not one
   */
  public static Rule open(String pattern) {
    return new Rule(TopicPattern.parse(pattern), List.of(), true);
  }

  /**
   * The rule's topic pattern.
   *
   * @return the pattern as written
   */
  public String pattern() {
    return pattern.text();
  }

  /**
   * The permissions the rule needs.
   *
   * @return the permissions, none for a rule on {@code {principal}} alone
   */
  public List<String> required() {
    return requires;
  }

  /**
   * Whether the rule is deliberately open.
   *
   * @return true for {@link #open}
   */
  public boolean isOpen() {
    return open;
  }

  String application() {
    return pattern.application();
  }

  /**
   * Whether the rule lets a context subscribe to a topic. A request without credentials (a null
   * context) is allowed only by an open rule without {@code {principal}} or {@code {client}}.
   *
   * @param context the request's context, null for none
   * @param topic the topic
   * @return true when allowed
   */
  public boolean allows(@Nullable Context context, String topic) {
    if (context == null && !open) {
      return false;
    }
    String[] segs = topic.split(":", -1);
    List<String> expected = pattern.segments();
    if (segs.length != expected.size()) {
      return false;
    }
    for (int i = 0; i < segs.length; i++) {
      String s = expected.get(i);
      if (s.equals("{principal}")) {
        if (context == null || context.principal() == null || !context.principal().value().equals(segs[i])) {
          return false;
        }
      } else if (s.equals("{client}")) {
        if (context == null || !context.client().value().equals(segs[i])) {
          return false;
        }
      } else if (!TopicPattern.isPlaceholder(s) && !s.equals(segs[i])) {
        return false;
      }
    }
    for (String permission : requires) {
      if (context == null || !context.hasPermission(permission)) {
        return false;
      }
    }
    return true;
  }
}
