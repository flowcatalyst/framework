package io.flowcatalyst.framework.sql;

import org.jspecify.annotations.Nullable;

/** Maps PostgreSQL type names to Java value types (checked-SQL contract, section 2). */
final class TypeMapper {

  /**
   * A mapped type.
   *
   * @param type the Java value type
   * @param array whether it is a one-dimensional array
   * @param elementPgType the database's name for the element type
   */
  record Mapped(ValueType type, boolean array, String elementPgType) {}

  private TypeMapper() {}

  /**
   * Maps a type name as the JDBC driver reports it.
   *
   * @param pgTypeName the name, for example {@code int4} or {@code _text}
   * @return the mapping, or null when the type is not supported
   */
  static @Nullable Mapped map(String pgTypeName) {
    boolean array = pgTypeName.startsWith("_");
    String element = array ? pgTypeName.substring(1) : pgTypeName;
    ValueType t = scalar(element);
    return t == null ? null : new Mapped(t, array, element);
  }

  private static @Nullable ValueType scalar(String name) {
    return switch (name) {
      case "text", "varchar", "bpchar", "char" -> ValueType.STRING;
      case "int2", "int4" -> ValueType.INT;
      case "int8" -> ValueType.LONG;
      case "bool" -> ValueType.BOOLEAN;
      case "numeric" -> ValueType.BIG_DECIMAL;
      case "float4" -> ValueType.FLOAT;
      case "float8" -> ValueType.DOUBLE;
      case "timestamptz" -> ValueType.INSTANT;
      case "timestamp" -> ValueType.LOCAL_DATE_TIME;
      case "date" -> ValueType.LOCAL_DATE;
      case "uuid" -> ValueType.UUID;
      case "bytea" -> ValueType.BYTES;
      case "json", "jsonb" -> ValueType.JSON;
      default -> null;
    };
  }
}
