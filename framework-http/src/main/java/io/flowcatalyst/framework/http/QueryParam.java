package io.flowcatalyst.framework.http;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A typed query parameter of an {@link Operation}. Required unless made {@link #optional()} or
 * given a {@link #defaultValue}; {@link #list()} makes a repeated parameter ({@code
 * ?status=OPEN&status=CANCELLED}), empty when absent. A missing required value, or a value that does
 * not parse, is a {@code 400 VALIDATION} naming the parameter.
 *
 * <pre>{@code
 * static final QueryParam<@Nullable String> CLIENT = QueryParam.string("clientId").optional();
 * static final QueryParam<Integer> LIMIT = QueryParam.integer("limit").defaultValue(20);
 * static final QueryParam<List<OrderStatus>> STATUS = QueryParam.enumOf("status", OrderStatus.class).list();
 * }</pre>
 *
 * @param <T> the value type the handler reads
 */
public final class QueryParam<T extends @Nullable Object> {

  private enum Mode {
    REQUIRED,
    OPTIONAL,
    DEFAULT,
    LIST
  }

  private final String name;
  private final Class<?> type;
  private final Mode mode;
  private final @Nullable Object defaultValue;

  private QueryParam(String name, Class<?> type, Mode mode, @Nullable Object defaultValue) {
    this.name = name;
    this.type = type;
    this.mode = mode;
    this.defaultValue = defaultValue;
  }

  private static <T> QueryParam<T> of(String name, Class<T> type) {
    ParamValues.checkName(name, false);
    ParamValues.checkType(type);
    return new QueryParam<>(name, type, Mode.REQUIRED, null);
  }

  /**
   * A required text parameter.
   *
   * @param name the name
   * @return the parameter
   */
  public static QueryParam<String> string(String name) {
    return of(name, String.class);
  }

  /**
   * A required 32-bit integer parameter.
   *
   * @param name the name
   * @return the parameter
   */
  public static QueryParam<Integer> integer(String name) {
    return of(name, Integer.class);
  }

  /**
   * A required 64-bit integer parameter.
   *
   * @param name the name
   * @return the parameter
   */
  public static QueryParam<Long> longInt(String name) {
    return of(name, Long.class);
  }

  /**
   * A required boolean parameter: {@code true} or {@code false}.
   *
   * @param name the name
   * @return the parameter
   */
  public static QueryParam<Boolean> bool(String name) {
    return of(name, Boolean.class);
  }

  /**
   * A required parameter whose value is the exact name of an enum constant.
   *
   * @param <E> the enum type
   * @param name the name
   * @param type the enum class
   * @return the parameter
   */
  public static <E extends Enum<E>> QueryParam<E> enumOf(String name, Class<E> type) {
    return of(name, type);
  }

  /**
   * The same parameter, optional: the handler reads null when it is absent.
   *
   * @return the optional parameter
   * @throws IllegalStateException unless this is a required single parameter
   */
  public QueryParam<@Nullable T> optional() {
    requireMode("optional()");
    return new QueryParam<>(name, type, Mode.OPTIONAL, null);
  }

  /**
   * The same parameter with a value used when it is absent; documented as the schema's {@code
   * default}.
   *
   * @param value the default
   * @return the parameter
   * @throws IllegalStateException unless this is a required single parameter
   */
  public QueryParam<T> defaultValue(T value) {
    requireMode("defaultValue(...)");
    Objects.requireNonNull(value, "a default value is not null; use optional() instead");
    if (!type.isInstance(value)) {
      throw new IllegalArgumentException("the default of " + name + " is not a " + type.getSimpleName());
    }
    return new QueryParam<>(name, type, Mode.DEFAULT, value);
  }

  /**
   * The same parameter, repeated: every value in order, an empty list when absent.
   *
   * @return the parameter
   * @throws IllegalStateException unless this is a required single parameter
   */
  public QueryParam<List<T>> list() {
    requireMode("list()");
    return new QueryParam<>(name, type, Mode.LIST, null);
  }

  private void requireMode(String what) {
    if (mode != Mode.REQUIRED) {
      throw new IllegalStateException(what + " applies to a required single parameter; " + name + " is " + mode);
    }
  }

  /**
   * The name.
   *
   * @return the name in the query string
   */
  public String name() {
    return name;
  }

  /**
   * The type of one value: {@code String}, {@code Integer}, {@code Long}, {@code Boolean} or an
   * enum.
   *
   * @return the class
   */
  public Class<?> elementType() {
    return type;
  }

  /**
   * Whether the request must carry it.
   *
   * @return true for a required single parameter
   */
  public boolean isRequired() {
    return mode == Mode.REQUIRED;
  }

  /**
   * Whether it is repeated.
   *
   * @return true after {@link #list()}
   */
  public boolean isRepeated() {
    return mode == Mode.LIST;
  }

  /**
   * The value used when it is absent.
   *
   * @return the default, or null when there is none
   */
  public @Nullable Object defaultValue() {
    return defaultValue;
  }

  /** Reads the value from the request; adds to {@code errors} when it is missing or bad. */
  @Nullable Object read(Request request, List<FieldError> errors) {
    switch (mode) {
      case LIST -> {
        List<Object> values = new java.util.ArrayList<>();
        for (String raw : request.queries(name)) {
          values.add(ParamValues.parse(type, name, raw, errors));
        }
        return List.copyOf(values);
      }
      default -> {
        String raw = request.query(name);
        if (raw == null) {
          if (mode == Mode.REQUIRED) {
            errors.add(new FieldError(name, "REQUIRED", "is required"));
          }
          return defaultValue;
        }
        return ParamValues.parse(type, name, raw, errors);
      }
    }
  }

  @Override
  public String toString() {
    return "QueryParam[" + name + ": " + type.getSimpleName() + ", " + mode + "]";
  }
}
