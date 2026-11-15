package io.flowcatalyst.framework.partitions;

import java.util.List;

/**
 * What one table's pass did.
 *
 * @param table the schema-qualified table
 * @param created the partitions the pass created
 * @param dropped the partitions it removed
 * @param archived the partitions it wrote to the object store (a partition archived and then not
 *     dropped is counted as archived)
 * @param failed the steps that failed, in step order of the pass; empty when none did (the log has the
 *     cause)
 */
public record PassResult(String table, int created, int dropped, int archived, List<Step> failed) {

  /** Copies the list. */
  public PassResult {
    failed = List.copyOf(failed);
  }

  /**
   * Whether no step failed.
   *
   * @return true when {@link #failed()} is empty
   */
  public boolean passed() {
    return failed.isEmpty();
  }
}
