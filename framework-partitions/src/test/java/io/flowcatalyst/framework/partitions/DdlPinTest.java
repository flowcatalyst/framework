package io.flowcatalyst.framework.partitions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Pins every statement that changes the catalog, byte for byte, against the Go implementation
 * ({@code go/partitions/ensure.go}, {@code retention.go}, {@code archive.go}, {@code table.go}): the expected strings
 * below are what Go sends for the same declaration and clock, written out by hand from its code.
 */
class DdlPinTest {

  @Test
  void theCreateStatementForAnIdKey() {
    Key key = Key.byId("rdg");
    Instant start = Instant.parse("2026-11-01T00:00:00Z");
    Instant next = Interval.MONTH.next(start);
    String parent = Sql.quote("public", "readings");
    String child = Sql.quote("public", PartitionNames.name("readings", start));
    assertThat(Sql.createPartition(child, parent, key.bound(start), key.bound(next)))
        .isEqualTo(
            "CREATE TABLE IF NOT EXISTS \"public\".\"readings_p20261101\" PARTITION OF \"public\".\"readings\""
                + " FOR VALUES FROM ('rdg_6GS8SZ8000000') TO ('rdg_6H2XSNG000000')");
  }

  @Test
  void theCreateStatementForATimestamptzKeyAndADateKey() {
    Instant start = Instant.parse("2026-11-09T00:00:00Z");
    Instant next = Interval.WEEK.next(start);
    String parent = Sql.quote("telemetry", "samples");
    String child = Sql.quote("telemetry", PartitionNames.name("samples", start));
    assertThat(Sql.createPartition(child, parent, Key.byColumn("recorded_at").bound(start), Key.byColumn("recorded_at").bound(next)))
        .isEqualTo(
            "CREATE TABLE IF NOT EXISTS \"telemetry\".\"samples_p20261109\" PARTITION OF \"telemetry\".\"samples\""
                + " FOR VALUES FROM ('2026-11-09 00:00:00+00') TO ('2026-11-16 00:00:00+00')");
    Instant year = Instant.parse("2026-01-01T00:00:00Z");
    assertThat(
            Sql.createPartition(
                Sql.quote("public", PartitionNames.name("runs", year)),
                Sql.quote("public", "runs"),
                Key.byDateColumn("d").bound(year),
                Key.byDateColumn("d").bound(Interval.YEAR.next(year))))
        .isEqualTo(
            "CREATE TABLE IF NOT EXISTS \"public\".\"runs_p20260101\" PARTITION OF \"public\".\"runs\""
                + " FOR VALUES FROM ('2026-01-01') TO ('2027-01-01')");
  }

  @Test
  void theTransactionPreambleDetachDropMarkAndArchiveRead() {
    assertThat(Sql.setLockTimeout()).isEqualTo("SET LOCAL lock_timeout = '10s'");
    assertThat(Sql.advisoryLock()).isEqualTo("SELECT pg_advisory_xact_lock(?)");
    String parent = Sql.quote("public", "readings");
    String child = Sql.quote("public", "readings_p20260501");
    assertThat(Sql.detach(parent, child))
        .isEqualTo("ALTER TABLE \"public\".\"readings\" DETACH PARTITION \"public\".\"readings_p20260501\"");
    assertThat(Sql.drop(child)).isEqualTo("DROP TABLE \"public\".\"readings_p20260501\"");
    assertThat(Sql.comment(child, Sql.detachMark("public.readings")))
        .isEqualTo(
            "COMMENT ON TABLE \"public\".\"readings_p20260501\" IS 'fw-partitions: detached from public.readings for archiving'");
    assertThat(Sql.archiveSelect(child)).isEqualTo("SELECT row_to_json(t)::text FROM \"public\".\"readings_p20260501\" t");
  }

  @Test
  void identifiersAreQuotedAndQuotesDoubled() {
    assertThat(Sql.quote("a\"b", "c")).isEqualTo("\"a\"\"b\".\"c\"");
    assertThat(Sql.quote("only")).isEqualTo("\"only\"");
  }

  @Test
  void theArchiveKeysAreTheContractsKeys() {
    assertThat(Partitions.archiveKey("public", "readings", "readings_p20260501"))
        .isEqualTo("partitions/public.readings/readings_p20260501.ndjson.gz");
    assertThat(Archiver.partKey("public", "readings", "readings_p20260501", 1))
        .isEqualTo("partitions/public.readings/readings_p20260501.ndjson.gz");
    assertThat(Archiver.partKey("public", "readings", "readings_p20260501", 3))
        .isEqualTo("partitions/public.readings/readings_p20260501.part3.ndjson.gz");
    assertThat(Archiver.CONTENT_TYPE).isEqualTo("application/gzip");
  }
}
