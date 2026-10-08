package io.flowcatalyst.framework.sql;

import java.util.List;

/**
 * A parsed {@code .sql} file.
 *
 * @param file the file as it should be shown in messages and headers
 * @param queries its queries, in file order
 */
public record QueryFile(String file, List<Query> queries) {

  /** Copies the list. */
  public QueryFile {
    queries = List.copyOf(queries);
  }
}
