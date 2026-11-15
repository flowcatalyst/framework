/**
 * Range-partitioned tables (partitions contract): partitions created ahead of time, old ones dropped
 * whole or archived to an object store first, a critical health check that goes down when the next
 * partition is missing, a status read and two operator actions. PostgreSQL only.
 */
@NullMarked
package io.flowcatalyst.framework.partitions;

import org.jspecify.annotations.NullMarked;
