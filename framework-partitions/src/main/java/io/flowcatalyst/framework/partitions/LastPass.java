package io.flowcatalyst.framework.partitions;

import java.time.Instant;
import java.util.Optional;

/**
 * The last pass of a table, by whichever replica ran it (the loop or a manual one). It is a record in
 * the table's own database, overwritten by the next pass: it shows what ran the latest pass and who, not a
 * history and not an audit trail.
 *
 * @param endedAt when the pass ended, UTC
 * @param ensure the ensure step
 * @param retention the retention step; {@link StepStatus#SKIPPED} for keepForever
 * @param archive the archive step; {@link StepStatus#SKIPPED} unless the retention archives
 * @param created partitions created
 * @param dropped partitions dropped
 * @param archived partitions archived
 * @param trigger what ran the pass; empty when the row was recorded before the column existed, or on an
 *     instance whose table lacks it
 * @param actor the principal id of a manual pass; empty for the loop's pass and when none was recorded
 */
public record LastPass(
    Instant endedAt,
    StepStatus ensure,
    StepStatus retention,
    StepStatus archive,
    int created,
    int dropped,
    int archived,
    Optional<PassTrigger> trigger,
    Optional<String> actor) {}
