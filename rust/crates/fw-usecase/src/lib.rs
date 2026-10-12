//! The write path (write-path contract; Rust design sections 1.6 and capabilities section 3): use
//! cases with validate, authorize and execute, the runner that applies a plan atomically with
//! optimistic locking and client scoping, events, audit rows and dispatch jobs written to the
//! SDK-compatible outbox in the same transaction.
//!
//! Persistence state is the framework's: a [`Fresh`] aggregate (made in this attempt, for the
//! context's client) is inserted, a [`Loaded`] one (found by a repository) is updated; the type
//! decides. `execute` receives an [`Authorized`] command, which only the runner can make, and
//! success is a [`Committed`] event, which only the runner can make.

mod aggregate;
mod dispatch;
mod error;
mod event;
mod outbox;
mod plan;
mod repository;
mod usecase;

use fw_core::{Declarations, Module, ModuleId};

pub use aggregate::{Aggregate, Fresh, Loaded, Version, VersionCheck, VersionError, Write};
pub use dispatch::{
    DispatchJob, DispatchJobBuilder, DispatchJobError, Kind as DispatchKind, Mode as DispatchMode,
    Queue as DispatchQueue,
};
pub use error::{ErrorCode, ErrorKind, UseCaseError};
pub use event::{DomainEvent, ErasedEvent, EventMetadata, EventType, Events};
pub use outbox::{
    AuditData, AuditEntry, INSERT_SQL, OutboxWriter, Sink, SinkError, instant_text, migration_set,
};
pub use plan::Plan;
pub use repository::Repository;
pub use usecase::{
    Attempts, Authorized, Committed, ConflictPolicy, Grant, UseCase, UseCaseName, UseCases, Valid,
};

/// Contributes the outbox table's migration set (`outbox`: the SDK's file byte for byte, then the
/// `client_id` widening). Build the runner with [`UseCases::build`].
#[derive(Debug, Default, Clone, Copy)]
pub struct OutboxModule;

impl Module for OutboxModule {
    type Declared = ();

    fn id(&self) -> ModuleId {
        ModuleId::of("outbox")
    }

    fn declare(self, d: &mut Declarations<'_>) {
        d.migrations(migration_set());
    }
}
