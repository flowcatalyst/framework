package io.flowcatalyst.framework.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.database.MigrationSet;
import io.flowcatalyst.framework.database.PostgresFixture;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whole generator on a real database: generate, compile, run, check. */
class CheckedSqlTest {

  static PostgresFixture pg;

  @TempDir Path dir;
  Layout layout;

  static final String V1 =
      """
      CREATE TABLE things (
        id varchar(26) PRIMARY KEY, client_id text NOT NULL, status text NOT NULL, qty int NOT NULL,
        note text, price numeric(10,2), created timestamptz NOT NULL, day date, local_ts timestamp,
        token uuid, payload jsonb, blob bytea, ratio float8, small float4,
        big bigint NOT NULL DEFAULT 0, flag bool NOT NULL DEFAULT false, tags text[], nums int4[]
      );
      """;

  static final String THINGS_SQL =
      """
      -- name: insertThing :exec
      -- Adds a thing.
      -- param: id p.ThingId
      -- param: status enum p.Status
      -- param: note ?
      -- param: price ?
      -- param: day ?
      -- param: local_ts ?
      -- param: token ?
      -- param: payload ?
      -- param: blob ?
      -- param: ratio ?
      -- param: small ?
      -- param: tags ?
      -- param: nums ?
      INSERT INTO things (id, client_id, status, qty, note, price, created, day, local_ts, token,
                          payload, blob, ratio, small, big, flag, tags, nums)
      VALUES (:id, :client_id, :status, :qty, :note, :price, :created, :day, :local_ts, :token,
              :payload, :blob, :ratio, :small, :big, :flag, :tags, :nums);

      -- name: findThing :one
      -- column: id p.ThingId
      -- column: status enum p.Status
      SELECT id, client_id, status, qty, note, price, created, day, local_ts, token, payload, blob,
             ratio, small, big, flag, tags, nums
        FROM things WHERE id = :id AND client_id = :client_id;

      -- name: listThings :many
      -- column: id p.ThingId
      SELECT id, qty FROM things WHERE client_id = :client_id ORDER BY id;

      -- name: stats :one
      SELECT count(*) AS "n!", coalesce(sum(qty), 0) AS "total!", max(note) AS biggest
        FROM things WHERE client_id = :client_id;

      -- name: noteOf :one
      SELECT note AS "note!" FROM things WHERE id = :id;

      -- name: anyThing :one
      SELECT id FROM things;

      -- name: bumpQty :exec
      UPDATE things SET qty = qty + :by WHERE client_id = :client_id;

      -- name: withTag :many
      SELECT id FROM things WHERE :tag = ANY(tags) OR :tag = 'all' ORDER BY id;

      -- name: byIds :many
      SELECT id FROM things WHERE id = ANY(:ids) ORDER BY id;
      """;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stopPostgres() {
    pg.close();
  }

  @BeforeEach
  void setUp() throws IOException {
    pg.reset();
    layout = new Layout(dir.resolve("sql"), "src/main/sql", dir.resolve("gen"), "src/main/java-generated");
    Files.createDirectories(layout.sqlDir().resolve("p"));
    Files.writeString(layout.sqlDir().resolve("p/Things.sql"), THINGS_SQL);
  }

  private List<MigrationSet> migrations(String... files) throws IOException {
    Path root = dir.resolve("migrations-root");
    Path m = Files.createDirectories(root.resolve("migrations"));
    for (int i = 0; i < files.length; i += 2) {
      Files.writeString(m.resolve(files[i]), files[i + 1]);
    }
    URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null);
    return List.of(new MigrationSet("things", loader, "migrations"));
  }

  private Map<String, String> generate(String... migrationFiles) throws IOException {
    return CheckedSql.generate(
        layout, pg.jdbcUrl(), pg.username(), pg.password(), migrations(migrationFiles));
  }

  private Map<String, String> generateV1() throws IOException {
    return generate("V0001__things.sql", V1);
  }

  // ---- the generated code runs --------------------------------------------------------------

  private ClassLoader load(Map<String, String> generated) throws IOException {
    return GeneratedCode.compile(
        dir.resolve("classes"),
        Map.of(
            "p.Things", generated.get("p/Things.java"),
            "p.ThingId", "package p; public record ThingId(String value) {}",
            "p.Status", "package p; public enum Status { NEW, DONE }"));
  }

  private static Object call(ClassLoader loader, Connection c, String method, Object... args) throws Exception {
    Class<?> things = loader.loadClass("p.Things");
    Object[] all = new Object[args.length + 1];
    all[0] = c;
    System.arraycopy(args, 0, all, 1, args.length);
    for (Method m : things.getMethods()) {
      if (m.getName().equals(method)) {
        try {
          return m.invoke(null, all);
        } catch (InvocationTargetException e) {
          if (e.getCause() instanceof Exception cause) {
            throw cause;
          }
          throw e;
        }
      }
    }
    throw new AssertionError("no method " + method);
  }

  private static Object thingId(ClassLoader loader, String v) throws Exception {
    return loader.loadClass("p.ThingId").getConstructor(String.class).newInstance(v);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object status(ClassLoader loader, String v) throws Exception {
    return Enum.valueOf((Class<Enum>) loader.loadClass("p.Status"), v);
  }

  @Test
  void generatedCodeBindsAndReadsEveryKindOfValue() throws Exception {
    Map<String, String> generated = generateV1();
    ClassLoader loader = load(generated);
    UUID token = UUID.randomUUID();
    Instant created = Instant.parse("2026-10-08T10:15:30.123456Z");

    try (Connection c = DriverManager.getConnection(pg.jdbcUrl(), pg.username(), pg.password())) {
      Object full =
          call(loader, c, "insertThing",
              thingId(loader, "t1"), "acme", status(loader, "NEW"), 3, "hello", new java.math.BigDecimal("12.50"),
              created, LocalDate.parse("2026-01-02"), LocalDateTime.parse("2026-01-02T03:04:05"), token,
              "{\"a\": 1}", new byte[] {1, 2, 3}, 0.5, 1.5f, 9_000_000_000L, true, List.of("x", "y"), List.of(1, 2, 3));
      Object sparse =
          call(loader, c, "insertThing",
              thingId(loader, "t2"), "acme", status(loader, "DONE"), 4, null, null,
              created, null, null, null, null, null, null, null, 0L, false, null, null);
      Object otherClient =
          call(loader, c, "insertThing",
              thingId(loader, "t3"), "globex", status(loader, "NEW"), 100, null, null,
              created, null, null, null, null, null, null, null, 0L, false, null, null);
      assertThat(List.of(full, sparse, otherClient)).containsOnly(1);

      String row = String.valueOf(((java.util.Optional<?>) call(loader, c, "findThing", "t1", "acme")).orElseThrow());
      assertThat(row)
          .contains("id=ThingId[value=t1]")
          .contains("status=NEW")
          .contains("qty=3")
          .contains("note=hello")
          .contains("price=12.50")
          .contains("created=2026-10-08T10:15:30.123456Z")
          .contains("day=2026-01-02")
          .contains("localTs=2026-01-02T03:04:05")
          .contains("token=" + token)
          .contains("\"a\"")
          .contains("ratio=0.5")
          .contains("small=1.5")
          .contains("big=9000000000")
          .contains("flag=true")
          .contains("tags=[x, y]")
          .contains("nums=[1, 2, 3]");

      String sparseRow = String.valueOf(((java.util.Optional<?>) call(loader, c, "findThing", "t2", "acme")).orElseThrow());
      assertThat(sparseRow)
          .contains("note=null").contains("price=null").contains("day=null").contains("token=null")
          .contains("payload=null").contains("blob=null").contains("tags=null").contains("nums=null")
          .contains("status=DONE");

      assertThat((java.util.Optional<?>) call(loader, c, "findThing", "t1", "globex")).isEmpty();
      assertThat(call(loader, c, "listThings", "acme").toString())
          .isEqualTo("[ListThingsRow[id=ThingId[value=t1], qty=3], ListThingsRow[id=ThingId[value=t2], qty=4]]");

      String stats = String.valueOf(((java.util.Optional<?>) call(loader, c, "stats", "acme")).orElseThrow());
      assertThat(stats).isEqualTo("StatsRow[n=2, total=7, biggest=hello]");
      assertThat(String.valueOf(((java.util.Optional<?>) call(loader, c, "stats", "nobody")).orElseThrow()))
          .isEqualTo("StatsRow[n=0, total=0, biggest=null]");

      assertThat(call(loader, c, "bumpQty", 10, "acme")).isEqualTo(2);
      assertThat(call(loader, c, "bumpQty", 10, "nobody")).isEqualTo(0);

      assertThat(call(loader, c, "withTag", "x").toString()).contains("t1").doesNotContain("t3");
      assertThat(call(loader, c, "byIds", List.of("t1", "t3")).toString()).contains("t1", "t3").doesNotContain("t2");
    }
  }

  @Test
  void aBangOverrideThatTurnsOutNullThrowsNamingTheQueryAndColumn() throws Exception {
    Map<String, String> generated = generateV1();
    ClassLoader loader = load(generated);
    try (Connection c = DriverManager.getConnection(pg.jdbcUrl(), pg.username(), pg.password())) {
      call(loader, c, "insertThing",
          thingId(loader, "t1"), "acme", status(loader, "NEW"), 1, null, null, Instant.EPOCH,
          null, null, null, null, null, null, null, 0L, false, null, null);
      call(loader, c, "insertThing",
          thingId(loader, "t2"), "acme", status(loader, "NEW"), 1, "n", null, Instant.EPOCH,
          null, null, null, null, null, null, null, 0L, false, null, null);

      Throwable t = catchThrowable(() -> call(loader, c, "noteOf", "t1"));
      assertThat(t).isInstanceOf(IllegalStateException.class).hasMessageContaining("query noteOf").hasMessageContaining("column note");
      assertThat(String.valueOf(((java.util.Optional<?>) call(loader, c, "noteOf", "t2")).orElseThrow())).contains("note=n");
    }
  }

  @Test
  void aOneQueryThatFindsManyRowsThrows() throws Exception {
    Map<String, String> generated = generateV1();
    ClassLoader loader = load(generated);
    try (Connection c = DriverManager.getConnection(pg.jdbcUrl(), pg.username(), pg.password())) {
      for (String id : List.of("t1", "t2")) {
        call(loader, c, "insertThing",
            thingId(loader, id), "acme", status(loader, "NEW"), 1, null, null, Instant.EPOCH,
            null, null, null, null, null, null, null, 0L, false, null, null);
      }

      Throwable t = catchThrowable(() -> call(loader, c, "anyThing"));

      assertThat(t).isInstanceOf(IllegalStateException.class).hasMessageContaining("anyThing").hasMessageContaining("more");
    }
  }

  // ---- determinism and check ----------------------------------------------------------------

  @Test
  void generatingTwiceGivesIdenticalBytes() throws Exception {
    Map<String, String> first = generateV1();
    pg.reset();
    Map<String, String> second = generateV1();

    assertThat(second).isEqualTo(first);
    assertThat(first.keySet()).containsExactly("p/Things.java");
    assertThat(first.get("p/Things.java").getBytes(StandardCharsets.UTF_8))
        .isEqualTo(second.get("p/Things.java").getBytes(StandardCharsets.UTF_8));
    assertThat(first.get("p/Things.java"))
        .startsWith("// Generated from src/main/sql/p/Things.sql. Do not edit.\n");
  }

  @Test
  void checkPassesOnFreshOutputAndNamesWhatIsStale() throws Exception {
    CheckedSql.write(layout, generateV1());
    pg.reset();
    assertThat(CheckedSql.check(layout, generateV1())).isEmpty();
    Path generatedFile = layout.outDir().resolve("p/Things.java");

    // A committed file edited by hand.
    String original = Files.readString(generatedFile);
    Files.writeString(generatedFile, original.replace("public final class", "public  final class"));
    pg.reset();
    assertThat(CheckedSql.check(layout, generateV1()))
        .singleElement()
        .asString()
        .startsWith("src/main/java-generated/p/Things.java:")
        .contains("differs");
    Files.writeString(generatedFile, original);

    // A query that changed.
    Path sql = layout.sqlDir().resolve("p/Things.sql");
    Files.writeString(sql, THINGS_SQL.replace("ORDER BY id;\n\n-- name: stats", "ORDER BY id DESC;\n\n-- name: stats"));
    pg.reset();
    assertThat(CheckedSql.check(layout, generateV1()))
        .singleElement()
        .asString()
        .startsWith("src/main/java-generated/p/Things.java:")
        .contains("differs");
    Files.writeString(sql, THINGS_SQL);

    // A generated file with no source.
    Files.createDirectories(layout.outDir().resolve("q"));
    Files.writeString(layout.outDir().resolve("q/Gone.java"), "// Generated from src/main/sql/q/Gone.sql. Do not edit.\npackage q;\nclass Gone {}\n");
    pg.reset();
    assertThat(CheckedSql.check(layout, generateV1()))
        .singleElement()
        .asString()
        .startsWith("src/main/java-generated/q/Gone.java:")
        .contains("no query file");

    // A hand-written file in the output directory is not ours to judge.
    Files.writeString(layout.outDir().resolve("q/Gone.java"), "package q;\nclass Gone {}\n");
    pg.reset();
    assertThat(CheckedSql.check(layout, generateV1())).isEmpty();

    // A query file never generated.
    Files.delete(generatedFile);
    pg.reset();
    assertThat(CheckedSql.check(layout, generateV1()))
        .singleElement()
        .asString()
        .startsWith("src/main/java-generated/p/Things.java:")
        .contains("missing");
  }

  @Test
  void writeReplacesFilesAndRemovesGeneratedFilesWithoutASource() throws Exception {
    Files.createDirectories(layout.outDir().resolve("q"));
    Path stale = layout.outDir().resolve("q/Gone.java");
    Files.writeString(stale, "// Generated from src/main/sql/q/Gone.sql. Do not edit.\npackage q;\n");

    CheckedSql.write(layout, generateV1());

    assertThat(stale).doesNotExist();
    assertThat(layout.outDir().resolve("p/Things.java")).exists();
  }

  // ---- the point of the milestone -----------------------------------------------------------

  @Test
  void aMigrationThatRenamesAColumnBreaksGenerationNamingTheQueryAndItsFileAndLine() throws Exception {
    assertThat(generateV1()).hasSize(1);
    pg.reset();

    Throwable t =
        catchThrowable(
            () ->
                generate(
                    "V0001__things.sql", V1,
                    "V0002__rename.sql", "ALTER TABLE things RENAME COLUMN qty TO quantity;"));

    assertThat(t).isInstanceOf(GenerationFailures.class);
    String message = t.getMessage();
    // insertThing (line 1) and findThing, listThings, stats, bumpQty (and so on) mention qty.
    assertThat(message)
        .contains("src/main/sql/p/Things.sql:1: query 'insertThing'")
        .contains("column \"qty\" of relation \"things\" does not exist")
        .contains("src/main/sql/p/Things.sql:")
        .contains("query 'findThing'")
        .contains("column \"qty\" does not exist");
    assertThat(((GenerationFailures) t).failures()).hasSizeGreaterThanOrEqualTo(4);
    assertThat(((GenerationFailures) t).failures()).allSatisfy(f -> assertThat(f.file()).isEqualTo("src/main/sql/p/Things.sql"));
  }

  @Test
  void aQueryFileNotInAPackageDirectoryOrWithABadClassNameIsRefused() throws Exception {
    Files.writeString(layout.sqlDir().resolve("Loose.sql"), "-- name: a :exec\nSELECT 1");
    Files.createDirectories(layout.sqlDir().resolve("Bad-Dir"));
    Files.writeString(layout.sqlDir().resolve("Bad-Dir/X.sql"), "-- name: a :exec\nSELECT 1");
    Files.writeString(layout.sqlDir().resolve("p/lower.sql"), "-- name: a :exec\nSELECT 1");

    Throwable t = catchThrowable(this::generateV1);

    assertThat(t).isInstanceOf(GenerationFailures.class);
    assertThat(t.getMessage())
        .contains("src/main/sql/Loose.sql: must be in a directory")
        .contains("src/main/sql/Bad-Dir/X.sql: directory 'Bad-Dir'")
        .contains("src/main/sql/p/lower.sql: file name 'lower'");
  }

  @Test
  void anEmptySourceDirectoryGeneratesNothing() throws Exception {
    Files.delete(layout.sqlDir().resolve("p/Things.sql"));

    assertThat(generateV1()).isEmpty();
  }
}
