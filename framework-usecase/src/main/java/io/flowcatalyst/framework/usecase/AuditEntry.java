package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.ClientId;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One audit row, as a use case run produces it.
 *
 * @param entityType the aggregate type, or the subject's aggregate when the plan has none
 * @param entityId the aggregate id
 * @param operation the use case name
 * @param data the command's {@link AuditData}, or null
 * @param principalId the acting principal, or null
 * @param performedAt when
 * @param source the result event's source
 * @param correlationId from the context
 * @param applicationCode the context's application, or null
 * @param client the client
 * @param operatorId who acted as the principal (an impersonated field request), or null
 * @param operatorType the operator's type, or null
 */
public record AuditEntry(
    String entityType,
    String entityId,
    String operation,
    @Nullable Map<String, Object> data,
    @Nullable String principalId,
    Instant performedAt,
    String source,
    String correlationId,
    @Nullable String applicationCode,
    ClientId client,
    @Nullable String operatorId,
    @Nullable String operatorType) {

  /**
   * An entry with no operator.
   *
   * @param entityType the aggregate type
   * @param entityId the aggregate id
   * @param operation the use case name
   * @param data the command's audit data, or null
   * @param principalId the acting principal, or null
   * @param performedAt when
   * @param source the result event's source
   * @param correlationId from the context
   * @param applicationCode the application, or null
   * @param client the client
   */
  public AuditEntry(
      String entityType,
      String entityId,
      String operation,
      @Nullable Map<String, Object> data,
      @Nullable String principalId,
      Instant performedAt,
      String source,
      String correlationId,
      @Nullable String applicationCode,
      ClientId client) {
    this(entityType, entityId, operation, data, principalId, performedAt, source, correlationId, applicationCode, client, null, null);
  }
}
