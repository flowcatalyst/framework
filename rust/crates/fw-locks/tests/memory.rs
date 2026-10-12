//! The memory driver against the contract suite, on a paused clock.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::sync::Arc;

use fw_locks::testing::LockHarness;
use fw_locks::{Lock, MemoryLockStore};

struct Memory;

impl LockHarness for Memory {
    fn lock(&self) -> Lock {
        Lock::new(Arc::new(MemoryLockStore::new()))
    }
    fn foreign_lock(&self) -> Lock {
        Lock::new(Arc::new(MemoryLockStore::new()))
    }
}

async fn harness() -> Memory {
    Memory
}

fw_locks::testing::suite!(harness(), #[tokio::test(start_paused = true)]);
