package io.flowcatalyst.framework.http;

/**
 * A typed path parameter of an {@link Operation}: every {@code {name}} in the operation's pattern
 * is declared with one. A value that does not parse is a {@code 400 VALIDATION} naming the
 * parameter.
 *
 * <pre>{@code
 * static final PathParam<String> ID = PathParam.string("id");
 * static final PathParam<OrderStatus> STATUS = PathParam.enumOf("status", OrderStatus.class);
 * }</pre>
 *
 * @param <T> the value type
 */
public final class PathParam<T> {

  private final String name;
  private final Class<T> type;

  private PathParam(String name, Class<T> type) {
    ParamValues.checkName(name, true);
    ParamValues.checkType(type);
    this.name = name;
    this.type = type;
  }

  /**
   * A text parameter.
   *
   * @param name the name in the pattern
   * @return the parameter
   */
  public static PathParam<String> string(String name) {
    return new PathParam<>(name, String.class);
  }

  /**
   * A 32-bit integer parameter.
   *
   * @param name the name in the pattern
   * @return the parameter
   */
  public static PathParam<Integer> integer(String name) {
    return new PathParam<>(name, Integer.class);
  }

  /**
   * A 64-bit integer parameter.
   *
   * @param name the name in the pattern
   * @return the parameter
   */
  public static PathParam<Long> longInt(String name) {
    return new PathParam<>(name, Long.class);
  }

  /**
   * A parameter whose value is the exact name of an enum constant.
   *
   * @param <E> the enum type
   * @param name the name in the pattern
   * @param type the enum class
   * @return the parameter
   */
  public static <E extends Enum<E>> PathParam<E> enumOf(String name, Class<E> type) {
    return new PathParam<>(name, type);
  }

  /**
   * The name.
   *
   * @return the name in the pattern
   */
  public String name() {
    return name;
  }

  /**
   * The value type: {@code String}, {@code Integer}, {@code Long} or an enum.
   *
   * @return the class
   */
  public Class<T> type() {
    return type;
  }

  @Override
  public String toString() {
    return "PathParam[" + name + ": " + type.getSimpleName() + "]";
  }
}
