//! The memory driver against the contract suite.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::sync::Arc;

use fw_objectstore::testing::ObjectStoreHarness;
use fw_objectstore::{MemoryObjectStore, ObjectStore};

struct Memory(Arc<MemoryObjectStore>);

impl ObjectStoreHarness for Memory {
    fn store(&self) -> Arc<dyn ObjectStore> {
        self.0.clone()
    }
    fn limit(&self) -> usize {
        4096
    }
}

async fn harness() -> Memory {
    Memory(Arc::new(MemoryObjectStore::new("t", 4096)))
}

fw_objectstore::testing::suite!(harness(), #[tokio::test(flavor = "multi_thread")]);
