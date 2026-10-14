package io.flowcatalyst.framework.http;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * A generic type, captured by an anonymous subclass, for an {@link Operation} whose response is a
 * generic record:
 *
 * <pre>{@code
 * Operation.get("/api/orders", new TypeRef<CursorPage<OrderView>>() {})
 * }</pre>
 *
 * @param <T> the type
 */
public abstract class TypeRef<T> {

  private final Type type;

  /**
   * Captures the type argument of the anonymous subclass.
   *
   * @throws IllegalArgumentException when created without a type argument
   */
  protected TypeRef() {
    Type superclass = getClass().getGenericSuperclass();
    if (!(superclass instanceof ParameterizedType p)) {
      throw new IllegalArgumentException("a TypeRef is created as new TypeRef<Type>() {}");
    }
    this.type = p.getActualTypeArguments()[0];
  }

  /**
   * The captured type.
   *
   * @return a class or a parameterised type
   */
  public final Type type() {
    return type;
  }

  @Override
  public String toString() {
    return "TypeRef<" + type.getTypeName() + ">";
  }
}
