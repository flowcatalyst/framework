package io.flowcatalyst.framework.http;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a {@link Rule} checks, as data: the OpenAPI emitter turns it into JSON Schema keywords
 * (OpenAPI contract section 5.4). A rule that cannot say what it checks ({@link Rules#custom}, or
 * an application's own lambda) describes itself as {@link None} and documents nothing.
 */
public sealed interface Constraint {

  /** Nothing that can be documented. */
  record None() implements Constraint {}

  /** {@link Rules#notNull()}: required-ness comes from the type, so it documents nothing. */
  record NotNull() implements Constraint {}

  /** {@link Rules#notBlank()}. */
  record NotBlank() implements Constraint {}

  /**
   * {@link Rules#minLength(int)}.
   *
   * @param min the minimum length
   */
  record MinLength(int min) implements Constraint {}

  /**
   * {@link Rules#maxLength(int)}.
   *
   * @param max the maximum length
   */
  record MaxLength(int max) implements Constraint {}

  /**
   * {@link Rules#pattern(String)}: the whole text must match.
   *
   * @param regex the Java regular expression
   * @param flags the {@link java.util.regex.Pattern} flags it was compiled with
   */
  record Pattern(String regex, int flags) implements Constraint {}

  /**
   * {@link Rules#range(long, long)} and {@link Rules#range(double, double)}, inclusive.
   *
   * @param min the lowest allowed value
   * @param max the highest allowed value
   */
  record Range(BigDecimal min, BigDecimal max) implements Constraint {}

  /**
   * {@link Rules#size(int, int)}, inclusive.
   *
   * @param min the smallest allowed size
   * @param max the largest allowed size
   */
  record Size(int min, int max) implements Constraint {}

  /**
   * {@link Rules#oneOf(Object[])}.
   *
   * @param values the allowed values, in the order given
   */
  record OneOf(List<Object> values) implements Constraint {
    /**
     * Copies the values.
     *
     * @param values the allowed values
     */
    public OneOf {
      values = List.copyOf(values);
    }
  }

  /**
   * {@link Rules#each(Rule)}: a constraint on every element.
   *
   * @param element the element's constraint
   */
  record Each(Constraint element) implements Constraint {}

  /**
   * {@link Rules#valid(Validator)} and {@link Validator#nested}: the value's own validator.
   *
   * @param validator the nested validator
   */
  record Valid(Validator<?> validator) implements Constraint {}

  /**
   * The constraint of a rule that documents nothing.
   *
   * @return {@link None}
   */
  static Constraint none() {
    return new None();
  }
}
