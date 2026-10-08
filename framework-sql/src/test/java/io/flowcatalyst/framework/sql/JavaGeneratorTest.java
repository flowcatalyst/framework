package io.flowcatalyst.framework.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The code generator without a database: resolved queries in, Java source out. */
class JavaGeneratorTest {

  @TempDir Path dir;

  private static ResolvedParam param(String name, ValueType t, boolean nullable) {
    return new ResolvedParam(name, Names.lowerCamel(name), t, false, "x", null, nullable);
  }

  private static ResolvedColumn column(String name, ValueType t, ColumnNullability n) {
    return new ResolvedColumn(name, Names.lowerCamel(name), t, false, null, n);
  }

  private static ResolvedQuery resolved(String text, List<ResolvedParam> params, List<ResolvedColumn> columns) {
    QueryFile f = QueryParser.parse("src/main/sql/p/Q.sql", text);
    return new ResolvedQuery(f.queries().get(0), params, columns);
  }

  private static String generate(ResolvedQuery... queries) {
    return JavaGenerator.generate("src/main/sql/p/Q.sql", "p", "Q", List.of(queries));
  }

  @Test
  void aOneQueryReturnsAnOptionalRowAndChecksForASecondRow() {
    String java =
        generate(
            resolved(
                "-- name: findThing :one\n-- Finds a thing.\nSELECT id, qty FROM things WHERE id = :id AND client_id = :client_id AND x = :id\n",
                List.of(param("id", ValueType.STRING, false), param("client_id", ValueType.LONG, false)),
                List.of(
                    column("id", ValueType.STRING, ColumnNullability.NOT_NULL),
                    column("qty", ValueType.INT, ColumnNullability.NOT_NULL))));

    assertThat(java)
        .startsWith("// Generated from src/main/sql/p/Q.sql. Do not edit.\npackage p;\n")
        .contains("public record FindThingRow(\n      String id,\n      int qty) {}")
        .contains("public static Optional<FindThingRow> findThing(\n      Connection connection,\n      String id,\n      long clientId)")
        .contains("ps.setString(1, id);\n      ps.setLong(2, clientId);\n      ps.setString(3, id);")
        .contains("expected at most one row, got more")
        .contains("// src/main/sql/p/Q.sql:3")
        .contains("<p>From src/main/sql/p/Q.sql:1.")
        .contains("   * Finds a thing.")
        .contains("public static final String FIND_THING_SQL =\n      \"\"\"\n      SELECT id, qty FROM things WHERE id = ? AND client_id = ? AND x = ?\"\"\";");
  }

  @Test
  void nullableColumnsAndParametersAreBoxedAndAnnotated() throws IOException {
    String java =
        generate(
            resolved(
                "-- name: q :many\nSELECT a, b, c, d, e, f FROM t WHERE x = :n\n",
                List.of(param("n", ValueType.INT, true)),
                List.of(
                    column("a", ValueType.INT, ColumnNullability.NULLABLE),
                    column("b", ValueType.STRING, ColumnNullability.NULLABLE),
                    column("c", ValueType.INSTANT, ColumnNullability.NULLABLE),
                    column("d", ValueType.BYTES, ColumnNullability.NULLABLE),
                    column("e", ValueType.LONG, ColumnNullability.CHECKED),
                    column("f", ValueType.UUID, ColumnNullability.NOT_NULL))));

    assertThat(java)
        .contains("@Nullable Integer a")
        .contains("@Nullable String b")
        .contains("@Nullable Instant c")
        .contains("byte @Nullable [] d")
        .contains("long e")
        .contains("@Nullable Integer n")
        .contains("if (n == null) {\n        ps.setNull(1, Types.INTEGER);")
        .contains("throw nullRead(\"q\", \"e\")");
    GeneratedCode.compile(dir, Map.of("p.Q", java));
  }

  @Test
  void applicationTypesAreWrappedAndUnwrapped() throws IOException {
    ResolvedQuery q =
        resolved(
            "-- name: q :one\nSELECT id, status, other FROM t WHERE id = :id AND status = :status AND o = :o\n",
            List.of(
                new ResolvedParam("id", "id", ValueType.STRING, false, "varchar", new AppType("p.Id", false), false),
                new ResolvedParam("status", "status", ValueType.STRING, false, "text", new AppType("p.Status", true), false),
                new ResolvedParam("o", "o", ValueType.STRING, false, "text", new AppType("p.Id", false), true)),
            List.of(
                new ResolvedColumn("id", "id", ValueType.STRING, false, new AppType("p.Id", false), ColumnNullability.NOT_NULL),
                new ResolvedColumn("status", "status", ValueType.STRING, false, new AppType("p.Status", true), ColumnNullability.NOT_NULL),
                new ResolvedColumn("other", "other", ValueType.STRING, false, new AppType("p.Id", false), ColumnNullability.NULLABLE)));
    String java = generate(q);

    assertThat(java)
        .contains("ps.setString(1, id.value());")
        .contains("ps.setString(2, status.name());")
        .contains("p.@Nullable Id o")
        .contains("new Id(rs.getString(1))".replace("new Id", "new p.Id"))
        .contains("p.Status.valueOf(rs.getString(2))")
        .contains("r2 == null ? null : new p.Id(r2)");
    GeneratedCode.compile(
        dir,
        Map.of(
            "p.Q", java,
            "p.Id", "package p; public record Id(String value) {}",
            "p.Status", "package p; public enum Status { A }"));
  }

  @Test
  void arraysBindAndReadThroughTheirElementType() throws IOException {
    ResolvedQuery q =
        resolved(
            "-- name: q :many\nSELECT tags, nums FROM t WHERE tags = :tags\n",
            List.of(new ResolvedParam("tags", "tags", ValueType.STRING, true, "text", null, false)),
            List.of(
                new ResolvedColumn("tags", "tags", ValueType.STRING, true, null, ColumnNullability.NOT_NULL),
                new ResolvedColumn("nums", "nums", ValueType.INT, true, null, ColumnNullability.NULLABLE)));
    String java = generate(q);

    assertThat(java)
        .contains("ps.setArray(1, connection.createArrayOf(\"text\", tags.toArray()));")
        .contains("List<String> tags")
        .contains("@Nullable List<Integer> nums")
        .contains("readStringArray(")
        .contains("readIntArray(");
    GeneratedCode.compile(dir, Map.of("p.Q", java));
  }

  @Test
  void anExecQueryReturnsTheRowCount() throws IOException {
    String java =
        generate(
            resolved(
                "-- name: bump :exec\nUPDATE t SET v = :v WHERE id = :id\n",
                List.of(param("v", ValueType.LONG, false), param("id", ValueType.STRING, false)),
                List.of()));

    assertThat(java).contains("public static int bump(").contains("return ps.executeUpdate();");
    assertThat(java).doesNotContain("Row");
    GeneratedCode.compile(dir, Map.of("p.Q", java));
  }

  @Test
  void everyValueTypeBindsAndReadsAndCompiles() throws IOException {
    List<ResolvedParam> params = new java.util.ArrayList<>();
    List<ResolvedColumn> columns = new java.util.ArrayList<>();
    StringBuilder select = new StringBuilder();
    for (ValueType t : ValueType.values()) {
      for (boolean nullable : new boolean[] {false, true}) {
        String n = "v_" + t.name().toLowerCase(java.util.Locale.ROOT) + (nullable ? "_n" : "");
        params.add(param(n, t, nullable));
        columns.add(column(n, t, nullable ? ColumnNullability.NULLABLE : ColumnNullability.NOT_NULL));
        columns.add(column(n + "_c", t, ColumnNullability.CHECKED));
        select.append(":").append(n).append(", ");
      }
    }
    String java =
        generate(resolved("-- name: all :one\nSELECT " + select + "1\n", params, columns));

    GeneratedCode.compile(dir, Map.of("p.Q", java));
  }

  @Test
  void sqlWithQuotesAndBackslashesSurvivesTheTextBlock() throws IOException {
    ResolvedQuery q =
        resolved(
            "-- name: q :one\nSELECT 'a\\b', \"\"\"x\"\"\", \"id\" FROM t WHERE y = '\"'\n",
            List.of(),
            List.of(column("a", ValueType.STRING, ColumnNullability.NULLABLE)));
    String java = generate(q);

    ClassLoader loader = GeneratedCode.compile(dir, Map.of("p.Q", java));
    try {
      Object sql = loader.loadClass("p.Q").getField("Q_SQL").get(null);
      assertThat(sql).isEqualTo(q.query().sql());
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void generatingTwiceGivesIdenticalBytes() {
    ResolvedQuery q =
        resolved(
            "-- name: q :many\nSELECT a FROM t WHERE b = :b\n",
            List.of(param("b", ValueType.STRING, false)),
            List.of(column("a", ValueType.LONG, ColumnNullability.NULLABLE)));

    assertThat(generate(q).getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .isEqualTo(generate(q).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
