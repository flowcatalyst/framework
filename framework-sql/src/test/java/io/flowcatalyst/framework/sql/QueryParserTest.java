package io.flowcatalyst.framework.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;

class QueryParserTest {

  private static QueryFile parse(String text) {
    return QueryParser.parse("q.sql", text);
  }

  private static GenerationException fails(String text) {
    return catchThrowableOfType(GenerationException.class, () -> parse(text));
  }

  @Test
  void readsNamesKindsAndLines() {
    QueryFile f =
        parse(
            """
            -- A comment before the first query.

            -- name: findOrder :one
            SELECT id FROM orders WHERE id = :id;

            -- name: linesOf :many
            SELECT * FROM order_lines
             WHERE order_id = :order_id

            -- name: updateOrder :exec
            UPDATE orders SET status = :status WHERE id = :id
            """);

    assertThat(f.queries()).extracting(Query::name).containsExactly("findOrder", "linesOf", "updateOrder");
    assertThat(f.queries()).extracting(Query::kind).containsExactly(QueryKind.ONE, QueryKind.MANY, QueryKind.EXEC);
    assertThat(f.queries()).extracting(Query::line).containsExactly(3, 6, 10);
    assertThat(f.queries()).extracting(Query::sqlLine).containsExactly(4, 7, 11);
    assertThat(f.queries().get(0).sql()).isEqualTo("SELECT id FROM orders WHERE id = ?");
    assertThat(f.queries().get(1).sql()).isEqualTo("SELECT * FROM order_lines\n WHERE order_id = ?");
  }

  @Test
  void aRepeatedNameIsOneParameterBoundAtEveryPlaceholder() {
    Query q = parse("-- name: q :many\nSELECT :a, :b, :a, :c, :b\n").queries().get(0);

    assertThat(q.sql()).isEqualTo("SELECT ?, ?, ?, ?, ?");
    assertThat(q.parameterNames()).containsExactly("a", "b", "c");
    assertThat(q.placeholders()).containsExactly(0, 1, 0, 2, 1);
  }

  @Test
  void castsAreNotParameters() {
    Query q = parse("-- name: q :one\nSELECT :id::text, x::int, y::numeric(10,2)::text FROM t\n").queries().get(0);

    assertThat(q.parameterNames()).containsExactly("id");
    assertThat(q.sql()).isEqualTo("SELECT ?::text, x::int, y::numeric(10,2)::text FROM t");
  }

  @Test
  void aNameInsideAStringOrAQuotedIdentifierOrACommentIsNotAParameter() {
    Query q =
        parse(
                """
                -- name: q :many
                SELECT ':in_string', 'it''s :still_string', E'\\':escaped', ":quoted_ident" -- :line_comment
                  /* :block_comment /* :nested */ :still_comment */
                  , $$ :dollar $$, $tag$ :tagged $tag$, :real
                  FROM t WHERE a = :real
                """)
            .queries()
            .get(0);

    assertThat(q.parameterNames()).containsExactly("real");
    assertThat(q.placeholders()).containsExactly(0, 0);
    assertThat(q.sql()).contains("':in_string'").contains("-- :line_comment").contains(":dollar");
  }

  @Test
  void slicesAndNumericDollarParametersAreNotNames() {
    Query q = parse("-- name: q :one\nSELECT arr[1:2], :x FROM t\n").queries().get(0);

    assertThat(q.parameterNames()).containsExactly("x");
  }

  @Test
  void headerLinesDeclareParametersColumnsAndDocumentation() {
    Query q =
        parse(
                """
                -- name: q :one
                -- Finds the thing.
                -- Second line.
                -- param: id com.acme.OrderId
                -- param: note ?
                -- param: status enum com.acme.Status
                -- param: both ? com.acme.X
                -- column: status enum com.acme.Status
                -- column: total!
                -- column: note ? com.acme.Y
                SELECT 1 WHERE :id = :note AND :status = :both
                """)
            .queries()
            .get(0);

    assertThat(q.doc()).containsExactly("Finds the thing.", "Second line.");
    assertThat(q.params())
        .containsExactly(
            new ParamDecl("id", false, "com.acme.OrderId", false, 4),
            new ParamDecl("note", true, null, false, 5),
            new ParamDecl("status", false, "com.acme.Status", true, 6),
            new ParamDecl("both", true, "com.acme.X", false, 7));
    assertThat(q.columns())
        .containsExactly(
            new ColumnDecl("status", Nullness.DEFAULT, "com.acme.Status", true, 8),
            new ColumnDecl("total", Nullness.NON_NULL, null, false, 9),
            new ColumnDecl("note", Nullness.NULLABLE, "com.acme.Y", false, 10));
  }

  @Test
  void commentsAfterTheSqlStartedAreSqlNotDeclarations() {
    Query q = parse("-- name: q :one\nSELECT 1\n-- param: x\n").queries().get(0);

    assertThat(q.params()).isEmpty();
    assertThat(q.sql()).isEqualTo("SELECT 1\n-- param: x");
  }

  @Test
  void theSqlIsDedentedAndTrailingBlankSpaceIsDropped() {
    Query q = parse("-- name: q :many\n    SELECT 1   \n      FROM t  \n\n\n").queries().get(0);

    assertThat(q.sql()).isEqualTo("SELECT 1\n  FROM t");
  }

  @Test
  void sqlBeforeTheFirstNameNamesTheLine() {
    GenerationException e = fails("-- hello\nSELECT 1;\n-- name: q :one\nSELECT 1\n");

    assertThat(e.file()).isEqualTo("q.sql");
    assertThat(e.line()).isEqualTo(2);
    assertThat(e).hasMessageContaining("q.sql:2:").hasMessageContaining("before the first");
  }

  @Test
  void badNameLinesAreErrorsWithLines() {
    assertThat(fails("-- name: q\nSELECT 1\n")).hasMessageContaining("q.sql:1:").hasMessageContaining(":one|:many|:exec");
    assertThat(fails("\n-- name: q :bogus\nSELECT 1\n")).hasMessageContaining("q.sql:2:").hasMessageContaining(":bogus");
    assertThat(fails("-- name: Find_Order :one\nSELECT 1\n")).hasMessageContaining("lower-camel");
    assertThat(fails("-- name: class :one\nSELECT 1\n")).hasMessageContaining("not a keyword");
    assertThat(fails("-- name: a :one\nSELECT 1\n-- name: a :many\nSELECT 2\n"))
        .hasMessageContaining("q.sql:3:")
        .hasMessageContaining("used twice");
    assertThat(fails("-- name: a :one\n-- just a comment\n")).hasMessageContaining("q.sql:1:").hasMessageContaining("no SQL");
    assertThat(fails("-- nothing\n")).hasMessageContaining("no queries");
  }

  @Test
  void badDeclarationsAreErrorsWithLines() {
    assertThat(fails("-- name: a :one\n-- param: ghost\nSELECT 1\n"))
        .hasMessageContaining("q.sql:2:")
        .hasMessageContaining("does not use :ghost");
    assertThat(fails("-- name: a :one\n-- param: x A\n-- param: x B\nSELECT :x\n"))
        .hasMessageContaining("q.sql:3:")
        .hasMessageContaining("declared twice");
    assertThat(fails("-- name: a :one\n-- param: x enum\nSELECT :x\n")).hasMessageContaining("q.sql:2:");
    assertThat(fails("-- name: a :one\n-- param: x 1bad\nSELECT :x\n")).hasMessageContaining("q.sql:2:");
    assertThat(fails("-- name: a :one\n-- column: x\nSELECT 1 AS x\n")).hasMessageContaining("overrides nothing");
  }

  @Test
  void moreThanOneStatementAndLiteralQuestionMarksAreErrors() {
    assertThat(fails("-- name: a :exec\nDELETE FROM t;\nDELETE FROM u\n"))
        .hasMessageContaining("q.sql:3:")
        .hasMessageContaining("more than one statement");
    assertThat(fails("-- name: a :one\nSELECT 1\n  WHERE x ? 'k'\n"))
        .hasMessageContaining("q.sql:3:")
        .hasMessageContaining("literal '?'");
  }

  @Test
  void aSemicolonOrQuestionMarkInAStringOrCommentIsFine() {
    Query q = parse("-- name: a :one\nSELECT ';?' -- why?; really\n;\n-- done\n").queries().get(0);

    assertThat(q.sql()).startsWith("SELECT ';?' -- why?; really");
  }

  @Test
  void errorsInParametersReportTheLineOfTheUse() {
    GenerationException e = fails("-- name: a :one\nSELECT 1\n  ,\n  ;\n  SELECT :x\n");

    assertThat(e.line()).isEqualTo(5);
  }

  @Test
  void codeMaskMarksOnlyCode() {
    String sql = "a 'b' \"c\" -- d\ne";
    boolean[] code = QueryParser.codeMask(sql);
    StringBuilder seen = new StringBuilder();
    for (int i = 0; i < sql.length(); i++) {
      seen.append(code[i] ? sql.charAt(i) : '.');
    }
    assertThat(seen.toString()).isEqualTo("a ... ... ....\ne");
  }
}
