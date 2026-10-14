package io.flowcatalyst.framework.http;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** The standard {@link Rule}s. Messages never contain the offending value. */
public final class Rules {

  private Rules() {}

  /**
   * The value must be present.
   *
   * @return the rule; code {@code NOT_NULL}
   */
  public static Rule<Object> notNull() {
    return described(
        new Constraint.NotNull(),
        (v, out) -> {
          if (v == null) {
            out.add("NOT_NULL", "is required");
          }
        });
  }

  /**
   * The text must be present and not blank.
   *
   * @return the rule; code {@code NOT_BLANK}
   */
  public static Rule<CharSequence> notBlank() {
    return described(
        new Constraint.NotBlank(),
        (v, out) -> {
          if (v == null || v.toString().isBlank()) {
            out.add("NOT_BLANK", "must not be blank");
          }
        });
  }

  /**
   * The text must have at least this many characters.
   *
   * @param min the minimum length
   * @return the rule; code {@code TOO_SHORT}
   */
  public static Rule<CharSequence> minLength(int min) {
    return described(
        new Constraint.MinLength(min),
        (v, out) -> {
          if (v != null && v.length() < min) {
            out.add("TOO_SHORT", "must be at least " + min + " characters");
          }
        });
  }

  /**
   * The text must have at most this many characters.
   *
   * @param max the maximum length
   * @return the rule; code {@code TOO_LONG}
   */
  public static Rule<CharSequence> maxLength(int max) {
    return described(
        new Constraint.MaxLength(max),
        (v, out) -> {
          if (v != null && v.length() > max) {
            out.add("TOO_LONG", "must be at most " + max + " characters");
          }
        });
  }

  /**
   * The whole text must match the pattern.
   *
   * @param regex the regular expression
   * @return the rule; code {@code PATTERN}
   */
  public static Rule<CharSequence> pattern(String regex) {
    return pattern(Pattern.compile(regex));
  }

  /**
   * The whole text must match the pattern.
   *
   * @param pattern the compiled pattern
   * @return the rule; code {@code PATTERN}
   */
  public static Rule<CharSequence> pattern(Pattern pattern) {
    return described(
        new Constraint.Pattern(pattern.pattern(), pattern.flags()),
        (v, out) -> {
          if (v != null && !pattern.matcher(v).matches()) {
            out.add("PATTERN", "has an invalid format");
          }
        });
  }

  /**
   * The number must be within the range, inclusive.
   *
   * @param min the lowest allowed value
   * @param max the highest allowed value
   * @return the rule; code {@code OUT_OF_RANGE}
   */
  public static Rule<Number> range(long min, long max) {
    return range(BigDecimal.valueOf(min), BigDecimal.valueOf(max), min + " and " + max);
  }

  /**
   * The number must be within the range, inclusive.
   *
   * @param min the lowest allowed value
   * @param max the highest allowed value
   * @return the rule; code {@code OUT_OF_RANGE}
   */
  public static Rule<Number> range(double min, double max) {
    return range(BigDecimal.valueOf(min), BigDecimal.valueOf(max), min + " and " + max);
  }

  private static Rule<Number> range(BigDecimal min, BigDecimal max, String text) {
    return described(
        new Constraint.Range(min, max),
        (v, out) -> {
          if (v == null) {
            return;
          }
          BigDecimal d = toDecimal(v);
          if (d == null || d.compareTo(min) < 0 || d.compareTo(max) > 0) {
            out.add("OUT_OF_RANGE", "must be between " + text);
          }
        });
  }

  private static @Nullable BigDecimal toDecimal(Number n) {
    try {
      return n instanceof BigDecimal b ? b : new BigDecimal(n.toString());
    } catch (NumberFormatException e) {
      return null; // NaN, infinity
    }
  }

  /**
   * The collection must have a size within the bounds, inclusive.
   *
   * @param min the smallest allowed size
   * @param max the largest allowed size
   * @return the rule; code {@code SIZE}
   */
  public static Rule<Collection<?>> size(int min, int max) {
    return described(
        new Constraint.Size(min, max),
        (v, out) -> {
          if (v != null && (v.size() < min || v.size() > max)) {
            out.add("SIZE", "must have between " + min + " and " + max + " items");
          }
        });
  }

  /**
   * The value must be one of the allowed values.
   *
   * @param <T> the value type
   * @param allowed the allowed values
   * @return the rule; code {@code NOT_ONE_OF}
   */
  @SafeVarargs
  public static <T> Rule<T> oneOf(T... allowed) {
    List<Object> ordered = List.of((Object[]) allowed);
    Set<T> set = Set.of(allowed);
    String text = String.join(", ", set.stream().map(String::valueOf).sorted().toList());
    return described(
        new Constraint.OneOf(ordered),
        (v, out) -> {
          if (v != null && !set.contains(v)) {
            out.add("NOT_ONE_OF", "must be one of: " + text);
          }
        });
  }

  /**
   * Applies a rule to every element; a violation of the element at index 2 has the path
   * {@code [2]}.
   *
   * @param <E> the element type
   * @param rule the rule for one element
   * @return the rule
   */
  public static <E> Rule<Collection<? extends E>> each(Rule<? super E> rule) {
    return described(new Constraint.Each(rule.describe()), (v, out) -> {
      if (v == null) {
        return;
      }
      int i = 0;
      for (E element : v) {
        Violations inner = new Violations();
        rule.check(element, inner);
        String prefix = "[" + i + "]";
        for (Violations.Entry e : inner.entries()) {
          out.addAt(Violations.join(prefix, e.path()), e.code(), e.message());
        }
        i++;
      }
    });
  }

  /**
   * The value must satisfy a nested validator; its field paths nest below this one.
   *
   * @param <E> the value type
   * @param validator the validator of the value's type
   * @return the rule
   */
  public static <E> Rule<E> valid(Validator<E> validator) {
    return described(
        new Constraint.Valid(validator),
        (v, out) -> {
          if (v == null) {
            return;
          }
          for (FieldError e : validator.validate(v)) {
            out.addAt(e.field(), e.code(), e.message());
          }
        });
  }

  /**
   * A rule from a predicate. It documents nothing in the OpenAPI document ({@link Rule#describe()}
   * is {@link Constraint.None}).
   *
   * @param <T> the value type
   * @param code the code reported when the predicate is false
   * @param message the message reported
   * @param valid true when the (non-null) value is acceptable
   * @return the rule
   */
  public static <T> Rule<T> custom(String code, String message, Predicate<? super T> valid) {
    return (v, out) -> {
      if (v != null && !valid.test(v)) {
        out.add(code, message);
      }
    };
  }

  /** A rule that also says what it checks. */
  private static <T> Rule<T> described(Constraint constraint, Rule<T> check) {
    return new Rule<>() {
      @Override
      public void check(@Nullable T value, Violations violations) {
        check.check(value, violations);
      }

      @Override
      public Constraint describe() {
        return constraint;
      }
    };
  }
}
