package io.flowcatalyst.framework.usecase;

import java.sql.SQLException;

/**
 * Writes one append-only row (an activity log line, a projection row, a live-update record) in the
 * attempt's transaction, for {@link Plan#andAppend}. The row is never loaded again by the plan, has
 * no version and is never the subject of the audit row: an append is not an aggregate.
 *
 * <pre>{@code
 * Appender<ActivityLine> activity = (tx, line) -> ActivitySql.insert(tx.connection(), line);
 * return Plan.save(ticket, tickets, event).andAppend(new ActivityLine(ticket.id(), "assigned"), activity);
 * }</pre>
 *
 * @param <R> the row type
 */
@FunctionalInterface
public interface Appender<R> {

  /**
   * Writes the row. Filter nothing and check nothing: the runner calls it once per attempt, in plan
   * order, and a retried attempt rolls back the row written before.
   *
   * @param tx the attempt's transaction
   * @param row the row
   * @throws SQLException on a database error; the attempt fails as for any write (a unique
   *     violation is {@code CONFLICT} / {@code UNIQUE_VIOLATION}, anything else {@code INTERNAL}
   *     at step {@code PERSIST})
   */
  void append(Tx tx, R row) throws SQLException;
}
