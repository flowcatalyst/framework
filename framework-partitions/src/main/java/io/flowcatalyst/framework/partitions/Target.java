package io.flowcatalyst.framework.partitions;

import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.metrics.Counter;
import io.flowcatalyst.framework.metrics.Gauge;
import io.flowcatalyst.framework.objectstore.ObjectStore;
import java.time.Instant;
import java.util.Optional;

/** A declared table once its schema and name are resolved against the database; immutable. */
final class Target {

  final Spec spec;
  final String schema;
  final String rel;
  final String qualified;
  final Gauge.Series ahead;
  final Counter.Series dropped;
  final Counter.Series archived;
  final Counter.Series rows;

  Target(Spec spec, String schema, String rel, Metrics metrics) {
    this.spec = spec;
    this.schema = schema;
    this.rel = rel;
    this.qualified = schema + "." + rel;
    this.ahead = metrics.ahead.labels(qualified);
    this.dropped = metrics.dropped.labels(qualified);
    this.archived = metrics.archived.labels(qualified);
    this.rows = metrics.rows.labels(qualified);
  }

  /** The module's metric families, created once. */
  record Metrics(Gauge ahead, Counter failures, Counter dropped, Counter archived, Counter rows) {}

  Table table() {
    return spec.table();
  }

  String name() {
    return spec.table().name();
  }

  Interval interval() {
    return spec.interval();
  }

  Database db() {
    return spec.db();
  }

  /** The store of an archiving table; the declaration check guarantees one. */
  ObjectStore store() {
    ObjectStore s = spec.store();
    if (s == null) {
      throw new IllegalStateException("table " + qualified + " does not archive");
    }
    return s;
  }

  boolean archives() {
    return spec.retention() instanceof Retention.ArchiveThenDrop;
  }

  /** The quoted parent. */
  String ident() {
    return Sql.quote(schema, rel);
  }

  /** The quoted partition of the parent's schema. */
  String childIdent(String name) {
    return Sql.quote(schema, name);
  }

  String detachMark() {
    return Sql.detachMark(qualified);
  }

  Optional<Instant> parse(String name) {
    return PartitionNames.parse(rel, spec.interval(), name);
  }

  boolean due(Instant start, Instant now) {
    return spec.table().due(start, now);
  }
}
