package io.flowcatalyst.framework.partitions;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

/**
 * The width of one partition (contract section 2). Every interval is in UTC: a day is the calendar
 * day, a week the ISO week from Monday 00:00, a month the calendar month and a year the calendar year.
 * The enum names are the wire codes {@code DAY}, {@code WEEK}, {@code MONTH} and {@code YEAR}.
 */
public enum Interval {
  /** The calendar day, 00:00 to 00:00 UTC. */
  DAY(Duration.ofHours(24)),
  /** The ISO week, Monday 00:00 UTC to the next Monday. */
  WEEK(Duration.ofDays(7)),
  /** The calendar month. */
  MONTH(Duration.ofDays(31)),
  /**
   * The calendar year, 1 January 00:00 UTC to the next 1 January: for long-retention, low-volume
   * tables.
   */
  YEAR(Duration.ofDays(366));

  private final Duration span;

  Interval(Duration span) {
    this.span = span;
  }

  /**
   * The start of the interval that contains an instant.
   *
   * @param t the instant
   * @return the start, in UTC
   */
  public Instant start(Instant t) {
    LocalDate day = t.atOffset(ZoneOffset.UTC).toLocalDate();
    LocalDate first =
        switch (this) {
          case DAY -> day;
          case WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
          case MONTH -> day.withDayOfMonth(1);
          case YEAR -> day.withDayOfYear(1);
        };
    return first.atStartOfDay().toInstant(ZoneOffset.UTC);
  }

  /**
   * The start of the interval after the one that starts at {@code start}.
   *
   * @param start the start of an interval, as {@link #start} returns it
   * @return the next start
   */
  public Instant next(Instant start) {
    LocalDate day = start.atOffset(ZoneOffset.UTC).toLocalDate();
    LocalDate next =
        switch (this) {
          case DAY -> day.plusDays(1);
          case WEEK -> day.plusDays(7);
          case MONTH -> day.plusMonths(1);
          case YEAR -> day.plusYears(1);
        };
    return next.atStartOfDay().toInstant(ZoneOffset.UTC);
  }

  /**
   * The nominal width the rules that need a duration use: a retention is at least one span, and the
   * retention pass never removes a partition that ended less than one span ago (day 24 h, week 7
   * days, month 31 days, year 366 days).
   *
   * @return the span
   */
  public Duration span() {
    return span;
  }

  /**
   * The wire code.
   *
   * @return {@code DAY}, {@code WEEK}, {@code MONTH} or {@code YEAR}
   */
  public String code() {
    return name();
  }

  /**
   * The lower-case word, for messages.
   *
   * @return {@code day}, {@code week}, {@code month} or {@code year}
   */
  String word() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
