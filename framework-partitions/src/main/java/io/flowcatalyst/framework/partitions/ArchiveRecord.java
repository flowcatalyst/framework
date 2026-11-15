package io.flowcatalyst.framework.partitions;

import java.time.Instant;
import java.util.Optional;

/**
 * One archived partition, from the manifest.
 *
 * @param table the schema-qualified table
 * @param partition the partition's name
 * @param from the partition's range start, parsed from its name; empty when the name is not one of the
 *     table's names (the declared interval changed)
 * @param to the range end
 * @param objects the number of objects
 * @param rows the rows in them
 * @param bytes their total size
 * @param archivedAt the time of the latest archive
 * @param key the key of the first object in the store ({@link Partitions#archiveKey}); the others sit beside it
 */
public record ArchiveRecord(
    String table,
    String partition,
    Optional<Instant> from,
    Optional<Instant> to,
    int objects,
    long rows,
    long bytes,
    Instant archivedAt,
    String key) {}
