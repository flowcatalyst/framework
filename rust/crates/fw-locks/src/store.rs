//! The driver contract.

use std::error::Error;

use fw_core::BoxFuture;

/// A lock store failure (store unreachable, query error, timeout). Not getting the lock is never
/// an error; it is [`crate::Acquire::Busy`].
#[derive(Debug, thiserror::Error)]
#[non_exhaustive]
pub enum LockStoreError {
    /// The store could not be reached or failed.
    #[error("lock store unavailable: {message}")]
    Unavailable {
        /// What failed.
        message: String,
        /// The cause.
        #[source]
        source: Box<dyn Error + Send + Sync>,
    },
    /// A lease from another lock instance was passed in (a programming error).
    #[error("this lease was not issued by this lock")]
    ForeignLease,
}

impl LockStoreError {
    /// An unavailable store, with its cause.
    pub fn unavailable(
        message: impl Into<String>,
        source: impl Error + Send + Sync + 'static,
    ) -> Self {
        Self::Unavailable {
            message: message.into(),
            source: Box::new(source),
        }
    }
}

/// A lock driver: the three atomic operations on stored keys. The user-facing logic (owner
/// tokens, deadlines, waiting, `with_lock`) is written once, in [`crate::Lock`].
///
/// Every driver guarantees (locks contract section 3): mutual exclusion on the store's clock,
/// expiry on the store's clock, increasing fencing tokens per key, owner-checked refresh and release
/// that refuse an expired lease, no reentrance, acquire after expiry or release at once, immediate
/// release.
pub trait LockStore: Send + Sync {
    /// Takes the key for `lease_ms` milliseconds if it is free or expired: the new fencing token,
    /// or `None` when it is held.
    fn try_acquire<'a>(
        &'a self,
        key: &'a str,
        owner: &'a str,
        lease_ms: i64,
    ) -> BoxFuture<'a, Result<Option<i64>, LockStoreError>>;

    /// Extends a lease that is still current and unexpired: true when it did.
    fn refresh<'a>(
        &'a self,
        key: &'a str,
        owner: &'a str,
        fence: i64,
        lease_ms: i64,
    ) -> BoxFuture<'a, Result<bool, LockStoreError>>;

    /// Ends a lease that is still current and unexpired: true when it did.
    fn release<'a>(
        &'a self,
        key: &'a str,
        owner: &'a str,
        fence: i64,
    ) -> BoxFuture<'a, Result<bool, LockStoreError>>;

    /// Test hook: expires the key's current lease at once in the store itself.
    fn expire_now<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Result<(), LockStoreError>>;
}
