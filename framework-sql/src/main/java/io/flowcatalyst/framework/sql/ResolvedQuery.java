package io.flowcatalyst.framework.sql;

import java.util.List;

/**
 * A query with its parameters and columns resolved: all the generator needs.
 *
 * @param query the parsed query
 * @param params one per distinct parameter name, in order of first appearance
 * @param columns the result columns in select order; empty for {@code :exec}
 */
public record ResolvedQuery(Query query, List<ResolvedParam> params, List<ResolvedColumn> columns) {

  /** Copies the lists. */
  public ResolvedQuery {
    params = List.copyOf(params);
    columns = List.copyOf(columns);
  }
}
