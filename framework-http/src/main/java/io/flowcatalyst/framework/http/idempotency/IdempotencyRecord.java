package io.flowcatalyst.framework.http.idempotency;

import org.jspecify.annotations.Nullable;

/**
 * A held key.
 *
 * @param requestHash the hash of the request that took it
 * @param answer the kept answer, or null while that request still runs
 */
public record IdempotencyRecord(String requestHash, @Nullable StoredAnswer answer) {}
