//! The contract test suite every lock driver passes (locks contract section 6), the Rust form of
//! the Java `LockStoreContract` test-jar: a driver's test file implements [`LockHarness`] and
//! invokes [`suite!`](crate::lock_store_suite), which expands to one `#[tokio::test]` per case.
//!
//! ```ignore
//! fw_locks::testing::suite!(harness(), #[tokio::test(start_paused = true)]);
//! ```

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // a test suite: a failed assertion is the point

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::time::Duration;

use fw_core::{BoxFuture, ClientId};
use tokio::time;

use crate::{
    Acquire, Lease, LeaseLength, Lock, LockKey, LockName, MaxWait, Refresh, Release, WithLock,
};

/// What a driver's test gives the suite.
pub trait LockHarness: Send + Sync + 'static {
    /// The lock under test (fresh state: no test sees another's keys).
    fn lock(&self) -> Lock;

    /// Another lock instance over a separate store, to check that a lease is refused by a lock that
    /// did not issue it.
    fn foreign_lock(&self) -> Lock;

    /// Lets time pass on the store's clock (a paused tokio clock for `memory`, real time otherwise).
    fn pass(&self, d: Duration) -> BoxFuture<'_, ()> {
        Box::pin(time::sleep(d))
    }
}

fn lease(ms: u64) -> LeaseLength {
    LeaseLength::new(Duration::from_millis(ms)).unwrap()
}

fn key(name: &str) -> LockKey {
    LockKey::service(&LockName::parse(name).unwrap())
}

async fn acquired(lock: &Lock, k: &LockKey, ms: u64) -> Lease {
    match lock.try_acquire(k, lease(ms)).await.unwrap() {
        Acquire::Acquired(l) => l,
        Acquire::Busy => panic!("expected to acquire {k}"),
    }
}

async fn busy(lock: &Lock, k: &LockKey) -> bool {
    matches!(
        lock.try_acquire(k, lease(1000)).await.unwrap(),
        Acquire::Busy
    )
}

/// The cases, one function each.
pub mod cases {
    use super::*;

    /// One of 50 concurrent `try_acquire`s on a free key wins; the rest are `Busy`.
    pub async fn fifty_racers_one_wins(h: &impl LockHarness) {
        let lock = h.lock();
        let k = key("race");
        let wins = Arc::new(AtomicUsize::new(0));
        let tasks: Vec<_> = (0..50)
            .map(|_| {
                let (lock, k, wins) = (lock.clone(), k.clone(), Arc::clone(&wins));
                tokio::spawn(async move {
                    if let Acquire::Acquired(_) = lock.try_acquire(&k, lease(10_000)).await.unwrap()
                    {
                        wins.fetch_add(1, Ordering::SeqCst);
                    }
                })
            })
            .collect();
        for t in tasks {
            t.await.unwrap();
        }
        assert_eq!(wins.load(Ordering::SeqCst), 1);
    }

    /// 20 acquire/release cycles give strictly increasing fencing tokens.
    pub async fn tokens_increase_over_release_cycles(h: &impl LockHarness) {
        let lock = h.lock();
        let k = key("cycles");
        let mut last = i64::MIN;
        for _ in 0..20 {
            let l = acquired(&lock, &k, 5000).await;
            assert!(l.fencing_token() > last);
            last = l.fencing_token();
            assert_eq!(lock.release(&l).await.unwrap(), Release::Released);
        }
    }

    /// Cycles that end by expiry instead of release give strictly increasing tokens too.
    pub async fn tokens_increase_over_expiry_cycles(h: &impl LockHarness) {
        let lock = h.lock();
        let k = key("expiry-cycles");
        let mut last = i64::MIN;
        for _ in 0..20 {
            let l = acquired(&lock, &k, 5000).await;
            assert!(l.fencing_token() > last);
            last = l.fencing_token();
            lock.expire_now(&k).await.unwrap();
        }
    }

    /// A refresh extends the lease past its first length.
    pub async fn refresh_extends_the_lease(h: &impl LockHarness) {
        let lock = h.lock();
        let k = key("refresh");
        let l = acquired(&lock, &k, 1000).await;
        h.pass(Duration::from_millis(600)).await;
        let Refresh::Refreshed(l2) = lock.refresh(&l, lease(1000)).await.unwrap() else {
            panic!("refresh lost")
        };
        assert_eq!(l2.fencing_token(), l.fencing_token());
        h.pass(Duration::from_millis(600)).await;
        assert!(
            busy(&lock, &k).await,
            "still held 1.2 s after acquiring with a 1 s lease"
        );
        assert_eq!(lock.release(&l2).await.unwrap(), Release::Released);
    }

    /// Refresh and release after expiry are `Lost` and `NotHeld`, even when nobody took the key.
    pub async fn refresh_and_release_after_expiry_fail_even_if_untaken(h: &impl LockHarness) {
        let lock = h.lock();
        let k = key("expired");
        let l = acquired(&lock, &k, 5000).await;
        lock.expire_now(&k).await.unwrap();
        assert!(matches!(
            lock.refresh(&l, lease(5000)).await.unwrap(),
            Refresh::Lost
        ));
        assert_eq!(lock.release(&l).await.unwrap(), Release::NotHeld);
    }

    /// A stale lease cannot release a newer holder's lock.
    pub async fn a_stale_lease_cannot_release_a_newer_holder(h: &impl LockHarness) {
        let lock = h.lock();
        let k = key("stale");
        let old = acquired(&lock, &k, 5000).await;
        lock.expire_now(&k).await.unwrap();
        let new = acquired(&lock, &k, 5000).await;
        assert!(new.fencing_token() > old.fencing_token());
        assert_eq!(lock.release(&old).await.unwrap(), Release::NotHeld);
        assert!(busy(&lock, &k).await);
        assert_eq!(lock.release(&new).await.unwrap(), Release::Released);
    }

    /// Holding a key and acquiring it again is `Busy`.
    pub async fn not_reentrant(h: &impl LockHarness) {
        let lock = h.lock();
        let k = key("reentrant");
        let _l = acquired(&lock, &k, 5000).await;
        assert!(busy(&lock, &k).await);
    }

    /// Release is immediate: the next `try_acquire` succeeds.
    pub async fn release_is_immediate(h: &impl LockHarness) {
        let lock = h.lock();
        let k = key("release");
        let l = acquired(&lock, &k, 5000).await;
        assert_eq!(lock.release(&l).await.unwrap(), Release::Released);
        let _again = acquired(&lock, &k, 5000).await;
    }

    /// `acquire` with a wait succeeds once the holder releases.
    pub async fn acquire_waits_for_a_release(h: &impl LockHarness) {
        let lock = h.lock();
        let k = key("wait");
        let held = acquired(&lock, &k, 5000).await;
        let releaser = {
            let lock = lock.clone();
            tokio::spawn(async move {
                time::sleep(Duration::from_millis(150)).await;
                lock.release(&held).await.unwrap();
            })
        };
        let got = lock
            .acquire(
                &k,
                lease(5000),
                MaxWait::new(Duration::from_secs(3)).unwrap(),
            )
            .await
            .unwrap();
        assert!(matches!(got, Acquire::Acquired(_)));
        releaser.await.unwrap();
    }

    /// `acquire` returns `Busy` when the wait runs out.
    pub async fn acquire_gives_up_when_the_wait_runs_out(h: &impl LockHarness) {
        let lock = h.lock();
        let k = key("give-up");
        let _held = acquired(&lock, &k, 10_000).await;
        let got = lock
            .acquire(
                &k,
                lease(5000),
                MaxWait::new(Duration::from_millis(200)).unwrap(),
            )
            .await
            .unwrap();
        assert!(matches!(got, Acquire::Busy));
    }

    /// Service and client keys with one name do not collide; nor do two clients.
    pub async fn scopes_do_not_collide(h: &impl LockHarness) {
        let lock = h.lock();
        let name = LockName::of("invoice-run");
        let (a, b) = (
            ClientId::new("acme").unwrap(),
            ClientId::new("beta").unwrap(),
        );
        let _s = acquired(&lock, &LockKey::service(&name), 5000).await;
        let _a = acquired(&lock, &LockKey::client(&a, &name), 5000).await;
        let _b = acquired(&lock, &LockKey::client(&b, &name), 5000).await;
    }

    /// `with_lock` keeps the lock beyond its lease length by refreshing it, then releases it.
    pub async fn with_lock_refreshes_beyond_the_lease(h: &impl LockHarness) {
        let lock = h.lock();
        let k = key("long-action");
        let result = lock
            .with_lock(&k, lease(600), MaxWait::ZERO, async |l: &Lease| {
                time::sleep(Duration::from_millis(1500)).await;
                let other = lock.try_acquire(&k, lease(1000)).await.unwrap();
                (l.fencing_token(), matches!(other, Acquire::Busy))
            })
            .await
            .unwrap();
        let WithLock::Completed((_, held_throughout)) = result else {
            panic!("expected Completed, got {result:?}")
        };
        assert!(held_throughout);
        let _after = acquired(&lock, &k, 1000).await;
    }

    /// `with_lock` reports `Lost` and stops the action when the lease is taken from it.
    pub async fn with_lock_reports_lost_and_stops_the_action(h: &impl LockHarness) {
        let lock = h.lock();
        let k = key("taken");
        let finished = Arc::new(AtomicBool::new(false));
        let thief = {
            let (lock, k) = (lock.clone(), k.clone());
            tokio::spawn(async move {
                time::sleep(Duration::from_millis(100)).await;
                lock.expire_now(&k).await.unwrap();
                acquired(&lock, &k, 10_000).await
            })
        };
        let f = Arc::clone(&finished);
        let result = lock
            .with_lock(&k, lease(600), MaxWait::ZERO, async move |_l: &Lease| {
                time::sleep(Duration::from_secs(30)).await;
                f.store(true, Ordering::SeqCst);
            })
            .await
            .unwrap();
        assert_eq!(result, WithLock::Lost(None));
        assert!(
            !finished.load(Ordering::SeqCst),
            "the action's future was dropped"
        );
        let thief_lease = thief.await.unwrap();
        assert!(
            busy(&lock, &k).await,
            "a loss does not release the new holder's lock"
        );
        lock.release(&thief_lease).await.unwrap();
    }

    /// A cooperative action that watches `lease.lost()` returns its value after a loss.
    pub async fn with_lock_lets_a_cooperative_action_return(h: &impl LockHarness) {
        let lock = h.lock();
        let k = key("cooperative");
        let thief = {
            let (lock, k) = (lock.clone(), k.clone());
            tokio::spawn(async move {
                time::sleep(Duration::from_millis(100)).await;
                lock.expire_now(&k).await.unwrap();
                acquired(&lock, &k, 10_000).await
            })
        };
        let result = lock
            .with_lock(&k, lease(600), MaxWait::ZERO, async |l: &Lease| {
                l.lost().cancelled().await;
                "stopped at a safe point"
            })
            .await
            .unwrap();
        assert_eq!(result, WithLock::Lost(Some("stopped at a safe point")));
        lock.release(&thief.await.unwrap()).await.unwrap();
    }

    /// An action's error is its value; the lock is released after it.
    pub async fn with_lock_releases_after_an_error(h: &impl LockHarness) {
        let lock = h.lock();
        let k = key("failing");
        let result = lock
            .with_lock(&k, lease(5000), MaxWait::ZERO, async |_l: &Lease| {
                Err::<(), _>("boom")
            })
            .await
            .unwrap();
        assert_eq!(result, WithLock::Completed(Err("boom")));
        let _again = acquired(&lock, &k, 1000).await;
    }

    /// `with_lock` on a held key does not run the action.
    pub async fn with_lock_not_acquired(h: &impl LockHarness) {
        let lock = h.lock();
        let k = key("held");
        let _held = acquired(&lock, &k, 10_000).await;
        let ran = AtomicBool::new(false);
        let result = lock
            .with_lock(&k, lease(1000), MaxWait::ZERO, async |_l: &Lease| {
                ran.store(true, Ordering::SeqCst)
            })
            .await
            .unwrap();
        assert_eq!(result, WithLock::NotAcquired);
        assert!(!ran.load(Ordering::SeqCst));
    }

    /// A lease from another lock instance is refused.
    pub async fn a_foreign_lease_is_refused(h: &impl LockHarness) {
        let lock = h.lock();
        let l = acquired(&lock, &key("foreign"), 5000).await;
        let other = h.foreign_lock();
        assert!(other.release(&l).await.is_err());
        assert!(other.refresh(&l, lease(1000)).await.is_err());
    }

    /// The argument limits: lease 100 ms to 24 h, wait zero to 24 h.
    pub async fn argument_limits(_h: &impl LockHarness) {
        assert!(LeaseLength::new(Duration::from_millis(99)).is_err());
        assert!(LeaseLength::new(Duration::from_millis(100)).is_ok());
        assert!(LeaseLength::new(Duration::from_secs(24 * 3600)).is_ok());
        assert!(LeaseLength::new(Duration::from_secs(24 * 3600 + 1)).is_err());
        assert!(MaxWait::new(Duration::ZERO).is_ok());
        assert!(MaxWait::new(Duration::from_secs(24 * 3600 + 1)).is_err());
    }
}

/// Expands to one `#[tokio::test]` (or the attribute given) per contract case, each calling the
/// harness expression afresh.
#[macro_export]
macro_rules! lock_store_suite {
    ($harness:expr, #[$attr:meta]) => {
        $crate::lock_store_suite!(@cases $harness, #[$attr],
            fifty_racers_one_wins,
            tokens_increase_over_release_cycles,
            tokens_increase_over_expiry_cycles,
            refresh_extends_the_lease,
            refresh_and_release_after_expiry_fail_even_if_untaken,
            a_stale_lease_cannot_release_a_newer_holder,
            not_reentrant,
            release_is_immediate,
            acquire_waits_for_a_release,
            acquire_gives_up_when_the_wait_runs_out,
            scopes_do_not_collide,
            with_lock_refreshes_beyond_the_lease,
            with_lock_reports_lost_and_stops_the_action,
            with_lock_lets_a_cooperative_action_return,
            with_lock_releases_after_an_error,
            with_lock_not_acquired,
            a_foreign_lease_is_refused,
            argument_limits
        );
    };
    (@cases $harness:expr, #[$attr:meta], $($case:ident),*) => {
        $(
            #[$attr]
            async fn $case() {
                $crate::testing::cases::$case(&$harness.await).await;
            }
        )*
    };
}

pub use crate::lock_store_suite as suite;
