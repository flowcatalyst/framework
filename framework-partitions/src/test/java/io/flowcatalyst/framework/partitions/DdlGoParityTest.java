package io.flowcatalyst.framework.partitions;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.conformance.Conformance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A drift guard for {@link DdlPinTest}: the statements the Java module pins are looked up, as source text, in
 * the Go implementation that sits in the same repository ({@code go/partitions/}). If Go changes a statement
 * the guard fails, and the Java statement and its pin change with it. (The fragments are Go string literals,
 * so a quote is written {@code \"}.)
 */
class DdlGoParityTest {

  private static String go(String file) throws IOException {
    Path root = Conformance.root().getParent();
    return Files.readString(root.resolve("go/partitions").resolve(file), StandardCharsets.UTF_8);
  }

  @Test
  void theEnsureStatementsAreGos() throws IOException {
    String ensure = go("ensure.go");
    assertThat(ensure)
        .contains("\"CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')\"")
        .contains("\"SET LOCAL lock_timeout = '\"+ddlLockTimeout+\"'\"")
        .contains("\"SELECT pg_advisory_xact_lock($1)\"")
        .contains("\"SELECT to_regclass($1) IS NOT NULL\"")
        .contains("tg.childIdent(name), tg.ident(), tg.By.Bound(start), tg.By.Bound(tg.Interval.Next(start))");
    assertThat(go("partitions.go")).contains("ddlLockTimeout      = \"10s\"");
  }

  @Test
  void theRetentionStatementsAreGos() throws IOException {
    String retention = go("retention.go");
    assertThat(retention)
        .contains("\"ALTER TABLE \"+tg.ident()+\" DETACH PARTITION \"+ident")
        .contains("\"DROP TABLE \"+ident")
        .contains("\"COMMENT ON TABLE \"+ident+\" IS '\"+strings.ReplaceAll(tg.detachMark(), \"'\", \"''\")+\"'\"")
        .contains("\"DROP TABLE \"+tg.childIdent(name)")
        .contains("return \"fw-partitions: detached from \" + tg.qualified + \" for archiving\"")
        .contains("tg.rel+\"\\\\_p%\"");
  }

  @Test
  void theArchiveStatementsAndKeysAreGos() throws IOException {
    String archive = go("archive.go");
    assertThat(archive)
        .contains("\"SELECT row_to_json(t)::text FROM \"+tg.childIdent(name)+\" t\"")
        .contains("return \"partitions/\" + schema + \".\" + table + \"/\" + partition + \".ndjson.gz\"")
        .contains("\"partitions/%s.%s/%s.part%d.ndjson.gz\"")
        .contains("const archiveContentType = \"application/gzip\"")
        .contains("pgx.TxOptions{IsoLevel: pgx.RepeatableRead, AccessMode: pgx.ReadOnly}");
    assertThat(go("retention.go")).doesNotContain("PARTITION CONCURRENTLY");
  }

  @Test
  void theMigrationsAreTheSameFilesAsGos() throws IOException {
    Path root = Conformance.root().getParent();
    for (String f :
        new String[] {
          "V0001__create_partition_archives_table.sql",
          "V0002__create_partition_passes_table.sql",
          "V0003__add_partition_pass_trigger.sql"
        }) {
      byte[] java;
      try (var in = DdlGoParityTest.class.getClassLoader().getResourceAsStream("db/migration/partitions/" + f)) {
        assertThat(in).isNotNull();
        java = in.readAllBytes();
      }
      assertThat(java).as(f).isEqualTo(Files.readAllBytes(root.resolve("go/partitions/migrations").resolve(f)));
    }
  }
}
