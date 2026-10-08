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
    ClientId client) {}
