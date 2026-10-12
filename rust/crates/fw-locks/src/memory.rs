//! The `memory` driver: locks within one process only (for one instance, local development and
//! tests). Expiry uses tokio's monotonic clock, so tests pause and advance it.

use std::collections::HashMap;
use std::sync::{Mutex, PoisonError};
use std::time::Duration;

use fw_core::BoxFuture;
use tokio::time::Instant;

use crate::store::{LockStore, LockStoreError};

struct Held {
    owner: String,
    fence: i64,
    expires: Instant,
}

#[derive(Default)]
struct State {
    held: HashMap<String, Held>,
    counter: i64,
    acquisitions: u64,
}

/// Locks in this process. Fencing tokens come from one counter per instance.
#[derive(Default)]
pub struct MemoryLockStore {
    state: Mutex<State>,
}

const SWEEP_EVERY: u64 = 4096;

fn lease(ms: i64) -> Duration {
    Duration::from_millis(u64::try_from(ms).unwrap_or(0))
}

impl MemoryLockStore {
    /// An empty store.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    fn with<T>(&self, f: impl FnOnce(&mut State, Instant) -> T) -> T {
        let mut state = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        f(&mut state, Instant::now())
    }
}

fn matches(held: Option<&Held>, owner: &str, fence: i64, now: Instant) -> bool {
    held.is_some_and(|h| h.owner == owner && h.fence == fence && h.expires > now)
}

impl LockStore for MemoryLockStore {
    fn try_acquire<'a>(
        &'a self,
        key: &'a str,
        owner: &'a str,
        lease_ms: i64,
    ) -> BoxFuture<'a, Result<Option<i64>, LockStoreError>> {
        let r = self.with(|s, now| {
            if s.held.get(key).is_some_and(|h| h.expires > now) {
                return None;
            }
            s.acquisitions += 1;
            if s.acquisitions % SWEEP_EVERY == 0 {
                s.held.retain(|_, h| h.expires > now);
            }
            s.counter += 1;
            let fence = s.counter;
            s.held.insert(
                key.to_owned(),
                Held {
                    owner: owner.to_owned(),
                    fence,
                    expires: now + lease(lease_ms),
                },
            );
            Some(fence)
        });
        Box::pin(async move { Ok(r) })
    }

    fn refresh<'a>(
        &'a self,
        key: &'a str,
        owner: &'a str,
        fence: i64,
        lease_ms: i64,
    ) -> BoxFuture<'a, Result<bool, LockStoreError>> {
        let r = self.with(|s, now| {
            if !matches(s.held.get(key), owner, fence, now) {
                return false;
            }
            s.held.insert(
                key.to_owned(),
                Held {
                    owner: owner.to_owned(),
                    fence,
                    expires: now + lease(lease_ms),
                },
            );
            true
        });
        Box::pin(async move { Ok(r) })
    }

    fn release<'a>(
        &'a self,
        key: &'a str,
        owner: &'a str,
        fence: i64,
    ) -> BoxFuture<'a, Result<bool, LockStoreError>> {
        let r = self.with(|s, now| {
            if !matches(s.held.get(key), owner, fence, now) {
                return false;
            }
            s.held.insert(
                key.to_owned(),
                Held {
                    owner: owner.to_owned(),
                    fence,
                    expires: now,
                },
            );
            true
        });
        Box::pin(async move { Ok(r) })
    }

    fn expire_now<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Result<(), LockStoreError>> {
        self.with(|s, now| {
            if let Some(h) = s.held.get_mut(key) {
                h.expires = now;
            }
        });
        Box::pin(async { Ok(()) })
    }
}
