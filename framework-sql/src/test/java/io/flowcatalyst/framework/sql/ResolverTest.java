package io.flowcatalyst.framework.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.flowcatalyst.framework.database.PostgresFixture;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The database's description of statements: types, nullability, overrides, errors. */
class ResolverTest {

  static PostgresFixture pg;
  Connection connection;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stopPostgres() {
    pg.close();
  }

  @BeforeEach
  void setUp() throws SQLException {
    pg.reset();
    pg.execute(
        """
        CREATE TABLE everything (
          c_text text NOT NULL, c_varchar varchar(20) NOT NULL, c_char char(3) NOT NULL,
          c_int2 int2 NOT NULL, c_int4 int4 NOT NULL, c_int8 int8 NOT NULL, c_bool bool NOT NULL,
          c_numeric numeric(10,2) NOT NULL, c_float4 float4 NOT NULL, c_float8 float8 NOT NULL,
          c_timestamptz timestamptz NOT NULL, c_timestamp timestamp NOT NULL, c_date date NOT NULL,
          c_uuid uuid NOT NULL, c_bytea bytea NOT NULL, c_json json NOT NULL, c_jsonb jsonb NOT NULL,
          a_text text[] NOT NULL, a_int4 int4[] NOT NULL, a_int8 int8[] NOT NULL, a_uuid uuid[] NOT NULL,
          a_timestamptz timestamptz[] NOT NULL, a_bool bool[] NOT NULL, a_numeric numeric[] NOT NULL,
          a_bytea bytea[] NOT NULL, a_date date[] NOT NULL, a_float8 float8[] NOT NULL,
          a_jsonb jsonb[] NOT NULL, a_timestamp timestamp[] NOT NULL, a_float4 float4[] NOT NULL,
          a_int2 int2[] NOT NULL, a_varchar varchar[] NOT NULL, a_json json[] NOT NULL
        );
        CREATE TABLE parents (id text PRIMARY KEY, name text NOT NULL, note text);
        CREATE TABLE children (id text PRIMARY KEY, parent_id text NOT NULL REFERENCES parents (id), qty int NOT NULL, memo text);
        CREATE TYPE mood AS ENUM ('happy', 'sad');
        CREATE TABLE odd (id int NOT NULL, ip inet NOT NULL, span interval NOT NULL, m mood NOT NULL, p point NOT NULL);
        """);
    connection = DriverManager.getConnection(pg.jdbcUrl(), pg.username(), pg.password());
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  private ResolvedQuery resolve(String kind, String sql) {
    Query q = QueryParser.parse("t.sql", "-- name: q :" + kind + "\n" + sql + "\n").queries().get(0);
    return QueryResolver.resolve("t.sql", q, connection);
  }

  private ResolvedQuery many(String sql) {
    return resolve("many", sql);
  }

  private static GenerationException failure(Runnable r) {
    return catchThrowableOfType(GenerationException.class, r::run);
  }

  private static ResolvedColumn col(ResolvedQuery q, String name) {
    return q.columns().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
  }

  // ---- types --------------------------------------------------------------------------------

  @Test
  void everyRowOfTheContractsTypeTable() {
    ResolvedQuery q = many("SELECT * FROM everything");

    assertThat(col(q, "c_text").type()).isEqualTo(ValueType.STRING);
    assertThat(col(q, "c_varchar").type()).isEqualTo(ValueType.STRING);
    assertThat(col(q, "c_char").type()).isEqualTo(ValueType.STRING);
    assertThat(col(q, "c_int2").type()).isEqualTo(ValueType.INT);
    assertThat(col(q, "c_int4").type()).isEqualTo(ValueType.INT);
    assertThat(col(q, "c_int8").type()).isEqualTo(ValueType.LONG);
    assertThat(col(q, "c_bool").type()).isEqualTo(ValueType.BOOLEAN);
    assertThat(col(q, "c_numeric").type()).isEqualTo(ValueType.BIG_DECIMAL);
    assertThat(col(q, "c_float4").type()).isEqualTo(ValueType.FLOAT);
    assertThat(col(q, "c_float8").type()).isEqualTo(ValueType.DOUBLE);
    assertThat(col(q, "c_timestamptz").type()).isEqualTo(ValueType.INSTANT);
    assertThat(col(q, "c_timestamp").type()).isEqualTo(ValueType.LOCAL_DATE_TIME);
    assertThat(col(q, "c_date").type()).isEqualTo(ValueType.LOCAL_DATE);
    assertThat(col(q, "c_uuid").type()).isEqualTo(ValueType.UUID);
    assertThat(col(q, "c_bytea").type()).isEqualTo(ValueType.BYTES);
    assertThat(col(q, "c_json").type()).isEqualTo(ValueType.JSON);
    assertThat(col(q, "c_jsonb").type()).isEqualTo(ValueType.JSON);
    assertThat(q.columns().stream().filter(c -> !c.array() && c.name().startsWith("c_"))).hasSize(17);
  }

  @Test
  void oneDimensionalArraysOfTheAboveAreLists() {
    ResolvedQuery q = many("SELECT * FROM everything");

    assertThat(q.columns().stream().filter(ResolvedColumn::array).map(ResolvedColumn::name))
        .hasSize(16);
    assertThat(col(q, "a_text")).satisfies(c -> assertThat(c.type()).isEqualTo(ValueType.STRING));
    assertThat(col(q, "a_int4").type()).isEqualTo(ValueType.INT);
    assertThat(col(q, "a_int2").type()).isEqualTo(ValueType.INT);
    assertThat(col(q, "a_int8").type()).isEqualTo(ValueType.LONG);
    assertThat(col(q, "a_uuid").type()).isEqualTo(ValueType.UUID);
    assertThat(col(q, "a_timestamptz").type()).isEqualTo(ValueType.INSTANT);
    assertThat(col(q, "a_timestamp").type()).isEqualTo(ValueType.LOCAL_DATE_TIME);
    assertThat(col(q, "a_date").type()).isEqualTo(ValueType.LOCAL_DATE);
    assertThat(col(q, "a_bytea").type()).isEqualTo(ValueType.BYTES);
    assertThat(col(q, "a_jsonb").type()).isEqualTo(ValueType.JSON);
    assertThat(col(q, "a_numeric").type()).isEqualTo(ValueType.BIG_DECIMAL);
    assertThat(col(q, "a_float4").type()).isEqualTo(ValueType.FLOAT);
    assertThat(col(q, "a_float8").type()).isEqualTo(ValueType.DOUBLE);
    assertThat(col(q, "a_bool").type()).isEqualTo(ValueType.BOOLEAN);
  }

  @Test
  void parametersTakeTheTypeTheDatabaseInfers() {
    ResolvedQuery q =
        many(
            """
            SELECT 1 AS one FROM everything
             WHERE c_int8 = :a AND c_text = :b AND c_uuid = :c AND c_int4 = ANY(:d)
               AND c_timestamptz > :e AND c_bool = :f AND c_numeric = :g AND c_date = :h
            """);

    assertThat(q.params())
        .extracting(ResolvedParam::name, ResolvedParam::type, ResolvedParam::array)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("a", ValueType.LONG, false),
            org.assertj.core.groups.Tuple.tuple("b", ValueType.STRING, false),
            org.assertj.core.groups.Tuple.tuple("c", ValueType.UUID, false),
            org.assertj.core.groups.Tuple.tuple("d", ValueType.INT, true),
            org.assertj.core.groups.Tuple.tuple("e", ValueType.INSTANT, false),
            org.assertj.core.groups.Tuple.tuple("f", ValueType.BOOLEAN, false),
            org.assertj.core.groups.Tuple.tuple("g", ValueType.BIG_DECIMAL, false),
            org.assertj.core.groups.Tuple.tuple("h", ValueType.LOCAL_DATE, false));
    assertThat(q.params().get(3).pgType()).isEqualTo("int4");
  }

  @Test
  void anUnsupportedColumnTypeFailsNamingTheQueryAndTheColumn() {
    GenerationException e = failure(() -> many("SELECT id, ip FROM odd"));

    assertThat(e)
        .hasMessageContaining("t.sql:1:")
        .hasMessageContaining("query 'q'")
        .hasMessageContaining("column 'ip'")
        .hasMessageContaining("'inet'")
        .hasMessageContaining("not supported");
    assertThat(failure(() -> many("SELECT span FROM odd"))).hasMessageContaining("column 'span'").hasMessageContaining("interval");
    assertThat(failure(() -> many("SELECT m FROM odd"))).hasMessageContaining("column 'm'").hasMessageContaining("mood");
    assertThat(failure(() -> many("SELECT p FROM odd"))).hasMessageContaining("'point'");
  }

  @Test
  void anUnsupportedParameterTypeFailsNamingTheQueryAndTheParameter() {
    GenerationException e = failure(() -> many("SELECT id FROM odd WHERE ip = :addr"));

    assertThat(e).hasMessageContaining("query 'q'").hasMessageContaining("parameter :addr").hasMessageContaining("'inet'");
  }

  @Test
  void aParameterUsedWithTwoTypesIsAnError() {
    GenerationException e = failure(() -> many("SELECT 1 FROM children WHERE qty = :x OR id = :x"));

    assertThat(e).hasMessageContaining("parameter :x").hasMessageContaining("two names");
  }

  // ---- nullability --------------------------------------------------------------------------

  @Test
  void aNotNullTableColumnIsNonNullAndANullableOneIsNullable() {
    ResolvedQuery q = many("SELECT id, name, note FROM parents");

    assertThat(col(q, "id").nullability()).isEqualTo(ColumnNullability.NOT_NULL);
    assertThat(col(q, "name").nullability()).isEqualTo(ColumnNullability.NOT_NULL);
    assertThat(col(q, "note").nullability()).isEqualTo(ColumnNullability.NULLABLE);
  }

  @Test
  void expressionsAndAggregatesAreNullable() {
    ResolvedQuery q = many("SELECT name || 'x' AS joined, upper(name) AS up, count(*) AS n, sum(1) AS total, 1 AS one FROM parents GROUP BY name");

    for (String c : List.of("joined", "up", "n", "total", "one")) {
      assertThat(col(q, c).nullability()).as(c).isEqualTo(ColumnNullability.NULLABLE);
    }
  }

  @Test
  void anOuterJoinMakesEveryColumnNullable() {
    for (String join : List.of("LEFT JOIN", "left outer join", "RIGHT JOIN", "FULL OUTER JOIN")) {
      ResolvedQuery q = many("SELECT p.id, p.name, c.qty FROM parents p " + join + " children c ON c.parent_id = p.id");
      assertThat(q.columns()).as(join).allSatisfy(c -> assertThat(c.nullability()).isEqualTo(ColumnNullability.NULLABLE));
    }
    ResolvedQuery inner = many("SELECT p.id, c.qty FROM parents p JOIN children c ON c.parent_id = p.id");
    assertThat(inner.columns()).allSatisfy(c -> assertThat(c.nullability()).isEqualTo(ColumnNullability.NOT_NULL));
  }

  @Test
  void theWordLeftJoinInACommentOrStringDoesNotCountAsAnOuterJoin() {
    ResolvedQuery q = many("SELECT id /* LEFT JOIN */ FROM parents WHERE name <> 'LEFT JOIN' -- LEFT JOIN");

    assertThat(col(q, "id").nullability()).isEqualTo(ColumnNullability.NOT_NULL);
  }

  @Test
  void bangAndQuestionOverridesInTheAlias() {
    ResolvedQuery q =
        many(
            """
            SELECT count(*) AS "n!", coalesce(sum(qty), 0) AS "total!", p.id AS "pid?", p.note AS "note!"
              FROM parents p LEFT JOIN children c ON c.parent_id = p.id GROUP BY p.id
            """);

    assertThat(col(q, "n").nullability()).isEqualTo(ColumnNullability.CHECKED);
    assertThat(col(q, "total").nullability()).isEqualTo(ColumnNullability.CHECKED);
    assertThat(col(q, "pid").nullability()).isEqualTo(ColumnNullability.NULLABLE);
    assertThat(col(q, "note").nullability()).isEqualTo(ColumnNullability.CHECKED);
  }

  @Test
  void overridesCanAlsoBeWrittenAsColumnLinesAndCombineWithTypes() {
    ResolvedQuery q =
        resolve(
            "many",
            """
            -- column: id com.acme.Id
            -- column: status ! enum com.acme.Status
            -- column: n!
            SELECT id, 'x'::text AS status, count(*) AS n FROM parents GROUP BY id
            """);

    assertThat(col(q, "id").app()).isEqualTo(new AppType("com.acme.Id", false));
    assertThat(col(q, "id").nullability()).isEqualTo(ColumnNullability.NOT_NULL);
    assertThat(col(q, "status").app()).isEqualTo(new AppType("com.acme.Status", true));
    assertThat(col(q, "status").nullability()).isEqualTo(ColumnNullability.CHECKED);
    assertThat(col(q, "n").nullability()).isEqualTo(ColumnNullability.CHECKED);
  }

  @Test
  void aliasOverridesCarryTypesAndCombine() {
    ResolvedQuery q =
        many(
            """
            SELECT id AS "id: com.acme.Id", name AS "name?: com.acme.Name", note AS "note!: com.acme.Note",
                   'x'::text AS "kind: enum com.acme.Kind" FROM parents
            """);

    assertThat(col(q, "id").app()).isEqualTo(new AppType("com.acme.Id", false));
    assertThat(col(q, "name").app()).isEqualTo(new AppType("com.acme.Name", false));
    assertThat(col(q, "name").nullability()).isEqualTo(ColumnNullability.NULLABLE);
    assertThat(col(q, "note").nullability()).isEqualTo(ColumnNullability.CHECKED);
    assertThat(col(q, "kind").app()).isEqualTo(new AppType("com.acme.Kind", true));
  }

  @Test
  void parameterDeclarationsSetNullabilityAndTypes() {
    ResolvedQuery q =
        resolve(
            "one",
            """
            -- param: id com.acme.Id
            -- param: note ?
            -- param: kind enum com.acme.Kind
            SELECT 1 AS one FROM parents WHERE id = :id AND note = :note AND name = :kind
            """);

    assertThat(q.params().get(0).app()).isEqualTo(new AppType("com.acme.Id", false));
    assertThat(q.params().get(0).nullable()).isFalse();
    assertThat(q.params().get(1).nullable()).isTrue();
    assertThat(q.params().get(2).app()).isEqualTo(new AppType("com.acme.Kind", true));
  }

  // ---- errors -------------------------------------------------------------------------------

  @Test
  void anAliasThePostgresqlWouldTruncateIsRefused() {
    String type = "com.acme.averyveryverylongpackage.name.that.cannot.fit.in.sixty.three.bytes.Type";
    GenerationException e = failure(() -> many("SELECT id AS \"id: " + type + "\" FROM parents"));

    assertThat(e).hasMessageContaining("truncates").hasMessageContaining("-- column:");
  }

  @Test
  void aColumnWithoutAnAliasAsksForOne() {
    GenerationException e = failure(() -> many("SELECT 1 + 1 FROM parents"));

    assertThat(e).hasMessageContaining("column '?column?'").hasMessageContaining("alias");
  }

  @Test
  void duplicateAndReservedNamesAreErrors() {
    assertThat(failure(() -> many("SELECT id AS x, name AS x FROM parents"))).hasMessageContaining("both be 'x'");
    assertThat(failure(() -> many("SELECT id AS \"class\" FROM parents"))).hasMessageContaining("reserved");
    assertThat(failure(() -> many("SELECT 1 FROM parents WHERE id = :connection"))).hasMessageContaining("reserved");
  }

  @Test
  void aColumnLineForAColumnThatIsNotReturnedIsAnError() {
    GenerationException e = failure(() -> resolve("many", "-- column: ghost!\nSELECT id FROM parents"));

    assertThat(e).hasMessageContaining("t.sql:2:").hasMessageContaining("ghost");
  }

  @Test
  void kindsAndColumnsMustAgree() {
    assertThat(failure(() -> resolve("exec", "DELETE FROM parents WHERE id = :id RETURNING id")))
        .hasMessageContaining("returns rows");
    assertThat(failure(() -> resolve("one", "DELETE FROM parents WHERE id = :id")))
        .hasMessageContaining("no columns");
    ResolvedQuery exec = resolve("exec", "DELETE FROM parents WHERE id = :id");
    assertThat(exec.columns()).isEmpty();
    assertThat(exec.params()).hasSize(1);
  }

  @Test
  void anUnknownColumnIsTheDatabasesMessageWithFileAndLine() {
    Query q =
        QueryParser.parse("src/main/sql/p/T.sql", "-- name: a :one\nSELECT 1\n\n-- name: b :many\nSELECT id,\n       nope\n  FROM parents\n")
            .queries()
            .get(1);

    GenerationException e = failure(() -> QueryResolver.resolve("src/main/sql/p/T.sql", q, connection));

    assertThat(e.file()).isEqualTo("src/main/sql/p/T.sql");
    assertThat(e.line()).isEqualTo(4);
    assertThat(e)
        .hasMessageContaining("src/main/sql/p/T.sql:4:")
        .hasMessageContaining("query 'b'")
        .hasMessageContaining("column \"nope\" does not exist")
        .hasMessageContaining("SQL line 6");
  }

  @Test
  void aSyntaxErrorIsTheDatabasesMessage() {
    GenerationException e = failure(() -> many("SELEKT 1"));

    assertThat(e).hasMessageContaining("t.sql:1:").hasMessageContaining("syntax error");
  }
}
