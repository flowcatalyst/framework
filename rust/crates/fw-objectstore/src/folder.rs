//! The `folder` driver: a file per value under one folder, a key's `/` being folders. A put writes
//! a hidden temporary file beside the target and renames it into place, so a reader sees the old
//! value or the new one. Keys cannot start a segment with a dot (see [`crate::check_key`]), so
//! temporary files never collide with a key and a key cannot leave the folder.

use std::error::Error;
use std::io::{self, ErrorKind};
use std::path::{Path, PathBuf};

use fw_core::BoxFuture;
use tokio::fs;

use crate::key::check_key;
use crate::store::{ObjectStore, ObjectStoreError, check_put};

/// Values as files. Build it with [`crate::FolderStore`], which applies the environment guard.
pub struct FolderObjectStore {
    name: String,
    /// The folder's real path (symbolic links resolved).
    root: PathBuf,
    max_bytes: usize,
}

impl FolderObjectStore {
    /// A store over `root`, which must be a real path (the result of `canonicalize`).
    #[must_use]
    pub fn new(name: impl Into<String>, root: PathBuf, max_bytes: usize) -> Self {
        Self {
            name: name.into(),
            root,
            max_bytes,
        }
    }

    /// Every key is checked, so the path is under the root and always has a parent folder.
    fn target(&self, key: &str) -> Result<(PathBuf, PathBuf), ObjectStoreError> {
        check_key(key)?;
        let target = self.root.join(key);
        let dir = target.parent().unwrap_or(&self.root).to_path_buf();
        Ok((target, dir))
    }

    fn failed(
        &self,
        what: &str,
        key: &str,
        e: impl Error + Send + Sync + 'static,
    ) -> ObjectStoreError {
        ObjectStoreError::failed_by(
            format!("object store '{}': cannot {what} '{key}'", self.name),
            e,
        )
    }

    /// A folder reached through a symbolic link could lead outside the store, so it is refused.
    async fn refuse_links(&self, dir: &Path) -> Result<(), ObjectStoreError> {
        let real = fs::canonicalize(dir).await.map_err(|e| {
            ObjectStoreError::failed_by(
                format!(
                    "object store '{}': cannot resolve '{}'",
                    self.name,
                    dir.display()
                ),
                e,
            )
        })?;
        if real.starts_with(&self.root) {
            Ok(())
        } else {
            Err(ObjectStoreError::failed(format!(
                "object store '{}': '{}' leads outside the store folder",
                self.name,
                dir.display()
            )))
        }
    }

    async fn write(&self, key: &str, content: &[u8]) -> Result<(), ObjectStoreError> {
        let (target, dir) = self.target(key)?;
        fs::create_dir_all(&dir)
            .await
            .map_err(|e| self.failed("write", key, e))?;
        self.refuse_links(&dir).await?;
        let tmp = dir.join(format!(".tmp-{:032x}", rand::random::<u128>()));
        let written = async {
            fs::write(&tmp, content).await?;
            fs::rename(&tmp, &target).await
        }
        .await;
        if let Err(e) = written {
            // the temporary file is hidden and harmless if this fails too
            if let Err(cleanup) = fs::remove_file(&tmp).await
                && cleanup.kind() != ErrorKind::NotFound
            {
                tracing::debug!(error = %cleanup, "cannot remove a temporary file");
            }
            return Err(self.failed("write", key, e));
        }
        Ok(())
    }

    /// Whether there is a regular file at the key (a symbolic link at the key itself is not one).
    async fn is_value(&self, target: &Path) -> Result<bool, io::Error> {
        match fs::symlink_metadata(target).await {
            Ok(meta) => Ok(meta.is_file()),
            // absent, or a key that is a value used as a folder
            Err(e) if matches!(e.kind(), ErrorKind::NotFound | ErrorKind::NotADirectory) => {
                Ok(false)
            }
            Err(e) => Err(e),
        }
    }

    async fn read(&self, key: &str) -> Result<Option<Vec<u8>>, ObjectStoreError> {
        let (target, dir) = self.target(key)?;
        if !self
            .is_value(&target)
            .await
            .map_err(|e| self.failed("read", key, e))?
        {
            return Ok(None);
        }
        self.refuse_links(&dir).await?;
        let size = match fs::metadata(&target).await {
            Ok(meta) => meta.len(),
            Err(e) if e.kind() == ErrorKind::NotFound => return Ok(None),
            Err(e) => return Err(self.failed("read", key, e)),
        };
        if size > self.max_bytes as u64 {
            return Err(ObjectStoreError::failed(format!(
                "object store '{}': '{key}' is {size} bytes, over the limit of {}",
                self.name, self.max_bytes
            )));
        }
        match fs::read(&target).await {
            Ok(bytes) => Ok(Some(bytes)),
            Err(e) if e.kind() == ErrorKind::NotFound => Ok(None),
            Err(e) => Err(self.failed("read", key, e)),
        }
    }

    async fn remove(&self, key: &str) -> Result<(), ObjectStoreError> {
        let (target, dir) = self.target(key)?;
        if self
            .is_value(&target)
            .await
            .map_err(|e| self.failed("delete", key, e))?
        {
            self.refuse_links(&dir).await?;
            match fs::remove_file(&target).await {
                Ok(()) => {}
                Err(e) if e.kind() == ErrorKind::NotFound => {}
                Err(e) => return Err(self.failed("delete", key, e)),
            }
        }
        Ok(())
    }
}

impl ObjectStore for FolderObjectStore {
    fn name(&self) -> &str {
        &self.name
    }

    fn put<'a>(
        &'a self,
        key: &'a str,
        content: &'a [u8],
    ) -> BoxFuture<'a, Result<(), ObjectStoreError>> {
        Box::pin(async move {
            check_put(key, content, self.max_bytes)?;
            self.write(key, content).await
        })
    }

    fn get<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Result<Option<Vec<u8>>, ObjectStoreError>> {
        Box::pin(self.read(key))
    }

    fn delete<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Result<(), ObjectStoreError>> {
        Box::pin(self.remove(key))
    }
}
