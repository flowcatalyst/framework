package io.flowcatalyst.framework.partitions;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One attached partition of a table.
 *
 * @param name the partition's name
 * @param from the start of its range, inclusive, UTC
 * @param to the end of its range, exclusive, UTC
 * @param position against the module's clock
 * @param bytes {@code pg_total_relation_size}: the partition with its indexes and TOAST
 * @param rows the planner's estimate ({@code pg_class.reltuples}); empty until the partition was analysed
 *     or vacuumed; never a {@code count(*)}
 * @param removalDue the first instant at which retention removes the partition, the skew guard
 *     included; empty for keepForever
 * @param archived set when the manifest holds an archive of this partition (a partition created again
 *     after it was removed)
 */
public record PartitionStatus(
    String name,
    Instant from,
    Instant to,
    Position position,
    long bytes,
    OptionalLong rows,
    Optional<Instant> removalDue,
    Optional<ArchivedPartition> archived) {}
