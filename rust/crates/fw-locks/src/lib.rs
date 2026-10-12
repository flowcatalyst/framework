//! Locks (locks contract): leases with fencing tokens, `with_lock`, and three drivers: `memory`
//! (always), `database` (feature `postgres`, table `fw_lock`) and `redis` (feature `redis`). The
//! statements and scripts are the ones the Java framework sends, byte for byte, so Java and Rust
//! replicas can share one lock table or one Redis server.
//!
//! ```
//! # use std::time::Duration;
//! # use std::sync::Arc;
//! use fw_locks::{LeaseLength, LockKey, LockName, Locks, MaxWait, MemoryLockStore, WithLock};
//! # async fn demo() -> Result<(), Box<dyn std::error::Error>> {
//! const EXPORT: LockName = LockName::of("nightly-export");
//! let locks = Locks::single("local", Arc::new(MemoryLockStore::new()));
//! let lock = locks.default_lock().unwrap();
//! let lease = LeaseLength::new(Duration::from_secs(10))?;
//! match lock.with_lock(&LockKey::service(&EXPORT), lease, MaxWait::ZERO, async |l| l.fencing_token()).await? {
//!     WithLock::Completed(token) => println!("ran with token {token}"),
//!     WithLock::NotAcquired => println!("another replica is exporting"),
//!     WithLock::Lost(_) => println!("ran without exclusion"),
//! }
//! # Ok(()) }
//! ```

mod gate;
mod key;
mod lease;
mod lock;
mod locks;
mod memory;
#[cfg(feature = "postgres")]
pub mod postgres;
#[cfg(feature = "redis")]
pub mod redis_store;
mod store;
#[cfg(feature = "testing")]
pub mod testing;

pub use gate::LockGate;
pub use key::{LockKey, LockName};
pub use lease::{Acquire, Lease, LeaseLength, MaxWait, Refresh, Release, WithLock};
pub use lock::Lock;
#[cfg(feature = "postgres")]
pub use locks::migration_set;
pub use locks::{Locks, LocksBuilder, LocksDeclared, LocksModule};
pub use memory::MemoryLockStore;
pub use store::{LockStore, LockStoreError};

/// A lock argument outside its range (a lease length, a wait, a name).
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum LockError {
    /// The argument and the rule it broke.
    #[error("{0}")]
    Argument(String),
}
