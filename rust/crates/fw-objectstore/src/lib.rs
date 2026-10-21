//! Object stores (object-stores contract): named byte values kept by key, with three operations
//! (`put`, `get`, `delete`) and three drivers: `memory`, `folder` (allowed only when `APP_ENV` is
//! `local` or `test`) and `s3` (feature `s3`, on the AWS SDK for Rust). Stores are named in code,
//! like lock stores; there is no configuration.
//!
//! ```
//! use fw_objectstore::{MemoryStore, ObjectStoreFactory};
//! # async fn demo() -> Result<(), Box<dyn std::error::Error>> {
//! let store = MemoryStore::new().create("archive", "local").await?;
//! store.put("sets/2026-10-21/apply-1.json", b"{}").await?;
//! assert_eq!(store.get("sets/2026-10-21/apply-1.json").await?, Some(b"{}".to_vec()));
//! store.delete("sets/2026-10-21/apply-1.json").await?;
//! # Ok(()) }
//! ```

mod factories;
mod folder;
mod key;
mod memory;
#[cfg(feature = "s3")]
mod s3;
mod store;
mod stores;
#[cfg(feature = "testing")]
pub mod testing;

#[cfg(feature = "s3")]
pub use factories::S3Store;
pub use factories::{DEFAULT_MAX_BYTES, FolderStore, MemoryStore, ObjectStoreFactory};
pub use folder::FolderObjectStore;
pub use key::{MAX_KEY_LENGTH, check_key};
pub use memory::MemoryObjectStore;
#[cfg(feature = "s3")]
pub use s3::S3ObjectStore;
pub use store::{ObjectStore, ObjectStoreError};
pub use stores::{ObjectStores, ObjectStoresBuilder};
