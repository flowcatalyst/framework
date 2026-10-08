package io.flowcatalyst.framework.sql;

import java.util.List;

/**
 * One query of a file, parsed.
 *
 * @param name the Java method name
 * @param kind what it returns
 * @param line the 1-based line of its {@code -- name:} line
 * @param doc the comment lines above the SQL, without the dashes
 * @param params the {@code -- param:} declarations
 * @param columns the {@code -- column:} declarations
 * @param sql the text that is executed: parameters are {@code ?}, the layout is the author's
 * @param parameterNames the distinct parameter names, in order of first appearance
 * @param placeholders for each {@code ?} of {@code sql}, the index into {@code parameterNames}
 * @param parameterLines for each parameter name, the line of its first use
 * @param sqlLine the 1-based line the SQL starts on
 */
public record Query(
    String name,
    QueryKind kind,
    int line,
    List<String> doc,
    List<ParamDecl> params,
    List<ColumnDecl> columns,
    String sql,
    List<String> parameterNames,
    List<Integer> placeholders,
    List<Integer> parameterLines,
    int sqlLine) {

  /** Copies the lists. */
  public Query {
    doc = List.copyOf(doc);
    params = List.copyOf(params);
    columns = List.copyOf(columns);
    parameterNames = List.copyOf(parameterNames);
    placeholders = List.copyOf(placeholders);
    parameterLines = List.copyOf(parameterLines);
  }
}
