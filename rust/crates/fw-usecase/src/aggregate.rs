//! Aggregates and their persistence state (Rust design section 1.6): the aggregate holds what its
//! rules need; the client and the version are the framework's, in [`Fresh`] and [`Loaded`].

use std::any;

use fw_core::{ClientId, Context};

/// An aggregate: a domain value with an id.
pub trait Aggregate: Send + Sync {
    /// Its id type (usually `fw_core::Id<Self>`).
    type Id: AsRef<str> + Send + Sync;

    /// Its id.
    fn id(&self) -> &Self::Id;

    /// The entity type in audit rows: by default the type's name without its module path (the
    /// Java simple class name).
    fn entity_type() -> &'static str
    where
        Self: Sized,
    {
        let full = any::type_name::<Self>();
        let base = full.split('<').next().unwrap_or(full);
        base.rsplit("::").next().unwrap_or(base)
    }
}

/// A stored aggregate's version: 1 after insert, plus one per update.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct Version(i64);

impl Version {
    /// A version read from a row.
    ///
    /// # Errors
    ///
    /// When the stored value is below 1 (a corrupt row).
    pub fn stored(v: i64) -> Result<Self, VersionError> {
        if v >= 1 {
            Ok(Self(v))
        } else {
            Err(VersionError(v))
        }
    }

    /// The number.
    #[must_use]
    pub fn get(self) -> i64 {
        self.0
    }
}

/// A stored version below 1.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
#[error("a stored version must be at least 1, found {0}")]
pub struct VersionError(i64);

/// The optimistic-locking check the runner gives `update` and `delete`:
/// `... WHERE id = ? AND client_id = ? AND version = expected`, writing `next`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VersionCheck {
    /// The version the aggregate was loaded at.
    pub expected: Version,
    /// The version to write (`expected + 1`).
    pub next: Version,
}

/// An aggregate made in this attempt, for the context's client: inserted, version 1.
#[derive(Debug, Clone)]
pub struct Fresh<A> {
    pub(crate) value: A,
    pub(crate) client: ClientId,
}

impl<A> Fresh<A> {
    /// A new aggregate for the context's client (there is no other constructor).
    #[must_use]
    pub fn new(ctx: &Context, value: A) -> Self {
        Self {
            value,
            client: ctx.client().clone(),
        }
    }

    /// The aggregate.
    #[must_use]
    pub fn get(&self) -> &A {
        &self.value
    }
}

/// An aggregate a repository found: the row's client and version travel with it.
#[derive(Debug, Clone)]
pub struct Loaded<A> {
    pub(crate) value: A,
    pub(crate) client: ClientId,
    pub(crate) version: Version,
}

impl<A> Loaded<A> {
    /// What a repository's `find` returns: the client as the row holds it (so the runner can catch
    /// a `find` that forgot to filter by client), the stored version, the aggregate.
    #[must_use]
    pub fn new(client: ClientId, version: Version, value: A) -> Self {
        Self {
            value,
            client,
            version,
        }
    }

    /// The aggregate.
    #[must_use]
    pub fn get(&self) -> &A {
        &self.value
    }

    /// The row's client.
    #[must_use]
    pub fn client(&self) -> &ClientId {
        &self.client
    }

    /// The stored version.
    #[must_use]
    pub fn version(&self) -> Version {
        self.version
    }

    /// The aggregate changed, keeping client and version (so the runner updates it).
    #[must_use]
    pub fn map(self, f: impl FnOnce(A) -> A) -> Self {
        Self {
            value: f(self.value),
            ..self
        }
    }

    /// As [`Loaded::map`], for a change that can be refused or that computes a second value.
    ///
    /// # Errors
    ///
    /// Whatever `f` returns.
    pub fn try_map<T, E>(self, f: impl FnOnce(A) -> Result<(A, T), E>) -> Result<(Self, T), E> {
        let Self {
            value,
            client,
            version,
        } = self;
        let (value, t) = f(value)?;
        Ok((
            Self {
                value,
                client,
                version,
            },
            t,
        ))
    }
}

/// What `Plan::save` writes: an insert for a [`Fresh`] aggregate, an update for a [`Loaded`] one.
/// The type decides, not the version.
#[derive(Debug, Clone)]
pub enum Write<A> {
    /// Insert, version 1.
    Insert(Fresh<A>),
    /// Update, version + 1.
    Update(Loaded<A>),
}

impl<A> From<Fresh<A>> for Write<A> {
    fn from(f: Fresh<A>) -> Self {
        Self::Insert(f)
    }
}

impl<A> From<Loaded<A>> for Write<A> {
    fn from(l: Loaded<A>) -> Self {
        Self::Update(l)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Order(String);
    impl Aggregate for Order {
        type Id = String;
        fn id(&self) -> &String {
            &self.0
        }
    }

    #[test]
    fn entity_type_is_the_simple_name() {
        assert_eq!(Order::entity_type(), "Order");
        assert!(Version::stored(0).is_err());
    }
}
