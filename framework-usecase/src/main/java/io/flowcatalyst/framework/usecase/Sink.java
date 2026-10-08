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
   * Writes the audit row.
   *
   * @param tx the attempt's transaction
   * @param entry the audit entry
   * @throws Exception on any failure; the attempt rolls back
   */
  void writeAudit(Tx tx, AuditEntry entry) throws Exception;
}
