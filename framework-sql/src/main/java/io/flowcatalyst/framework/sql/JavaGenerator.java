package io.flowcatalyst.framework.sql;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Writes the Java class for one query file (checked-SQL contract, section 3). Output depends only
 * on the input: same queries, same bytes.
 */
public final class JavaGenerator {

  /** The first line of every generated file; the check uses it to tell generated files. */
  public static final String HEADER_PREFIX = "// Generated from ";

  private final String sourceLabel;
  private final String packageName;
  private final String className;
  private final List<ResolvedQuery> queries;
  private final Set<String> imports = new TreeSet<>();
  private final Set<ValueType> arrayReaders = EnumSet.noneOf(ValueType.class);
  private boolean needsNullRead;

  private JavaGenerator(
      String sourceLabel, String packageName, String className, List<ResolvedQuery> queries) {
    this.sourceLabel = sourceLabel;
    this.packageName = packageName;
    this.className = className;
    this.queries = queries;
  }

  /**
   * Generates the class.
   *
   * @param sourceLabel the query file as it is shown in the header, for example {@code
   *     src/main/sql/com/acme/OrderQueries.sql}
   * @param packageName the package of the generated class
   * @param className the generated class name
   * @param queries the resolved queries of the file, in file order
   * @return the Java source
   */
  public static String generate(
      String sourceLabel, String packageName, String className, List<ResolvedQuery> queries) {
    return new JavaGenerator(sourceLabel, packageName, className, queries).write();
  }

  private String write() {
    StringBuilder body = new StringBuilder();
    body.append("public final class ").append(className).append(" {\n\n");
    body.append("  private ").append(className).append("() {}\n");
    for (ResolvedQuery q : queries) {
      body.append('\n');
      query(body, q);
    }
    if (needsNullRead) {
      body.append("\n  private static IllegalStateException nullRead(String query, String column) {\n");
      body.append(
          "    return new IllegalStateException(\n"
              + "        \"query \" + query + \": column \" + column\n"
              + "            + \" was NULL but is declared non-null with '!'\");\n");
      body.append("  }\n");
    }
    for (ValueType t : arrayReaders) {
      body.append('\n');
      arrayReader(body, t);
    }
    body.append("}\n");

    StringBuilder out = new StringBuilder();
    out.append(HEADER_PREFIX).append(sourceLabel).append(". Do not edit.\n");
    out.append("package ").append(packageName).append(";\n\n");
    for (String i : imports) {
      out.append("import ").append(i).append(";\n");
    }
    if (!imports.isEmpty()) {
      out.append('\n');
    }
    out.append("/** Typed queries of ").append(sourceLabel).append(". Generated; do not edit. */\n");
    out.append(body);
    return out.toString();
  }

  // ---- one query ---------------------------------------------------------------------------

  private void query(StringBuilder out, ResolvedQuery rq) {
    Query q = rq.query();
    String rowName = Names.upperCamel(q.name()) + "Row";
    String constant = Names.constant(q.name()) + "_SQL";
    boolean rows = q.kind() != QueryKind.EXEC;

    if (rows) {
      out.append("  /** One row of {@link #").append(q.name()).append("}. */\n");
      out.append("  public record ").append(rowName).append("(");
      for (int i = 0; i < rq.columns().size(); i++) {
        ResolvedColumn c = rq.columns().get(i);
        out.append(i == 0 ? "\n      " : ",\n      ");
        out.append(columnType(c)).append(' ').append(c.javaName());
      }
      out.append(") {}\n\n");
    }

    out.append("  /** The text of {@link #").append(q.name()).append("}, as executed. */\n");
    out.append("  // ").append(sourceLabel).append(':').append(q.sqlLine()).append('\n');
    out.append("  public static final String ").append(constant).append(" =\n      \"\"\"\n");
    out.append(textBlock(q.sql())).append("\"\"\";\n\n");

    imp("java.sql.Connection");
    imp("java.sql.PreparedStatement");
    imp("java.sql.SQLException");
    out.append("  /**\n");
    for (String d : q.doc()) {
      out.append("   * ").append(d.replace("*/", "* /")).append('\n');
    }
    if (q.doc().isEmpty()) {
      out.append("   * The {@code ").append(q.name()).append("} query.\n");
    }
    out.append("   *\n   * <p>From ").append(sourceLabel).append(':').append(q.line()).append(".\n");
    out.append("   *\n   * @param connection the connection, for example a transaction's\n");
    for (ResolvedParam p : rq.params()) {
      out.append("   * @param ").append(p.javaName()).append(" :").append(p.name()).append('\n');
    }
    out.append("   * @return ").append(returnDoc(q.kind())).append('\n');
    out.append("   * @throws SQLException on a database error\n   */\n");

    out.append("  public static ").append(returnType(q.kind(), rowName)).append(' ').append(q.name());
    out.append("(\n      Connection connection");
    for (ResolvedParam p : rq.params()) {
      out.append(",\n      ").append(paramType(p)).append(' ').append(p.javaName());
    }
    out.append(")\n      throws SQLException {\n");
    out.append("    try (PreparedStatement ps = connection.prepareStatement(").append(constant).append(")) {\n");
    List<Integer> placeholders = q.placeholders();
    for (int i = 0; i < placeholders.size(); i++) {
      bind(out, rq.params().get(placeholders.get(i)), i + 1);
    }
    switch (q.kind()) {
      case EXEC -> out.append("      return ps.executeUpdate();\n");
      case ONE -> {
        imp("java.sql.ResultSet");
        imp("java.util.Optional");
        out.append("      try (ResultSet rs = ps.executeQuery()) {\n");
        out.append("        if (!rs.next()) {\n          return Optional.empty();\n        }\n");
        out.append("        ").append(rowName).append(" row = read").append(rowName).append("(rs);\n");
        out.append("        if (rs.next()) {\n");
        out.append("          throw new IllegalStateException(\n");
        out.append("              \"").append(q.name()).append(": expected at most one row, got more\");\n");
        out.append("        }\n        return Optional.of(row);\n      }\n");
      }
      case MANY -> {
        imp("java.sql.ResultSet");
        imp("java.util.ArrayList");
        imp("java.util.List");
        out.append("      try (ResultSet rs = ps.executeQuery()) {\n");
        out.append("        List<").append(rowName).append("> list = new ArrayList<>();\n");
        out.append("        while (rs.next()) {\n          list.add(read").append(rowName).append("(rs));\n        }\n");
        out.append("        return list;\n      }\n");
      }
    }
    out.append("    }\n  }\n");

    if (rows) {
      imp("java.sql.ResultSet");
      out.append("\n  private static ").append(rowName).append(" read").append(rowName);
      out.append("(ResultSet rs) throws SQLException {\n");
      for (int i = 0; i < rq.columns().size(); i++) {
        readColumn(out, q, rq.columns().get(i), i);
      }
      out.append("    return new ").append(rowName).append("(");
      for (int i = 0; i < rq.columns().size(); i++) {
        out.append(i == 0 ? "" : ", ").append('v').append(i);
      }
      out.append(");\n  }\n");
    }
  }

  private String returnType(QueryKind kind, String rowName) {
    return switch (kind) {
      case ONE -> {
        imp("java.util.Optional");
        yield "Optional<" + rowName + ">";
      }
      case MANY -> {
        imp("java.util.List");
        yield "List<" + rowName + ">";
      }
      case EXEC -> "int";
    };
  }

  private static String returnDoc(QueryKind kind) {
    return switch (kind) {
      case ONE -> "the row, or empty when there is none";
      case MANY -> "the rows, in the order the database returned them";
      case EXEC -> "the number of rows affected";
    };
  }

  // ---- types -------------------------------------------------------------------------------

  private String columnType(ResolvedColumn c) {
    return type(c.type(), c.array(), c.app(), c.nullability() == ColumnNullability.NULLABLE);
  }

  private String paramType(ResolvedParam p) {
    return type(p.type(), p.array(), p.app(), p.nullable());
  }

  private String type(ValueType t, boolean array, @Nullable AppType app, boolean nullable) {
    String base;
    if (app != null) {
      base = app.className();
    } else {
      base = boxedOrPrimitive(t, nullable || array);
    }
    if (array) {
      imp("java.util.List");
      base = "List<" + (app != null ? app.className() : boxedOrPrimitive(t, true)) + ">";
    }
    if (!nullable) {
      return base;
    }
    imp("org.jspecify.annotations.Nullable");
    if (base.endsWith("[]")) {
      return base.substring(0, base.length() - 2) + " @Nullable []";
    }
    int dot = base.indexOf('<') < 0 ? base.lastIndexOf('.') : -1;
    return dot >= 0
        ? base.substring(0, dot + 1) + "@Nullable " + base.substring(dot + 1)
        : "@Nullable " + base;
  }

  private String boxedOrPrimitive(ValueType t, boolean boxed) {
    return switch (t) {
      case STRING, JSON -> "String";
      case INT -> boxed ? "Integer" : "int";
      case LONG -> boxed ? "Long" : "long";
      case BOOLEAN -> boxed ? "Boolean" : "boolean";
      case FLOAT -> boxed ? "Float" : "float";
      case DOUBLE -> boxed ? "Double" : "double";
      case BIG_DECIMAL -> imp("java.math.BigDecimal");
      case INSTANT -> imp("java.time.Instant");
      case LOCAL_DATE_TIME -> imp("java.time.LocalDateTime");
      case LOCAL_DATE -> imp("java.time.LocalDate");
      case UUID -> imp("java.util.UUID");
      case BYTES -> "byte[]";
    };
  }

  private String imp(String fqn) {
    imports.add(fqn);
    return fqn.substring(fqn.lastIndexOf('.') + 1);
  }

  // ---- binding -----------------------------------------------------------------------------

  private void bind(StringBuilder out, ResolvedParam p, int index) {
    String name = p.javaName();
    if (!p.nullable()) {
      out.append("      ").append(bindCall(p, name, index)).append('\n');
      return;
    }
    imp("java.sql.Types");
    out.append("      if (").append(name).append(" == null) {\n");
    out.append("        ps.setNull(").append(index).append(", Types.").append(sqlType(p)).append(");\n");
    out.append("      } else {\n");
    out.append("        ").append(bindCall(p, name, index)).append('\n');
    out.append("      }\n");
  }

  private String bindCall(ResolvedParam p, String name, int index) {
    if (p.array()) {
      imp("java.sql.Array");
      String elements = p.type() == ValueType.BYTES ? name + ".toArray(new byte[0][])" : name + ".toArray()";
      return "ps.setArray(" + index + ", connection.createArrayOf(\"" + p.pgType() + "\", " + elements + "));";
    }
    AppType app = p.app();
    String u = app == null ? name : app.enumType() ? name + ".name()" : name + ".value()";
    return switch (p.type()) {
      case STRING -> "ps.setString(" + index + ", " + u + ");";
      case INT -> "ps.setInt(" + index + ", " + u + ");";
      case LONG -> "ps.setLong(" + index + ", " + u + ");";
      case BOOLEAN -> "ps.setBoolean(" + index + ", " + u + ");";
      case BIG_DECIMAL -> "ps.setBigDecimal(" + index + ", " + u + ");";
      case FLOAT -> "ps.setFloat(" + index + ", " + u + ");";
      case DOUBLE -> "ps.setDouble(" + index + ", " + u + ");";
      case INSTANT -> {
        imp("java.time.OffsetDateTime");
        imp("java.time.ZoneOffset");
        yield "ps.setObject(" + index + ", OffsetDateTime.ofInstant(" + u + ", ZoneOffset.UTC));";
      }
      case LOCAL_DATE_TIME, LOCAL_DATE, UUID -> "ps.setObject(" + index + ", " + u + ");";
      case BYTES -> "ps.setBytes(" + index + ", " + u + ");";
      case JSON -> {
        imp("java.sql.Types");
        yield "ps.setObject(" + index + ", " + u + ", Types.OTHER);";
      }
    };
  }

  private static String sqlType(ResolvedParam p) {
    if (p.array()) {
      return "ARRAY";
    }
    return switch (p.type()) {
      case STRING -> "VARCHAR";
      case INT -> "INTEGER";
      case LONG -> "BIGINT";
      case BOOLEAN -> "BOOLEAN";
      case BIG_DECIMAL -> "NUMERIC";
      case FLOAT -> "REAL";
      case DOUBLE -> "DOUBLE";
      case INSTANT -> "TIMESTAMP_WITH_TIMEZONE";
      case LOCAL_DATE_TIME -> "TIMESTAMP";
      case LOCAL_DATE -> "DATE";
      case UUID, JSON -> "OTHER";
      case BYTES -> "BINARY";
    };
  }

  // ---- reading -----------------------------------------------------------------------------

  /** Writes the local {@code v<i>} holding column {@code i} (0-based) as its Java type. */
  private void readColumn(StringBuilder out, Query q, ResolvedColumn c, int i) {
    int idx = i + 1;
    String v = "v" + i;
    String r = "r" + i;
    String jt = columnType(c);
    boolean nullable = c.nullability() == ColumnNullability.NULLABLE;
    boolean checked = c.nullability() == ColumnNullability.CHECKED;

    if (c.array()) {
      arrayReaders.add(c.type());
      imp("java.sql.Array");
      String reader = "read" + arrayName(c.type()) + "Array";
      if (c.nullability() == ColumnNullability.NOT_NULL) {
        out.append("    ").append(jt).append(' ').append(v).append(" = ").append(reader)
            .append("(rs.getArray(").append(idx).append("));\n");
        return;
      }
      out.append("    Array ").append(r).append(" = rs.getArray(").append(idx).append(");\n");
      if (checked) {
        nullCheck(out, q, c, r + " == null");
        out.append("    ").append(jt).append(' ').append(v).append(" = ").append(reader)
            .append('(').append(r).append(");\n");
      } else {
        out.append("    ").append(jt).append(' ').append(v).append(" = ").append(r).append(" == null ? null : ")
            .append(reader).append('(').append(r).append(");\n");
      }
      return;
    }

    String raw = rawRead(c.type(), "rs", Integer.toString(idx));
    String rawType = rawType(c.type());
    boolean primitive = isPrimitive(rawType);
    if (c.nullability() == ColumnNullability.NOT_NULL) {
      out.append("    ").append(jt).append(' ').append(v).append(" = ")
          .append(wrap(c, convert(c.type(), raw))).append(";\n");
      return;
    }
    out.append("    ").append(rawType).append(' ').append(r).append(" = ").append(raw).append(";\n");
    String isNull = primitive ? "rs.wasNull()" : r + " == null";
    if (checked) {
      nullCheck(out, q, c, isNull);
      out.append("    ").append(jt).append(' ').append(v).append(" = ")
          .append(wrap(c, convert(c.type(), r))).append(";\n");
    } else if (nullable) {
      out.append("    ").append(jt).append(' ').append(v).append(" = ").append(isNull)
          .append(" ? null : ").append(wrap(c, convert(c.type(), r))).append(";\n");
    }
  }

  private void nullCheck(StringBuilder out, Query q, ResolvedColumn c, String condition) {
    needsNullRead = true;
    out.append("    if (").append(condition).append(") {\n");
    out.append("      throw nullRead(\"").append(q.name()).append("\", \"").append(c.name()).append("\");\n");
    out.append("    }\n");
  }

  private static String wrap(ResolvedColumn c, String underlying) {
    AppType app = c.app();
    if (app == null) {
      return underlying;
    }
    return app.enumType()
        ? app.className() + ".valueOf(" + underlying + ")"
        : "new " + app.className() + "(" + underlying + ")";
  }

  private String convert(ValueType t, String raw) {
    return t == ValueType.INSTANT ? raw + ".toInstant()" : raw;
  }

  private String rawType(ValueType t) {
    return switch (t) {
      case STRING, JSON -> "String";
      case INT -> "int";
      case LONG -> "long";
      case BOOLEAN -> "boolean";
      case FLOAT -> "float";
      case DOUBLE -> "double";
      case BIG_DECIMAL -> imp("java.math.BigDecimal");
      case INSTANT -> imp("java.time.OffsetDateTime");
      case LOCAL_DATE_TIME -> imp("java.time.LocalDateTime");
      case LOCAL_DATE -> imp("java.time.LocalDate");
      case UUID -> imp("java.util.UUID");
      case BYTES -> "byte[]";
    };
  }

  private static boolean isPrimitive(String rawType) {
    return switch (rawType) {
      case "int", "long", "boolean", "float", "double" -> true;
      default -> false;
    };
  }

  private String rawRead(ValueType t, String rs, String idx) {
    return switch (t) {
      case STRING, JSON -> rs + ".getString(" + idx + ")";
      case INT -> rs + ".getInt(" + idx + ")";
      case LONG -> rs + ".getLong(" + idx + ")";
      case BOOLEAN -> rs + ".getBoolean(" + idx + ")";
      case FLOAT -> rs + ".getFloat(" + idx + ")";
      case DOUBLE -> rs + ".getDouble(" + idx + ")";
      case BIG_DECIMAL -> rs + ".getBigDecimal(" + idx + ")";
      case INSTANT -> rs + ".getObject(" + idx + ", OffsetDateTime.class)";
      case LOCAL_DATE_TIME -> rs + ".getObject(" + idx + ", LocalDateTime.class)";
      case LOCAL_DATE -> rs + ".getObject(" + idx + ", LocalDate.class)";
      case UUID -> rs + ".getObject(" + idx + ", UUID.class)";
      case BYTES -> rs + ".getBytes(" + idx + ")";
    };
  }

  private static String arrayName(ValueType t) {
    StringBuilder b = new StringBuilder();
    for (String part : t.name().toLowerCase(java.util.Locale.ROOT).split("_")) {
      b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
    }
    return b.toString();
  }

  /** A private reader turning a one-dimensional SQL array into a list. */
  private void arrayReader(StringBuilder out, ValueType t) {
    String element = boxedOrPrimitive(t, true);
    String raw = rawType(t);
    imp("java.util.ArrayList");
    imp("java.util.List");
    imp("java.sql.Array");
    imp("java.sql.ResultSet");
    out.append("  private static List<").append(element).append("> read").append(arrayName(t))
        .append("Array(Array array) throws SQLException {\n");
    out.append("    List<").append(element).append("> list = new ArrayList<>();\n");
    out.append("    try (ResultSet rs = array.getResultSet()) {\n");
    out.append("      while (rs.next()) {\n");
    out.append("        ").append(raw).append(" r = ").append(rawRead(t, "rs", "2")).append(";\n");
    out.append("        if (").append(isPrimitive(raw) ? "rs.wasNull()" : "r == null").append(") {\n");
    out.append("          throw new IllegalStateException(\"a null element in an array column\");\n");
    out.append("        }\n");
    out.append("        list.add(").append(convert(t, "r")).append(");\n");
    out.append("      }\n    } finally {\n      array.free();\n    }\n    return list;\n  }\n");
  }

  // ---- text block --------------------------------------------------------------------------

  /** The SQL as the lines of a text block, indented six spaces, with no trailing newline. */
  private static String textBlock(String sql) {
    String escaped = escape(sql);
    StringBuilder b = new StringBuilder();
    List<String> lines = new ArrayList<>(List.of(escaped.split("\n", -1)));
    for (int i = 0; i < lines.size(); i++) {
      String l = lines.get(i);
      b.append(l.isEmpty() ? "" : "      " + l);
      if (i < lines.size() - 1) {
        b.append('\n');
      }
    }
    return b.toString();
  }

  private static String escape(String sql) {
    StringBuilder b = new StringBuilder();
    int n = sql.length();
    int i = 0;
    while (i < n) {
      char c = sql.charAt(i);
      if (c == '\\') {
        b.append("\\\\");
        i++;
      } else if (c == '"') {
        int j = i;
        while (j < n && sql.charAt(j) == '"') {
          j++;
        }
        boolean escapeAll = j - i >= 3;
        for (int k = i; k < j; k++) {
          boolean last = k == n - 1;
          b.append(escapeAll || last ? "\\\"" : "\"");
        }
        i = j;
      } else {
        b.append(c);
        i++;
      }
    }
    return b.toString();
  }
}
