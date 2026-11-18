//! An instant as text, the way the Java framework writes one (`Instant.toString()`, which is
//! `DateTimeFormatter.ISO_INSTANT`, and Jackson's `InstantSerializer`), wherever the bytes are a
//! contract: outbox payloads (the vectors in `conformance/outbox/`), event data, JSON on the wire.
//!
//! Do not use jiff's `Display` or serde forms there: they print as few fraction digits as the value
//! needs (`10:15:31.5Z`, where Java writes `10:15:31.500Z`) and six-digit negative years
//! (`-009999`, where Java writes `-9999`).

use std::fmt;

use jiff::Timestamp;
use jiff::tz::TimeZone;
use serde::Deserializer;
use serde::de;

/// The Java `Instant.toString()`: UTC, seconds always, the fraction in groups of three digits
/// (3, 6 or 9) when there is one, `Z`; a year before 1 with a minus sign and four digits.
#[must_use]
pub fn instant_text(t: Timestamp) -> String {
    let dt = TimeZone::UTC.to_datetime(t);
    let year = dt.year();
    let sign = if year < 0 { "-" } else { "" };
    let nanos = dt.subsec_nanosecond();
    let fraction = if nanos == 0 {
        String::new()
    } else if nanos % 1_000_000 == 0 {
        format!(".{:03}", nanos / 1_000_000)
    } else if nanos % 1_000 == 0 {
        format!(".{:06}", nanos / 1_000)
    } else {
        format!(".{nanos:09}")
    };
    format!(
        "{sign}{:04}-{:02}-{:02}T{:02}:{:02}:{:02}{fraction}Z",
        year.unsigned_abs(),
        dt.month(),
        dt.day(),
        dt.hour(),
        dt.minute(),
        dt.second(),
    )
}

/// Reads an instant written as RFC 3339 / ISO 8601 with an offset: `Z` or `z`, any offset
/// (`+02:00`, `-0530`), `T`, `t` or a space between date and time, zero to nine fraction digits.
///
/// # Errors
///
/// When the text is not such an instant, or is outside jiff's range (years -9999 to 9999).
pub fn parse_instant(text: &str) -> Result<Timestamp, jiff::Error> {
    text.parse()
}

/// Serde for a [`Timestamp`] field in Java's form ([`instant_text`]), for event data and any JSON
/// whose bytes a contract pins: `#[serde(with = "fw_core::instant_serde")]`, and
/// `fw_core::instant_serde::option` for `Option<Timestamp>`. Reading accepts what
/// [`parse_instant`] accepts.
pub mod instant_serde {
    use jiff::Timestamp;
    use serde::{Deserializer, Serializer};

    use super::{TimestampVisitor, instant_text};

    /// Writes `t` as [`instant_text`].
    ///
    /// # Errors
    ///
    /// The serializer's.
    pub fn serialize<S: Serializer>(t: &Timestamp, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&instant_text(*t))
    }

    /// Reads a string as [`parse_instant`](super::parse_instant) does.
    ///
    /// # Errors
    ///
    /// When the value is not a string holding an instant.
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Timestamp, D::Error> {
        d.deserialize_str(TimestampVisitor)
    }

    /// The same for `Option<Timestamp>`: `None` is written as `null` (or left out with
    /// `skip_serializing_if`), and `null` reads as `None`.
    pub mod option {
        use jiff::Timestamp;
        use serde::{Deserializer, Serializer};

        use super::super::{OptionalTimestampVisitor, instant_text};

        /// Writes `Some(t)` as [`instant_text`], `None` as `null`.
        ///
        /// # Errors
        ///
        /// The serializer's.
        pub fn serialize<S: Serializer>(t: &Option<Timestamp>, s: S) -> Result<S::Ok, S::Error> {
            match t {
                Some(t) => s.serialize_some(&instant_text(*t)),
                None => s.serialize_none(),
            }
        }

        /// Reads `null` as `None` and a string as [`parse_instant`](super::super::parse_instant)
        /// does.
        ///
        /// # Errors
        ///
        /// When the value is neither `null` nor a string holding an instant.
        pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Option<Timestamp>, D::Error> {
            d.deserialize_option(OptionalTimestampVisitor)
        }
    }
}

struct TimestampVisitor;

impl de::Visitor<'_> for TimestampVisitor {
    type Value = Timestamp;

    fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("an instant such as 2026-10-08T10:15:30.123Z")
    }

    fn visit_str<E: de::Error>(self, v: &str) -> Result<Timestamp, E> {
        parse_instant(v).map_err(|e| E::custom(format!("not an instant: {v:?}: {e}")))
    }
}

struct OptionalTimestampVisitor;

impl<'de> de::Visitor<'de> for OptionalTimestampVisitor {
    type Value = Option<Timestamp>;

    fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("null or an instant such as 2026-10-08T10:15:30.123Z")
    }

    fn visit_none<E: de::Error>(self) -> Result<Self::Value, E> {
        Ok(None)
    }

    fn visit_unit<E: de::Error>(self) -> Result<Self::Value, E> {
        Ok(None)
    }

    fn visit_some<D: Deserializer<'de>>(self, d: D) -> Result<Self::Value, D::Error> {
        d.deserialize_str(TimestampVisitor).map(Some)
    }
}

#[cfg(test)]
mod tests {
    use jiff::SignedDuration;
    use serde::{Deserialize, Serialize};

    use super::*;

    /// Java's `Instant.ofEpochSecond(second, nano)`: `nano` is 0 to 999 999 999 after a whole
    /// second rounded down.
    fn java(second: i64, nano: i64) -> Timestamp {
        Timestamp::from_second(second)
            .unwrap()
            .checked_add(SignedDuration::from_nanos(nano))
            .unwrap()
    }

    /// The right-hand sides are what Java 25 prints for `Instant.ofEpochSecond(s, n).toString()`
    /// (and what `chrono`'s `to_rfc3339_opts(SecondsFormat::AutoSi, true)` printed before).
    #[test]
    fn instants_print_as_java_does() {
        let cases = [
            // whole seconds, and the fraction in groups of three
            ((1_759_918_530, 0), "2025-10-08T10:15:30Z"),
            ((1_759_918_531, 500_000_000), "2025-10-08T10:15:31.500Z"),
            ((1_759_918_530, 123_000_000), "2025-10-08T10:15:30.123Z"),
            ((1_759_918_530, 123_000), "2025-10-08T10:15:30.000123Z"),
            ((1_759_918_530, 1), "2025-10-08T10:15:30.000000001Z"),
            (
                (1_759_918_530, 123_456_700),
                "2025-10-08T10:15:30.123456700Z",
            ),
            ((1_759_918_530, 120_000_000), "2025-10-08T10:15:30.120Z"),
            // before 1970, before year 1, and the ends of jiff's range
            ((0, 0), "1970-01-01T00:00:00Z"),
            ((-1, 999_000_000), "1969-12-31T23:59:59.999Z"),
            ((-2_208_988_800, 120_000_000), "1900-01-01T00:00:00.120Z"),
            ((-62_135_596_800, 0), "0001-01-01T00:00:00Z"),
            ((-62_167_219_200, 0), "0000-01-01T00:00:00Z"),
            ((-62_185_708_800, 1_000), "-0001-06-01T00:00:00.000001Z"),
            (
                (-65_317_752_000, 123_456_789),
                "-0100-03-01T12:00:00.123456789Z",
            ),
            ((-93_724_128_000, 0), "-1000-01-01T00:00:00Z"),
            ((-377_705_023_201, 0), "-9999-01-02T01:59:59Z"),
            (
                (253_402_207_200, 999_999_999),
                "9999-12-30T22:00:00.999999999Z",
            ),
        ];
        for ((second, nano), want) in cases {
            assert_eq!(instant_text(java(second, nano)), want, "{second} {nano}");
        }
        assert_eq!(instant_text(Timestamp::MIN), "-9999-01-02T01:59:59Z");
        assert_eq!(
            instant_text(Timestamp::MAX),
            "9999-12-30T22:00:00.999999999Z"
        );
    }

    #[test]
    fn what_is_accepted_on_input() {
        let t = java(1_791_454_530, 0);
        for text in [
            "2026-10-08T10:15:30Z",
            "2026-10-08t10:15:30z",
            "2026-10-08 10:15:30Z",
            "2026-10-08T12:15:30+02:00",
            "2026-10-08T04:45:30-05:30",
            "2026-10-08T12:15:30+0200",
            "2026-10-08T10:15:30.000Z",
            "2026-10-08T10:15:30.000000000Z",
        ] {
            assert_eq!(parse_instant(text).unwrap(), t, "{text}");
        }
        assert_eq!(
            parse_instant("2026-10-08T10:15:30.5Z").unwrap(),
            java(1_791_454_530, 500_000_000)
        );
        for text in [
            "2026-10-08T10:15:30",   // no offset: a civil date-time, not an instant
            "2026-10-08",            // a date
            "2026-10-08T10:15:30.Z", // a point without digits
            "10000-01-01T00:00:00Z", // beyond jiff's range
            "2026-10-08T10:15:30.1234567891Z", // more than nine fraction digits
        ] {
            assert!(parse_instant(text).is_err(), "{text}");
        }
    }

    #[derive(Debug, PartialEq, Serialize, Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Data {
        #[serde(with = "instant_serde")]
        placed_at: Timestamp,
        #[serde(with = "instant_serde::option", default)]
        paid_at: Option<Timestamp>,
    }

    #[test]
    fn serde_writes_and_reads_java_form() {
        let data = Data {
            placed_at: java(1_791_454_531, 500_000_000),
            paid_at: None,
        };
        let json = serde_json::to_string(&data).unwrap();
        assert_eq!(
            json,
            r#"{"placedAt":"2026-10-08T10:15:31.500Z","paidAt":null}"#
        );
        assert_eq!(serde_json::from_str::<Data>(&json).unwrap(), data);
        let paid = Data {
            paid_at: Some(java(1_791_454_530, 123_000)),
            ..data
        };
        let json = serde_json::to_string(&paid).unwrap();
        assert_eq!(
            json,
            r#"{"placedAt":"2026-10-08T10:15:31.500Z","paidAt":"2026-10-08T10:15:30.000123Z"}"#
        );
        assert_eq!(serde_json::from_str::<Data>(&json).unwrap(), paid);
        let lenient: Data =
            serde_json::from_str(r#"{"placedAt":"2026-10-08t12:15:31.5+02:00"}"#).unwrap();
        assert_eq!(lenient.placed_at, java(1_791_454_531, 500_000_000));
        assert_eq!(lenient.paid_at, None);
        let err = serde_json::from_str::<Data>(r#"{"placedAt":"yesterday"}"#).unwrap_err();
        assert!(err.to_string().contains("not an instant"), "{err}");
        let err = serde_json::from_str::<Data>(r#"{"placedAt":1791454531}"#).unwrap_err();
        assert!(err.to_string().contains("an instant such as"), "{err}");
    }
}
