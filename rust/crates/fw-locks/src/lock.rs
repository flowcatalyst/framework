//! The user-facing lock: owner tokens, deadlines, waiting with backoff, `with_lock` (locks
//! contract sections 2 and 4), written once over any driver.

use std::pin::pin;
use std::sync::Arc;
use std::time::Duration;
use std::{fmt, ptr};

use futures::FutureExt;
use rand::Rng;
use tokio::time::{self, Instant};
use tokio_util::sync::CancellationToken;

use crate::key::LockKey;
use crate::lease::{Acquire, Lease, LeaseLength, MaxWait, Refresh, Release, WithLock};
use crate::store::{LockStore, LockStoreError};

const BACKOFF_START: Duration = Duration::from_millis(10);
const BACKOFF_MAX: Duration = Duration::from_millis(250);
const REFRESH_RETRY_MAX: Duration = Duration::from_millis(250);

/// One named lock instance over a driver. Cloning shares it.
#[derive(Clone)]
pub struct Lock {
    store: Arc<dyn LockStore>,
}

impl fmt::Debug for Lock {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Lock")
    }
}

fn owner_token() -> String {
    let bytes: [u8; 16] = rand::rng().random();
    hex::encode(bytes)
}

impl Lock {
    /// A lock over a driver.
    #[must_use]
    pub fn new(store: Arc<dyn LockStore>) -> Self {
        Self { store }
    }

    fn own(&self, lease: &Lease) -> Result<(), LockStoreError> {
        if ptr::addr_eq(Arc::as_ptr(&lease.store), Arc::as_ptr(&self.store)) {
            Ok(())
        } else {
            Err(LockStoreError::ForeignLease)
        }
    }

    /// Tries once to take the lock.
    ///
    /// # Errors
    ///
    /// When the store fails.
    pub async fn try_acquire(
        &self,
        key: &LockKey,
        lease: LeaseLength,
    ) -> Result<Acquire, LockStoreError> {
        let owner = owner_token();
        let sent_at = Instant::now();
        match self
            .store
            .try_acquire(key.stored(), &owner, lease.millis())
            .await?
        {
            None => Ok(Acquire::Busy),
            Some(fence) => Ok(Acquire::Acquired(Lease {
                key: key.clone(),
                owner: Arc::from(owner),
                fence,
                deadline: sent_at + lease.get(),
                store: Arc::clone(&self.store),
                lost: CancellationToken::new(),
            })),
        }
    }

    /// Takes the lock, retrying with backoff (10 ms doubling to 250 ms, with jitter) until it is
    /// acquired or the wait is used up. Dropping the future abandons the wait.
    ///
    /// # Errors
    ///
    /// When the store fails.
    pub async fn acquire(
        &self,
        key: &LockKey,
        lease: LeaseLength,
        max_wait: MaxWait,
    ) -> Result<Acquire, LockStoreError> {
        let started = Instant::now();
        let mut backoff = BACKOFF_START;
        loop {
            let result = self.try_acquire(key, lease).await?;
            if matches!(result, Acquire::Acquired(_)) {
                return Ok(result);
            }
            let left = max_wait.get().saturating_sub(started.elapsed());
            if left.is_zero() {
                return Ok(result);
            }
            let jittered = rand::rng().random_range(backoff / 2..=backoff);
            time::sleep(jittered.min(left).max(Duration::from_millis(1))).await;
            backoff = (backoff * 2).min(BACKOFF_MAX);
        }
    }

    /// Extends a lease; acts only while it has not expired on the store's clock.
    ///
    /// # Errors
    ///
    /// When the store fails, or the lease is from another lock.
    pub async fn refresh(
        &self,
        lease: &Lease,
        length: LeaseLength,
    ) -> Result<Refresh, LockStoreError> {
        self.own(lease)?;
        let sent_at = Instant::now();
        if self
            .store
            .refresh(
                lease.key.stored(),
                &lease.owner,
                lease.fence,
                length.millis(),
            )
            .await?
        {
            Ok(Refresh::Refreshed(Lease {
                deadline: sent_at + length.get(),
                ..lease.clone()
            }))
        } else {
            Ok(Refresh::Lost)
        }
    }

    /// Releases a lease so the next acquire can succeed at once; acts only while it has not
    /// expired on the store's clock.
    ///
    /// # Errors
    ///
    /// When the store fails, or the lease is from another lock.
    pub async fn release(&self, lease: &Lease) -> Result<Release, LockStoreError> {
        self.own(lease)?;
        if self
            .store
            .release(lease.key.stored(), &lease.owner, lease.fence)
            .await?
        {
            Ok(Release::Released)
        } else {
            Ok(Release::NotHeld)
        }
    }

    /// Test hook: expires the key's current lease at once in the store.
    ///
    /// # Errors
    ///
    /// When the store fails.
    pub async fn expire_now(&self, key: &LockKey) -> Result<(), LockStoreError> {
        self.store.expire_now(key.stored()).await
    }

    /// Runs `action` while holding the lock (locks contract section 4), refreshing the lease every
    /// third of its length.
    ///
    /// If a refresh finds the lease lost, or refreshes keep failing until the local deadline has
    /// passed, the lock is lost: `lease.lost()` is cancelled, the action is polled once more (a
    /// cooperative action can return then, giving `Lost(Some(value))`), and otherwise its future is
    /// dropped (`Lost(None)`), which stops the work at its next `.await` (design decision 15). When
    /// the action ends with the lock held, the lock is released (a release failure is logged). The
    /// action's own errors are its value: `T` is usually a `Result`.
    ///
    /// # Errors
    ///
    /// When the store fails while acquiring.
    pub async fn with_lock<T>(
        &self,
        key: &LockKey,
        lease: LeaseLength,
        max_wait: MaxWait,
        action: impl AsyncFnOnce(&Lease) -> T,
    ) -> Result<WithLock<T>, LockStoreError> {
        let Acquire::Acquired(first) = self.acquire(key, lease, max_wait).await? else {
            return Ok(WithLock::NotAcquired);
        };
        let period = (lease.get() / 3).max(Duration::from_millis(1));
        let retry = period.clamp(Duration::from_millis(10), REFRESH_RETRY_MAX);
        let mut current = first.clone();
        let mut next_refresh = Instant::now() + period;
        let mut run = pin!(action(&first));
        loop {
            tokio::select! {
                value = &mut run => {
                    if let Err(e) = self.release(&current).await {
                        tracing::warn!(key = %key, error = %e, "could not release lock");
                    }
                    return Ok(WithLock::Completed(value));
                }
                () = time::sleep_until(next_refresh) => {
                    match self.refresh(&current, lease).await {
                        Ok(Refresh::Refreshed(l)) => {
                            current = l;
                            next_refresh = Instant::now() + period;
                        }
                        Ok(Refresh::Lost) => break,
                        Err(e) if current.expired_locally() => {
                            tracing::warn!(key = %key, error = %e, "lock lost: refresh kept failing until the lease deadline");
                            break;
                        }
                        Err(e) => {
                            tracing::warn!(key = %key, error = %e, "refreshing lock failed; will retry until its deadline");
                            next_refresh = Instant::now() + retry;
                        }
                    }
                }
            }
        }
        first.lost.cancel();
        Ok(WithLock::Lost(run.now_or_never()))
    }
}
