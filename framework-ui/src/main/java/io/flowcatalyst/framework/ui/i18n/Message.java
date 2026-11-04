package io.flowcatalyst.framework.ui.i18n;

import java.util.Map;

/** One catalog entry: a text, or a plural object keyed by CLDR plural category. */
public sealed interface Message {

  /**
   * A message that is one text.
   *
   * @param text the text, with {@code {name}} placeholders
   */
  record Text(String text) implements Message {}

  /**
   * A message with one text per plural category ({@code zero}, {@code one}, {@code two}, {@code
   * few}, {@code many}, {@code other}); {@code other} is required.
   *
   * @param forms category to text
   */
  record Plural(Map<String, String> forms) implements Message {
    /**
     * Checks the categories.
     *
     * @param forms category to text
     * @throws IllegalArgumentException for an unknown category or a missing {@code other}
     */
    public Plural {
      forms = Map.copyOf(forms);
      for (String c : forms.keySet()) {
        if (!Catalog.CATEGORIES.contains(c)) {
          throw new IllegalArgumentException("\"" + c + "\" is not a plural category");
        }
      }
      if (!forms.containsKey("other")) {
        throw new IllegalArgumentException("a plural message has no \"other\"");
      }
    }
  }
}
