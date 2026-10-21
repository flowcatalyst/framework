//! The drivers a store can be built with, chosen in code. Each is a plain value with `with_...`
//! methods that return copies; its values are checked when the store is built at start-up, and a
//! problem fails start-up naming the store.

use std::io;
use std::path::PathBuf;
use std::sync::Arc;

use fw_core::BoxFuture;
use tokio::fs;

use crate::folder::FolderObjectStore;
use crate::memory::MemoryObjectStore;
#[cfg(feature = "s3")]
use crate::s3::S3ObjectStore;
use crate::store::ObjectStore;

/// The largest value a store accepts unless told otherwise: 64 MiB.
pub const DEFAULT_MAX_BYTES: usize = 64 * 1024 * 1024;

/// Builds one named object store at start-up, with the driver it names (config-and-code contract,
/// section 3.4). [`MemoryStore`], [`FolderStore`] and, with the feature `s3`, [`S3Store`] are the
/// framework's; an application may write its own.
pub trait ObjectStoreFactory: Send + Sync {
    /// Builds the store, given its name in [`crate::ObjectStores`] and `APP_ENV`. The error is the
    /// problem, which fails start-up naming the store.
    fn create<'a>(
        &'a self,
        name: &'a str,
        environment: &'a str,
    ) -> BoxFuture<'a, Result<Arc<dyn ObjectStore>, String>>;
}

fn check_limit(max_bytes: usize) -> Result<usize, String> {
    if max_bytes < 1 {
        return Err(format!("max_bytes must be at least 1, was {max_bytes}"));
    }
    Ok(max_bytes)
}

/// The `memory` driver: values live in this process only.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MemoryStore {
    max_bytes: usize,
}

impl MemoryStore {
    /// A memory store with a 64 MiB limit.
    #[must_use]
    pub fn new() -> Self {
        Self {
            max_bytes: DEFAULT_MAX_BYTES,
        }
    }

    /// A copy with another size limit (at least 1).
    #[must_use]
    pub fn with_max_bytes(self, max_bytes: usize) -> Self {
        Self { max_bytes }
    }
}

impl Default for MemoryStore {
    fn default() -> Self {
        Self::new()
    }
}

impl ObjectStoreFactory for MemoryStore {
    fn create<'a>(
        &'a self,
        name: &'a str,
        _environment: &'a str,
    ) -> BoxFuture<'a, Result<Arc<dyn ObjectStore>, String>> {
        Box::pin(async move {
            let max_bytes = check_limit(self.max_bytes)?;
            Ok(Arc::new(MemoryObjectStore::new(name, max_bytes)) as Arc<dyn ObjectStore>)
        })
    }
}

/// The `folder` driver: a value is a file under the directory. Allowed only when `APP_ENV` is
/// `local` or `test`; elsewhere start-up fails, because a folder on one machine is not shared
/// storage.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FolderStore {
    directory: PathBuf,
    max_bytes: usize,
}

impl FolderStore {
    /// A folder store with a 64 MiB limit. `directory` must be absolute; it is created if missing.
    #[must_use]
    pub fn new(directory: impl Into<PathBuf>) -> Self {
        Self {
            directory: directory.into(),
            max_bytes: DEFAULT_MAX_BYTES,
        }
    }

    /// A copy with another size limit (at least 1).
    #[must_use]
    pub fn with_max_bytes(self, max_bytes: usize) -> Self {
        Self { max_bytes, ..self }
    }
}

impl ObjectStoreFactory for FolderStore {
    fn create<'a>(
        &'a self,
        name: &'a str,
        environment: &'a str,
    ) -> BoxFuture<'a, Result<Arc<dyn ObjectStore>, String>> {
        Box::pin(async move {
            if environment != "local" && environment != "test" {
                return Err(format!(
                    "the folder object store is allowed only when APP_ENV is local or test (it is '{environment}'): a folder on one machine is not shared storage; use the s3 driver when deployed"
                ));
            }
            if !self.directory.is_absolute() {
                return Err(format!(
                    "directory must be an absolute path, was {}",
                    self.directory.display()
                ));
            }
            let max_bytes = check_limit(self.max_bytes)?;
            let unusable = |e: io::Error| {
                format!(
                    "directory {} cannot be created or resolved: {e}",
                    self.directory.display()
                )
            };
            fs::create_dir_all(&self.directory)
                .await
                .map_err(unusable)?;
            let root = fs::canonicalize(&self.directory).await.map_err(unusable)?;
            Ok(Arc::new(FolderObjectStore::new(name, root, max_bytes)) as Arc<dyn ObjectStore>)
        })
    }
}

/// The `s3` driver (feature `s3`): one bucket on AWS S3 or an S3-compatible service.
///
/// ```no_run
/// # use fw_objectstore::S3Store;
/// let archive = S3Store::new("acme-archive").with_prefix("ingest/sets");
/// ```
#[cfg(feature = "s3")]
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct S3Store {
    pub(crate) bucket: String,
    pub(crate) prefix: Option<String>,
    pub(crate) region: Option<String>,
    pub(crate) endpoint: Option<String>,
    pub(crate) max_bytes: usize,
    pub(crate) verify_at_startup: bool,
}

#[cfg(feature = "s3")]
impl S3Store {
    /// The driver for one bucket, which must already exist: no key prefix, the region from the
    /// SDK's chain, a 64 MiB limit, and a check at start-up that the bucket can be reached.
    #[must_use]
    pub fn new(bucket: impl Into<String>) -> Self {
        Self {
            bucket: bucket.into(),
            prefix: None,
            region: None,
            endpoint: None,
            max_bytes: DEFAULT_MAX_BYTES,
            verify_at_startup: true,
        }
    }

    /// A copy that stores everything under a prefix (itself a valid key, such as `ingest/sets`),
    /// so several stores can share a bucket.
    #[must_use]
    pub fn with_prefix(self, prefix: impl Into<String>) -> Self {
        Self {
            prefix: Some(prefix.into()),
            ..self
        }
    }

    /// A copy for a region, for example `eu-west-1`.
    #[must_use]
    pub fn with_region(self, region: impl Into<String>) -> Self {
        Self {
            region: Some(region.into()),
            ..self
        }
    }

    /// A copy for an S3-compatible service, for example `http://localhost:4566`. Addressing is
    /// path-style with it.
    #[must_use]
    pub fn with_endpoint(self, endpoint: impl Into<String>) -> Self {
        Self {
            endpoint: Some(endpoint.into()),
            ..self
        }
    }

    /// A copy with another size limit (at least 1).
    #[must_use]
    pub fn with_max_bytes(self, max_bytes: usize) -> Self {
        Self { max_bytes, ..self }
    }

    /// A copy that does or does not check the bucket at start-up with a `HeadBucket` call (which
    /// needs the `s3:ListBucket` permission: turn it off if the role lacks it).
    #[must_use]
    pub fn with_verify_at_startup(self, verify: bool) -> Self {
        Self {
            verify_at_startup: verify,
            ..self
        }
    }

    fn check(&self) -> Result<(), String> {
        if !is_bucket_name(&self.bucket) {
            return Err(format!("not a valid S3 bucket name: '{}'", self.bucket));
        }
        if let Some(prefix) = &self.prefix {
            crate::check_key(prefix).map_err(|e| format!("prefix: {e}"))?;
        }
        if self.region.as_deref().is_some_and(|r| r.trim().is_empty()) {
            return Err("region must not be blank".to_owned());
        }
        if self
            .endpoint
            .as_deref()
            .is_some_and(|e| e.trim().is_empty())
        {
            return Err("endpoint must not be blank".to_owned());
        }
        check_limit(self.max_bytes).map(|_| ())
    }
}

/// `[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]`: 3 to 63 characters.
#[cfg(feature = "s3")]
fn is_bucket_name(name: &str) -> bool {
    let b = name.as_bytes();
    let edge = |c: u8| c.is_ascii_lowercase() || c.is_ascii_digit();
    (3..=63).contains(&b.len())
        && b.first().is_some_and(|c| edge(*c))
        && b.last().is_some_and(|c| edge(*c))
        && b.iter().all(|c| edge(*c) || matches!(c, b'.' | b'-'))
}

#[cfg(feature = "s3")]
impl ObjectStoreFactory for S3Store {
    fn create<'a>(
        &'a self,
        name: &'a str,
        _environment: &'a str,
    ) -> BoxFuture<'a, Result<Arc<dyn ObjectStore>, String>> {
        Box::pin(async move {
            self.check()?;
            let store = S3ObjectStore::open(name, self).await?;
            Ok(Arc::new(store) as Arc<dyn ObjectStore>)
        })
    }
}
