package io.flowcatalyst.framework.partitions.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.conformance.Conformance;
import io.flowcatalyst.framework.http.Json;
import io.flowcatalyst.framework.partitions.ArchivedPartition;
import io.flowcatalyst.framework.partitions.Interval;
import io.flowcatalyst.framework.partitions.Key;
import io.flowcatalyst.framework.partitions.LastPass;
import io.flowcatalyst.framework.partitions.PartitionStatus;
import io.flowcatalyst.framework.partitions.PassTrigger;
import io.flowcatalyst.framework.partitions.Position;
import io.flowcatalyst.framework.partitions.RetentionKind;
import io.flowcatalyst.framework.partitions.RetentionStatus;
import io.flowcatalyst.framework.partitions.StepStatus;
import io.flowcatalyst.framework.partitions.TableStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The projection of a status to the section's wire documents and the formatting of durations, against
 * {@code conformance/admin/partitions.json} (the Go {@code vector_test.go}). No database: a status is
 * built from the vector's neutral input.
 */
class PartitionsAdminProjectionTest {

  static final JsonMapper READER = JsonMapper.builder().build();

  static JsonNode vector() {
    return READER.readTree(Conformance.read("admin/partitions.json"));
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? "" : v.asString();
  }

  static Key key(JsonNode k) {
    String kind = k.get("kind").asString();
    String column = k.get("column").asString();
    if (kind.equals("ID")) {
      return Key.byIdColumn(column, k.get("prefix").asString());
    }
    return k.get("type").asString().equals("DATE") ? Key.byDateColumn(column) : Key.byColumn(column);
  }

  static OptionalLong optionalLong(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? OptionalLong.empty() : OptionalLong.of(v.asLong());
  }

  static Optional<String> optionalText(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? Optional.empty() : Optional.of(v.asString());
  }

  static TableStatus status(JsonNode in) {
    JsonNode r = in.get("retention");
    RetentionStatus retention = new RetentionStatus(RetentionKind.valueOf(r.get("kind").asString()), Duration.ofSeconds(r.hasNonNull("afterSeconds") ? r.get("afterSeconds").asLong() : 0), text(r, "store"));
    Optional<LastPass> lastPass = Optional.empty();
    if (in.hasNonNull("lastPass")) {
      JsonNode l = in.get("lastPass");
      lastPass =
          Optional.of(
              new LastPass(
                  Instant.parse(l.get("endedAt").asString()),
                  StepStatus.valueOf(l.get("ensure").asString()),
                  StepStatus.valueOf(l.get("retention").asString()),
                  StepStatus.valueOf(l.get("archive").asString()),
                  l.get("created").asInt(),
                  l.get("dropped").asInt(),
                  l.get("archived").asInt(),
                  optionalText(l, "trigger").map(PassTrigger::valueOf),
                  optionalText(l, "actor")));
    }
    List<PartitionStatus> partitions = new ArrayList<>();
    for (JsonNode p : in.get("partitions")) {
      Optional<ArchivedPartition> archived = Optional.empty();
      if (p.hasNonNull("archived")) {
        JsonNode a = p.get("archived");
        archived = Optional.of(new ArchivedPartition(a.get("objects").asInt(), a.get("rows").asLong(), a.get("bytes").asLong(), Instant.parse(a.get("archivedAt").asString())));
      }
      partitions.add(
          new PartitionStatus(
              p.get("name").asString(),
              Instant.parse(p.get("from").asString()),
              Instant.parse(p.get("to").asString()),
              Position.valueOf(p.get("position").asString()),
              p.get("bytes").asLong(),
              optionalLong(p, "rows"),
              optionalText(p, "removalDue").map(Instant::parse),
              archived));
    }
    List<String> detached = new ArrayList<>();
    in.get("detached").forEach(d -> detached.add(d.asString()));
    List<String> strays = new ArrayList<>();
    in.get("strays").forEach(d -> strays.add(d.asString()));
    return new TableStatus(
        in.get("name").asString(),
        text(in, "database"),
        key(in.get("key")),
        Interval.valueOf(in.get("interval").asString()),
        in.get("ahead").asInt(),
        retention,
        in.get("healthy").asBoolean(),
        lastPass,
        partitions,
        detached,
        strays,
        in.get("bytes").asLong(),
        optionalLong(in, "rows"),
        in.get("partitionsWithoutEstimate").asInt());
  }

  static JsonNode wire(Object document) {
    // the mapper typed operations answer with
    return READER.readTree(Json.operationMapper().writeValueAsString(document));
  }

  @Test
  void theProjectionVector() {
    JsonNode cases = vector().get("projection");
    assertThat(cases.size()).isGreaterThanOrEqualTo(4);
    for (JsonNode c : cases) {
      TableStatus st = status(c.get("input"));
      String name = c.get("name").asString();
      assertThat(wire(PartitionsAdminApi.summaryOf(st))).as("%s: summary", name).isEqualTo(c.get("expect").get("summary"));
      assertThat(wire(PartitionsAdminApi.tableOf(st))).as("%s: table", name).isEqualTo(c.get("expect").get("table"));
    }
  }

  @Test
  void theDurationsVector() {
    JsonNode cases = vector().get("durations");
    assertThat(cases.size()).isPositive();
    for (JsonNode c : cases) {
      Duration d = Duration.ofNanos(Math.round(c.get("seconds").asDouble() * 1_000_000_000d));
      JsonNode retention = wire(PartitionsAdminApi.retentionOf(new RetentionStatus(RetentionKind.DROP, d, "")));
      assertThat(retention.get("after").asString()).as("%s seconds", c.get("seconds")).isEqualTo(c.get("iso").asString());
    }
  }
}
