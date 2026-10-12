//! Leases and the checked argument types.

use std::fmt;
use std::sync::Arc;
use std::time::Duration;

use tokio::time::Instant;
use tokio_util::sync::CancellationToken;

use crate::LockError;
use crate::key::LockKey;
use crate::store::LockStore;

const MIN_LEASE: Duration = Duration::from_millis(100);
const MAX: Duration = Duration::from_secs(24 * 3600);

/// A lease length, 100 ms to 24 h inclusive; checked once, at construction.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct LeaseLength(Duration);

impl LeaseLength {
    /// A lease length.
    ///
    /// # Errors
    ///
    /// When `d` is outside 100 ms to 24 h.
    pub fn new(d: Duration) -> Result<Self, LockError> {
        if (MIN_LEASE..=MAX).contains(&d) {
            Ok(Self(d))
        } else {
            Err(LockError::Argument(format!(
                "lease must be from 100ms to 24h: {d:?}"
            )))
        }
    }

    /// The length.
    #[must_use]
    pub fn get(self) -> Duration {
        self.0
    }

    pub(crate) fn millis(self) -> i64 {
        i64::try_from(self.0.as_millis()).unwrap_or(i64::MAX)
    }
}

/// How long `acquire` may wait, zero to 24 h inclusive.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct MaxWait(Duration);

impl MaxWait {
    /// No wait: one try.
    pub const ZERO: Self = Self(Duration::ZERO);

    /// A wait.
    ///
    /// # Errors
    ///
    /// When `d` is over 24 h.
    pub fn new(d: Duration) -> Result<Self, LockError> {
        if d <= MAX {
            Ok(Self(d))
        } else {
            Err(LockError::Argument(format!(
                "maxWait must be from zero to 24h: {d:?}"
            )))
        }
    }

    /// The wait.
    #[must_use]
    pub fn get(self) -> Duration {
        self.0
    }
}

/// A held lock: key, owner token (128 random bits), fencing token, and the **local deadline**: the
/// monotonic time read before the acquire or refresh request was sent, plus the lease length, so it
/// is never later than the store's own expiry.
#[derive(Clone)]
pub struct Lease {
    pub(crate) key: LockKey,
    pub(crate) owner: Arc<str>,
    pub(crate) fence: i64,
    pub(crate) deadline: Instant,
    pub(crate) store: Arc<dyn LockStore>,
    pub(crate) lost: CancellationToken,
}

impl fmt::Debug for Lease {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Lease")
            .field("key", &self.key)
            .field("fencing_token", &self.fence)
            .field("deadline", &self.deadline)
            .finish_non_exhaustive()
    }
}

impl Lease {
    /// The key.
    #[must_use]
    pub fn key(&self) -> &LockKey {
        &self.key
    }

    /// The owner token.
    #[must_use]
    pub fn owner_token(&self) -> &str {
        &self.owner
    }

    /// The fencing token: pass it to what the protected work writes, which rejects a lower one.
    #[must_use]
    pub fn fencing_token(&self) -> i64 {
        self.fence
    }

    /// Time left before the local deadline.
    #[must_use]
    pub fn remaining(&self) -> Duration {
        self.deadline.saturating_duration_since(Instant::now())
    }

    /// Whether the local deadline has passed.
    #[must_use]
    pub fn expired_locally(&self) -> bool {
        Instant::now() >= self.deadline
    }

    /// Cancelled when `with_lock` finds the lock lost, so a cooperative action can stop at a safe
    /// point (`lease.lost().cancelled().await`) before its future is dropped.
    #[must_use]
    pub fn lost(&self) -> &CancellationToken {
        &self.lost
    }
}

/// The result of an acquire.
#[derive(Debug)]
pub enum Acquire {
    /// The lock is held.
    Acquired(Lease),
    /// Someone else holds it (or the wait ran out).
    Busy,
}

/// The result of a refresh.
#[derive(Debug)]
pub enum Refresh {
    /// A new lease value: same tokens, new deadline.
    Refreshed(Lease),
    /// The lease expired or was taken; nothing changed.
    Lost,
}

/// The result of a release.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Release {
    /// Released; the next acquire may succeed at once.
    Released,
    /// The lease was not current any more; nothing changed.
    NotHeld,
}

/// The result of `with_lock`.
#[derive(Debug, PartialEq, Eq)]
pub enum WithLock<T> {
    /// The action returned and the lock was held throughout.
    Completed(T),
    /// The lock was not acquired; the action did not run.
    NotAcquired,
    /// The lock was lost while the action ran: treat the work as having run without exclusion. The
    /// value is there when the action still returned after the loss was signalled.
    Lost(Option<T>),
}
