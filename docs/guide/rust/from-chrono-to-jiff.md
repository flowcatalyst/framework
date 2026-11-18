<!-- types: Clock, FixedClock, ManualClock, SystemClock, EventMetadata, AuditEntry, PgTimestamptz, PgTimestamp, PgDate -->
# Rust: from chrono to jiff

On 2026-11-18 the Rust framework moved its time types from `chrono` to [`jiff`](https://docs.rs/jiff/1)
1.0 (the owner's decision). `chrono` is unmaintained (RUSTSEC-2026-0543), and `jiff` keeps an
instant (`Timestamp`) apart from a zoned date-time (`ZonedDateTime`) and a civil one
(`civil::DateTime`), as Java's `java.time` and Go's `time` do, with named time zones and zone-aware
arithmetic for the applications that need them. `chrono` is no longer a dependency of any crate, and
`rust/deny.toml` refuses it.

## The types

| Meaning | Before | Now | Java | Go |
|---|---|---|---|---|
| an instant (event time, audit time, row timestamps, `Clock::now`) | `chrono::DateTime<Utc>` | `jiff::Timestamp` | `Instant` | `time.Time` |
| a length of wall time (`ManualClock::advance`) | `chrono::TimeDelta` | `jiff::SignedDuration` | `Duration` | `time.Duration` |
| a date and time in a named zone | `chrono::DateTime<Tz>` (with `chrono-tz`) | `jiff::ZonedDateTime` | `ZonedDateTime` | `time.Time` with a `*time.Location` |
| a civil date and time (`timestamp` column) | `chrono::NaiveDateTime` | `jiff::civil::DateTime` | `LocalDateTime` | `time.Time` |
| a civil date (`date` column) | `chrono::NaiveDate` | `jiff::civil::Date` | `LocalDate` | `time.Time` |

`SignedDuration` and not `jiff::Span` or `std::time::Duration`: the clock moves by an exact length
of time, forwards or backwards (a negative one), as `TimeDelta` did. `std::time::Duration` cannot be
negative, and a `Span` counts calendar units (days, months) that an instant has no calendar for.
Elapsed time (leases, timeouts, loop intervals) is unchanged: `std::time::Duration` and
`tokio::time::Instant`.

## Converting

| From | To | How |
|---|---|---|
| `DateTime<Utc>` | `Timestamp` | `Timestamp::new(t.timestamp(), t.timestamp_subsec_nanos() as i32)?` |
| `Timestamp` | `DateTime<Utc>` | `let n = t.as_nanosecond(); DateTime::from_timestamp(n.div_euclid(1_000_000_000) as i64, n.rem_euclid(1_000_000_000) as u32)` (while you still need one; jiff's fraction has the sign of the seconds, chrono's is never negative) |
| `Utc.timestamp_millis_opt(ms).unwrap()` | `Timestamp::from_millisecond(ms)?` | |
| `Utc.with_ymd_and_hms(2026, 10, 8, 10, 15, 30).unwrap()` | `"2026-10-08T10:15:30Z".parse::<Timestamp>()?` | or `civil::date(2026, 10, 8).at(10, 15, 30, 0).to_zoned_datetime(TimeZone::UTC)?.timestamp()` |
| `t.timestamp_millis()` | `Clock::millis` | rounds down before 1970, as Java and Go; jiff's `as_millisecond` rounds towards zero |
| `TimeDelta::milliseconds(n)` | `SignedDuration::from_millis(n)` | |
| `t.to_rfc3339_opts(SecondsFormat::AutoSi, true)` | `fw_core::instant_text(t)` | Java's `Instant.toString()`; also `fw_usecase::instant_text` |
| `s.parse::<DateTime<Utc>>()` | `fw_core::parse_instant(s)` | see "Text" below |

## What an application changes

- **Clocks.** `Clock::now` returns `Timestamp`; `FixedClock::at`, `ManualClock::starting_at` and
  `ManualClock::set` take one; `ManualClock::advance` takes a `SignedDuration`
  ([core](core.md#time-and-the-clock)). Never read the system clock or the host's time zone:
  `jiff::Timestamp::now`, `jiff::ZonedDateTime::now` and `TimeZone::system` are refused by the
  workspace's `clippy.toml`; copy it into the application's.
- **Events and audit rows.** `EventMetadata::new` takes and `EventMetadata::time` returns a
  `Timestamp`; `AuditEntry::performed_at` is one. The outbox rows are unchanged, byte for byte.
- **Time fields in event data and other JSON whose bytes count.** A `Timestamp` field takes
  `#[serde(with = "fw_core::instant_serde")]` (`instant_serde::option` for `Option<Timestamp>`) to
  write Java's form, as a `DateTime<Utc>` did. jiff's own serde form (its `serde` feature, which the
  framework does not turn on) trims the fraction (`10:15:31.5Z` where Java writes `10:15:31.500Z`).
- **Time columns.** sqlx 0.8 has no jiff support, so its `chrono` feature is gone and a time column
  is read and bound through `fw_db::PgTimestamptz`, `PgTimestamp` or `PgDate`
  ([checked SQL](checked-sql.md#time-columns)):

| | Before (`chrono`) | Now (`jiff`) |
|---|---|---|
| column in `query_as!` into a struct | `placed_at` into a `DateTime<Utc>` field | `placed_at AS "placed_at: PgTimestamptz"` into a `Timestamp` field |
| column in `query!` | `row.placed_at` is a `DateTime<Utc>` | `row.placed_at` is a `PgTimestamptz`; `.0` or `Timestamp::from(..)` |
| nullable column | `Option<DateTime<Utc>>` | `Option<PgTimestamptz>`; `.map(Timestamp::from)` |
| bind in a checked query | `order.placed_at` | `PgTimestamptz(order.placed_at) as PgTimestamptz` |
| bind in `sqlx::query(..)` | `.bind(order.placed_at)` | `.bind(PgTimestamptz(order.placed_at))` |
| read with `Row::try_get` | `row.try_get::<DateTime<Utc>, _>("placed_at")?` | `row.try_get::<PgTimestamptz, _>("placed_at")?.0` |
| `#[derive(FromRow)]` field | `placed_at: DateTime<Utc>` | `#[sqlx(try_from = "PgTimestamptz")] placed_at: Timestamp` |

  A query that reads a time column without the override no longer compiles; sqlx's message says
  "SQLx feature `time` required for type TIMESTAMPTZ of column #1". Do not turn that feature on: add
  the override (or, for a parameter, the cast).

## Text

`fw_core::instant_text` writes exactly what `chrono`'s `AutoSi` form wrote, which is Java's
`Instant.toString()`: UTC, seconds always, a fraction of 3, 6 or 9 digits when there is one, `Z`,
and a year before 1 as `-0001`. The outbox vectors pin it (`fw-conformance`,
`instants_are_written_as_java_wrote_them`), and `fw-core`'s tests pin the rest against Java 25's
output (before 1970, before year 1, the ends of the range).

`fw_core::parse_instant` (and `instant_serde` reading) accepts what `chrono` accepted: an offset
other than `Z`, lower-case `t` and `z`, a space for `T`, no fraction or up to nine digits. It differs
at the edges: it refuses more than nine fraction digits (`chrono` cut them), a four-digit negative
year (jiff writes `-009999`), and a leap second `:60` reads as `:59`; it also accepts forms `chrono`
refused (no seconds, a `,` before the fraction, a bracketed zone after the offset).

## Range

`Timestamp` holds years -9999 to 9999 (`chrono` held about ±262 000). Reading a database value
beyond it, or PostgreSQL's `infinity`, is an error naming the value (with `chrono` the latter
panicked); a value before PostgreSQL's first year (4713 BC) is refused by the database, as before.

## The time zone database

`jiff` is built with its bundled copy of the IANA database (`tzdb-bundle-always`) and without
`tz-system`. The framework runs in small images, often distroless, with no `/usr/share/zoneinfo`;
the bundled copy works there and gives every environment the same rules, pinned by `Cargo.lock`
(update it with `cargo update -p jiff-tzdb`). A zone that is not in the database is an error naming
it (`TimeZone::get("Nowhere/Land")`), never UTC. An application that turns on jiff's default
features also gets the system database, which jiff then prefers where it exists.
