//! The registry of named object stores, built in code.

use std::fmt;
use std::sync::Arc;

use fw_core::{Boot, Problem, StartupError};

use crate::factories::ObjectStoreFactory;
use crate::store::ObjectStore;

const MODULE: &str = "object-stores";

/// The object stores of an application, by name (cheap to clone; clones share the stores).
#[derive(Clone)]
pub struct ObjectStores {
    default: Option<String>,
    /// In the order they were added.
    stores: Arc<Vec<(String, Arc<dyn ObjectStore>)>>,
}

impl fmt::Debug for ObjectStores {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ObjectStores")
            .field("default", &self.default)
            .field("names", &self.names().collect::<Vec<_>>())
            .finish()
    }
}

impl ObjectStores {
    /// Starts a definition with no store. There is no configuration: stores are named in code.
    #[must_use]
    pub fn builder() -> ObjectStoresBuilder {
        ObjectStoresBuilder::default()
    }

    /// One store over a driver (tests, tools).
    #[must_use]
    pub fn single(name: &str, store: Arc<dyn ObjectStore>) -> Self {
        Self {
            default: Some(name.to_owned()),
            stores: Arc::new(vec![(name.to_owned(), store)]),
        }
    }

    /// The named store.
    #[must_use]
    pub fn get(&self, name: &str) -> Option<&Arc<dyn ObjectStore>> {
        self.stores.iter().find(|(n, _)| n == name).map(|(_, s)| s)
    }

    /// The default store, when one is configured.
    #[must_use]
    pub fn default_store(&self) -> Option<&Arc<dyn ObjectStore>> {
        self.default.as_deref().and_then(|n| self.get(n))
    }

    /// The store names, in the order they were added.
    pub fn names(&self) -> impl Iterator<Item = &str> {
        self.stores.iter().map(|(n, _)| n.as_str())
    }

    /// Closes every store (each store's [`ObjectStore::close`]). Registered on the application's
    /// shutdown by [`ObjectStoresBuilder::build`].
    pub async fn close(&self) {
        for (_, store) in self.stores.iter() {
            store.close().await;
        }
    }
}

/// Builds [`ObjectStores`] (from [`ObjectStores::builder`]).
///
/// ```no_run
/// # use fw_objectstore::{FolderStore, MemoryStore, ObjectStores};
/// # async fn demo(boot: &mut fw_core::Boot) -> Result<(), fw_core::StartupError> {
/// let stores = ObjectStores::builder()
///     .store("archive", MemoryStore::new())
///     .store("scratch", FolderStore::new("/var/tmp/scratch")) // local and test only
///     .default_store("archive")
///     .build(boot)
///     .await?;
/// # Ok(()) }
/// ```
#[derive(Default)]
pub struct ObjectStoresBuilder {
    factories: Vec<(String, Box<dyn ObjectStoreFactory>)>,
    default: Option<String>,
    problems: Vec<Problem>,
}

impl fmt::Debug for ObjectStoresBuilder {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("ObjectStoresBuilder")
    }
}

fn problem(message: impl Into<String>) -> Problem {
    Problem::new(Some(MODULE), None, message)
}

fn is_name(name: &str) -> bool {
    let b = name.as_bytes();
    b.first().is_some_and(u8::is_ascii_lowercase)
        && b.iter()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'-')
}

impl ObjectStoresBuilder {
    /// Adds a named store (`[a-z][a-z0-9-]*`, unique), built at start-up with the driver given.
    /// A bad or repeated name fails [`build`](Self::build).
    #[must_use]
    pub fn store(mut self, name: &str, factory: impl ObjectStoreFactory + 'static) -> Self {
        if !is_name(name) {
            self.problems.push(problem(format!(
                "object store name must match [a-z][a-z0-9-]*, was '{name}'"
            )));
        } else if self.factories.iter().any(|(n, _)| n == name) {
            self.problems
                .push(problem(format!("object store '{name}' is already given")));
        } else {
            self.factories.push((name.to_owned(), Box::new(factory)));
        }
        self
    }

    /// Names the store [`ObjectStores::default_store`] returns. Needed with several stores; with
    /// one, that one is the default.
    #[must_use]
    pub fn default_store(mut self, name: &str) -> Self {
        self.default = Some(name.to_owned());
        self
    }

    /// Builds every store, in the order added, and closes them all at shutdown. The problems of
    /// all stores are reported together; if any store fails, the ones built before it are closed.
    ///
    /// # Errors
    ///
    /// A bad or repeated name, a default that names no store, several stores and no default, and
    /// any store that cannot be built (the problem names it).
    pub async fn build(self, boot: &mut Boot) -> Result<ObjectStores, StartupError> {
        let Self {
            factories,
            default,
            mut problems,
        } = self;
        let names = || {
            if factories.is_empty() {
                "(none)".to_owned()
            } else {
                factories
                    .iter()
                    .map(|(n, _)| n.as_str())
                    .collect::<Vec<_>>()
                    .join(", ")
            }
        };
        let default = match default {
            Some(d) if !factories.iter().any(|(n, _)| *n == d) => {
                problems.push(problem(format!(
                    "default store '{d}' is not a store; stores: {}",
                    names()
                )));
                None
            }
            Some(d) => Some(d),
            None if factories.len() == 1 => factories.first().map(|(n, _)| n.clone()),
            None if factories.len() > 1 => {
                problems.push(problem(format!(
                    "several object stores ({}): name the default with default_store",
                    names()
                )));
                None
            }
            None => None,
        };
        let environment = boot.config().environment().to_owned();
        let mut built = Vec::new();
        for (name, factory) in &factories {
            match factory.create(name, &environment).await {
                Ok(store) => built.push((name.clone(), store)),
                Err(message) => problems.push(problem(format!("object store '{name}': {message}"))),
            }
        }
        let stores = ObjectStores {
            default,
            stores: Arc::new(built),
        };
        if !problems.is_empty() {
            stores.close().await;
            return Err(StartupError::new(problems));
        }
        let closing = stores.clone();
        boot.on_shutdown(MODULE, move || async move { closing.close().await });
        Ok(stores)
    }
}
