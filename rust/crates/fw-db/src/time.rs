//! PostgreSQL's time columns as jiff values (checked-SQL contract section 2: `timestamptz`,
//! `timestamp` and `date`).
//!
//! sqlx 0.8 maps these columns only to `chrono` or `time` types, and Rust's orphan rule keeps
//! anyone but sqlx or jiff from implementing sqlx's traits for jiff's types. So each column type
//! has one small type here that carries the jiff value across the database boundary and nowhere
//! else: an application's domain types, events and use cases hold [`jiff::Timestamp`],
//! [`jiff::civil::DateTime`] and [`jiff::civil::Date`]; only the SQL of a repository names these,
//! where it binds a value and where it reads a column:
//!
//! ```text
//! sqlx::query!(
//!     "UPDATE orders SET placed_at = $1 WHERE id = $2",
//!     PgTimestamptz(order.placed_at) as PgTimestamptz,
//!     order.id() as &OrderId,
//! )
//! // and `placed_at AS "placed_at: PgTimestamptz"` in a `query_as!` into a struct whose field is
//! // a `jiff::Timestamp` (the macro converts with `Into`).
//! ```
//!
//! The binary encoding is sqlx's own for `chrono` (microseconds or days since 2000-01-01), so a
//! value is stored as it was before: a fraction finer than a microsecond is cut towards
//! 2000-01-01. Reading PostgreSQL's `infinity` or `-infinity`, or a value outside jiff's range
//! (years -9999 to 9999), is an error naming the value, not a panic.

use std::error::Error;
use std::fmt;

use jiff::civil::{self, Date, DateTime};
use jiff::{SignedDuration, Timestamp};
use sqlx::encode::IsNull;
use sqlx::error::BoxDynError;
use sqlx::postgres::types::Oid;
use sqlx::postgres::{
    PgArgumentBuffer, PgHasArrayType, PgTypeInfo, PgValueFormat, PgValueRef, Postgres,
};
use sqlx::{Decode, Encode, Type};

/// A `timestamptz` column: an instant.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct PgTimestamptz(pub Timestamp);

/// A `timestamp` (without time zone) column: a civil date and time.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct PgTimestamp(pub DateTime);

/// A `date` column: a civil date.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct PgDate(pub Date);

// PostgreSQL's built-in type ids (pg_type.dat), as sqlx uses them for chrono.
const TIMESTAMPTZ: Oid = Oid(1184);
const TIMESTAMPTZ_ARRAY: Oid = Oid(1185);
const TIMESTAMP: Oid = Oid(1114);
const TIMESTAMP_ARRAY: Oid = Oid(1115);
const DATE: Oid = Oid(1082);
const DATE_ARRAY: Oid = Oid(1182);

/// PostgreSQL's epoch, 2000-01-01, from which it counts.
const EPOCH_DATE: Date = civil::date(2000, 1, 1);
const EPOCH_DATETIME: DateTime = EPOCH_DATE.at(0, 0, 0, 0);
const EPOCH: Timestamp = Timestamp::constant(946_684_800, 0);

/// A value the column holds that jiff cannot (`infinity`, or a year beyond 9999).
#[derive(Debug)]
struct OutOfRange {
    column: &'static str,
    value: String,
}

impl fmt::Display for OutOfRange {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{} value {} cannot be read as a jiff value (years -9999 to 9999)",
            self.column, self.value
        )
    }
}

impl Error for OutOfRange {}

fn out_of_range(column: &'static str, value: impl fmt::Display) -> BoxDynError {
    Box::new(OutOfRange {
        column,
        value: value.to_string(),
    })
}

/// Microseconds since PostgreSQL's epoch, rounded towards it (as sqlx's chrono encoding).
fn micros(d: SignedDuration) -> Result<i64, BoxDynError> {
    Ok(i64::try_from(d.as_micros())?)
}

fn from_micros(column: &'static str, us: i64) -> Result<SignedDuration, BoxDynError> {
    match us {
        i64::MAX => Err(out_of_range(column, "infinity")),
        i64::MIN => Err(out_of_range(column, "-infinity")),
        _ => Ok(SignedDuration::from_micros(us)),
    }
}

impl From<Timestamp> for PgTimestamptz {
    fn from(t: Timestamp) -> Self {
        Self(t)
    }
}

impl From<PgTimestamptz> for Timestamp {
    fn from(t: PgTimestamptz) -> Self {
        t.0
    }
}

impl From<DateTime> for PgTimestamp {
    fn from(t: DateTime) -> Self {
        Self(t)
    }
}

impl From<PgTimestamp> for DateTime {
    fn from(t: PgTimestamp) -> Self {
        t.0
    }
}

impl From<Date> for PgDate {
    fn from(d: Date) -> Self {
        Self(d)
    }
}

impl From<PgDate> for Date {
    fn from(d: PgDate) -> Self {
        d.0
    }
}

impl Type<Postgres> for PgTimestamptz {
    fn type_info() -> PgTypeInfo {
        PgTypeInfo::with_oid(TIMESTAMPTZ)
    }
}

impl PgHasArrayType for PgTimestamptz {
    fn array_type_info() -> PgTypeInfo {
        PgTypeInfo::with_oid(TIMESTAMPTZ_ARRAY)
    }
}

impl Encode<'_, Postgres> for PgTimestamptz {
    fn encode_by_ref(&self, buf: &mut PgArgumentBuffer) -> Result<IsNull, BoxDynError> {
        Encode::<Postgres>::encode(micros(self.0.duration_since(EPOCH))?, buf)
    }

    fn size_hint(&self) -> usize {
        size_of::<i64>()
    }
}

impl<'r> Decode<'r, Postgres> for PgTimestamptz {
    fn decode(value: PgValueRef<'r>) -> Result<Self, BoxDynError> {
        match value.format() {
            PgValueFormat::Binary => {
                let us: i64 = Decode::<Postgres>::decode(value)?;
                let d = from_micros("timestamptz", us)?;
                Ok(Self(EPOCH.checked_add(d).map_err(|_| {
                    out_of_range("timestamptz", format!("{us} microseconds after 2000-01-01"))
                })?))
            }
            // The simple query protocol: `2026-10-08 10:15:30.123+00` (sqlx sets the session's
            // TimeZone to UTC; any offset is read as written).
            PgValueFormat::Text => Ok(Self(value.as_str()?.parse()?)),
        }
    }
}

impl Type<Postgres> for PgTimestamp {
    fn type_info() -> PgTypeInfo {
        PgTypeInfo::with_oid(TIMESTAMP)
    }
}

impl PgHasArrayType for PgTimestamp {
    fn array_type_info() -> PgTypeInfo {
        PgTypeInfo::with_oid(TIMESTAMP_ARRAY)
    }
}

impl Encode<'_, Postgres> for PgTimestamp {
    fn encode_by_ref(&self, buf: &mut PgArgumentBuffer) -> Result<IsNull, BoxDynError> {
        Encode::<Postgres>::encode(micros(self.0.duration_since(EPOCH_DATETIME))?, buf)
    }

    fn size_hint(&self) -> usize {
        size_of::<i64>()
    }
}

impl<'r> Decode<'r, Postgres> for PgTimestamp {
    fn decode(value: PgValueRef<'r>) -> Result<Self, BoxDynError> {
        match value.format() {
            PgValueFormat::Binary => {
                let us: i64 = Decode::<Postgres>::decode(value)?;
                let d = from_micros("timestamp", us)?;
                Ok(Self(EPOCH_DATETIME.checked_add(d).map_err(|_| {
                    out_of_range("timestamp", format!("{us} microseconds after 2000-01-01"))
                })?))
            }
            PgValueFormat::Text => Ok(Self(value.as_str()?.parse()?)),
        }
    }
}

impl Type<Postgres> for PgDate {
    fn type_info() -> PgTypeInfo {
        PgTypeInfo::with_oid(DATE)
    }
}

impl PgHasArrayType for PgDate {
    fn array_type_info() -> PgTypeInfo {
        PgTypeInfo::with_oid(DATE_ARRAY)
    }
}

impl Encode<'_, Postgres> for PgDate {
    fn encode_by_ref(&self, buf: &mut PgArgumentBuffer) -> Result<IsNull, BoxDynError> {
        // Whole days: a civil date has no time of day and no zone.
        let days = self.0.duration_since(EPOCH_DATE).as_secs() / 86_400;
        Encode::<Postgres>::encode(i32::try_from(days)?, buf)
    }

    fn size_hint(&self) -> usize {
        size_of::<i32>()
    }
}

impl<'r> Decode<'r, Postgres> for PgDate {
    fn decode(value: PgValueRef<'r>) -> Result<Self, BoxDynError> {
        match value.format() {
            PgValueFormat::Binary => {
                let days: i32 = Decode::<Postgres>::decode(value)?;
                let d = match days {
                    i32::MAX => return Err(out_of_range("date", "infinity")),
                    i32::MIN => return Err(out_of_range("date", "-infinity")),
                    _ => SignedDuration::from_hours(i64::from(days) * 24),
                };
                Ok(Self(EPOCH_DATE.checked_add(d).map_err(|_| {
                    out_of_range("date", format!("{days} days after 2000-01-01"))
                })?))
            }
            PgValueFormat::Text => Ok(Self(value.as_str()?.parse()?)),
        }
    }
}
