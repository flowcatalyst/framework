package io.flowcatalyst.framework.partitions;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The state of one declared table, read from the catalog and the module's own tables with a fixed
 * number of queries, whatever the number of partitions; it never counts rows.
 *
 * @param name the schema-qualified table
 * @param database the database instance; empty is the default instance
 * @param key the declared partition key: read {@link Key#kind()}, {@link Key#column()}, {@link
 *     Key#prefix()} and {@link Key#columnType()} rather than parsing {@link Key#toString()}
 * @param interval the declared interval
 * @param ahead the declared number of partitions ahead
 * @param retention the declared retention
 * @param healthy the decision of the health check {@code partitions:<table>}: the partition of the next
 *     interval is attached
 * @param lastPass the last pass; empty when none is recorded (a new table, or an instance without
 *     {@code fw_partition_passes})
 * @param partitions the attached partitions the module named, oldest first
 * @param detached the tables (schema-qualified) that carry the module's detach mark and are not attached:
 *     a pass that died between detaching and dropping, which the next pass finishes
 * @param strays the attached partitions (schema-qualified) that are not the module's names; the module
 *     leaves them alone
 * @param bytes the sum of the partitions
 * @param rows the sum of the known estimates of the partitions; empty when the table has partitions and
 *     none has an estimate (the total is not known, which is not the same as empty), and 0 for a table with
 *     no partitions
 * @param partitionsWithoutEstimate how many listed partitions have no estimate
 */
public record TableStatus(
    String name,
    String database,
    Key key,
    Interval interval,
    int ahead,
    RetentionStatus retention,
    boolean healthy,
    Optional<LastPass> lastPass,
    List<PartitionStatus> partitions,
    List<String> detached,
    List<String> strays,
    long bytes,
    OptionalLong rows,
    int partitionsWithoutEstimate) {

  /** Copies the lists. */
  public TableStatus {
    partitions = List.copyOf(partitions);
    detached = List.copyOf(detached);
    strays = List.copyOf(strays);
  }
}
