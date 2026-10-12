//! Wall-clock time, injected (the Rust form of Java's `InstantSource`).
//!
//! Elapsed time (leases, timeouts, loop schedules) is `tokio::time::Instant`, which tests pause and
//! advance; wall time (event times, row timestamps, audit times) comes from a [`Clock`] given to the
//! application builder, so tests fix it.

use std::fmt;
use std::sync::{Mutex, PoisonError};

use chrono::{DateTime, TimeDelta, Utc};

/// The application's wall clock.
pub trait Clock: Send + Sync + fmt::Debug {
    /// The current time.
    fn now(&self) -> DateTime<Utc>;

    /// The current time in milliseconds since the Unix epoch.
    fn millis(&self) -> i64 {
        self.now().timestamp_millis()
    }
}

/// The system clock.
#[derive(Debug, Clone, Copy, Default)]
pub struct SystemClock;

impl Clock for SystemClock {
    fn now(&self) -> DateTime<Utc> {
        Utc::now()
    }
}

/// A clock that always reads the same time.
#[derive(Debug, Clone, Copy)]
pub struct FixedClock(DateTime<Utc>);

impl FixedClock {
    /// A clock stopped at `at`.
    #[must_use]
    pub const fn at(at: DateTime<Utc>) -> Self {
        Self(at)
    }
}

impl Clock for FixedClock {
    fn now(&self) -> DateTime<Utc> {
        self.0
    }
}

/// A clock a test moves by hand.
#[derive(Debug)]
pub struct ManualClock(Mutex<DateTime<Utc>>);

impl ManualClock {
    /// A clock starting at `at`.
    #[must_use]
    pub const fn starting_at(at: DateTime<Utc>) -> Self {
        Self(Mutex::new(at))
    }

    /// Sets the time.
    pub fn set(&self, at: DateTime<Utc>) {
        *self.0.lock().unwrap_or_else(PoisonError::into_inner) = at;
    }

    /// Moves the time forward (or back, for a negative delta).
    pub fn advance(&self, by: TimeDelta) {
        let mut now = self.0.lock().unwrap_or_else(PoisonError::into_inner);
        *now += by;
    }
}

impl Clock for ManualClock {
    fn now(&self) -> DateTime<Utc> {
        *self.0.lock().unwrap_or_else(PoisonError::into_inner)
    }
}
