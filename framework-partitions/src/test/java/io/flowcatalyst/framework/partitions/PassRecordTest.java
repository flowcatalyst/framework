package io.flowcatalyst.framework.partitions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** Contract sections 10 and 11: what ran the last pass and who, the rows total, and the structured key. */
class PassRecordTest extends PartitionsTestBase {

  private static LastPass last(Started s, String table) {
    return s.ps().tableStatus(table).orElseThrow().lastPass().orElseThrow(() -> new AssertionError("no last pass for " + table));
  }

  @Test
  void theLastPassSaysWhatTriggeredItAndWho() throws IOException {
    exec(ID_TABLE);
    Cfg cfg = Cfg.of(monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90)))).every(Duration.ofHours(1));
    Started a = start(cfg);
    Started b = start(cfg); // another replica reads the record

    a.ps().pass(); // the loop's entry point
    assertThat(last(b, "readings").trigger()).contains(PassTrigger.LOOP);
    assertThat(last(b, "readings").actor()).as("the loop has no actor").isEmpty();

    a.ps().runPass(operator(), "readings");
    assertThat(last(b, "readings").trigger()).contains(PassTrigger.MANUAL);
    assertThat(last(b, "readings").actor()).contains("prn_OP1");

    a.ps().runPass(Context.of(new ClientId("ops")), ""); // no principal
    assertThat(last(b, "readings").trigger()).contains(PassTrigger.MANUAL);
    assertThat(last(b, "readings").actor()).as("a manual pass without a principal has no actor").isEmpty();

    a.ps().pass();
    assertThat(last(b, "readings").trigger()).as("the next loop pass overwrites the record").contains(PassTrigger.LOOP);
    assertThat(last(b, "readings").actor()).isEmpty();

    // a refused manual pass records nothing
    assertThatThrownBy(() -> a.ps().runPass(operator(), "nope")).isInstanceOf(PartitionRefusedException.class);
    assertThat(last(b, "readings").trigger()).contains(PassTrigger.LOOP);
  }

  @Test
  void aRowRecordedBeforeTheMigrationReadsWithoutTriggerAndActor() throws IOException {
    exec(ID_TABLE);
    Started s = start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90)))).every(Duration.ofHours(1)));
    exec(
        "INSERT INTO fw_partition_passes (table_name, ended_at, ensure_status, retention_status, archive_status, created, dropped, archived, pass_trigger, actor) "
            + "VALUES ('public.readings', '2026-11-14 10:00:00+00', 'OK', 'OK', 'SKIPPED', 1, 0, 0, NULL, NULL) "
            + "ON CONFLICT (table_name) DO UPDATE SET pass_trigger = NULL, actor = NULL");
    LastPass lp = last(s, "readings");
    assertThat(lp.trigger()).isEmpty();
    assertThat(lp.actor()).isEmpty();
    assertThat(lp.ensure()).isEqualTo(StepStatus.OK);
    assertThat(lp.endedAt()).isEqualTo(T0);
  }

  @Test
  void aTableMadeByHandInTheShapeOfV0002StillRecordsAndReadsAndWarnsOnce() throws IOException {
    createOther(COL_TABLE, moduleMigrations(2));
    Started s =
        start(
            Cfg.of(Table.named("samples").database("other").by(Key.byColumn("recorded_at")).interval(Interval.MONTH).ahead(1).retention(Retention.dropAfter(days(60))))
                .every(Duration.ofHours(1)));
    assertThat(log.text().split("has no pass_trigger and actor columns", -1).length - 1).as("the warning appears once").isEqualTo(1);
    assertThat(log.text()).contains("V0003__add_partition_pass_trigger.sql");
    s.ps().runPass(operator(), "");
    LastPass lp = last(s, "samples");
    assertThat(lp.ensure()).isEqualTo(StepStatus.OK);
    assertThat(lp.trigger()).isEmpty();
    assertThat(lp.actor()).isEmpty();
    assertThat(s.metric("fw_partition_failures_total", "public.samples", "record")).isZero();
  }

  @Test
  void theRowsTotalIsNotKnownUntilAPartitionHasAnEstimate() throws IOException {
    exec(ID_TABLE);
    exec(TRAIL_TABLE);
    Started s =
        start(
            Cfg.of(monthly("readings", Key.byId("rdg"), Retention.keepForever()), monthly("trail", Key.byId("trl"), Retention.keepForever()))
                .every(Duration.ofHours(1)));
    TableStatus st = s.ps().tableStatus("readings").orElseThrow();
    int n = st.partitions().size();
    assertThat(n).isGreaterThanOrEqualTo(2);
    assertThat(st.rows()).as("before ANALYZE").isEqualTo(OptionalLong.empty());
    assertThat(st.partitionsWithoutEstimate()).isEqualTo(n);
    // analyse only the current partition, which holds three rows
    exec("INSERT INTO readings (id) VALUES ('" + idAt(T0) + "'), ('" + idAt(T0.plusSeconds(3600)) + "'), ('" + idAt(T0.plusSeconds(7200)) + "')");
    exec("ANALYZE readings_p20261101");
    st = s.ps().tableStatus("readings").orElseThrow();
    assertThat(st.rows()).as("the sum of the analysed partitions").hasValue(3);
    assertThat(st.partitionsWithoutEstimate()).isEqualTo(n - 1);
    // a table with no partitions is empty, not unknown
    for (String name : parts("trail")) {
      exec("ALTER TABLE trail DETACH PARTITION " + name);
    }
    TableStatus empty = s.ps().tableStatus("trail").orElseThrow();
    assertThat(empty.partitions()).isEmpty();
    assertThat(empty.rows()).hasValue(0);
    assertThat(empty.partitionsWithoutEstimate()).isZero();
  }

  @Test
  void theStatusKeyIsStructuredForEachKind() throws IOException {
    exec(ID_TABLE);
    exec(COL_TABLE);
    exec("CREATE TABLE runs (id text NOT NULL, service_day date NOT NULL, PRIMARY KEY (id, service_day)) PARTITION BY RANGE (service_day)");
    exec("CREATE TABLE evts (event_id text COLLATE \"C\" PRIMARY KEY) PARTITION BY RANGE (event_id)");
    Started s =
        start(
            Cfg.of(
                    monthly("readings", Key.byId("rdg"), Retention.keepForever()),
                    monthly("samples", Key.byColumn("recorded_at"), Retention.keepForever()),
                    monthly("runs", Key.byDateColumn("service_day"), Retention.keepForever()),
                    monthly("evts", Key.byIdColumn("event_id", "evt"), Retention.keepForever()))
                .every(Duration.ofHours(1)));
    record Case(String table, KeyKind kind, String column, String prefix, Optional<KeyColumnType> type) {}
    for (Case c :
        new Case[] {
          new Case("readings", KeyKind.ID, "id", "rdg", Optional.empty()),
          new Case("samples", KeyKind.COLUMN, "recorded_at", "", Optional.of(KeyColumnType.TIMESTAMPTZ)),
          new Case("runs", KeyKind.COLUMN, "service_day", "", Optional.of(KeyColumnType.DATE)),
          new Case("evts", KeyKind.ID, "event_id", "evt", Optional.empty()),
        }) {
      Key k = s.ps().tableStatus(c.table()).orElseThrow().key();
      assertThat(k.kind()).as(c.table()).isEqualTo(c.kind());
      assertThat(k.column()).as(c.table()).isEqualTo(c.column());
      assertThat(k.prefix()).as(c.table()).isEqualTo(c.prefix());
      assertThat(k.columnType()).as(c.table()).isEqualTo(c.type());
    }
  }

  @Test
  void anIdKeyOnAnotherColumnNeedsThatColumnCollatedC() throws IOException {
    exec("CREATE TABLE evts (event_id text PRIMARY KEY) PARTITION BY RANGE (event_id)");
    assertThatThrownBy(() -> start(Cfg.of(monthly("evts", Key.byIdColumn("event_id", "evt"), Retention.keepForever()))))
        .hasMessageContaining("COLLATE \"C\"");
  }

  @Test
  void thePassUsesTheModulesClockNotTheWall() throws IOException {
    exec(ID_TABLE);
    Started s = start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90)))).every(Duration.ofHours(1)));
    Instant when = Instant.parse("2031-02-03T04:05:06Z");
    clock.set(when);
    s.ps().runPass(operator(), "");
    assertThat(last(s, "readings").endedAt()).isEqualTo(when);
    assertThat(parts("readings")).contains("readings_p20310201");
  }
}
