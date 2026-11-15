package io.flowcatalyst.framework.partitions;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.conformance.JsonVectors;
import io.flowcatalyst.framework.core.Ids;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The shared vector {@code conformance/partitions/bounds.json} (contract sections 2 and 9), and the pure rules around it. */
class BoundsConformanceTest {

  static final String VECTOR = "partitions/bounds.json";

  static Interval interval(String s) {
    return switch (s) {
      case "day" -> Interval.DAY;
      case "week" -> Interval.WEEK;
      case "month" -> Interval.MONTH;
      case "year" -> Interval.YEAR;
      default -> throw new IllegalArgumentException("unknown interval " + s);
    };
  }

  @Test
  void theVectorNamesAndBoundsEveryCase() {
    ObjectNode doc = JsonVectors.read(VECTOR);
    assertThat(doc.get("bounds").size()).isEqualTo(80);
    for (JsonNode c : doc.get("bounds")) {
      Instant at = Instant.parse(c.get("instant").asString());
      Interval iv = interval(c.get("interval").asString());
      Key key =
          switch (c.get("by").asString()) {
            case "id" -> Key.byId(c.get("prefix").asString());
            case "date" -> Key.byDateColumn(c.get("column").asString());
            default -> Key.byColumn(c.get("column").asString());
          };
      Instant start = iv.start(at);
      String where = c.get("interval").asString() + " " + c.get("by").asString() + " " + c.get("instant").asString();
      assertThat(PartitionNames.name(c.get("table").asString(), start)).as(where + " name").isEqualTo(c.get("partition").asString());
      assertThat(key.bound(start)).as(where + " lower").isEqualTo(c.get("lower").asString());
      assertThat(key.bound(iv.next(start))).as(where + " upper").isEqualTo(c.get("upper").asString());
      assertThat(PartitionNames.parse(c.get("table").asString(), iv, c.get("partition").asString()))
          .as(where + " parses back")
          .contains(start);
    }
  }

  @Test
  void theVectorIdBoundsAndLockKeys() {
    ObjectNode doc = JsonVectors.read(VECTOR);
    assertThat(doc.get("idBounds").size()).isEqualTo(15);
    for (JsonNode c : doc.get("idBounds")) {
      assertThat(Key.idBound(c.get("prefix").asString(), Instant.parse(c.get("instant").asString())))
          .as(c.get("instant").asString())
          .isEqualTo(c.get("bound").asString());
    }
    assertThat(doc.get("lockKeys").size()).isEqualTo(3);
    for (JsonNode c : doc.get("lockKeys")) {
      assertThat(Long.toString(PartitionNames.lockKey(c.get("schema").asString(), c.get("table").asString())))
          .as(c.get("schema").asString() + "." + c.get("table").asString())
          .isEqualTo(c.get("key").asString());
    }
  }

  @Test
  void anIsoWeekCrossesTheYear() {
    for (String in : new String[] {"2026-12-31T10:00:00Z", "2027-01-03T23:59:59Z", "2026-12-28T00:00:00Z"}) {
      assertThat(Interval.WEEK.start(Instant.parse(in))).isEqualTo(Instant.parse("2026-12-28T00:00:00Z"));
    }
    assertThat(Interval.WEEK.start(Instant.parse("2027-01-04T00:00:00Z"))).isEqualTo(Instant.parse("2027-01-04T00:00:00Z"));
  }

  @Test
  void theUtcDayDecides() {
    // 2026-11-01 23:30 in UTC-5 is 2026-11-02 04:30 UTC
    Instant at = OffsetDateTime.of(2026, 11, 1, 23, 30, 0, 0, ZoneOffset.ofHours(-5)).toInstant();
    assertThat(Interval.DAY.start(at)).isEqualTo(Instant.parse("2026-11-02T00:00:00Z"));
    // and the date bound is the UTC date, not the local one
    assertThat(Key.byDateColumn("service_day").bound(at)).isEqualTo("2026-11-02");
    assertThat(Interval.YEAR.start(OffsetDateTime.of(2026, 12, 31, 23, 0, 0, 0, ZoneOffset.ofHours(-5)).toInstant()))
        .isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
  }

  @Test
  void theYearIntervalIsTheCalendarYear() {
    String[][] cases = {
      {"2026-11-14T10:00:00Z", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"},
      {"2026-12-31T23:59:59.999Z", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"},
      {"2027-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "2028-01-01T00:00:00Z"},
      {"2024-02-29T12:00:00Z", "2024-01-01T00:00:00Z", "2025-01-01T00:00:00Z"},
    };
    for (String[] c : cases) {
      Instant start = Interval.YEAR.start(Instant.parse(c[0]));
      assertThat(start).isEqualTo(Instant.parse(c[1]));
      assertThat(Interval.YEAR.next(start)).isEqualTo(Instant.parse(c[2]));
    }
    assertThat(Interval.YEAR.span()).hasDays(366);
    assertThat(Interval.YEAR.code()).isEqualTo("YEAR");
  }

  @Test
  void aGeneratedIdSortsIntoTheBoundsOfTheIntervalItsOwnMillisecondBelongsTo() {
    Key key = Key.byId("rdg");
    for (Interval iv : Interval.values()) {
      Instant start = iv.start(Instant.parse("2026-12-15T12:00:00Z"));
      Instant end = iv.next(start);
      boolean sawNext = false;
      for (Instant edge :
          new Instant[] {start.minusMillis(1), start, start.plusMillis(1), end.minusMillis(1), end}) {
        Ids ids = new Ids(() -> edge);
        for (int i = 0; i < 5000; i++) { // more than 4096 in one millisecond: the generator borrows the next
          String id = ids.generate("rdg");
          Instant own = iv.start(Instant.ofEpochMilli(msOf(id)));
          assertThat(id).as(iv + " " + id).isGreaterThanOrEqualTo(key.bound(own)).isLessThan(key.bound(iv.next(own)));
          for (Instant other : new Instant[] {iv.next(own), iv.start(own.minusMillis(1))}) {
            boolean inside = id.compareTo(key.bound(other)) >= 0 && id.compareTo(key.bound(iv.next(other))) < 0;
            assertThat(inside).as(id + " is inside the neighbour " + other).isFalse();
          }
          if (own.equals(end) && edge.isBefore(end)) {
            sawNext = true;
          }
        }
        if (edge.equals(end.minusMillis(1))) {
          assertThat(sawNext).as(iv + ": 5000 ids in the last millisecond borrow into the next interval").isTrue();
        }
      }
    }
  }

  /** The milliseconds a TSID carries, found independently of the bound code (the decode of Ids). */
  private static long msOf(String id) {
    long v = Ids.toLong(id.substring("rdg_".length())).orElseThrow();
    return v >>> 22;
  }

  @Test
  void anIdRangeSelectsAWindow() {
    Instant from = Instant.parse("2026-11-01T00:00:00Z");
    Instant to = from.plusSeconds(7 * 86400);
    Key.IdRange r = Key.idRange("rdg", from, to);
    assertThat(r.lo()).isEqualTo(Key.idBound("rdg", from)).isLessThan(r.hi());
    String in = new Ids(() -> from.plusSeconds(3600)).generate("rdg");
    String before = new Ids(() -> from.minusMillis(1)).generate("rdg");
    String after = new Ids(() -> to).generate("rdg");
    assertThat(in).isGreaterThanOrEqualTo(r.lo()).isLessThan(r.hi());
    assertThat(before).isLessThan(r.lo());
    assertThat(after).isGreaterThanOrEqualTo(r.hi());
  }

  @Test
  void aWindowAcrossAYearBoundary() {
    Instant from = Instant.parse("2026-12-20T00:00:00Z");
    Instant to = Instant.parse("2027-01-10T00:00:00Z");
    Key.IdRange r = Key.idRange("rdg", from, to);
    String yearStart = Key.idBound("rdg", Instant.parse("2027-01-01T00:00:00Z"));
    assertThat(r.lo()).isLessThan(yearStart);
    assertThat(yearStart).isLessThan(r.hi());
    record Case(String at, boolean in) {}
    for (Case c :
        new Case[] {
          new Case("2026-12-19T23:59:59.999Z", false),
          new Case("2026-12-20T00:00:00Z", true),
          new Case("2026-12-31T23:59:59.999Z", true),
          new Case("2027-01-01T00:00:00Z", true),
          new Case("2027-01-09T23:59:59.999Z", true),
          new Case("2027-01-10T00:00:00Z", false)
        }) {
      Instant at = Instant.parse(c.at());
      String id = new Ids(() -> at).generate("rdg");
      assertThat(id.compareTo(r.lo()) >= 0 && id.compareTo(r.hi()) < 0).as(c.at()).isEqualTo(c.in());
    }
  }

  @Test
  void anInstantBefore1970IsBoundAs1970() {
    assertThat(Key.idBound("rdg", Instant.parse("1969-06-01T00:00:00Z"))).isEqualTo(Key.idBound("rdg", Instant.EPOCH));
    assertThat(Key.idBound("rdg", Instant.EPOCH)).isEqualTo("rdg_0000000000000");
  }

  @Test
  void dateBoundsAreDateLiterals() {
    Key key = Key.byDateColumn("service_day");
    Instant at = Instant.parse("2026-12-31T23:59:59Z");
    for (Interval iv : Interval.values()) {
      Instant s = iv.start(at);
      assertThat(key.bound(s)).hasSize(10).isEqualTo(s.toString().substring(0, 10));
    }
    assertThat(key).hasToString("ByDateColumn(\"service_day\")");
    assertThat(key.isId()).isFalse();
  }

  @Test
  void parsingANameAcceptsOnlyARealStartOfTheDeclaredInterval() {
    record Case(String table, String name, Interval iv, boolean ok) {}
    for (Case c :
        new Case[] {
          new Case("readings", "readings_p20261101", Interval.MONTH, true),
          new Case("readings", "readings_p20261102", Interval.MONTH, false),
          new Case("readings", "readings_p20260101", Interval.YEAR, true),
          new Case("readings", "readings_p20260102", Interval.YEAR, false),
          new Case("readings", "readings_p20261101", Interval.YEAR, false),
          new Case("readings", "readings_p20240101", Interval.MONTH, true),
          new Case("readings", "readings_p20261228", Interval.WEEK, true),
          new Case("readings", "readings_p20261229", Interval.WEEK, false),
          new Case("readings", "readings_2026_11", Interval.MONTH, false),
          new Case("readings", "readings_p2026110", Interval.MONTH, false),
          new Case("readings", "readings_p20261301", Interval.MONTH, false),
          new Case("readings", "readings_old_p20261101", Interval.MONTH, false),
          new Case("readings", "other_p20261101", Interval.MONTH, false),
          new Case("readings", "readings_p20261101x", Interval.MONTH, false)
        }) {
      assertThat(PartitionNames.parse(c.table(), c.iv(), c.name()).isPresent()).as(c.name() + " as " + c.iv()).isEqualTo(c.ok());
    }
  }

  @Test
  void theKeyKindColumnTypeColumnAndPrefix() {
    assertKey(Key.byId("rdg"), KeyKind.ID, null, "id", "rdg");
    assertKey(Key.byIdColumn("event_id", "evt"), KeyKind.ID, null, "event_id", "evt");
    assertKey(Key.byColumn("recorded_at"), KeyKind.COLUMN, KeyColumnType.TIMESTAMPTZ, "recorded_at", "");
    assertKey(Key.byDateColumn("service_day"), KeyKind.COLUMN, KeyColumnType.DATE, "service_day", "");
  }

  private static void assertKey(Key k, KeyKind kind, KeyColumnType type, String column, String prefix) {
    assertThat(k.kind()).isEqualTo(kind);
    assertThat(k.columnType()).isEqualTo(java.util.Optional.ofNullable(type));
    assertThat(k.column()).isEqualTo(column);
    assertThat(k.prefix()).isEqualTo(prefix);
  }

  @Test
  void theKeyTextIsStillTheTextProblemsUse() {
    assertThat(Key.byId("rdg")).hasToString("ByID(\"rdg\") on id");
    assertThat(Key.byColumn("recorded_at")).hasToString("ByColumn(\"recorded_at\")");
  }
}
