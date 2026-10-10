package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.Context;
import java.util.List;

/**
 * Where a committed attempt's events and audit row go. They are written inside the attempt's
 * transaction. The framework's implementation is {@link OutboxWriter}.
 */
public interface Sink {

  /**
   * Writes the plan's events, in order.
   *
   * @param tx the attempt's transaction
   * @param context who is acting
   * @param events the events
   * @throws Exception on any failure; the attempt rolls back
   */
  void writeEvents(Tx tx, Context context, List<DomainEvent> events) throws Exception;

  /**
   * Writes the plan's dispatch jobs, in order (write-path contract, section 7a). Called only when
   * the plan carries at least one. The default refuses, so a sink that does not know dispatch
   * jobs fails the attempt rather than dropping them.
   *
   * @param tx the attempt's transaction
   * @param context who is acting
   * @param jobs the jobs, never empty
   * @throws Exception on any failure; the attempt rolls back
   */
  default void writeDispatchJobs(Tx tx, Context context, List<DispatchJob> jobs) throws Exception {
    throw new UnsupportedOperationException(
        getClass().getName() + " does not write dispatch jobs");
  }

  /**
   * Writes the audit row.
   *
   * @param tx the attempt's transaction
   * @param entry the audit entry
   * @throws Exception on any failure; the attempt rolls back
   */
  void writeAudit(Tx tx, AuditEntry entry) throws Exception;
}
