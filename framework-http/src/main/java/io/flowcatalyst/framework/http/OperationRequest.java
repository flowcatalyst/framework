package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.context.Context;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The request an {@link OperationHandler} receives: the body decoded and validated, the declared
 * parameters parsed, and the raw {@link Request} for anything else (headers).
 *
 * @param <B> the body type ({@link NoBody} when the operation has none)
 */
public final class OperationRequest<B> {

  private final Request raw;
  private final B body;
  private final Map<Object, @Nullable Object> values;

  OperationRequest(Request raw, B body, Map<Object, @Nullable Object> values) {
    this.raw = raw;
    this.body = body;
    this.values = values;
  }

  /**
   * The decoded, validated body.
   *
   * @return the body; {@link NoBody#VALUE} for an operation without one
   */
  public B body() {
    return body;
  }

  /**
   * A path parameter's value.
   *
   * @param <T> the value type
   * @param param a parameter the operation declares
   * @return the value
   * @throws IllegalArgumentException when the operation does not declare it
   */
  @SuppressWarnings("unchecked")
  public <T> T path(PathParam<T> param) {
    if (!values.containsKey(param)) {
      throw new IllegalArgumentException("the operation does not declare " + param);
    }
    return (T) java.util.Objects.requireNonNull(values.get(param));
  }

  /**
   * A query parameter's value.
   *
   * @param <T> the value type
   * @param param a parameter the operation declares
   * @return the value: null for an absent optional parameter, the default when one was given, an
   *     empty list for an absent repeated parameter
   * @throws IllegalArgumentException when the operation does not declare it
   */
  @SuppressWarnings("unchecked")
  public <T extends @Nullable Object> T query(QueryParam<T> param) {
    if (!values.containsKey(param)) {
      throw new IllegalArgumentException("the operation does not declare " + param);
    }
    return (T) values.get(param);
  }

  /**
   * The resolved context.
   *
   * @return the context
   * @throws IllegalStateException for an operation that does not require a context
   */
  public Context context() {
    return raw.context();
  }

  /**
   * The request itself, for headers and other details.
   *
   * @return the request
   */
  public Request raw() {
    return raw;
  }
}
