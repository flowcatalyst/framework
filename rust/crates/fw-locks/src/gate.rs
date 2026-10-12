//! The lock gate for background loops (background contract section 1, "Gate"): each run takes the
//! lock with `with_lock` and no wait, so a held lock skips the run instead of waiting, and the lease
//! is refreshed while the task runs.

use fw_core::BoxFuture;
use fw_core::StartupError;
use fw_core::background::{GateOutcome, GatedRun, LoopGate};

use crate::key::{LockKey, LockName};
use crate::lease::{Lease, LeaseLength, MaxWait, WithLock};
use crate::lock::Lock;
use crate::locks::Locks;

/// Runs a loop's task only while holding a lock; the run's [`fw_core::background::LoopTick`] carries
/// the fencing token.
#[derive(Debug, Clone)]
pub struct LockGate {
    lock: Lock,
    key: LockKey,
    lease: LeaseLength,
}

impl LockGate {
    /// A gate on a lock instance and key.
    #[must_use]
    pub fn new(lock: Lock, key: LockKey, lease: LeaseLength) -> Self {
        Self { lock, key, lease }
    }

    /// A gate on the default instance with a service-scoped key.
    ///
    /// # Errors
    ///
    /// When no lock instance is configured.
    pub fn service(
        locks: &Locks,
        name: &LockName,
        lease: LeaseLength,
    ) -> Result<Self, StartupError> {
        let lock = locks.default_lock().ok_or_else(|| {
            StartupError::of(
                Some("locks"),
                Some("locks"),
                "a lock gate needs a lock instance, but none is configured",
            )
        })?;
        Ok(Self::new(lock.clone(), LockKey::service(name), lease))
    }
}

impl LoopGate for LockGate {
    fn admit(&self, run: GatedRun) -> BoxFuture<'_, GateOutcome> {
        Box::pin(async move {
            let result = self
                .lock
                .with_lock(
                    &self.key,
                    self.lease,
                    MaxWait::ZERO,
                    async move |lease: &Lease| run(Some(lease.fencing_token())).await,
                )
                .await;
            match result {
                Ok(WithLock::Completed(r)) => GateOutcome::Ran(r),
                Ok(WithLock::NotAcquired) => {
                    GateOutcome::Skipped(format!("lock {} is held elsewhere", self.key))
                }
                Ok(WithLock::Lost(_)) => GateOutcome::Lost,
                Err(e) => GateOutcome::Ran(Err(Box::new(e))),
            }
        })
    }
}
