package io.flowcatalyst.framework.http;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * An explicit validation function for a request record, written by the record's author. It
 * collects every field error, not the first. Immutable: each builder call returns a new
 * validator.
 *
 * <pre>{@code
 * record CreateOrder(String customer, int quantity) {
 *   static final Validator<CreateOrder> V = Validator.of(CreateOrder.class)
 *       .field("customer", CreateOrder::customer, Rules.notBlank(), Rules.maxLength(100))
 *       .field("quantity", CreateOrder::quantity, Rules.range(1, 1000));
 * }
 * CreateOrder body = request.json(CreateOrder.V);
 * }</pre>
 *
 * @param <T> the validated type
 */
public final class Validator<T> {

  @FunctionalInterface
  private interface Step<T> {
    void run(T root, List<FieldError> out);
  }

  /**
   * The rules of one field, as data (for the OpenAPI document; contract section 5.4).
   *
   * @param name the field name, as given to {@link #field}
   * @param constraints what each rule checks, in the order given
   */
  public record FieldRules(String name, List<Constraint> constraints) {
    /**
     * Copies the constraints.
     *
     * @param name the field name
     * @param constraints the constraints
     */
    public FieldRules {
      constraints = List.copyOf(constraints);
    }
  }

  private final Class<T> type;
  private final List<Step<T>> steps;
  private final List<FieldRules> fields;

  private Validator(Class<T> type, List<Step<T>> steps, List<FieldRules> fields) {
    this.type = type;
    this.steps = steps;
    this.fields = fields;
  }

  /**
   * Starts a validator for a type.
   *
   * @param <T> the type
   * @param type the record class the request body is decoded into
   * @return a validator with no rules
   */
  public static <T> Validator<T> of(Class<T> type) {
    return new Validator<>(type, List.of(), List.of());
  }

  /**
   * Adds the rules for one field.
   *
   * @param <V> the field type
   * @param name the field name, used in error paths
   * @param getter reads the field from the record
   * @param rules the rules, all run
   * @return a validator with the field added
   */
  @SafeVarargs
  public final <V> Validator<T> field(
      String name, Function<T, @Nullable V> getter, Rule<? super V>... rules) {
    List<Rule<? super V>> copy = List.of(rules);
    Step<T> step =
        (root, out) -> {
          V value = getter.apply(root);
          Violations violations = new Violations();
          for (Rule<? super V> rule : copy) {
            rule.check(value, violations);
          }
          for (Violations.Entry e : violations.entries()) {
            out.add(new FieldError(Violations.join(name, e.path()), e.code(), e.message()));
          }
        };
    List<Constraint> described = new ArrayList<>();
    for (Rule<? super V> rule : copy) {
      described.add(rule.describe());
    }
    return with(step, new FieldRules(name, described));
  }

  /**
   * Adds a nested record, validated by its own validator; error paths read {@code address.street}.
   * A null nested value passes (add {@link Rules#notNull()} through {@link #field} to require it).
   *
   * @param <V> the nested type
   * @param name the field name
   * @param getter reads the nested record
   * @param nested the nested record's validator
   * @return a validator with the field added
   */
  public <V> Validator<T> nested(String name, Function<T, @Nullable V> getter, Validator<V> nested) {
    return field(name, getter, Rules.valid(nested));
  }

  private Validator<T> with(Step<T> step, FieldRules field) {
    List<Step<T>> list = new ArrayList<>(steps);
    list.add(step);
    List<FieldRules> described = new ArrayList<>(fields);
    described.add(field);
    return new Validator<>(type, List.copyOf(list), List.copyOf(described));
  }

  /**
   * The rules of every field, as data, in the order they were added; a field added twice appears
   * twice. The OpenAPI emitter reads them (contract section 5.4).
   *
   * @return the fields
   */
  public List<FieldRules> fields() {
    return fields;
  }

  /**
   * The type this validator validates, which {@code Request.json} decodes into.
   *
   * @return the class
   */
  public Class<T> type() {
    return type;
  }

  /**
   * Runs every rule.
   *
   * @param value the value to check
   * @return every error found, empty when valid
   */
  public List<FieldError> validate(T value) {
    List<FieldError> out = new ArrayList<>();
    for (Step<T> step : steps) {
      step.run(value, out);
    }
    return out;
  }

  /**
   * Runs every rule and throws when any fails.
   *
   * @param value the value to check
   * @return the same value, for chaining
   * @throws ValidationException when any rule fails
   */
  public T check(T value) {
    List<FieldError> errors = validate(value);
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    return value;
  }
}
