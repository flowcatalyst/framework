package io.flowcatalyst.framework.partitions;

/**
 * What {@link Partitions#ensure} did.
 *
 * @param created the partitions it created
 * @param alreadyDue how many partitions of the range are past retention, which the next pass removes (so
 *     an import can be warned before it relies on them)
 */
public record EnsureResult(int created, int alreadyDue) {}
