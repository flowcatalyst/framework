package io.flowcatalyst.framework.usecase;

import java.util.Map;

/**
 * Implemented by a command that wants its data in the audit row. Commands that do not implement
 * it record nothing but the fact of the operation: take care with secrets.
 */
public interface AuditData {

  /**
   * The data to record.
   *
   * @return values that serialise to JSON
   */
  Map<String, Object> auditData();
}
