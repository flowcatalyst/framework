//! The framework's core (Rust design, capabilities section 1): the application lifecycle and
//! modules, configuration, `Context` and ids, health with criticality, background loops, metrics
//! and the Prometheus writer, commands, and the `ServiceError` the HTTP layer maps.
//!
//! There is no container: modules declare settings, instance sections and migrations and hand
//! back typed handles; the application builds its services in its own `build` function, passing
//! each its dependencies, and registers started and shutdown actions on [`Boot`].
//!
//! ```no_run
//! use std::process::ExitCode;
//! use fw_core::{AppBuilder, AppName, Boot, StartupError, launch};
//!
//! #[tokio::main]
//! async fn main() -> ExitCode {
//!     let app = AppBuilder::new(AppName::of("my-service"));
//!     launch(app, std::env::args(), async |boot: &mut Boot| -> Result<(), StartupError> {
//!         let _ids = boot.ids().clone();
//!         Ok(())
//!     })
//!     .await
//! }
//! ```

mod app;
pub mod background;
mod clock;
pub mod config;
pub mod context;
pub mod health;
mod ids;
pub mod metrics;
mod module;
mod problem;
mod secret;
mod service_error;
pub mod tsid;

use std::pin::Pin;

pub use app::{
    AppBuilder, AppName, Boot, Built, Command, Configured, MetricsDriver, Running, ShutdownHandle,
    launch, settings,
};
pub use clock::{Clock, FixedClock, ManualClock, SystemClock};
pub use context::{
    ApplicationCode, CausationId, ClientId, Context, CorrelationId, ExecutionId, IdTextError,
    PrincipalId,
};
pub use ids::{Id, IdError, IdKind, IdPrefix, IdPrefixError, Ids};
pub use module::{Declarations, MigrationFile, MigrationSet, Module, ModuleId};
pub use problem::{Problem, StartupError};
pub use secret::Secret;
pub use service_error::ServiceError;

/// A boxed, `Send` future: what dyn-compatible async trait methods return.
pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;
