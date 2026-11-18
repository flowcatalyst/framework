//! Wall-clock time, injected (the Rust form of Java's `InstantSource` and Go's `fw.Clock`).
//!
//! Elapsed time (leases, timeouts, loop schedules) is `tokio::time::Instant`, which tests pause and
//! advance; wall time (event times, row timestamps, audit times) comes from a [`Clock`] given to the
//! application builder, so tests fix it. An instant is a [`jiff::Timestamp`] (Java's `Instant`); a
//! length of wall time is a [`jiff::SignedDuration`] (Java's `Duration`, Go's `time.Duration`).

use std::fmt;
use std::sync::{Mutex, PoisonError};

use jiff::{SignedDuration, Timestamp};

/// The application's wall clock.
pub trait Clock: Send + Sync + fmt::Debug {
    /// The current time.
    fn now(&self) -> Timestamp;

    /// The current time in milliseconds since the Unix epoch, rounded down (Java's
    /// `InstantSource.millis()`, Go's `UnixMilli`): a time before the epoch with a fraction of a
    /// millisecond reads as the millisecond before it.
    fn millis(&self) -> i64 {
        epoch_millis(self.now())
    }
}

/// Milliseconds since the Unix epoch, rounded towards negative infinity. jiff's own
/// `as_millisecond` rounds towards zero, which differs before 1970.
fn epoch_millis(t: Timestamp) -> i64 {
    // The fraction has the sign of the seconds, so a negative fraction takes a millisecond off.
    t.as_second() * 1000 + i64::from(t.subsec_nanosecond().div_euclid(1_000_000))
}

/// The system clock.
#[derive(Debug, Clone, Copy, Default)]
pub struct SystemClock;

impl Clock for SystemClock {
    #[expect(
        clippy::disallowed_methods,
        reason = "the one place that reads the system clock"
    )]
    fn now(&self) -> Timestamp {
        Timestamp::now()
    }
}

/// A clock that always reads the same time.
#[derive(Debug, Clone, Copy)]
pub struct FixedClock(Timestamp);

impl FixedClock {
    /// A clock stopped at `at`.
    #[must_use]
    pub const fn at(at: Timestamp) -> Self {
        Self(at)
    }
}

impl Clock for FixedClock {
    fn now(&self) -> Timestamp {
        self.0
    }
}

/// A clock a test moves by hand.
#[derive(Debug)]
pub struct ManualClock(Mutex<Timestamp>);

impl ManualClock {
    /// A clock starting at `at`.
    #[must_use]
    pub const fn starting_at(at: Timestamp) -> Self {
        Self(Mutex::new(at))
    }

    /// Sets the time.
    pub fn set(&self, at: Timestamp) {
        *self.0.lock().unwrap_or_else(PoisonError::into_inner) = at;
    }

    /// Moves the time forward (or back, for a negative duration).
    ///
    /// # Panics
    ///
    /// When the result is outside the range of [`Timestamp`] (years -9999 to 9999), as moving the
    /// clock out of its range did before.
    pub fn advance(&self, by: SignedDuration) {
        let mut now = self.0.lock().unwrap_or_else(PoisonError::into_inner);
        *now += by;
    }
}

impl Clock for ManualClock {
    fn now(&self) -> Timestamp {
        *self.0.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

#[cfg(test)]
mod tests {
    use jiff::tz::{self, TimeZone};

    use super::*;

    fn at(second: i64, nanosecond: i32) -> Timestamp {
        Timestamp::new(second, nanosecond).unwrap()
    }

    #[test]
    fn millis_round_down_as_java_and_go_do() {
        // Java: Instant.ofEpochSecond(s, n).toEpochMilli() (Math.floorDiv).
        assert_eq!(FixedClock::at(at(0, 0)).millis(), 0);
        assert_eq!(
            FixedClock::at(at(1_760_000_000, 123_999_999)).millis(),
            1_760_000_000_123
        );
        assert_eq!(FixedClock::at(at(0, -500_000)).millis(), -1);
        assert_eq!(FixedClock::at(at(-1, -999_000_000)).millis(), -1_999);
        assert_eq!(FixedClock::at(at(-1, -999_500_000)).millis(), -2_000);
        assert_eq!(
            FixedClock::at(Timestamp::MAX).millis(),
            Timestamp::MAX.as_millisecond()
        );
        assert_eq!(
            FixedClock::at(Timestamp::MIN).millis(),
            Timestamp::MIN.as_millisecond()
        );
    }

    #[test]
    fn a_manual_clock_moves_both_ways() {
        let clock = ManualClock::starting_at(at(1_000, 0));
        clock.advance(SignedDuration::from_millis(1_500));
        assert_eq!(clock.now(), at(1_001, 500_000_000));
        clock.advance(SignedDuration::from_secs(-2));
        assert_eq!(clock.now(), at(999, 500_000_000));
        clock.set(at(5, 0));
        assert_eq!(clock.millis(), 5_000);
    }

    // The workspace's jiff features: the bundled time zone database and no `tz-system`
    // (rust/Cargo.toml). If a dependency turned on jiff's defaults, these say so.

    #[test]
    fn the_time_zone_database_is_the_bundled_one_and_a_missing_zone_is_an_error() {
        assert!(!tz::db().is_definitively_empty());
        let joburg = TimeZone::get("Africa/Johannesburg").unwrap();
        let local = at(1_760_000_000, 0).to_zoned_datetime(joburg);
        assert_eq!(
            local.to_string(),
            "2025-10-09T10:53:20+02:00[Africa/Johannesburg]"
        );
        let missing = TimeZone::get("Nowhere/Land").unwrap_err().to_string();
        assert!(missing.contains("Nowhere/Land"), "{missing}");
    }

    #[test]
    #[expect(
        clippy::disallowed_methods,
        reason = "proves the host's zone is not read"
    )]
    fn the_hosts_time_zone_is_never_read() {
        assert!(TimeZone::try_system().is_err());
    }

    #[test]
    #[should_panic]
    fn a_manual_clock_moved_out_of_range_panics() {
        let clock = ManualClock::starting_at(Timestamp::MAX);
        clock.advance(SignedDuration::from_secs(1));
    }
}
