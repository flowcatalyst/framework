package io.flowcatalyst.framework.partitions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.core.HealthResult;
import io.flowcatalyst.framework.locks.AcquireResult;
import io.flowcatalyst.framework.locks.LockKey;
import io.flowcatalyst.framework.locks.LockStore;
import io.flowcatalyst.framework.objectstore.ObjectStoreFactories;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Contract sections 10 to 12 and 14: the status read, the last pass, the two operator actions. */
class PartitionsStatusTest extends PartitionsTestBase {

  static PartitionStatus partition(TableStatus st, String name) {
    return st.partitions().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError("no partition " + name + " in " + names(st)));
  }

  static List<String> names(TableStatus st) {
    return st.partitions().stream().map(PartitionStatus::name).toList();
  }

  static TableStatus status(Started s, String table) {
    return s.ps().tableStatus(table).orElseThrow(() -> new AssertionError("no table " + table));
  }

  @Test
  void theStatusReadsDeclarationSizesEstimatesPositionsAndRemovalDue() throws IOException {
    exec(ID_TABLE);
    exec(COL_TABLE);
    exec(TRAIL_TABLE);
    Started s =
        start(
            Cfg.of(
                    monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90))),
                    Table.named("samples").by(Key.byColumn("recorded_at")).interval(Interval.WEEK).ahead(1).retention(Retention.archiveThenDropAfter(days(30), "archive")),
                    Table.named("trail").by(Key.byId("trl")).interval(Interval.MONTH).ahead(2).retention(Retention.keepForever()))
                .store(ObjectStoreFactories.memory()));
    List<TableStatus> all = s.ps().status();
    assertThat(all.stream().map(TableStatus::name)).as("declaration order").containsExactly("public.readings", "public.samples", "public.trail");

    TableStatus readings = all.get(0);
    assertThat(readings.database()).isEmpty();
    assertThat(readings.key()).hasToString("ByID(\"rdg\") on id");
    assertThat(readings.interval()).isEqualTo(Interval.MONTH);
    assertThat(readings.interval().code()).isEqualTo("MONTH");
    assertThat(readings.ahead()).isEqualTo(3);
    assertThat(readings.retention()).isEqualTo(new RetentionStatus(RetentionKind.DROP, days(90), ""));
    assertThat(readings.healthy()).isTrue();
    assertThat(readings.lastPass()).isEmpty();
    assertThat(readings.detached()).isEmpty();
    assertThat(readings.strays()).isEmpty();
    assertThat(names(readings)).containsExactly("readings_p20261101", "readings_p20261201", "readings_p20270101", "readings_p20270201");
    long sum = 0;
    for (PartitionStatus p : readings.partitions()) {
      assertThat(p.bytes()).as(p.name() + " size").isPositive();
      assertThat(p.rows()).as(p.name() + " has no estimate before ANALYZE").isEmpty();
      sum += p.bytes();
    }
    assertThat(readings.bytes()).isEqualTo(sum);
    assertThat(readings.rows()).as("not known, which is not empty").isEmpty();
    assertThat(readings.partitionsWithoutEstimate()).isEqualTo(readings.partitions().size());

    // positions against the module's clock, and the removal instants
    EnsureResult res = s.ps().ensure("readings", Instant.parse("2026-05-10T00:00:00Z"), Instant.parse("2026-07-31T00:00:00Z"));
    assertThat(res).isEqualTo(new EnsureResult(3, 3)); // May to July are three partitions past the retention
    readings = status(s, "readings");
    assertThat(partition(readings, "readings_p20260501").position()).isEqualTo(Position.PAST);
    assertThat(partition(readings, "readings_p20260601").position()).isEqualTo(Position.PAST);
    assertThat(partition(readings, "readings_p20260701").position()).isEqualTo(Position.PAST);
    assertThat(partition(readings, "readings_p20261101").position()).isEqualTo(Position.CURRENT);
    assertThat(partition(readings, "readings_p20261201").position()).isEqualTo(Position.FUTURE);
    assertThat(partition(readings, "readings_p20270201").position()).isEqualTo(Position.FUTURE);
    assertThat(names(readings)).isSorted();
    assertThat(names(readings).get(0)).isEqualTo("readings_p20260501");
    PartitionStatus may = partition(readings, "readings_p20260501");
    assertThat(may.from()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
    assertThat(may.to()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    assertThat(may.removalDue()).contains(Instant.parse("2026-06-01T00:00:00Z").plus(days(90)));

    TableStatus samples = status(s, "public.samples"); // schema-qualified works too
    PartitionStatus week = partition(samples, "samples_p20261109");
    assertThat(week.position()).isEqualTo(Position.CURRENT);
    assertThat(week.removalDue()).contains(Instant.parse("2026-11-16T00:00:00Z").plus(days(30)));
    assertThat(samples.retention()).isEqualTo(new RetentionStatus(RetentionKind.ARCHIVE_THEN_DROP, days(30), "archive"));
    TableStatus trail = status(s, "trail");
    assertThat(trail.retention()).isEqualTo(new RetentionStatus(RetentionKind.KEEP, Duration.ZERO, ""));
    trail.partitions().forEach(p -> assertThat(p.removalDue()).as("keepForever has no removal instant").isEmpty());

    // estimates are absent until the table is analysed, present after
    exec("INSERT INTO readings (id) VALUES ('" + idAt(T0) + "'), ('" + idAt(T0.plusSeconds(3600)) + "'), ('" + idAt(T0.plusSeconds(7200)) + "')");
    exec("ANALYZE readings");
    readings = status(s, "readings");
    readings.partitions().forEach(p -> assertThat(p.rows()).as(p.name() + " after ANALYZE").isPresent());
    assertThat(partition(readings, "readings_p20261101").rows()).hasValue(3);
    assertThat(readings.rows()).hasValue(3);
    assertThat(readings.partitionsWithoutEstimate()).isZero();

    // the clock moves: November is past, December current
    clock.set(Instant.parse("2026-12-10T00:00:00Z"));
    readings = status(s, "readings");
    assertThat(partition(readings, "readings_p20261101").position()).isEqualTo(Position.PAST);
    assertThat(partition(readings, "readings_p20261201").position()).isEqualTo(Position.CURRENT);

    // healthy is the health check's decision: the next partition is attached or not
    clock.set(T0);
    exec("DROP TABLE readings_p20261201");
    readings = status(s, "readings");
    assertThat(readings.healthy()).isFalse();
    assertThat(s.app().health().check().results().get("partitions:readings")).isInstanceOf(HealthResult.Down.class);

    assertThat(s.ps().tableStatus("nope")).isEmpty();
  }

  @Test
  void theStatusListsStraysDetachedAndArchivedPartitionsAndPagesTheArchives() throws Exception {
    exec(COL_TABLE);
    exec("CREATE TABLE samples_legacy PARTITION OF samples FOR VALUES FROM ('2000-01-01 00:00:00+00') TO ('2001-01-01 00:00:00+00')");
    // a pass that died between the detach and the drop: a plain table with the module's mark
    exec("CREATE TABLE samples_p20260301 (id text NOT NULL, recorded_at timestamptz NOT NULL, payload text)");
    exec("COMMENT ON TABLE samples_p20260301 IS 'fw-partitions: detached from public.samples for archiving'");
    // a table of a partition's name without the mark is not the module's
    exec("CREATE TABLE samples_p20260401 (id text)");
    Started s =
        start(
            Cfg.of(Table.named("samples").by(Key.byColumn("recorded_at")).interval(Interval.MONTH).ahead(1).retention(Retention.archiveThenDropAfter(days(60), "archive")))
                .store(ObjectStoreFactories.memory()));
    TableStatus st = status(s, "samples");
    assertThat(st.strays()).containsExactly("public.samples_legacy");
    assertThat(st.detached()).as("the unmarked table is not the module's").containsExactly("public.samples_p20260301");
    assertThat(names(st)).as("a stray is not listed as a partition").doesNotContain("samples_legacy");

    s.ps().ensure("samples", Instant.parse("2026-05-01T00:00:00Z"), Instant.parse("2026-05-31T00:00:00Z"));
    for (int i = 0; i < 7; i++) {
      exec("INSERT INTO samples (id, recorded_at, payload) VALUES ('s" + i + "', '2026-05-" + String.format("%02d", 3 + i) + " 01:00:00+00', 'p')");
    }
    s.ps().pass();
    assertThat(status(s, "samples").detached()).as("the pass finished the detached table").isEmpty();
    List<ArchiveRecord> archives = s.ps().archives("samples", "", 50);
    assertThat(archives).hasSize(2);
    assertThat(archives.get(0).partition()).isEqualTo("samples_p20260501");
    assertThat(archives.get(1).partition()).isEqualTo("samples_p20260301");
    ArchiveRecord mayRec = archives.get(0);
    assertThat(mayRec.table()).isEqualTo("public.samples");
    assertThat(mayRec.rows()).isEqualTo(7);
    assertThat(mayRec.objects()).isEqualTo(1);
    assertThat(mayRec.bytes()).isPositive();
    assertThat(mayRec.key()).isEqualTo(Partitions.archiveKey("public", "samples", "samples_p20260501"));
    assertThat(mayRec.from()).contains(Instant.parse("2026-05-01T00:00:00Z"));
    assertThat(mayRec.to()).contains(Instant.parse("2026-06-01T00:00:00Z"));
    assertThat(mayRec.archivedAt()).isEqualTo(T0);
    // paging: after = the partition name of the last record of the previous page
    List<ArchiveRecord> page1 = s.ps().archives("samples", "", 1);
    assertThat(page1).extracting(ArchiveRecord::partition).containsExactly("samples_p20260501");
    List<ArchiveRecord> page2 = s.ps().archives("public.samples", page1.get(0).partition(), 1);
    assertThat(page2).extracting(ArchiveRecord::partition).containsExactly("samples_p20260301");
    assertThat(s.ps().archives("samples", page2.get(0).partition(), 1)).isEmpty();
    assertThatThrownBy(() -> s.ps().archives("nope", "", 1))
        .isInstanceOfSatisfying(PartitionRefusedException.class, e -> assertThat(e.reason()).isEqualTo(PartitionRefusedException.Reason.TABLE_NOT_DECLARED));
    assertThat(s.ps().archives("samples", "", 0)).as("a limit below 1 is 1").hasSize(1);

    // the partition comes back by hand (a backfill): the manifest says it was archived
    s.ps().ensure("samples", Instant.parse("2026-05-01T00:00:00Z"), Instant.parse("2026-05-31T00:00:00Z"));
    st = status(s, "samples");
    ArchivedPartition back = partition(st, "samples_p20260501").archived().orElseThrow();
    assertThat(back.rows()).isEqualTo(7);
    assertThat(back.objects()).isEqualTo(1);
    assertThat(back.bytes()).isEqualTo(mayRec.bytes());
    assertThat(back.archivedAt()).isEqualTo(T0);
    assertThat(partition(st, "samples_p20261101").archived()).as("never archived").isEmpty();
  }

  @Test
  void aPartitionDroppedWhileTheStatusIsReadIsLeftOutAndTheReadDoesNotFail() throws Exception {
    exec(ID_TABLE);
    Started s = start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.keepForever())));
    assertThat(names(status(s, "readings"))).hasSize(4);
    // A pass drops a partition (an uncommitted DROP holds its lock) while the status asks for its size: the
    // catalog row is in the status query's snapshot, the relation is gone when its size is read, which is NULL.
    try (Connection holder = DriverManager.getConnection(pg.jdbcUrl(), pg.username(), pg.password())) {
      holder.setAutoCommit(false);
      try (var st = holder.createStatement()) {
        st.execute("DROP TABLE readings_p20270201");
      }
      CompletableFuture<TableStatus> read = CompletableFuture.supplyAsync(() -> status(s, "readings"));
      eventually(
          "the status read to wait for the dropped partition's lock",
          () -> count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%pg_total_relation_size%'") > 0);
      assertThat(read).as("blocked behind the drop").isNotDone();
      holder.commit();
      TableStatus st = read.get(30, TimeUnit.SECONDS);
      assertThat(names(st)).as("the dropped partition is left out").containsExactly("readings_p20261101", "readings_p20261201", "readings_p20270101");
      assertThat(st.bytes()).isEqualTo(st.partitions().stream().mapToLong(PartitionStatus::bytes).sum());
    }
  }

  @Test
  void theLastPassIsSharedAcrossReplicas() throws IOException {
    exec(ID_TABLE);
    Cfg quiet = Cfg.of(monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90)))).every(Duration.ofHours(1));
    Started a = start(quiet);
    Started b = start(quiet);
    assertThat(status(b, "readings").lastPass()).as("no pass was recorded before any ran").isEmpty();
    clock.set(Instant.parse("2027-03-15T12:00:00Z")); // November is due, new partitions are needed
    start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90)))).every(Duration.ofMillis(20)));
    eventually("the loop's pass to be recorded", () -> status(a, "readings").lastPass().isPresent());
    for (Started replica : List.of(a, b)) {
      LastPass lp = status(replica, "readings").lastPass().orElseThrow();
      assertThat(lp.endedAt()).isEqualTo(clock.instant());
      assertThat(lp.ensure()).isEqualTo(StepStatus.OK);
      assertThat(lp.retention()).isEqualTo(StepStatus.OK);
      assertThat(lp.archive()).as("archive does not apply to dropAfter").isEqualTo(StepStatus.SKIPPED);
    }
  }

  @Test
  void stepStatusesAreRecordedAndKeepForeverSkipsRetention() throws Exception {
    exec(ID_TABLE);
    exec(TRAIL_TABLE);
    Stores.Flaky store = new Stores.Flaky();
    Started s =
        start(
            Cfg.of(
                    monthly("readings", Key.byId("rdg"), Retention.archiveThenDropAfter(days(90), "archive")),
                    monthly("trail", Key.byId("trl"), Retention.keepForever()))
                .store(store.factory()));
    s.ps().ensure("readings", Instant.parse("2026-05-01T00:00:00Z"), Instant.parse("2026-05-31T00:00:00Z"));
    exec("INSERT INTO readings (id) VALUES ('" + idAt(Instant.parse("2026-05-03T00:00:00Z")) + "')");
    store.putError = "disk on fire";

    List<PassResult> results = s.ps().runPass(operator(), "");
    assertThat(results).hasSize(2);
    assertThat(results.get(0).table()).isEqualTo("public.readings");
    assertThat(results.get(0).passed()).as("the failing archive is in the result, not an exception").isFalse();
    assertThat(results.get(0).failed()).containsExactly(Step.ARCHIVE);
    assertThat(results.get(0).archived()).isZero();
    assertThat(results.get(0).dropped()).isZero();
    assertThat(results.get(1).table()).isEqualTo("public.trail");
    assertThat(results.get(1).passed()).isTrue();
    LastPass lp = status(s, "readings").lastPass().orElseThrow();
    assertThat(lp.ensure()).isEqualTo(StepStatus.OK);
    assertThat(lp.retention()).isEqualTo(StepStatus.OK);
    assertThat(lp.archive()).isEqualTo(StepStatus.FAILED);
    assertThat(status(s, "readings").detached()).as("the failed archive left the table detached").containsExactly("public.readings_p20260501");
    LastPass keep = status(s, "trail").lastPass().orElseThrow();
    assertThat(keep.retention()).isEqualTo(StepStatus.SKIPPED);
    assertThat(keep.archive()).isEqualTo(StepStatus.SKIPPED);
    assertThat(keep.ensure()).isEqualTo(StepStatus.OK);

    // the store recovers: the next manual pass archives, drops, and says so
    store.heal();
    results = s.ps().runPass(operator(), "readings");
    assertThat(results).hasSize(1);
    assertThat(results.get(0).archived()).isEqualTo(1);
    assertThat(results.get(0).dropped()).isEqualTo(1);
    assertThat(results.get(0).passed()).isTrue();
    lp = status(s, "readings").lastPass().orElseThrow();
    assertThat(lp.archive()).isEqualTo(StepStatus.OK);
    assertThat(lp.archived()).isEqualTo(1);
    assertThat(lp.dropped()).isEqualTo(1);
  }

  @Test
  void manualActionsAreRecordedWithThePrincipal() throws IOException {
    exec(ID_TABLE);
    Started s = start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90)))));
    s.ps().runPass(operator(), "public.readings"); // schema-qualified
    s.ps().ensureAs(operator(), "readings", Instant.parse("2026-05-10T00:00:00Z"), Instant.parse("2026-06-10T00:00:00Z"));
    assertThatThrownBy(() -> s.ps().ensureAs(operator(), "readings", T0, T0.minus(Duration.ofDays(1))))
        .isInstanceOfSatisfying(PartitionRefusedException.class, e -> assertThat(e.reason()).isEqualTo(PartitionRefusedException.Reason.RANGE_INVALID));
    assertThatThrownBy(() -> s.ps().runPass(operator(), "nope"))
        .isInstanceOfSatisfying(PartitionRefusedException.class, e -> assertThat(e.reason()).isEqualTo(PartitionRefusedException.Reason.TABLE_NOT_DECLARED));
    String logs = log.text();
    for (String want :
        new String[] {
          "partitions: manual pass",
          "partitions: manual ensure",
          "principal=prn_OP1",
          "principal_kind=USER",
          "correlation_id=",
          "table=public.readings",
          "outcome=ok",
          "from=2026-05-10",
          "to=2026-06-10",
          "created=2",
          "outcome=\"refused: range is invalid: to is before from\"",
          "outcome=\"refused: table is not declared:"
        }) {
      assertThat(logs).as("the log has " + want).contains(want);
    }
  }

  @Test
  void theRecordOfAManualActionCarriesTheTierWhenTheContextHasOne() throws IOException {
    exec(ID_TABLE);
    Started s = start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90)))));
    s.ps().runPass(operator().withTier("ANCHOR"), "readings");
    s.ps().ensureAs(operator().withTier("ANCHOR"), "readings", Instant.parse("2026-05-10T00:00:00Z"), Instant.parse("2026-06-10T00:00:00Z"));
    String withTier = log.text();
    assertThat(withTier.split("tier=ANCHOR", -1).length - 1).as("one tier on the pass and one on the ensure").isEqualTo(2);
    int before = log.text().length();
    s.ps().runPass(operator(), "readings"); // no tier in this context
    assertThat(log.text().substring(before)).contains("partitions: manual pass").doesNotContain("tier=");
  }

  @Test
  void aRunningPassRefusesAManualPassUntilItEnds() throws Exception {
    for (boolean withLocks : new boolean[] {false, true}) {
      pg.reset();
      closeApps();
      log = LogCapture.start();
      exec(ID_TABLE);
      Stores.Blocking store = new Stores.Blocking();
      Cfg quiet = Cfg.of(monthly("readings", Key.byId("rdg"), Retention.archiveThenDropAfter(days(90), "archive"))).store(store.factory()).every(Duration.ofHours(1));
      Started setup = start(quiet);
      setup.ps().ensure("readings", Instant.parse("2026-05-01T00:00:00Z"), Instant.parse("2026-05-31T00:00:00Z"));
      exec("INSERT INTO readings (id) VALUES ('" + idAt(Instant.parse("2026-05-03T00:00:00Z")) + "')");
      Cfg loop = Cfg.of(monthly("readings", Key.byId("rdg"), Retention.archiveThenDropAfter(days(90), "archive"))).store(store.factory()).every(Duration.ofMillis(20));
      if (withLocks) {
        loop.locks();
      }
      Started s = start(loop);
      assertThat(store.entered.await(20, java.util.concurrent.TimeUnit.SECONDS)).as("the loop's pass reached the archive").isTrue();
      for (String table : new String[] {"", "readings"}) {
        // on its own thread and bounded: a manual pass that queued would wait in the blocked put
        CompletableFuture<List<PassResult>> manual = CompletableFuture.supplyAsync(() -> s.ps().runPass(operator(), table));
        assertThatThrownBy(() -> manual.get(10, TimeUnit.SECONDS))
            .as("withLocks=" + withLocks + " table '" + table + "': refused, not queued")
            .isInstanceOf(java.util.concurrent.ExecutionException.class)
            .cause()
            .isInstanceOfSatisfying(PartitionRefusedException.class, e -> assertThat(e.reason()).isEqualTo(PartitionRefusedException.Reason.PASS_RUNNING));
      }
      store.release.countDown();
      eventually(
          "the loop's pass to end and a manual pass to be accepted",
          () -> {
            try {
              return s.ps().runPass(operator(), "readings").size() == 1;
            } catch (PartitionRefusedException e) {
              return false;
            }
          });
    }
  }

  @Test
  void anotherReplicasPassRefusesAManualPassAndTheLockIsFreeAfterwards() throws Exception {
    exec(ID_TABLE);
    LockStore shared = sharedLockStore();
    Started a = start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90)))).every(Duration.ofHours(1)).lockStore(shared));
    // another replica holds the loop's service lock (it is running a pass)
    AcquireResult.Acquired held = (AcquireResult.Acquired) shared.tryAcquire(LockKey.service(Partitions.LOCK_NAME), Duration.ofHours(1));
    exec("DROP TABLE readings_p20261201"); // work a pass would do
    assertThatThrownBy(() -> a.ps().runPass(operator(), ""))
        .isInstanceOfSatisfying(PartitionRefusedException.class, e -> assertThat(e.reason()).isEqualTo(PartitionRefusedException.Reason.PASS_RUNNING));
    assertThat(parts("readings")).as("the refused pass did DDL").doesNotContain("readings_p20261201");
    assertThat(log.text()).contains("refused: the pass lock is held");
    shared.release(held.lease());
    List<PassResult> res = a.ps().runPass(operator(), "");
    assertThat(res).hasSize(1);
    assertThat(res.get(0).created()).isEqualTo(1);
    assertThat(parts("readings")).contains("readings_p20261201");
    // the lock is free again afterwards: the pass released it
    assertThat(shared.tryAcquire(LockKey.service(Partitions.LOCK_NAME), Duration.ofHours(1))).isInstanceOf(AcquireResult.Acquired.class);
  }

  @Test
  void ensureHasTypedRefusalsAndReportsAlreadyDue() throws IOException {
    exec(ID_TABLE);
    Started s = start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90)))));
    record Case(String name, String table, Instant from, Instant to, PartitionRefusedException.Reason want) {}
    for (Case c :
        new Case[] {
          new Case("unknown table", "nope", T0, T0, PartitionRefusedException.Reason.TABLE_NOT_DECLARED),
          new Case("to before from", "readings", T0, T0.minus(Duration.ofDays(1)), PartitionRefusedException.Reason.RANGE_INVALID),
          new Case("before 1970", "readings", Instant.parse("1969-01-01T00:00:00Z"), T0, PartitionRefusedException.Reason.RANGE_INVALID),
          new Case("too large", "readings", Instant.parse("1990-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"), PartitionRefusedException.Reason.RANGE_TOO_LARGE),
        }) {
      assertThatThrownBy(() -> s.ps().ensure(c.table(), c.from(), c.to()))
          .as(c.name())
          .isInstanceOfSatisfying(PartitionRefusedException.class, e -> assertThat(e.reason()).isEqualTo(c.want()));
    }
    // a detached table of a partition's name
    exec("CREATE TABLE readings_p20260801 (id text)");
    assertThatThrownBy(() -> s.ps().ensure("readings", Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-20T00:00:00Z")))
        .isInstanceOfSatisfying(PartitionRefusedException.class, e -> assertThat(e.reason()).isEqualTo(PartitionRefusedException.Reason.DETACHED_LEFTOVER));
    exec("DROP TABLE readings_p20260801");
    // the cutoff is 2026-08-16: May to July are due, August (ends September 1) and later are not
    assertThat(s.ps().ensure("readings", Instant.parse("2026-05-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z")))
        .as("3 due (May to July), 6 created (May to October, the rest exist)")
        .isEqualTo(new EnsureResult(6, 3));
    assertThat(s.ps().ensure("readings", Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-20T00:00:00Z")))
        .as("an existing range that is not due")
        .isEqualTo(new EnsureResult(0, 0));
  }

  @Test
  void aDateKeyedYearlyTableInsertsDropsAndReads() throws IOException {
    exec("CREATE TABLE runs (id text NOT NULL, service_day date NOT NULL, note text, PRIMARY KEY (id, service_day)) PARTITION BY RANGE (service_day)");
    Started s =
        start(
            Cfg.of(
                Table.named("runs").by(Key.byDateColumn("service_day")).interval(Interval.YEAR).ahead(1).retention(Retention.dropAfter(Duration.ofDays(2 * 366)))));
    assertThat(parts("runs")).containsExactly("runs_p20260101", "runs_p20270101");
    exec("INSERT INTO runs (id, service_day) VALUES ('a', '2026-12-31'), ('b', '2027-01-01')");
    assertThat(strings("SELECT id || '@' || tableoid::regclass::text FROM runs ORDER BY id")).containsExactly("a@runs_p20260101", "b@runs_p20270101");
    TableStatus st = status(s, "runs");
    assertThat(st.key()).hasToString("ByDateColumn(\"service_day\")");
    assertThat(st.interval()).isEqualTo(Interval.YEAR);
    assertThat(st.healthy()).isTrue();
    PartitionStatus p = partition(st, "runs_p20260101");
    assertThat(p.from()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(p.to()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    assertThat(p.position()).isEqualTo(Position.CURRENT);
    assertThat(p.removalDue()).contains(Instant.parse("2027-01-01T00:00:00Z").plus(Duration.ofDays(2 * 366)));
    // 2026 ended 2027-01-01; with a retention of 732 days it goes at 2029-01-02
    clock.set(Instant.parse("2029-01-01T00:00:00Z"));
    s.ps().pass();
    assertThat(parts("runs")).as("2026 is still kept at 2029-01-01 (due 2029-01-02)").contains("runs_p20260101", "runs_p20290101");
    clock.set(Instant.parse("2029-01-04T00:00:00Z"));
    s.ps().pass();
    assertThat(parts("runs")).doesNotContain("runs_p20260101");
    assertThat(strings("SELECT id FROM runs")).as("2026's row is gone with its partition and 2027's stays").containsExactly("b");
  }

  @Test
  void aDateColumnNeedsByDateColumnAndATimestamptzColumnNeedsByColumn() throws IOException {
    record Case(String name, String ddl, Key key, String want) {}
    for (Case c :
        new Case[] {
          new Case("ByColumn on a date", "CREATE TABLE runs (id text, service_day date NOT NULL, PRIMARY KEY (id, service_day)) PARTITION BY RANGE (service_day)", Key.byColumn("service_day"), "byDateColumn"),
          new Case("ByDateColumn on a timestamptz", "CREATE TABLE runs (id text, service_day timestamptz NOT NULL, PRIMARY KEY (id, service_day)) PARTITION BY RANGE (service_day)", Key.byDateColumn("service_day"), "Key.byColumn"),
          new Case("ByDateColumn on a timestamp", "CREATE TABLE runs (id text, service_day timestamp NOT NULL, PRIMARY KEY (id, service_day)) PARTITION BY RANGE (service_day)", Key.byDateColumn("service_day"), "must be date"),
          new Case("ByColumn on a timestamp", "CREATE TABLE runs (id text, service_day timestamp NOT NULL, PRIMARY KEY (id, service_day)) PARTITION BY RANGE (service_day)", Key.byColumn("service_day"), "must be timestamptz"),
        }) {
      pg.reset();
      exec(c.ddl());
      assertThatThrownBy(() -> start(Cfg.of(Table.named("runs").by(c.key()).interval(Interval.MONTH).ahead(1).retention(Retention.keepForever()))))
          .as(c.name())
          .isInstanceOf(io.flowcatalyst.framework.core.StartupException.class)
          .hasMessageContaining(c.want());
    }
  }

  @Test
  void aTableOnASecondDatabaseInstanceHasItsOwnStatusAndLastPass() throws IOException {
    createOther(COL_TABLE, moduleMigrations(3));
    exec(ID_TABLE);
    Started s =
        start(
            Cfg.of(
                monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90))),
                Table.named("samples").database("other").by(Key.byColumn("recorded_at")).interval(Interval.MONTH).ahead(1).retention(Retention.dropAfter(days(60)))));
    s.ps().pass();
    TableStatus st = status(s, "samples");
    assertThat(st.database()).isEqualTo("other");
    assertThat(st.partitions()).hasSize(2);
    assertThat(st.healthy()).isTrue();
    assertThat(st.lastPass().orElseThrow().ensure()).isEqualTo(StepStatus.OK);
    assertThat(count(pg.jdbcUrl("other"), "SELECT count(*) FROM fw_partition_passes WHERE table_name = 'public.samples'")).as("the record lives in the table's own database").isEqualTo(1);
    assertThat(count("SELECT count(*) FROM fw_partition_passes WHERE table_name = 'public.samples'")).isZero();
    TableStatus def = status(s, "readings");
    assertThat(def.database()).isEmpty();
    assertThat(def.lastPass()).isPresent();
  }

  @Test
  void anInstanceWithoutThePassTableSaysSoAtStartUpAndRecordsNothing() throws IOException {
    createOther(COL_TABLE, moduleMigrations(1));
    Started s =
        start(Cfg.of(Table.named("samples").database("other").by(Key.byColumn("recorded_at")).interval(Interval.MONTH).ahead(1).retention(Retention.dropAfter(days(60)))));
    assertThat(log.text()).contains("fw_partition_passes does not exist in database instance 'other'");
    s.ps().pass();
    assertThat(status(s, "samples").lastPass()).isEmpty();
    assertThat(s.metric("fw_partition_failures_total", "public.samples", "record")).as("not counted for an instance that has no table").isZero();
  }

  @Test
  void noWarningForAnInstanceThatHasTheTables() throws IOException {
    exec(COL_TABLE);
    start(Cfg.of(Table.named("samples").by(Key.byColumn("recorded_at")).interval(Interval.MONTH).ahead(1).retention(Retention.keepForever())));
    assertThat(log.text()).doesNotContain("fw_partition_passes does not exist");
  }

  @Test
  void aFailedRecordIsLoggedAndCountedAndNeverFailsThePass() throws IOException {
    exec(ID_TABLE);
    Started s = start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90)))));
    exec("ALTER TABLE fw_partition_passes ADD CONSTRAINT never CHECK (created < 0)"); // exists but cannot be written
    s.ps().pass(); // does not throw
    assertThat(s.metric("fw_partition_failures_total", "public.readings", "record")).isEqualTo(1);
    assertThat(log.text()).contains("could not record the last partition pass");
    assertThat(s.ps().runPass(operator(), "readings")).hasSize(1);
  }

  // ---- the number of queries -------------------------------------------------------------------

  @Test
  void theStatusMakesAFixedNumberOfQueriesWhateverThePartitionCount() throws Exception {
    exec(ID_TABLE);
    Matcher m = Pattern.compile("jdbc:postgresql://([^:/]+):(\\d+)/(\\w+)").matcher(pg.jdbcUrl());
    assertThat(m.find()).isTrue();
    try (CountingProxy proxy = new CountingProxy(m.group(1), Integer.parseInt(m.group(2)))) {
      mainUrl = "jdbc:postgresql://127.0.0.1:" + proxy.port() + "/" + m.group(3) + "?sslmode=disable";
      poolMax = 1; // one connection: no validation query slips into the count
      Started s = start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90)))));
      java.util.function.Supplier<Long> queries =
          () -> {
            long before = proxy.executions.get();
            assertThat(s.ps().status()).hasSize(1);
            return proxy.executions.get() - before;
          };
      assertThat(queries.get()).as("warm: the statements are prepared on the connection").isPositive();
      long few = queries.get();
      int partitionsFew = status(s, "readings").partitions().size();
      s.ps().ensure("readings", Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-06-30T00:00:00Z"));
      assertThat(queries.get()).isPositive();
      long many = queries.get();
      int partitionsMany = status(s, "readings").partitions().size();
      assertThat(partitionsMany).as("the test added partitions").isGreaterThanOrEqualTo(partitionsFew + 20);
      assertThat(many).as(few + " queries for " + partitionsFew + " partitions, " + many + " for " + partitionsMany).isEqualTo(few);
      assertThat(few).isBetween(1L, 5L);
      s.app().close();
    }
  }
}
