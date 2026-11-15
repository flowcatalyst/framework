package io.flowcatalyst.framework.partitions;

import java.time.Instant;

/**
 * What the manifest holds for an archived partition.
 *
 * @param objects the number of objects
 * @param rows the rows in them
 * @param bytes their total size
 * @param archivedAt the time of the latest archive
 */
public record ArchivedPartition(int objects, long rows, long bytes, Instant archivedAt) {}
