package io.flowcatalyst.framework.partitions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.core.HealthReport;
import io.flowcatalyst.framework.core.HealthResult;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.objectstore.ObjectStore;
import io.flowcatalyst.framework.objectstore.ObjectStoreFactories;
import io.flowcatalyst.framework.objectstore.ObjectStores;
import io.flowcatalyst.framework.objectstore.StoredObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Contract sections 3 to 6 on a real PostgreSQL: start-up, ensure, retention, archive, the loop and the health check. */
class PartitionsLifecycleTest extends PartitionsTestBase {

  // ---- start-up and ensure ----------------------------------------------------------------

  @Test
  void startUpEnsuresTheCurrentAndAheadPartitionsForBothKeys() throws IOException {
    exec(ID_TABLE);
    exec(COL_TABLE);
    Started s =
        start(
            Cfg.of(
                monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90))),
                Table.named("public.samples").by(Key.byColumn("recorded_at")).interval(Interval.WEEK).ahead(2).retention(Retention.keepForever())));
    assertThat(parts("readings"))
        .containsExactly("readings_p20261101", "readings_p20261201", "readings_p20270101", "readings_p20270201");
    // 2026-11-14 is a Saturday: the week starts Monday 2026-11-09
    assertThat(parts("samples")).containsExactly("samples_p20261109", "samples_p20261116", "samples_p20261123");
    assertThat(s.metric("fw_partitions_ahead", "public.readings")).isEqualTo(3);
    assertThat(s.metric("fw_partitions_ahead", "public.samples")).isEqualTo(2);
    // the first inserts find their partitions, and land in the right ones
    String id = idAt(T0);
    exec("INSERT INTO readings (id) VALUES ('" + id + "')");
    assertThat(strings("SELECT tableoid::regclass::text FROM readings WHERE id = '" + id + "'")).containsExactly("readings_p20261101");
    exec("INSERT INTO samples (id, recorded_at) VALUES ('a', '2026-11-22 23:59:59+00')");
    assertThat(strings("SELECT tableoid::regclass::text FROM samples")).containsExactly("samples_p20261116");
    HealthReport report = s.app().health().check();
    for (String n : List.of("partitions:readings", "partitions:public.samples")) {
      assertThat(report.results().get(n)).as(n).isInstanceOf(HealthResult.Up.class);
      assertThat(report.criticality(n)).as(n).isEqualTo(io.flowcatalyst.framework.core.Criticality.CRITICAL);
    }
    assertThat(log.text()).contains("partition created");
  }

  @Test
  void theMigrationsRunOnTheDefaultInstanceUnderTheModuleId() throws IOException {
    exec(ID_TABLE);
    start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.keepForever())));
    assertThat(strings("SELECT version || ':' || description FROM fw_schema_history WHERE module = 'partitions' ORDER BY version"))
        .hasSize(3);
    assertThat(count("SELECT count(*) FROM fw_partition_archives")).isZero();
    assertThat(strings("SELECT column_name FROM information_schema.columns WHERE table_name = 'fw_partition_passes' ORDER BY ordinal_position"))
        .containsExactly(
            "table_name", "ended_at", "ensure_status", "retention_status", "archive_status", "created", "dropped", "archived", "pass_trigger", "actor");
  }

  private static final Pattern IDENT = Pattern.compile("[A-Za-z0-9_]+");

  /** The partitions of a table that an EXPLAIN plan scans. */
  private List<String> scanned(String plan, String table) {
    Set<String> seen = new LinkedHashSet<>();
    var m = IDENT.matcher(plan);
    while (m.find()) {
      String w = m.group();
      if (w.startsWith(table + "_p") && !w.contains("pkey")) {
        seen.add(w);
      }
    }
    return seen.stream().sorted().toList();
  }

  private String explain(String sql) {
    return String.join("\n", strings("EXPLAIN (COSTS OFF) " + sql));
  }

  @Test
  void anIdLookupAndAnIdRangeWindowTouchOnlyTheMatchingPartitions() throws IOException {
    exec(ID_TABLE);
    start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90)))));

    String id = idAt(Instant.parse("2026-12-05T08:00:00Z"));
    String plan = explain("SELECT * FROM readings WHERE id = '" + id + "'");
    assertThat(scanned(plan, "readings")).as(plan).containsExactly("readings_p20261201");

    Key.IdRange within = Key.idRange("rdg", Instant.parse("2026-11-03T00:00:00Z"), Instant.parse("2026-11-20T00:00:00Z"));
    plan = explain("SELECT * FROM readings WHERE id >= '" + within.lo() + "' AND id < '" + within.hi() + "'");
    assertThat(scanned(plan, "readings")).as(plan).containsExactly("readings_p20261101");

    Key.IdRange across = Key.idRange("rdg", Instant.parse("2026-11-20T00:00:00Z"), Instant.parse("2026-12-10T00:00:00Z"));
    plan = explain("SELECT * FROM readings WHERE id >= '" + across.lo() + "' AND id < '" + across.hi() + "'");
    assertThat(scanned(plan, "readings")).as(plan).containsExactly("readings_p20261101", "readings_p20261201");

    String in = idAt(Instant.parse("2026-11-25T00:00:00Z"));
    String out = idAt(Instant.parse("2026-12-30T00:00:00Z"));
    exec("INSERT INTO readings (id) VALUES ('" + in + "'), ('" + out + "')");
    assertThat(strings("SELECT id FROM readings WHERE id >= '" + across.lo() + "' AND id < '" + across.hi() + "'")).containsExactly(in);
  }

  record BadShape(String name, List<String> ddl, Table table, String want) {}

  @Test
  void everyStartUpValidationFailureNamesTheTableAndTheExpectedDdl() throws IOException {
    Table byId = monthly("readings", Key.byId("rdg"), Retention.keepForever());
    List<BadShape> cases =
        List.of(
            new BadShape("missing table", List.of(), byId, "does not exist"),
            new BadShape("not partitioned", List.of("CREATE TABLE readings (id text COLLATE \"C\" PRIMARY KEY)"), byId, "not a partitioned table"),
            new BadShape(
                "wrong key",
                List.of("CREATE TABLE readings (id text COLLATE \"C\", recorded_at timestamptz NOT NULL, PRIMARY KEY (id, recorded_at)) PARTITION BY RANGE (recorded_at)"),
                byId,
                "expected PARTITION BY RANGE (id)"),
            new BadShape(
                "list partitioned",
                List.of("CREATE TABLE readings (id text COLLATE \"C\" PRIMARY KEY) PARTITION BY LIST (id)"),
                byId,
                "expected PARTITION BY RANGE (id)"),
            new BadShape(
                "ByID without COLLATE C",
                List.of("CREATE TABLE readings (id text PRIMARY KEY) PARTITION BY RANGE (id)"),
                byId,
                "COLLATE \"C\""),
            new BadShape(
                "ByID on a non text column",
                List.of("CREATE TABLE readings (id bigint PRIMARY KEY) PARTITION BY RANGE (id)"),
                byId,
                "must be text or varchar"),
            new BadShape(
                "ByColumn wrong type",
                List.of("CREATE TABLE samples (id text, recorded_at timestamp NOT NULL, PRIMARY KEY (id, recorded_at)) PARTITION BY RANGE (recorded_at)"),
                monthly("samples", Key.byColumn("recorded_at"), Retention.keepForever()),
                "must be timestamptz"),
            new BadShape(
                "default partition",
                List.of(ID_TABLE, "CREATE TABLE readings_default PARTITION OF readings DEFAULT"),
                byId,
                "DEFAULT partition"));
    for (BadShape c : cases) {
      pg.reset();
      for (String d : c.ddl()) {
        exec(d);
      }
      assertThatThrownBy(() -> start(Cfg.of(c.table())))
          .as(c.name())
          .isInstanceOf(StartupException.class)
          .hasMessageContaining("key 'table " + c.table().name() + "'")
          .hasMessageContaining(c.want());
    }
  }

  @Test
  void aKeyInCollationCOnAColumnInTheDefaultCollationIsNotPrunedAndRefusedAtStartUp() throws IOException {
    // the key says C; the column is in the default collation (PostgreSQL refuses a primary key here)
    exec("CREATE TABLE readings (id text, payload text) PARTITION BY RANGE (id COLLATE \"C\")");
    exec("CREATE TABLE readings_p20261101 PARTITION OF readings FOR VALUES FROM ('rdg_0') TO ('rdg_1')");
    exec("CREATE TABLE readings_p20261201 PARTITION OF readings FOR VALUES FROM ('rdg_1') TO ('rdg_2')");
    String plan = explain("SELECT * FROM readings WHERE id = 'rdg_0000000000001'");
    // documented in the contract: with only the key in C, an id lookup is NOT pruned
    assertThat(scanned(plan, "readings")).as(plan).hasSize(2);
    assertThatThrownBy(() -> start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.keepForever()))))
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("the column \"id\" has collation \"default\"")
        .hasMessageContaining("COLLATE \"C\"");
  }

  @Test
  void theManifestMustExistOnAnInstanceWhereATableArchives() throws IOException {
    // migrations run on the default instance only: another instance needs the manifest by hand, and start-up says so
    createOther("CREATE TABLE readings (id text COLLATE \"C\" PRIMARY KEY, payload text) PARTITION BY RANGE (id)");
    Table archiving = monthly("readings", Key.byId("rdg"), Retention.archiveThenDropAfter(days(90), "archive")).database("other");
    assertThatThrownBy(() -> start(Cfg.of(archiving).store(ObjectStoreFactories.memory())))
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("fw_partition_archives must exist in database instance 'other'");
  }

  @Test
  void everyDeclarationProblemIsReportedAtStartUpTogether() throws IOException {
    exec(ID_TABLE);
    Table good = monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90)));
    Table noRetention = Table.named("a").by(Key.byId("rdg")).interval(Interval.MONTH).ahead(3);
    Table archiving = monthly("b", Key.byId("rdg"), Retention.archiveThenDropAfter(days(90), "archive"));
    assertThatThrownBy(() -> start(Cfg.of(good, good, noRetention, archiving)))
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("key 'table readings': declared twice")
        .hasMessageContaining("key 'table a': no retention")
        .hasMessageContaining("key 'table b': retention names object store");
  }

  @Test
  void theSameTableUnderTwoSpellingsIsDeclaredTwice() throws IOException {
    exec(ID_TABLE);
    assertThatThrownBy(
            () ->
                start(
                    Cfg.of(
                        monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90))),
                        monthly("public.readings", Key.byId("rdg"), Retention.keepForever()))))
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("declared twice");
  }

  @Test
  void anUnknownObjectStoreIsAStartUpProblem() throws IOException {
    exec(ID_TABLE);
    assertThatThrownBy(
            () ->
                start(
                    Cfg.of(monthly("readings", Key.byId("rdg"), Retention.archiveThenDropAfter(days(90), "nope")))
                        .store(ObjectStoreFactories.memory())))
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("no ObjectStore named 'nope'");
  }

  // ---- ensure, backfill and retention ------------------------------------------------------

  @Test
  void ensureBackfillsAndRetentionDropsWholePartitions() throws IOException {
    exec(ID_TABLE);
    Started s = start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90)))));
    Instant from = Instant.parse("2026-05-10T00:00:00Z");
    Instant to = Instant.parse("2026-07-31T00:00:00Z");
    assertThat(s.ps().ensure("readings", from, to).created()).as("May, June and July").isEqualTo(3);
    assertThat(s.ps().ensure("readings", from, to).created()).as("a second ensure").isZero();
    exec(
        "INSERT INTO readings (id) VALUES ('"
            + idAt(Instant.parse("2026-05-20T00:00:00Z"))
            + "'), ('"
            + idAt(Instant.parse("2026-07-31T23:00:00Z"))
            + "'), ('"
            + idAt(T0)
            + "')");

    s.ps().pass();
    assertThat(parts("readings")).containsExactly("readings_p20261101", "readings_p20261201", "readings_p20270101", "readings_p20270201");
    assertThat(count("SELECT count(*) FROM readings")).as("only the current row is left").isEqualTo(1);
    assertThat(s.metric("fw_partitions_dropped_total", "public.readings")).isEqualTo(3);
    assertThat(log.text()).contains("partition dropped");
  }

  @Test
  void keepForeverDropsNothing() throws IOException {
    exec(ID_TABLE);
    Started s = start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.keepForever())));
    s.ps().ensure("readings", Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-03-01T00:00:00Z"));
    List<String> before = parts("readings");
    s.ps().pass();
    assertThat(parts("readings")).isEqualTo(before);
  }

  @Test
  void strayPartitionsAreLeftAloneAndLoggedOnce() throws IOException {
    exec(COL_TABLE);
    exec("CREATE TABLE samples_legacy PARTITION OF samples FOR VALUES FROM ('2000-01-01 00:00:00+00') TO ('2001-01-01 00:00:00+00')");
    Started s =
        start(
            Cfg.of(Table.named("samples").by(Key.byColumn("recorded_at")).interval(Interval.MONTH).ahead(1).retention(Retention.dropAfter(days(60)))));
    s.ps().pass();
    s.ps().pass();
    assertThat(parts("samples")).contains("samples_legacy");
    assertThat(log.text().split("samples_legacy", -1).length - 1).as("mentioned once").isEqualTo(1);
  }

  @Test
  void theHealthCheckIsDownWhenTheNextPartitionIsMissingAndRecoversAfterAPass() throws IOException {
    exec(ID_TABLE);
    Started s = start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90)))));
    assertThat(s.app().health().check().results().get("partitions:readings")).isInstanceOf(HealthResult.Up.class);
    exec("DROP TABLE readings_p20261201"); // the next interval, by hand
    HealthReport down = s.app().health().check();
    assertThat(down.results().get("partitions:readings"))
        .isInstanceOfSatisfying(
            HealthResult.Down.class, d -> assertThat(d.reason()).contains("next interval").contains("2026-12-01"));
    assertThat(down.criticality("partitions:readings")).isEqualTo(io.flowcatalyst.framework.core.Criticality.CRITICAL);
    assertThat(down.isReady()).as("not ready with the next partition missing").isFalse();
    s.ps().pass();
    assertThat(s.app().health().check().results().get("partitions:readings")).isInstanceOf(HealthResult.Up.class);
  }

  @Test
  void concurrentEnsureOnOneTableFromTwoReplicasCreatesOneSet() throws Exception {
    exec(ID_TABLE);
    Cfg cfg = Cfg.of(monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(900))));
    Started a = start(cfg);
    Started b = start(cfg);
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<EnsureResult>> futures = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        Partitions ps = i % 2 == 0 ? a.ps() : b.ps();
        futures.add(pool.submit(() -> ps.ensure("readings", Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-12-31T00:00:00Z"))));
      }
      for (Future<EnsureResult> f : futures) {
        f.get(); // none fails
      }
    }
    assertThat(parts("readings")).as("12 backfilled plus 4 current").hasSize(16);
  }

  @Test
  void theLoopCreatesAndDropsAsTheClockAdvances() throws IOException {
    exec(ID_TABLE);
    Started s =
        start(
            Cfg.of(monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90))))
                .every(Duration.ofMillis(30))
                .locks());
    assertThat(parts("readings").get(0)).isEqualTo("readings_p20261101");
    clock.set(Instant.parse("2027-03-15T12:00:00Z")); // cutoff 2026-12-15: November ended 2026-12-01
    eventually(
        "the loop to create the new partitions and drop November",
        () -> parts("readings").contains("readings_p20270601") && !parts("readings").contains("readings_p20261101"));
    assertThat(parts("readings")).as("December ended 2027-01-01, after the cutoff, and stays").contains("readings_p20261201");
    HealthReport report = s.app().health().check();
    assertThat(report.results().get("background:partitions")).isInstanceOf(HealthResult.Up.class);
    assertThat(report.results().get("partitions:readings")).isInstanceOf(HealthResult.Up.class);
  }

  @Test
  void aSecondReplicaWithoutTheLockSkipsAndDoesNoDdl() throws Exception {
    exec(ID_TABLE);
    var shared = sharedLockStore();
    Cfg cfg =
        Cfg.of(monthly("readings", Key.byId("rdg"), Retention.dropAfter(days(90)))).every(Duration.ofMillis(20)).lockStore(shared);
    Started a = start(cfg);
    Started b = start(cfg);
    // another holder takes the loop's lock; a loop's own pass may hold it for an instant, so wait for it
    var held =
        shared.acquire(io.flowcatalyst.framework.locks.LockKey.service(Partitions.LOCK_NAME), Duration.ofHours(1), Duration.ofSeconds(10));
    assertThat(held).isInstanceOf(io.flowcatalyst.framework.locks.AcquireResult.Acquired.class);
    exec("DROP TABLE readings_p20261201"); // work only a pass would do
    double base = a.metric("fw_background_runs_total", "partitions", "skipped") + b.metric("fw_background_runs_total", "partitions", "skipped");
    eventually(
        "both loops to be skipped",
        () ->
            a.metric("fw_background_runs_total", "partitions", "skipped") + b.metric("fw_background_runs_total", "partitions", "skipped")
                >= base + 6);
    assertThat(parts("readings")).as("a replica did DDL without holding the lock").doesNotContain("readings_p20261201");
    shared.release(((io.flowcatalyst.framework.locks.AcquireResult.Acquired) held).lease());
    eventually("a replica to take the lock and recreate the partition", () -> parts("readings").contains("readings_p20261201"));
  }

  @Test
  void ensureRefusesADetachedTableOfAPartitionsNameAndStartUpFailsOnIt() throws IOException {
    exec(ID_TABLE);
    Started s = start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.keepForever())));
    exec("ALTER TABLE readings DETACH PARTITION readings_p20261201"); // a future partition left detached
    assertThatThrownBy(() -> s.ps().pass())
        .isInstanceOf(PartitionsException.class)
        .hasMessageContaining("\"public\".\"readings_p20261201\" exists but is not attached");
    assertThat(s.metric("fw_partition_failures_total", "public.readings", "ensure")).isEqualTo(1);
    assertThatThrownBy(() -> start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.keepForever()))))
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("exists but is not attached");
  }

  // ---- archive ------------------------------------------------------------------------------

  private static final JsonMapper JSON = JsonMapper.builder().build();

  static List<String> gunzipLines(byte[] content) throws IOException {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(content))) {
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      if (text.isEmpty()) {
        return List.of();
      }
      return List.of(text.substring(0, text.length() - 1).split("\n", -1));
    }
  }

  /** Inserts n old rows (May 2026) with awkward payloads, and returns id to payload. */
  Map<String, String> insertOld(int n) throws SQLException {
    Map<String, String> want = new HashMap<>();
    try (Connection c = DriverManager.getConnection(pg.jdbcUrl(), pg.username(), pg.password());
        PreparedStatement ps = c.prepareStatement("INSERT INTO readings (id, payload) VALUES (?, ?)")) {
      for (int i = 0; i < n; i++) {
        Instant at = Instant.parse("2026-05-01T00:00:00Z").plusSeconds((i % 28) * 86400L + (i % 24) * 3600L).plusMillis(i);
        String id = idAt(at);
        String payload =
            "row " + i + ": line1\nline2 \"quoted\" back\\slash é " + HexFormat.of().formatHex(new byte[] {(byte) i, (byte) (i >> 8), 7, 9});
        want.put(id, payload);
        ps.setString(1, id);
        ps.setString(2, payload);
        ps.addBatch();
      }
      ps.executeBatch();
    }
    return want;
  }

  private void ensureMay(Started s) {
    s.ps().ensure("readings", Instant.parse("2026-05-01T00:00:00Z"), Instant.parse("2026-05-31T00:00:00Z"));
  }

  Map<String, String> archivedRows(ObjectStore store, List<String> keys) throws IOException {
    Map<String, String> got = new HashMap<>();
    for (String key : keys) {
      StoredObject obj = store.read(key).orElseThrow();
      for (String line : gunzipLines(obj.content())) {
        var node = JSON.readTree(line);
        got.put(node.get("id").asString(), node.get("payload").asString());
      }
    }
    return got;
  }

  void archiveThenDropWritesVerifiedNdjsonAndManifest(int segment, int rows, boolean several) throws Exception {
    exec(ID_TABLE);
    Cfg cfg =
        Cfg.of(monthly("readings", Key.byId("rdg"), Retention.archiveThenDropAfter(days(90), "archive")))
            .store(ObjectStoreFactories.memory());
    if (segment > 0) {
      cfg.segment(segment);
    }
    Started s = start(cfg);
    ensureMay(s);
    Map<String, String> want = insertOld(rows);
    s.ps().pass();
    assertThat(parts("readings")).as("the archived partition was dropped").doesNotContain("readings_p20260501");

    ObjectStore store = s.app().get(ObjectStores.class).get("archive");
    record M(int part, int rows, int bytes, String sha, String key) {}
    List<M> manifest = new ArrayList<>();
    try (Connection c = DriverManager.getConnection(pg.jdbcUrl(), pg.username(), pg.password());
        var st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT part, row_count, byte_count, sha256, object_key FROM fw_partition_archives WHERE table_name = 'public.readings' AND partition_name = 'readings_p20260501' ORDER BY part")) {
      while (rs.next()) {
        manifest.add(new M(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getString(5)));
      }
    }
    assertThat(several ? manifest.size() > 1 : manifest.size() == 1).as(manifest.size() + " manifest rows").isTrue();
    assertThat(manifest.get(0).key()).isEqualTo("partitions/public.readings/readings_p20260501.ndjson.gz");
    int total = 0;
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < manifest.size(); i++) {
      M m = manifest.get(i);
      assertThat(m.part()).isEqualTo(i + 1);
      if (i > 0) {
        assertThat(m.key()).isEqualTo("partitions/public.readings/readings_p20260501.part" + (i + 1) + ".ndjson.gz");
      }
      StoredObject obj = store.read(m.key()).orElseThrow();
      assertThat(obj.contentType()).isEqualTo("application/gzip");
      assertThat(obj.content()).hasSize(m.bytes());
      assertThat(Archiver.sha256(obj.content())).isEqualTo(m.sha());
      assertThat(gunzipLines(obj.content())).as(m.key() + " rows").hasSize(m.rows());
      total += m.rows();
      keys.add(m.key());
    }
    assertThat(total).isEqualTo(rows);
    assertThat(archivedRows(store, keys)).isEqualTo(want); // row for row, awkward characters included
    assertThat(s.metric("fw_partition_rows_archived_total", "public.readings")).isEqualTo(rows);
    assertThat(s.metric("fw_partitions_archived_total", "public.readings")).isEqualTo(1);
    assertThat(s.metric("fw_partitions_dropped_total", "public.readings")).isEqualTo(1);
  }

  @Test
  void archiveThenDropWritesOneVerifiedObjectAndTheManifest() throws Exception {
    archiveThenDropWritesVerifiedNdjsonAndManifest(0, 120, false);
  }

  @Test
  void archiveThenDropCutsALargePartitionIntoSeveralObjects() throws Exception {
    archiveThenDropWritesVerifiedNdjsonAndManifest(1500, 400, true);
  }

  @Test
  void aPartitionWithoutRowsIsOneEmptyObject() throws Exception {
    exec(ID_TABLE);
    Started s =
        start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.archiveThenDropAfter(days(90), "archive"))).store(ObjectStoreFactories.memory()));
    ensureMay(s);
    s.ps().pass();
    assertThat(count("SELECT count(*) FROM fw_partition_archives WHERE partition_name = 'readings_p20260501' AND row_count = 0")).isEqualTo(1);
    ObjectStore store = s.app().get(ObjectStores.class).get("archive");
    assertThat(gunzipLines(store.read(Partitions.archiveKey("public", "readings", "readings_p20260501")).orElseThrow().content())).isEmpty();
  }

  @Test
  void aFailedOrCorruptArchiveDropsNothingLeavesTheTableDetachedAndIsRetried() throws Exception {
    record Fail(String name, String logged) {}
    for (Fail f : List.of(new Fail("the store refuses the put", "disk on fire"), new Fail("the stored object is not what was written", "stored"))) {
      pg.reset();
      closeApps();
      log = LogCapture.start();
      exec(ID_TABLE);
      Stores.Flaky store = new Stores.Flaky();
      Started s =
          start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.archiveThenDropAfter(days(90), "archive"))).store(store.factory()));
      ensureMay(s);
      insertOld(50);
      if (f.logged().equals("disk on fire")) {
        store.putError = "disk on fire";
      } else {
        store.truncate = true;
      }
      for (int i = 0; i < 2; i++) {
        assertThatThrownBy(() -> s.ps().pass()).as(f.name()).isInstanceOf(PartitionsException.class);
      }
      assertThat(parts("readings")).as("detached, not re-attached").doesNotContain("readings_p20260501");
      assertThat(count("SELECT count(*) FROM readings_p20260501")).as("detached, not dropped").isEqualTo(50);
      assertThat(count("SELECT count(*) FROM fw_partition_archives")).as("no manifest for a failed archive").isZero();
      assertThat(s.metric("fw_partition_failures_total", "public.readings", "archive")).as("one per failed pass").isEqualTo(2);
      assertThat(log.text()).contains("partition archive failed").contains(f.logged());
      assertThat(s.app().health().check().results().get("partitions:readings"))
          .as("a failing archive does not touch the next-partition check")
          .isInstanceOf(HealthResult.Up.class);
      // the store heals; the next pass retries and succeeds
      store.heal();
      s.ps().pass();
      assertThat(count("SELECT count(*) FROM pg_class WHERE relname = 'readings_p20260501'")).as("the retry dropped it").isZero();
      assertThat(count("SELECT sum(row_count)::int FROM fw_partition_archives")).isEqualTo(50);
    }
  }

  @Test
  void aWriteCannotReachADetachedPartitionSoTheArchiveHoldsTheFinalData() throws Exception {
    exec(ID_TABLE);
    Stores.Flaky store = new Stores.Flaky();
    Started s =
        start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.archiveThenDropAfter(days(90), "archive"))).store(store.factory()));
    ensureMay(s);
    Map<String, String> want = insertOld(10);
    String victim = want.keySet().iterator().next();
    String late = idAt(Instant.parse("2026-05-31T23:00:00Z"));
    AtomicReference<Throwable> insertError = new AtomicReference<>();
    AtomicInteger updated = new AtomicInteger(-1);
    AtomicInteger fired = new AtomicInteger();
    store.afterPut =
        () -> {
          if (fired.getAndIncrement() > 0) {
            return;
          }
          // after the first object was written, before the drop
          try (Connection c = DriverManager.getConnection(pg.jdbcUrl(), pg.username(), pg.password());
              var st = c.createStatement()) {
            try {
              st.executeUpdate("INSERT INTO readings (id, payload) VALUES ('" + late + "', 'late')");
            } catch (SQLException e) {
              insertError.set(e);
            }
            updated.set(st.executeUpdate("UPDATE readings SET payload = 'changed' WHERE id = '" + victim + "'"));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        };
    s.ps().pass();
    assertThat(insertError.get()).as("an insert into the range of a detached partition fails loudly").isNotNull();
    assertThat(updated.get()).as("an update through the parent finds no row").isZero();
    ObjectStore archive = s.app().get(ObjectStores.class).get("archive");
    Map<String, String> got = archivedRows(archive, List.of(Partitions.archiveKey("public", "readings", "readings_p20260501")));
    assertThat(got).isEqualTo(want);
    assertThat(count("SELECT count(*) FROM pg_class WHERE relname = 'readings_p20260501'")).isZero();
  }

  @Test
  void recoveryFinishesAPartitionDetachedByAnInterruptedPassAndTouchesOnlyMarkedTables() throws Exception {
    exec(ID_TABLE);
    Started s =
        start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.archiveThenDropAfter(days(90), "archive"))).store(ObjectStoreFactories.memory()));
    ensureMay(s);
    insertOld(25);
    // the state a pass that crashed right after its detach leaves behind: detached, with the mark
    exec("ALTER TABLE readings DETACH PARTITION readings_p20260501");
    exec("COMMENT ON TABLE readings_p20260501 IS 'fw-partitions: detached from public.readings for archiving'");
    // a detached table that is not one of ours, or not due, is not touched
    exec("CREATE TABLE readings_p20261101_copy (id text)");
    // nor is a due table of a partition's name that this module did not detach (no mark)
    exec("CREATE TABLE readings_p20260401 (id text)");
    exec("INSERT INTO readings_p20260401 VALUES ('kept')");
    s.ps().pass();
    assertThat(count("SELECT count(*) FROM pg_class WHERE relname = 'readings_p20260501'")).as("recovered and dropped").isZero();
    assertThat(count("SELECT count(*) FROM pg_class WHERE relname = 'readings_p20261101_copy'")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM readings_p20260401")).as("unmarked: not dropped").isEqualTo(1);
    assertThat(count("SELECT count(*) FROM fw_partition_archives WHERE partition_name = 'readings_p20260401'")).as("unmarked: not archived").isZero();
    assertThat(count("SELECT sum(row_count)::int FROM fw_partition_archives WHERE partition_name = 'readings_p20260501'")).isEqualTo(25);
  }

  @Test
  void anAlreadyDueDetachedMarkedTableThatIsNotOursByNameIsLeftAlone() throws Exception {
    exec(ID_TABLE);
    Started s =
        start(Cfg.of(monthly("readings", Key.byId("rdg"), Retention.archiveThenDropAfter(days(90), "archive"))).store(ObjectStoreFactories.memory()));
    exec("CREATE TABLE readings_p20260502 (id text)"); // marked, but 05-02 is not a month start
    exec("COMMENT ON TABLE readings_p20260502 IS 'fw-partitions: detached from public.readings for archiving'");
    s.ps().pass();
    assertThat(count("SELECT count(*) FROM pg_class WHERE relname = 'readings_p20260502'")).isEqualTo(1);
  }
}
