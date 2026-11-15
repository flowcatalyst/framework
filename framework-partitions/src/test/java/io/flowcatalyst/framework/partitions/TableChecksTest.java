package io.flowcatalyst.framework.partitions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Declaration errors and due-ness, including the skew guard (contract sections 1, 5 and 9). */
class TableChecksTest {

  static Table good() {
    return Table.named("readings")
        .by(Key.byId("rdg"))
        .interval(Interval.MONTH)
        .ahead(3)
        .retention(Retention.dropAfter(Duration.ofDays(90)));
  }

  private static String problems(Table t) {
    return String.join("; ", t.check());
  }

  @Test
  void aGoodTableHasNoProblems() {
    assertThat(good().check()).isEmpty();
    assertThat(good().retention(Retention.keepForever()).check()).isEmpty();
    assertThat(good().retention(Retention.archiveThenDropAfter(Duration.ofDays(90), "archive")).check()).isEmpty();
  }

  @Test
  void everyDeclarationProblemIsFound() {
    String sixtyFour = "a".repeat(54); // 54 + 10 = 64 bytes
    record Case(String name, Table table, String want) {}
    for (Case c :
        new Case[] {
          new Case("no retention", Table.named("readings").by(Key.byId("rdg")).interval(Interval.MONTH).ahead(3), "no retention"),
          new Case("retention shorter than an interval", good().retention(Retention.dropAfter(Duration.ofDays(1))), "shorter than one month"),
          new Case("archive without a store", good().retention(Retention.archiveThenDropAfter(Duration.ofDays(90), "")), "needs the name of an object store"),
          new Case("bad prefix", good().by(Key.byId("Rdg")), "prefix"),
          new Case("bad column", good().by(Key.byColumn("Recorded At")), "column"),
          new Case("no key", Table.named("readings").interval(Interval.MONTH).ahead(3).retention(Retention.keepForever()), "no partition key"),
          new Case("ahead 0", good().ahead(0), "ahead must be 1 to 24"),
          new Case("ahead 25", good().ahead(25), "ahead must be 1 to 24"),
          new Case("no interval", Table.named("readings").by(Key.byId("rdg")).ahead(3).retention(Retention.keepForever()), "no interval"),
          new Case("bad name", Table.named("a.b.c").by(Key.byId("rdg")).interval(Interval.MONTH).ahead(3).retention(Retention.keepForever()), "[schema.]table"),
          new Case("upper case name", good().database("").retention(Retention.keepForever()).by(Key.byId("rdg")).interval(Interval.MONTH), null),
          new Case("name too long for partition names", Table.named("s." + sixtyFour).by(Key.byId("rdg")).interval(Interval.MONTH).ahead(3).retention(Retention.keepForever()), "too long"),
        }) {
      if (c.want() != null) {
        assertThat(problems(c.table())).as(c.name()).contains(c.want());
      }
    }
    assertThat(problems(Table.named("Readings").by(Key.byId("rdg")).interval(Interval.MONTH).ahead(3).retention(Retention.keepForever())))
        .contains("[schema.]table");
    Table fiftyThree = Table.named("a".repeat(53)).by(Key.byId("rdg")).interval(Interval.MONTH).ahead(3).retention(Retention.keepForever());
    assertThat(fiftyThree.check()).as("53 bytes plus the suffix is exactly 63").isEmpty();
  }

  @Test
  void theDeclarationProblemsAreAllReportedTogether() {
    Table bad = Table.named("Bad.Name.X");
    assertThat(bad.check()).hasSizeGreaterThanOrEqualTo(4);
  }

  @Test
  void aPartitionIsDueOnlyWhenItsWholeRangeIsPastTheCutoff() {
    Table tb = good(); // monthly, drop after 90 days
    Instant now = Instant.parse("2026-11-14T10:00:00Z"); // cutoff 2026-08-16
    Instant aug = Instant.parse("2026-08-01T00:00:00Z"); // ends 09-01: after the cutoff
    Instant jul = Instant.parse("2026-07-01T00:00:00Z"); // ends 08-01: before it
    assertThat(tb.due(aug, now)).isFalse();
    assertThat(tb.due(jul, now)).isTrue();
    assertThat(tb.due(Instant.parse("2026-11-01T00:00:00Z"), now)).as("current").isFalse();
    assertThat(tb.due(Instant.parse("2026-12-01T00:00:00Z"), now)).as("future").isFalse();
    Instant exact = Instant.parse("2026-09-01T00:00:00Z").plus(Duration.ofDays(90));
    assertThat(tb.due(aug, exact)).as("a partition whose upper bound equals the cutoff is due").isTrue();
    assertThat(good().retention(Retention.keepForever()).due(jul, now)).isFalse();
  }

  /**
   * Whatever the retention says, a partition that ended less than one interval ago is never due. These
   * tables bypass the declaration check (a retention under one interval is refused at start-up) to prove the
   * guard on its own.
   */
  @Test
  void theSkewGuardRefusesWhatARetentionWouldRemove() {
    Instant now = Instant.parse("2026-11-14T10:00:00Z");
    Table daily = Table.named("t").by(Key.byColumn("c")).interval(Interval.DAY).ahead(1).retention(Retention.dropAfter(Duration.ofHours(1)));
    assertThat(daily.due(Instant.parse("2026-11-13T00:00:00Z"), now)).as("ended 10 hours ago").isFalse();
    assertThat(daily.due(Instant.parse("2026-11-12T00:00:00Z"), now)).as("ended 34 hours ago").isTrue();
    Table negative = Table.named("t").by(Key.byColumn("c")).interval(Interval.MONTH).ahead(1).retention(Retention.dropAfter(Duration.ofDays(-365)));
    for (String s : List.of("2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z", "2027-03-01T00:00:00Z")) {
      assertThat(negative.due(Instant.parse(s), now)).as(s).isFalse();
    }
  }

  @Test
  void removalDueIsTheFirstInstantAPartitionIsDue() {
    Instant may = Instant.parse("2026-05-01T00:00:00Z");
    Instant year = Instant.parse("2026-01-01T00:00:00Z");
    record Case(String name, Table table, Instant start, Instant want) {}
    for (Case c :
        new Case[] {
          new Case("retention longer than the span", good(), may, Instant.parse("2026-06-01T00:00:00Z").plus(Duration.ofDays(90))),
          new Case("archive", Table.named("t").by(Key.byColumn("c")).interval(Interval.MONTH).ahead(1).retention(Retention.archiveThenDropAfter(Duration.ofDays(40), "s")), may, Instant.parse("2026-06-01T00:00:00Z").plus(Duration.ofDays(40))),
          new Case("the guard decides", Table.named("t").by(Key.byColumn("c")).interval(Interval.MONTH).ahead(1).retention(Retention.dropAfter(Duration.ofHours(1))), may, Instant.parse("2026-06-01T00:00:00Z").plus(Duration.ofDays(31))),
          new Case("a year", Table.named("t").by(Key.byDateColumn("d")).interval(Interval.YEAR).ahead(1).retention(Retention.dropAfter(Duration.ofDays(3 * 366))), year, Instant.parse("2027-01-01T00:00:00Z").plus(Duration.ofDays(3 * 366))),
        }) {
      Instant got = c.table().removalDue(c.start()).orElseThrow();
      assertThat(got).as(c.name()).isEqualTo(c.want());
      assertThat(c.table().due(c.start(), got)).as(c.name() + " due at removalDue").isTrue();
      assertThat(c.table().due(c.start(), got.minusNanos(1))).as(c.name() + " not due a nanosecond before").isFalse();
    }
    assertThat(good().retention(Retention.keepForever()).removalDue(may)).isEmpty();
  }

  @Test
  void aYearlyDateKeyedTableIsDeclared() {
    Table yearly =
        Table.named("history").by(Key.byDateColumn("service_day")).interval(Interval.YEAR).ahead(2).retention(Retention.dropAfter(Duration.ofDays(5 * 366)));
    assertThat(yearly.check()).isEmpty();
    assertThat(problems(yearly.retention(Retention.dropAfter(Duration.ofDays(300))))).contains("shorter than one year interval");
    assertThat(problems(yearly.by(Key.byDateColumn("Service Day")))).contains("column");
  }
}
