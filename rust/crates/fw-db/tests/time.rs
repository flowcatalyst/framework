//! PostgreSQL's time columns as jiff values (`PgTimestamptz`, `PgTimestamp`, `PgDate`), against a
//! real PostgreSQL 18: what is stored, what is read, the edges of both ranges, the text protocol,
//! arrays, and checked queries (the `query!` family) reading and binding them.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use fw_core::{MigrationFile, MigrationSet, ModuleId};
use fw_db::migrations;
use fw_db::{PgDate, PgTimestamp, PgTimestamptz};
use jiff::civil::{self, Date, DateTime};
use jiff::{SignedDuration, Timestamp};
use sqlx::{PgPool, Row};

async fn pool() -> PgPool {
    let db = fw_test::postgres()
        .await
        .unwrap()
        .fresh_database()
        .await
        .unwrap();
    PgPool::connect(&db.url).await.unwrap()
}

fn ts(text: &str) -> Timestamp {
    text.parse().unwrap()
}

/// What PostgreSQL stored, as its own text (UTC, microseconds).
async fn stored_tz(pool: &PgPool, t: Timestamp) -> String {
    sqlx::query_scalar(
        "SELECT to_char($1::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US')",
    )
    .bind(PgTimestamptz(t))
    .fetch_one(pool)
    .await
    .unwrap()
}

#[tokio::test]
async fn an_instant_is_stored_and_read_to_the_microsecond() {
    let pool = pool().await;
    for (t, stored) in [
        ("2026-10-08T10:15:30Z", "2026-10-08T10:15:30.000000"),
        ("2026-10-08T10:15:30.123456Z", "2026-10-08T10:15:30.123456"),
        ("2026-10-08T12:15:30.5+02:00", "2026-10-08T10:15:30.500000"),
        ("2000-01-01T00:00:00Z", "2000-01-01T00:00:00.000000"),
        ("1999-12-31T23:59:59.999999Z", "1999-12-31T23:59:59.999999"),
        ("1969-12-31T23:59:59.999999Z", "1969-12-31T23:59:59.999999"),
        ("0001-01-01T00:00:00Z", "0001-01-01T00:00:00.000000"),
        ("9999-12-30T22:00:00.999999Z", "9999-12-30T22:00:00.999999"),
    ] {
        let t = ts(t);
        assert_eq!(stored_tz(&pool, t).await, stored, "{t}");
        let back: PgTimestamptz = sqlx::query_scalar("SELECT $1::timestamptz")
            .bind(PgTimestamptz(t))
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(back.0, t);
    }
}

/// sqlx's chrono encoding, kept byte for byte: the microseconds since 2000-01-01, cut towards
/// 2000-01-01. After it that is down; before it, up.
#[tokio::test]
async fn a_fraction_finer_than_a_microsecond_is_cut_towards_2000() {
    let pool = pool().await;
    assert_eq!(
        stored_tz(&pool, ts("2026-10-08T10:15:30.123456789Z")).await,
        "2026-10-08T10:15:30.123456"
    );
    assert_eq!(
        stored_tz(&pool, ts("1999-12-31T23:59:59.9999995Z")).await,
        "2000-01-01T00:00:00.000000"
    );
    assert_eq!(
        stored_tz(&pool, ts("1969-12-31T23:59:59.1234567Z")).await,
        "1969-12-31T23:59:59.123457"
    );
}

#[tokio::test]
async fn values_jiff_cannot_hold_are_errors_naming_them() {
    let pool = pool().await;
    for (sql, value) in [
        ("SELECT 'infinity'::timestamptz", "infinity"),
        ("SELECT '-infinity'::timestamptz", "-infinity"),
        (
            "SELECT '9999-12-31 23:59:59+00'::timestamptz",
            "252455615999000000 microseconds after 2000-01-01",
        ),
        (
            "SELECT '10000-01-01 00:00:00+00'::timestamptz",
            "microseconds after 2000-01-01",
        ),
    ] {
        let row = sqlx::query(sql).fetch_one(&pool).await.unwrap();
        let err = row.try_get::<PgTimestamptz, _>(0).unwrap_err().to_string();
        assert!(err.contains("timestamptz value"), "{sql}: {err}");
        assert!(err.contains(value), "{sql}: {err}");
    }
    let row = sqlx::query("SELECT 'infinity'::timestamp, 'infinity'::date")
        .fetch_one(&pool)
        .await
        .unwrap();
    let err = row.try_get::<PgTimestamp, _>(0).unwrap_err().to_string();
    assert!(err.contains("timestamp value infinity"), "{err}");
    let err = row.try_get::<PgDate, _>(1).unwrap_err().to_string();
    assert!(err.contains("date value infinity"), "{err}");
    // Before PostgreSQL's first year (4713 BC) the database refuses it.
    let err = sqlx::query("SELECT $1::timestamptz")
        .bind(PgTimestamptz(Timestamp::MIN))
        .fetch_one(&pool)
        .await
        .unwrap_err()
        .to_string();
    assert!(err.contains("out of range"), "{err}");
}

#[tokio::test]
async fn a_civil_date_time_and_a_date_are_stored_as_written() {
    let pool = pool().await;
    for (dt, text) in [
        (
            civil::date(2026, 10, 8).at(10, 15, 30, 500_000_000),
            "2026-10-08 10:15:30.5",
        ),
        (
            civil::date(1999, 12, 31).at(23, 59, 59, 999_999_000),
            "1999-12-31 23:59:59.999999",
        ),
        (civil::date(1, 1, 1).at(0, 0, 0, 0), "0001-01-01 00:00:00"),
        (
            DateTime::MAX.saturating_sub(SignedDuration::from_nanos(999)),
            "9999-12-31 23:59:59.999999",
        ),
    ] {
        let row = sqlx::query("SELECT $1::timestamp::text, $1::timestamp")
            .bind(PgTimestamp(dt))
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(row.get::<String, _>(0), text);
        assert_eq!(row.get::<PgTimestamp, _>(1).0, dt);
    }
    for (d, text) in [
        (civil::date(2026, 10, 8), "2026-10-08"),
        (civil::date(2000, 1, 1), "2000-01-01"),
        (civil::date(1999, 12, 31), "1999-12-31"),
        (civil::date(1, 1, 1), "0001-01-01"),
        (Date::MAX, "9999-12-31"),
    ] {
        let row = sqlx::query("SELECT $1::date::text, $1::date")
            .bind(PgDate(d))
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(row.get::<String, _>(0), text);
        assert_eq!(row.get::<PgDate, _>(1).0, d);
    }
}

#[tokio::test]
async fn the_text_protocol_reads_the_same_values() {
    let pool = pool().await;
    let row = sqlx::raw_sql(
        "SELECT '2026-10-08 12:15:30.123+02'::timestamptz, '2026-10-08 10:15:30.5'::timestamp, '2026-10-08'::date",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(
        row.get::<PgTimestamptz, _>(0).0,
        ts("2026-10-08T10:15:30.123Z")
    );
    assert_eq!(
        row.get::<PgTimestamp, _>(1).0,
        civil::date(2026, 10, 8).at(10, 15, 30, 500_000_000)
    );
    assert_eq!(row.get::<PgDate, _>(2).0, civil::date(2026, 10, 8));
}

#[tokio::test]
async fn arrays_and_nulls() {
    let pool = pool().await;
    let times = vec![
        PgTimestamptz(ts("2026-10-08T10:15:30Z")),
        PgTimestamptz(ts("1999-12-31T23:59:59.5Z")),
    ];
    let back: Vec<PgTimestamptz> = sqlx::query_scalar("SELECT $1::timestamptz[]")
        .bind(&times)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(back, times);
    let days = vec![PgDate(civil::date(2026, 10, 8))];
    let back: Vec<PgDate> = sqlx::query_scalar("SELECT $1::date[]")
        .bind(&days)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(back, days);
    let none: Option<PgTimestamptz> = sqlx::query_scalar("SELECT NULL::timestamptz")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(none, None);
}

/// One migration applied, so `fw_schema_history` holds a row whose `applied_at` is `now()`.
async fn migrated() -> PgPool {
    let pool = pool().await;
    let set = MigrationSet::new(
        ModuleId::of("alpha"),
        [MigrationFile::embedded(
            "V1__create_a.sql",
            b"CREATE TABLE a (x int);",
        )],
    );
    migrations::apply("main", &pool, &[set]).await.unwrap();
    pool
}

/// One row of the history, read by a checked query.
#[derive(Debug)]
struct Applied {
    module: String,
    version: i32,
    applied_at: Timestamp,
}

#[tokio::test]
async fn checked_queries_bind_and_read_time_columns() {
    let pool = migrated().await;
    let cutoff = ts("2100-01-01T00:00:00Z");

    // Reading into a struct: the column names `PgTimestamptz`, the field is a `Timestamp`
    // (`query_as!` converts with `Into`).
    let applied = sqlx::query_as!(
        Applied,
        r#"SELECT module, version, applied_at AS "applied_at: PgTimestamptz"
             FROM fw_schema_history
            WHERE applied_at <= $1
            ORDER BY module, version"#,
        PgTimestamptz(cutoff) as PgTimestamptz,
    )
    .fetch_all(&pool)
    .await
    .unwrap();
    assert_eq!(applied.len(), 1);
    assert_eq!(
        (applied[0].module.as_str(), applied[0].version),
        ("alpha", 1)
    );
    assert!(applied[0].applied_at <= cutoff);

    // An anonymous record keeps the column type; a nullable column is an `Option` of it.
    let last =
        sqlx::query!(r#"SELECT max(applied_at) AS "last: PgTimestamptz" FROM fw_schema_history"#)
            .fetch_one(&pool)
            .await
            .unwrap()
            .last
            .map(Timestamp::from);
    assert_eq!(last, Some(applied[0].applied_at));
}
