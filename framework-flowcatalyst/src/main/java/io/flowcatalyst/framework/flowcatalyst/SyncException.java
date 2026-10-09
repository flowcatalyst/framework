package io.flowcatalyst.framework.flowcatalyst;

import java.util.stream.Collectors;

/**
 * At least one category of a sync failed. Every category was attempted first; the report says
 * which succeeded and which did not. What did sync stays synced.
 */
public final class SyncException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient SyncReport report;

  /**
   * Creates the exception.
   *
   * @param report the full report, with at least one failed category
   */
  public SyncException(SyncReport report) {
    super(
        "definition sync failed: "
            + report.categories().stream()
                .filter(r -> r.outcome() == CategoryResult.Outcome.FAILED)
                .map(r -> r.category() + " (" + r.message() + ")")
                .collect(Collectors.joining("; ")));
    this.report = report;
  }

  /**
   * The full report.
   *
   * @return every category's result
   */
  public SyncReport report() {
    return report;
  }
}
