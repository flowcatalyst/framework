package io.flowcatalyst.framework.partitions.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.locks.AcquireResult;
import io.flowcatalyst.framework.locks.LockKey;
import io.flowcatalyst.framework.partitions.Partitions;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The five operations against a real PostgreSQL (the Go {@code section_test.go}). */
class PartitionsAdminOperationsTest extends PartitionsAdminTestBase {

  static List<JsonNode> list(TestResponse r, String field) {
    List<JsonNode> out = new ArrayList<>();
    JsonNode items = r.tree().get(field);
    if (items != null) {
      items.forEach(out::add);
    }
    return out;
  }

  @Test
  void theListAnswersEveryTableWithoutItsPartitions() throws IOException {
    Rig rig = start(false);
    TestResponse a = send(rig, "GET", "/api/admin/partitions", null, OPERATOR_VIEW_ONLY);
    assertThat(a.status()).as(a.body()).isEqualTo(200);
    List<JsonNode> tables = list(a, "tables");
    assertThat(tables).extracting(t -> t.get("name").asString()).containsExactly("public.readings", "public.samples");
    JsonNode readings = tables.get(0);
    for (String f : new String[] {"partitions", "detached", "strays"}) {
      assertThat(readings.has(f)).as("the list carries %s", f).isFalse();
    }
    // the earliest removal: November ends 2026-12-01, plus the 90 days of the retention
    assertThat(readings.get("interval").asString()).isEqualTo("MONTH");
    assertThat(readings.get("ahead").asInt()).isEqualTo(2);
    assertThat(readings.get("healthy").asBoolean()).isTrue();
    assertThat(readings.get("partitionCount").asInt()).isEqualTo(3);
    assertThat(readings.get("oldestFrom").asString()).isEqualTo("2026-11-01T00:00:00Z");
    assertThat(readings.get("nextRemovalDue").asString()).isEqualTo("2027-03-01T00:00:00Z");
    assertThat(readings.get("key").toString()).isEqualTo("{\"kind\":\"ID\",\"column\":\"id\",\"prefix\":\"rdg\"}");
    assertThat(readings.has("rows")).as("rows is absent before ANALYZE").isFalse();
    assertThat(readings.get("partitionsWithoutEstimate").asInt()).isEqualTo(3);
    JsonNode retention = readings.get("retention");
    assertThat(retention.get("kind").asString()).isEqualTo("ARCHIVE_THEN_DROP");
    assertThat(retention.get("after").asString()).as("90 days").isEqualTo("PT2160H");
    assertThat(retention.get("store").asString()).isEqualTo("archive");
    assertThat(readings.has("lastPass")).as("a last pass before any pass").isFalse();
    assertThat(readings.has("database")).as("database on the default instance").isFalse();
    JsonNode samples = tables.get(1);
    assertThat(samples.get("retention").get("kind").asString()).isEqualTo("DROP");
    assertThat(samples.get("retention").get("after").asString()).isEqualTo("PT720H");
    assertThat(samples.get("retention").has("store")).isFalse();
    assertThat(samples.get("interval").asString()).isEqualTo("WEEK");
    assertThat(samples.get("key").toString()).isEqualTo("{\"kind\":\"COLUMN\",\"column\":\"recorded_at\",\"type\":\"TIMESTAMPTZ\"}");
  }

  @Test
  void oneTableWithItsPartitionsAndTheRefusalOfAnUnknownOne() throws IOException {
    Rig rig = start(false);
    for (String name : new String[] {"readings", "public.readings"}) {
      TestResponse a = send(rig, "GET", "/api/admin/partitions/" + name, null, OPERATOR_VIEW_ONLY);
      assertThat(a.status()).as("%s: %s", name, a.body()).isEqualTo(200);
      JsonNode tree = a.tree();
      List<JsonNode> parts = list(a, "partitions");
      assertThat(tree.get("name").asString()).isEqualTo("public.readings");
      assertThat(parts).hasSize(3);
      assertThat(parts.get(0).get("name").asString()).isEqualTo("readings_p20261101");
      assertThat(parts.get(0).get("position").asString()).isEqualTo("CURRENT");
      assertThat(parts.get(1).get("position").asString()).isEqualTo("FUTURE");
      JsonNode p = parts.get(0);
      assertThat(p.get("from").asString()).isEqualTo("2026-11-01T00:00:00Z");
      assertThat(p.get("to").asString()).isEqualTo("2026-12-01T00:00:00Z");
      assertThat(p.get("bytes").asLong()).isPositive();
      assertThat(p.has("rows")).as("an estimate before ANALYZE").isFalse();
      assertThat(p.get("removalDue").asString()).as("2026-12-01 plus 90 days").isEqualTo("2027-03-01T00:00:00Z");
      assertThat(tree.get("detached").isArray()).isTrue();
      assertThat(tree.get("detached")).isEmpty();
      assertThat(tree.get("strays").isArray()).isTrue();
      assertThat(tree.get("strays")).isEmpty();
    }
    for (String target : new String[] {"/api/admin/partitions/nope", "/api/admin/partitions/nope/archives"}) {
      TestResponse a = send(rig, "GET", target, null, OPERATOR_VIEW_ONLY);
      assertThat(a.status()).as("%s: %s", target, a.body()).isEqualTo(404);
      assertThat(errorCode(a)).isEqualTo("PARTITION_TABLE_NOT_FOUND");
      assertThat(errorMessage(a)).isEqualTo("no declared table is named 'nope'");
    }
  }

  @Test
  void aPassArchivesAndTheArchivesPageNewestFirst() throws IOException {
    Rig rig = start(false);
    rig.ps().ensure("readings", Instant.parse("2026-04-01T00:00:00Z"), Instant.parse("2026-05-31T00:00:00Z"));
    for (int i = 0; i < 4; i++) {
      pg.execute("INSERT INTO readings (id, payload) VALUES ('" + idAt(Instant.parse("2026-04-0" + (3 + i) + "T01:00:00Z")) + "', 'x')");
    }
    pg.execute("INSERT INTO readings (id, payload) VALUES ('" + idAt(Instant.parse("2026-05-03T01:00:00Z")) + "', 'x')");

    TestResponse a = send(rig, "POST", "/api/admin/partitions/passes", "{\"table\":\"public.readings\"}", OPERATOR_WITH_BOTH);
    assertThat(a.status()).as(a.body()).isEqualTo(200);
    List<JsonNode> results = list(a, "results");
    assertThat(results).hasSize(1);
    JsonNode result = results.get(0);
    assertThat(result.get("table").asString()).isEqualTo("public.readings");
    assertThat(result.get("archived").asInt()).isEqualTo(2);
    assertThat(result.get("dropped").asInt()).isEqualTo(2);
    assertThat(result.get("created").asInt()).isZero();
    assertThat(result.get("failed").isArray()).isTrue();
    assertThat(result.get("failed")).isEmpty();
    // every table when the body names none
    a = send(rig, "POST", "/api/admin/partitions/passes", "{}", OPERATOR_WITH_BOTH);
    assertThat(list(a, "results")).extracting(r -> r.get("table").asString()).containsExactly("public.readings", "public.samples");

    // the record says the pass was manual and by whom
    a = send(rig, "GET", "/api/admin/partitions/readings", null, OPERATOR_VIEW_ONLY);
    JsonNode lastPass = a.tree().get("lastPass");
    assertThat(lastPass.get("trigger").asString()).isEqualTo("MANUAL");
    assertThat(lastPass.get("actor").asString()).isEqualTo("prn_TESTOP");

    a = send(rig, "GET", "/api/admin/partitions/readings/archives", null, OPERATOR_VIEW_ONLY);
    List<JsonNode> items = list(a, "items");
    assertThat(a.status()).as(a.body()).isEqualTo(200);
    assertThat(items).extracting(i -> i.get("partition").asString()).containsExactly("readings_p20260501", "readings_p20260401");
    assertThat(a.tree().has("nextCursor")).as("a next cursor on the only page").isFalse();
    JsonNode may = items.get(0);
    assertThat(may.get("table").asString()).isEqualTo("public.readings");
    assertThat(may.get("rows").asLong()).isEqualTo(1);
    assertThat(may.get("objects").asInt()).isEqualTo(1);
    assertThat(may.get("from").asString()).isEqualTo("2026-05-01T00:00:00Z");
    assertThat(may.get("to").asString()).isEqualTo("2026-06-01T00:00:00Z");
    assertThat(may.get("key").asString()).isEqualTo(Partitions.archiveKey("public", "readings", "readings_p20260501"));
    assertThat(may.get("bytes").asLong()).isPositive();
    assertThat(items.get(1).get("rows").asLong()).as("april").isEqualTo(4);

    // paging: limit and after, in the manner of admin pages
    a = send(rig, "GET", "/api/admin/partitions/readings/archives?limit=1", null, OPERATOR_VIEW_ONLY);
    assertThat(list(a, "items")).extracting(i -> i.get("partition").asString()).containsExactly("readings_p20260501");
    assertThat(a.tree().get("nextCursor").asString()).isEqualTo("readings_p20260501");
    a = send(rig, "GET", "/api/admin/partitions/readings/archives?limit=1&after=readings_p20260501", null, OPERATOR_VIEW_ONLY);
    assertThat(list(a, "items")).extracting(i -> i.get("partition").asString()).containsExactly("readings_p20260401");
    assertThat(a.tree().has("nextCursor")).as("a next cursor on the last page: %s", a.body()).isFalse();
    a = send(rig, "GET", "/api/admin/partitions/readings/archives?after=readings_p20260401", null, OPERATOR_VIEW_ONLY);
    assertThat(a.status()).isEqualTo(200);
    assertThat(a.body()).contains("\"items\":[]");
    // the last pass is on the list now
    a = send(rig, "GET", "/api/admin/partitions", null, OPERATOR_VIEW_ONLY);
    JsonNode readingsPass = list(a, "tables").get(0).get("lastPass");
    assertThat(readingsPass.get("ensure").asString()).isEqualTo("OK");
    assertThat(readingsPass.get("retention").asString()).isEqualTo("OK");
    assertThat(readingsPass.get("archive").asString()).isEqualTo("OK");
    assertThat(readingsPass.get("endedAt").asString()).isEqualTo("2026-11-14T10:00:00Z");
    assertThat(list(a, "tables").get(1).get("lastPass").get("archive").asString()).as("the archive step does not apply to a drop").isEqualTo("SKIPPED");
    String logs = log.text();
    for (String want : new String[] {"partitions: manual pass", "principal=prn_TESTOP", "tier=ANCHOR", "table=public.readings", "table=all"}) {
      assertThat(logs).as("the record has %s", want).contains(want);
    }
  }

  @Test
  void aFullPageOfTheLargestSizeStillSaysWhetherAnotherFollows() throws IOException {
    Rig rig = start(false);
    // 201 archived partitions: a day-by-day table would need 201 partitions; the manifest is filled by hand
    for (int i = 0; i < 201; i++) {
      String name = "readings_p" + String.format("%08d", 10000000 + i);
      pg.execute(
          "INSERT INTO fw_partition_archives (table_name, partition_name, part, object_key, row_count, byte_count, sha256, archived_at) VALUES ('public.readings', '"
              + name
              + "', 1, 'k/" + name + "', 1, 1, 'x', now())");
    }
    TestResponse first = send(rig, "GET", "/api/admin/partitions/readings/archives?limit=200", null, OPERATOR_VIEW_ONLY);
    assertThat(first.status()).as(first.body()).isEqualTo(200);
    assertThat(list(first, "items")).hasSize(200);
    String cursor = first.tree().get("nextCursor").asString();
    assertThat(cursor).as("one more follows").isEqualTo("readings_p" + String.format("%08d", 10000001));
    TestResponse second = send(rig, "GET", "/api/admin/partitions/readings/archives?limit=200&after=" + cursor, null, OPERATOR_VIEW_ONLY);
    assertThat(list(second, "items")).hasSize(1);
    assertThat(second.tree().has("nextCursor")).isFalse();
    // a limit above 200 is 200
    assertThat(list(send(rig, "GET", "/api/admin/partitions/readings/archives?limit=5000", null, OPERATOR_VIEW_ONLY), "items")).hasSize(200);
  }

  @Test
  void aPassIsRefusedWhileAnotherRunsAndAnUnknownTableIs404() throws Exception {
    Rig rig = start(false);
    TestResponse a = send(rig, "POST", "/api/admin/partitions/passes", "{\"table\":\"nope\"}", OPERATOR_WITH_BOTH);
    assertThat(a.status()).as(a.body()).isEqualTo(404);
    assertThat(errorCode(a)).isEqualTo("PARTITION_TABLE_NOT_FOUND");
    // another replica holds the loop's service lock
    AcquireResult.Acquired held = (AcquireResult.Acquired) rig.locks().tryAcquire(LockKey.service(Partitions.LOCK_NAME), Duration.ofHours(1));
    a = send(rig, "POST", "/api/admin/partitions/passes", "{}", OPERATOR_WITH_BOTH);
    assertThat(a.status()).as("a pass while another runs: %s", a.body()).isEqualTo(409);
    assertThat(errorCode(a)).isEqualTo("PARTITION_PASS_RUNNING");
    assertThat(errorMessage(a)).isEqualTo("another pass is running; try again when it has ended");
    rig.locks().release(held.lease());
    assertThat(send(rig, "POST", "/api/admin/partitions/passes", "{}", OPERATOR_WITH_BOTH).status()).as("after it ended").isEqualTo(200);
    for (String malformed : new String[] {"{\"table\":", "{\"table\":[\"a\"]}", "{\"tables\":\"readings\"}"}) {
      TestResponse bad = send(rig, "POST", "/api/admin/partitions/passes", malformed, OPERATOR_WITH_BOTH);
      assertThat(bad.status()).as("a malformed body %s: %s", malformed, bad.body()).isEqualTo(400);
    }
    assertThat(errorCode(send(rig, "POST", "/api/admin/partitions/passes", "{\"table\":\"" + "x".repeat(201) + "\"}", OPERATOR_WITH_BOTH))).isEqualTo("VALIDATION");
  }

  @Test
  void ensureAnswersCreatedAndAlreadyDueAndEveryRefusalHasItsCode() throws IOException {
    Rig rig = start(false);
    // 2026-08-16 is the cutoff: May to July end before it, so they are past retention at once
    String wide = "{\"from\":\"2026-05-10T00:00:00Z\",\"to\":\"2026-12-31T00:00:00Z\"}";
    TestResponse a = send(rig, "POST", "/api/admin/partitions/readings/ensure", wide, OPERATOR_WITH_BOTH);
    assertThat(a.status()).as(a.body()).isEqualTo(200);
    assertThat(a.tree().get("created").asInt()).isEqualTo(6);
    assertThat(a.tree().get("alreadyDue").asInt()).isEqualTo(3);
    a = send(rig, "POST", "/api/admin/partitions/public.readings/ensure", wide, OPERATOR_WITH_BOTH);
    assertThat(a.tree().get("created").asInt()).as("a second call: %s", a.body()).isZero();
    record Refusal(String name, String table, String body, int status, String code, String message) {}
    for (Refusal c :
        List.of(
            new Refusal("unknown table", "nope", "{\"from\":\"2026-05-10T00:00:00Z\",\"to\":\"2026-06-01T00:00:00Z\"}", 404, "PARTITION_TABLE_NOT_FOUND", "no declared table is named 'nope'"),
            new Refusal("to before from", "readings", "{\"from\":\"2026-06-10T00:00:00Z\",\"to\":\"2026-06-01T00:00:00Z\"}", 400, "PARTITION_RANGE_INVALID", "to is before from"),
            new Refusal("before 1970", "readings", "{\"from\":\"1969-06-10T00:00:00Z\",\"to\":\"2026-06-01T00:00:00Z\"}", 400, "PARTITION_RANGE_INVALID", "from is before 1970-01-01"),
            new Refusal(
                "too large",
                "readings",
                "{\"from\":\"1990-01-01T00:00:00Z\",\"to\":\"2026-01-01T00:00:00Z\"}",
                400,
                "PARTITION_RANGE_TOO_LARGE",
                "that range needs more than 400 partitions; ensure it in several calls"),
            new Refusal("a missing bound", "readings", "{\"from\":\"2026-06-10T00:00:00Z\"}", 400, "VALIDATION", ""),
            new Refusal("not an instant", "readings", "{\"from\":\"yesterday\",\"to\":\"2026-06-01T00:00:00Z\"}", 400, "", ""))) {
      a = send(rig, "POST", "/api/admin/partitions/" + c.table() + "/ensure", c.body(), OPERATOR_WITH_BOTH);
      assertThat(a.status()).as("%s: %s", c.name(), a.body()).isEqualTo(c.status());
      if (!c.code().isEmpty()) {
        assertThat(errorCode(a)).as(c.name()).isEqualTo(c.code());
      }
      if (!c.message().isEmpty()) {
        assertThat(errorMessage(a)).as(c.name()).isEqualTo(c.message());
      }
    }
    // a missing bound is named
    a = send(rig, "POST", "/api/admin/partitions/readings/ensure", "{\"from\":\"2026-06-10T00:00:00Z\"}", OPERATOR_WITH_BOTH);
    assertThat(a.body()).contains("\"to\"").contains("must be given");
    // a table of a partition's name that is not attached
    pg.execute("CREATE TABLE readings_p20250101 (id text)");
    a = send(rig, "POST", "/api/admin/partitions/readings/ensure", "{\"from\":\"2025-01-05T00:00:00Z\",\"to\":\"2025-01-20T00:00:00Z\"}", OPERATOR_WITH_BOTH);
    assertThat(a.status()).as(a.body()).isEqualTo(409);
    assertThat(errorCode(a)).isEqualTo("PARTITION_DETACHED_LEFTOVER");
    assertThat(errorMessage(a)).contains("readings_p20250101").doesNotStartWith("a detached table has the name of a partition").doesNotStartWith("partitions:");
    assertThat(log.text()).contains("partitions: manual ensure").contains("principal=prn_TESTOP").contains("tier=ANCHOR");
  }
}
