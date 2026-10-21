//! The `memory` driver: values in this process, lost when it stops. For tests and throwaway use.

use std::collections::HashMap;
use std::sync::{Mutex, PoisonError};

use fw_core::BoxFuture;

use crate::key::check_key;
use crate::store::{ObjectStore, ObjectStoreError, check_put};

/// Values in a map. Stored and returned buffers are copies.
pub struct MemoryObjectStore {
    name: String,
    max_bytes: usize,
    values: Mutex<HashMap<String, Vec<u8>>>,
}

impl MemoryObjectStore {
    /// An empty store with a size limit.
    #[must_use]
    pub fn new(name: impl Into<String>, max_bytes: usize) -> Self {
        Self {
            name: name.into(),
            max_bytes,
            values: Mutex::default(),
        }
    }

    fn with<T>(&self, f: impl FnOnce(&mut HashMap<String, Vec<u8>>) -> T) -> T {
        f(&mut self.values.lock().unwrap_or_else(PoisonError::into_inner))
    }
}

impl ObjectStore for MemoryObjectStore {
    fn name(&self) -> &str {
        &self.name
    }

    fn put<'a>(
        &'a self,
        key: &'a str,
        content: &'a [u8],
    ) -> BoxFuture<'a, Result<(), ObjectStoreError>> {
        let r = check_put(key, content, self.max_bytes).map(|()| {
            self.with(|m| m.insert(key.to_owned(), content.to_vec()));
        });
        Box::pin(async move { r })
    }

    fn get<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Result<Option<Vec<u8>>, ObjectStoreError>> {
        let r = check_key(key).map(|_| self.with(|m| m.get(key).cloned()));
        Box::pin(async move { r })
    }

    fn delete<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Result<(), ObjectStoreError>> {
        let r = check_key(key).map(|_| {
            self.with(|m| m.remove(key));
        });
        Box::pin(async move { r })
    }
}
