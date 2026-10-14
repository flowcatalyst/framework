package io.flowcatalyst.framework.http.openapi;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A JSON Schema written in code, for what the type walk cannot derive (OpenAPI contract section
 * 5.1): an application value type ({@link OpenApi#schema}), a property with a fixed value ({@link
 * OpenApi#property}), or a union without a discriminator. Immutable; each method returns a copy.
 *
 * <pre>{@code
 * OpenApi.schema(app, Money.class, Schema.string().pattern("^-?[0-9]+\\.[0-9]{2}$"));
 * OpenApi.property(app, Csrf.class, "mode", Schema.string().constant("fetch-metadata"));
 * OpenApi.schema(app, Session.class, Schema.oneOf(Session.SignedIn.class, Session.Anonymous.class));
 * }</pre>
 *
 * <p>A pattern here is a JSON Schema (ECMA-262) pattern, written as the document should carry it:
 * it is not anchored for you.
 */
public final class Schema {

  /** Keywords in insertion order; values are String, Boolean, Long, BigDecimal, List, Schema or Class. */
  final Map<String, Object> keywords;

  private Schema(Map<String, Object> keywords) {
    this.keywords = keywords;
  }

  private static Schema type(String type) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", type);
    return new Schema(m);
  }

  /**
   * {@code {"type": "string"}}.
   *
   * @return the schema
   */
  public static Schema string() {
    return type("string");
  }

  /**
   * {@code {"type": "integer", "format": "int32"}}.
   *
   * @return the schema
   */
  public static Schema int32() {
    return type("integer").with("format", "int32");
  }

  /**
   * {@code {"type": "integer", "format": "int64"}}.
   *
   * @return the schema
   */
  public static Schema int64() {
    return type("integer").with("format", "int64");
  }

  /**
   * {@code {"type": "number", "format": "double"}}.
   *
   * @return the schema
   */
  public static Schema number() {
    return type("number").with("format", "double");
  }

  /**
   * {@code {"type": "boolean"}}.
   *
   * @return the schema
   */
  public static Schema bool() {
    return type("boolean");
  }

  /**
   * {@code {"type": "array", "items": ...}}.
   *
   * @param items the element schema
   * @return the schema
   */
  public static Schema array(Schema items) {
    return type("array").with("items", Objects.requireNonNull(items));
  }

  /**
   * The schema the type walk gives a type: a {@code $ref} for a record or a sealed interface, the
   * type table's entry otherwise.
   *
   * @param type the type
   * @return the schema
   */
  public static Schema of(Class<?> type) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("$type", Objects.requireNonNull(type));
    return new Schema(m);
  }

  /**
   * {@code {"oneOf": [...]}} of the given types, each walked (records become components), without
   * a {@code discriminator}: for a union whose variants are told apart by a property with a fixed
   * value ({@link #constant}), such as the session answer.
   *
   * @param variants the variant types, in the order the document lists them
   * @return the schema
   */
  public static Schema oneOf(Class<?>... variants) {
    if (variants.length < 2) {
      throw new IllegalArgumentException("oneOf needs at least two variants");
    }
    List<Object> list = new ArrayList<>();
    for (Class<?> v : variants) {
      list.add(of(v));
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("oneOf", List.copyOf(list));
    return new Schema(m);
  }

  /**
   * Adds {@code format}.
   *
   * @param value the format, for example {@code date-time}
   * @return a copy
   */
  public Schema format(String value) {
    return with("format", value);
  }

  /**
   * Adds {@code pattern}, an ECMA-262 regular expression, not anchored for you.
   *
   * @param ecmaRegex the pattern
   * @return a copy
   */
  public Schema pattern(String ecmaRegex) {
    return with("pattern", ecmaRegex);
  }

  /**
   * Adds {@code minLength}.
   *
   * @param value the minimum length
   * @return a copy
   */
  public Schema minLength(int value) {
    return with("minLength", (long) value);
  }

  /**
   * Adds {@code maxLength}.
   *
   * @param value the maximum length
   * @return a copy
   */
  public Schema maxLength(int value) {
    return with("maxLength", (long) value);
  }

  /**
   * Adds {@code minimum}.
   *
   * @param value the lowest value
   * @return a copy
   */
  public Schema minimum(BigDecimal value) {
    return with("minimum", value);
  }

  /**
   * Adds {@code maximum}.
   *
   * @param value the highest value
   * @return a copy
   */
  public Schema maximum(BigDecimal value) {
    return with("maximum", value);
  }

  /**
   * Adds {@code const}: the property always has this value.
   *
   * @param value a string
   * @return a copy
   */
  public Schema constant(String value) {
    return with("const", value);
  }

  /**
   * Adds {@code const}: the property always has this value.
   *
   * @param value a boolean
   * @return a copy
   */
  public Schema constant(boolean value) {
    return with("const", value);
  }

  /**
   * Adds {@code enum}.
   *
   * @param values the allowed values, in this order
   * @return a copy
   */
  public Schema enumOf(String... values) {
    return with("enum", List.of((Object[]) values));
  }

  /**
   * Adds {@code description}.
   *
   * @param text the description
   * @return a copy
   */
  public Schema description(String text) {
    return with("description", text);
  }

  private Schema with(String key, Object value) {
    if (keywords.containsKey("$type") || keywords.containsKey("oneOf")) {
      throw new IllegalStateException("Schema.of and Schema.oneOf take no further keywords");
    }
    Map<String, Object> m = new LinkedHashMap<>(keywords);
    m.put(key, value);
    return new Schema(m);
  }

  @Override
  public String toString() {
    return "Schema" + keywords;
  }
}
