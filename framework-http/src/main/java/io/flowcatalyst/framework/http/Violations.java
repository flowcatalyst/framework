package io.flowcatalyst.framework.http;

import java.util.ArrayList;
import java.util.List;

/** Collects what a {@link Rule} found wrong with one value. */
public final class Violations {

  /**
   * One violation.
   *
   * @param path the path below the value ({@code ""} for the value itself, {@code [2]},
   *     {@code street})
   * @param code the rule code
   * @param message the message
   */
  public record Entry(String path, String code, String message) {}

  private final List<Entry> entries = new ArrayList<>();

  /** Creates an empty collection. */
  public Violations() {}

  /**
   * Records a violation of the value itself.
   *
   * @param code the rule code
   * @param message the message; must not contain the value
   */
  public void add(String code, String message) {
    entries.add(new Entry("", code, message));
  }

  /**
   * Records a violation of a part of the value.
   *
   * @param path the path below the value
   * @param code the rule code
   * @param message the message; must not contain the value
   */
  public void addAt(String path, String code, String message) {
    entries.add(new Entry(path, code, message));
  }

  /**
   * The violations so far.
   *
   * @return the entries in the order found
   */
  public List<Entry> entries() {
    return entries;
  }

  /**
   * Whether nothing was recorded.
   *
   * @return true when empty
   */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /**
   * Joins two field paths: {@code a} + {@code b} is {@code a.b}, and {@code a} + {@code [1]} is
   * {@code a[1]}.
   *
   * @param parent the parent path, possibly empty
   * @param child the child path, possibly empty
   * @return the joined path
   */
  static String join(String parent, String child) {
    if (parent.isEmpty()) {
      return child;
    }
    if (child.isEmpty()) {
      return parent;
    }
    return child.startsWith("[") ? parent + child : parent + "." + child;
  }
}
