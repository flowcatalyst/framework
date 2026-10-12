//! The orders demo on the Rust framework: the Java `examples/orders-demo` (the same table, use
//! cases, events and so the same outbox rows), written as [`docs/rust/orders-showcase.md`]
//! describes: one type per order state, commands parsed at the edge, checked SQL, `Fresh` and
//! `Loaded`.
//!
//! [`docs/rust/orders-showcase.md`]: https://github.com/flowcatalyst/framework/blob/main/docs/rust/orders-showcase.md

pub mod domain;
pub mod events;
pub mod repository;
pub mod usecases;

use fw_core::{
    AppBuilder, Boot, Declarations, MigrationFile, MigrationSet, Module, ModuleId, StartupError,
};
use fw_db::{DatabaseDeclared, DatabaseModule, Databases};
use fw_usecase::{Events, OutboxModule, UseCases};

use crate::usecases::{OrderUseCases, Policies};

/// Contributes the `orders` migration set: the Java demo's `V0001__create_orders.sql`, byte for
/// byte, recorded under the same module id.
#[derive(Debug, Default, Clone, Copy)]
pub struct OrdersModule;

impl Module for OrdersModule {
    type Declared = ();

    fn id(&self) -> ModuleId {
        ModuleId::of("orders")
    }

    fn declare(self, d: &mut Declarations<'_>) {
        d.migrations(migration_set());
    }
}

/// The `orders` migration set.
#[must_use]
pub fn migration_set() -> MigrationSet {
    MigrationSet::new(
        ModuleId::of("orders"),
        [MigrationFile::embedded(
            "V0001__create_orders.sql",
            include_bytes!("../migrations/orders/V0001__create_orders.sql"),
        )],
    )
}

/// The modules' handles.
#[derive(Debug, Clone, Copy)]
pub struct Declared {
    database: DatabaseDeclared,
}

/// Adds the modules: database, outbox (its migrations), orders (its migrations).
pub fn declare(app: &mut AppBuilder) -> Declared {
    let database = app.module(DatabaseModule);
    app.module(OutboxModule);
    app.module(OrdersModule);
    Declared { database }
}

/// The built application's services.
#[derive(Debug, Clone)]
pub struct OrdersApp {
    /// The runner.
    pub use_cases: UseCases,
    /// The demo's use cases.
    pub orders: OrderUseCases,
}

/// Builds the services: the databases (migrated: outbox, then orders), the runner, the use cases.
///
/// # Errors
///
/// Any start-up problem of the parts.
pub async fn build(
    boot: &mut Boot,
    declared: &Declared,
    policies: Policies,
) -> Result<OrdersApp, StartupError> {
    let dbs = Databases::connect(boot, &declared.database).await?;
    dbs.migrate(boot).await?;
    let use_cases = UseCases::build(boot, &dbs)?;
    let events = Events::new(boot.clock(), boot.ids().clone());
    Ok(OrdersApp {
        use_cases,
        orders: OrderUseCases::new(boot.ids().clone(), events, policies),
    })
}
