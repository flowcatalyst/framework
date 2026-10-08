package io.flowcatalyst.framework.sql;

import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Describes a query against a live database (prepare, without executing) and resolves its
 * parameters and result columns to Java types, nullness and overrides (checked-SQL contract,
 * section 2).
 */
public final class QueryResolver {

  private static final Pattern OUTER_JOIN =
      Pattern.compile("\\b(LEFT|RIGHT|FULL)\\b(\\s+OUTER)?\\s+JOIN\\b");
  private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern LABEL =
      Pattern.compile(
          "([A-Za-z_][A-Za-z0-9_]*)\\s*([!?])?\\s*(?::\\s*(enum\\s+)?"
              + "([A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)*))?");
  /** PostgreSQL cuts identifiers at this many bytes. */
  private static final int IDENTIFIER_LIMIT = 63;

  private QueryResolver() {}

  /**
   * Resolves one query.
   *
   * @param file the query file as shown in messages
   * @param q the parsed query
   * @param connection a connection to the migrated database
   * @return the resolved query
   * @throws GenerationException naming the file and line when the database rejects the query, a
   *     type is unsupported, or an override is wrong
   */
  public static ResolvedQuery resolve(String file, Query q, Connection connection) {
    List<String> paramTypes = new ArrayList<>();
    List<String[]> columnInfo = new ArrayList<>(); // label, pg type name, "t" when NOT NULL in the table
    try (PreparedStatement ps = connection.prepareStatement(q.sql())) {
      ParameterMetaData pm = ps.getParameterMetaData();
      for (int i = 1; i <= pm.getParameterCount(); i++) {
        paramTypes.add(pm.getParameterTypeName(i));
      }
      ResultSetMetaData md = ps.getMetaData();
      if (md != null) {
        for (int i = 1; i <= md.getColumnCount(); i++) {
          columnInfo.add(
              new String[] {
                md.getColumnLabel(i),
                md.getColumnTypeName(i),
                md.isNullable(i) == ResultSetMetaData.columnNoNulls ? "t" : "f"
              });
        }
      }
    } catch (SQLException e) {
      throw new GenerationException(
          file, q.line(), "query '" + q.name() + "': " + describeError(file, q, e), e);
    }

    List<ResolvedParam> params = resolveParams(file, q, paramTypes);
    List<ResolvedColumn> columns = resolveColumns(file, q, columnInfo);
    return new ResolvedQuery(q, params, columns);
  }

  private static String describeError(String file, Query q, SQLException e) {
    String message = e.getMessage() == null ? e.toString() : e.getMessage();
    if (e instanceof PSQLException pe) {
      ServerErrorMessage server = pe.getServerErrorMessage();
      if (server != null && server.getPosition() > 0) {
        int line = lineOfPosition(q, server.getPosition());
        String clean = message.strip().replaceAll("\\s*Position: \\d+$", "").replace('\n', ' ');
        return clean + " (SQL line " + line + " of " + file + ")";
      }
    }
    return message.strip().replace('\n', ' ');
  }

  /** The file line of a server-side character position; the server saw {@code $n} for each {@code ?}. */
  private static int lineOfPosition(Query q, int position) {
    String sql = q.sql();
    int server = 0;
    int line = q.sqlLine();
    int param = 0;
    boolean[] code = QueryParser.codeMask(sql);
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      int width = 1;
      if (c == '?' && code[i]) {
        param++;
        width = 1 + Integer.toString(param).length();
      }
      if (server + width >= position) {
        return line;
      }
      server += width;
      if (c == '\n') {
        line++;
      }
    }
    return line;
  }

  // ---- parameters ---------------------------------------------------------------------------

  private static List<ResolvedParam> resolveParams(String file, Query q, List<String> paramTypes) {
    List<String> names = q.parameterNames();
    if (paramTypes.size() != q.placeholders().size()) {
      throw new GenerationException(
          file,
          q.line(),
          "query '" + q.name() + "': the database reports " + paramTypes.size() + " parameters, expected "
              + q.placeholders().size());
    }
    List<ResolvedParam> out = new ArrayList<>();
    Set<String> javaNames = new HashSet<>();
    for (int k = 0; k < names.size(); k++) {
      String name = names.get(k);
      int line = q.parameterLines().get(k);
      TypeMapper.Mapped mapped = null;
      String firstType = null;
      for (int p = 0; p < q.placeholders().size(); p++) {
        if (q.placeholders().get(p) != k) {
          continue;
        }
        String pg = paramTypes.get(p);
        TypeMapper.Mapped m = TypeMapper.map(pg);
        if (m == null) {
          throw new GenerationException(
              file, line, "query '" + q.name() + "': parameter :" + name + " has type '" + pg + "', which is not supported");
        }
        if (mapped == null) {
          mapped = m;
          firstType = pg;
        } else if (mapped.type() != m.type() || mapped.array() != m.array()) {
          throw new GenerationException(
              file,
              line,
              "query '" + q.name() + "': parameter :" + name + " is used as '" + firstType + "' and as '" + pg
                  + "'; use two names");
        }
      }
      if (mapped == null) {
        throw new IllegalStateException("parameter without a placeholder");
      }
      ParamDecl decl = QueryParser.findParam(q.params(), name);
      boolean nullable = decl != null && decl.nullable();
      AppType app = null;
      if (decl != null && decl.type() != null) {
        if (mapped.array()) {
          throw new GenerationException(
              file, decl.line(), "query '" + q.name() + "': :" + name + " is an array; a typed value cannot be applied to it");
        }
        app = new AppType(decl.type(), decl.enumType());
      }
      String javaName = Names.lowerCamel(name);
      if (Names.isReservedLocal(javaName)) {
        throw new GenerationException(
            file, line, "query '" + q.name() + "': parameter :" + name + " would be the Java name '" + javaName
                + "', which is reserved; rename it");
      }
      if (!javaNames.add(javaName)) {
        throw new GenerationException(
            file, line, "query '" + q.name() + "': two parameters would both be Java '" + javaName + "'");
      }
      out.add(new ResolvedParam(name, javaName, mapped.type(), mapped.array(), mapped.elementPgType(), app, nullable));
    }
    return out;
  }

  // ---- columns ------------------------------------------------------------------------------

  private static List<ResolvedColumn> resolveColumns(String file, Query q, List<String[]> info) {
    if (q.kind() == QueryKind.EXEC) {
      if (!info.isEmpty()) {
        throw new GenerationException(
            file, q.line(), "query '" + q.name() + "' returns rows; use :one or :many (an :exec returns the row count)");
      }
      if (!q.columns().isEmpty()) {
        throw new GenerationException(
            file, q.columns().get(0).line(), "query '" + q.name() + "' is :exec and has no columns to override");
      }
      return List.of();
    }
    if (info.isEmpty()) {
      throw new GenerationException(
          file, q.line(), "query '" + q.name() + "' returns no columns; use :exec");
    }
    boolean outerJoin = QueryParser.codeMatches(q.sql(), OUTER_JOIN);
    Map<String, ColumnDecl> decls = new HashMap<>();
    for (ColumnDecl d : q.columns()) {
      decls.put(d.name(), d);
    }
    Set<String> used = new HashSet<>();
    Set<String> javaNames = new HashSet<>();
    List<ResolvedColumn> out = new ArrayList<>();
    for (String[] c : info) {
      String label = c[0];
      Nullness nullness = Nullness.DEFAULT;
      String type = null;
      boolean isEnum = false;
      String name;
      if (PLAIN_NAME.matcher(label).matches()) {
        name = label;
      } else {
        if (label.contains(":") && label.getBytes(StandardCharsets.UTF_8).length >= IDENTIFIER_LIMIT) {
          throw new GenerationException(
              file,
              q.line(),
              "query '" + q.name() + "': column alias '" + label + "' is " + IDENTIFIER_LIMIT
                  + " bytes long and PostgreSQL truncates it; put the override in a '-- column:' line");
        }
        Matcher m = LABEL.matcher(label);
        if (!m.matches()) {
          throw new GenerationException(
              file,
              q.line(),
              "query '" + q.name() + "': column '" + label + "' has no usable name; give it an alias (AS name)");
        }
        name = m.group(1);
        if (m.group(2) != null) {
          nullness = m.group(2).equals("!") ? Nullness.NON_NULL : Nullness.NULLABLE;
        }
        isEnum = m.group(3) != null;
        type = m.group(4);
        if (isEnum && type == null) {
          throw new GenerationException(file, q.line(), "query '" + q.name() + "': 'enum' needs a type in '" + label + "'");
        }
      }
      ColumnDecl decl = decls.get(name);
      if (decl != null) {
        used.add(name);
        if (decl.nullness() != Nullness.DEFAULT) {
          if (nullness != Nullness.DEFAULT) {
            throw new GenerationException(
                file, decl.line(), "query '" + q.name() + "': column '" + name + "' is overridden in its alias and in '-- column:'");
          }
          nullness = decl.nullness();
        }
        if (decl.type() != null) {
          if (type != null) {
            throw new GenerationException(
                file, decl.line(), "query '" + q.name() + "': column '" + name + "' has a type in its alias and in '-- column:'");
          }
          type = decl.type();
          isEnum = decl.enumType();
        }
      }

      TypeMapper.Mapped mapped = TypeMapper.map(c[1]);
      if (mapped == null) {
        throw new GenerationException(
            file, q.line(), "query '" + q.name() + "': column '" + name + "' has type '" + c[1] + "', which is not supported");
      }
      AppType app = null;
      if (type != null) {
        if (mapped.array()) {
          throw new GenerationException(
              file, q.line(), "query '" + q.name() + "': column '" + name + "' is an array; a typed value cannot be applied to it");
        }
        app = new AppType(type, isEnum);
      }
      ColumnNullability nullability =
          switch (nullness) {
            case NON_NULL -> ColumnNullability.CHECKED;
            case NULLABLE -> ColumnNullability.NULLABLE;
            case DEFAULT ->
                c[2].equals("t") && !outerJoin ? ColumnNullability.NOT_NULL : ColumnNullability.NULLABLE;
          };
      String javaName = Names.lowerCamel(name);
      if (Names.isReserved(javaName)) {
        throw new GenerationException(
            file, q.line(), "query '" + q.name() + "': column '" + name + "' would be the Java name '" + javaName
                + "', which is reserved; give it an alias");
      }
      if (!javaNames.add(javaName)) {
        throw new GenerationException(
            file, q.line(), "query '" + q.name() + "': two columns would both be '" + javaName + "'; give one an alias");
      }
      out.add(new ResolvedColumn(name, javaName, mapped.type(), mapped.array(), app, nullability));
    }
    for (ColumnDecl d : q.columns()) {
      if (!used.contains(d.name())) {
        throw new GenerationException(
            file, d.line(), "query '" + q.name() + "' declares column '" + d.name() + "' but returns no such column");
      }
    }
    return out;
  }
}
