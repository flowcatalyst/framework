package io.flowcatalyst.framework.partitions;

import java.time.Duration;

/**
 * A table's declared retention.
 *
 * @param kind the kind
 * @param after the declared duration; zero for {@link RetentionKind#KEEP}
 * @param store the object store an archive goes to; empty unless the kind is {@link
 *     RetentionKind#ARCHIVE_THEN_DROP}
 */
public record RetentionStatus(RetentionKind kind, Duration after, String store) {}
