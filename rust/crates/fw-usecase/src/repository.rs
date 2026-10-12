//! Repositories (write-path contract section 2): written by the application, with explicit SQL.

use fw_core::ClientId;
use fw_db::{Db, DbError, Tx};

use crate::aggregate::{Aggregate, Loaded, VersionCheck};

/// Stores one aggregate type.
///
/// - `find` filters by `client_id` and returns the row's client and version in a [`Loaded`].
/// - `insert` stores version 1.
/// - `update` writes `SET version = v.next ... WHERE id = ? AND client_id = ? AND version =
///   v.expected`, then the children, and returns the root update's row count (0 means stale).
/// - `delete` is `DELETE ... WHERE id = ? AND client_id = ? AND version = v.expected`, returning
///   the row count.
///
/// Implementations write `async fn`; the futures must be `Send`.
pub trait Repository<A: Aggregate>: Send + Sync {
    /// Loads an aggregate of the client, or none.
    fn find(
        &self,
        db: &Db,
        client: &ClientId,
        id: &A::Id,
    ) -> impl Future<Output = Result<Option<Loaded<A>>, DbError>> + Send;

    /// Inserts a new aggregate with version 1.
    fn insert(
        &self,
        tx: &mut Tx,
        client: &ClientId,
        aggregate: &A,
    ) -> impl Future<Output = Result<(), DbError>> + Send;

    /// Updates a loaded aggregate; returns the root row count.
    fn update(
        &self,
        tx: &mut Tx,
        client: &ClientId,
        aggregate: &A,
        version: VersionCheck,
    ) -> impl Future<Output = Result<u64, DbError>> + Send;

    /// Deletes a loaded aggregate; returns the root row count.
    fn delete(
        &self,
        tx: &mut Tx,
        client: &ClientId,
        aggregate: &A,
        version: VersionCheck,
    ) -> impl Future<Output = Result<u64, DbError>> + Send;
}
