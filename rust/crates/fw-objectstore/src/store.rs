//! The driver contract.

use std::error::Error;

use fw_core::BoxFuture;

use crate::key::check_key;

/// What went wrong with an operation.
#[derive(Debug, thiserror::Error)]
#[non_exhaustive]
pub enum ObjectStoreError {
    /// A key that is not allowed, or content over the store's size limit. Nothing was stored.
    #[error("{0}")]
    Argument(String),
    /// The store could not be read or written, or holds a value over its size limit. The message
    /// names the store and key. The framework does not retry.
    #[error("{message}")]
    Failed {
        /// What failed.
        message: String,
        /// The cause, when there is one.
        #[source]
        source: Option<Box<dyn Error + Send + Sync>>,
    },
}

impl ObjectStoreError {
    /// A failure with no underlying error.
    pub fn failed(message: impl Into<String>) -> Self {
        Self::Failed {
            message: message.into(),
            source: None,
        }
    }

    /// A failure with its cause.
    pub fn failed_by(
        message: impl Into<String>,
        source: impl Error + Send + Sync + 'static,
    ) -> Self {
        Self::Failed {
            message: message.into(),
            source: Some(Box::new(source)),
        }
    }
}

/// A place to keep named byte values. Deliberately small: a key names one value; there is no
/// listing, no metadata and no partial read, so every driver can honour it the same way.
///
/// Keys follow [`check_key`]. A key must not be both a value and the start of another key (`a` and
/// `a/b`): a folder cannot hold both, so do not rely on it with any driver.
///
/// Implementations are safe for concurrent use. A `put` replaces any earlier value completely and
/// a reader sees the old value or the new one, never a mixture.
pub trait ObjectStore: Send + Sync {
    /// The store's name in [`crate::ObjectStores`].
    fn name(&self) -> &str;

    /// Stores a value, replacing any value under the key. Empty content is a value.
    ///
    /// # Errors
    ///
    /// [`ObjectStoreError::Argument`] for a key that is not allowed or content over the size
    /// limit; [`ObjectStoreError::Failed`] when the store cannot be written.
    fn put<'a>(
        &'a self,
        key: &'a str,
        content: &'a [u8],
    ) -> BoxFuture<'a, Result<(), ObjectStoreError>>;

    /// Reads a value: `None` when there is no value under the key.
    ///
    /// # Errors
    ///
    /// [`ObjectStoreError::Argument`] for a key that is not allowed; [`ObjectStoreError::Failed`]
    /// when the store cannot be read or the value is over the size limit.
    fn get<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Result<Option<Vec<u8>>, ObjectStoreError>>;

    /// Removes a value. Removing a value that is not there is not an error.
    ///
    /// # Errors
    ///
    /// [`ObjectStoreError::Argument`] for a key that is not allowed; [`ObjectStoreError::Failed`]
    /// when the store cannot be written.
    fn delete<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Result<(), ObjectStoreError>>;

    /// Releases what the store holds (a client, connections). Called once by
    /// [`crate::ObjectStores::close`] at shutdown; the default does nothing.
    fn close(&self) -> BoxFuture<'_, ()> {
        Box::pin(async {})
    }
}

/// Refuses content over the limit.
pub(crate) fn check_size(length: usize, max_bytes: usize) -> Result<(), ObjectStoreError> {
    if length > max_bytes {
        return Err(ObjectStoreError::Argument(format!(
            "content is {length} bytes, over the store's limit of {max_bytes}"
        )));
    }
    Ok(())
}

/// A key and a content that may be put: the checks every driver makes first.
pub(crate) fn check_put(
    key: &str,
    content: &[u8],
    max_bytes: usize,
) -> Result<(), ObjectStoreError> {
    check_key(key)?;
    check_size(content.len(), max_bytes)
}
