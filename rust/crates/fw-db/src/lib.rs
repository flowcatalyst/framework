//! PostgreSQL for the framework (database contract): named instances on sqlx pools, a start-up
//! ping and a health check per instance, the framework's own migration runner on
//! `fw_schema_history` (shared with the Java framework, so either implementation accepts a
//! database the other migrated), and the transaction type the use-case runner writes in.
//!
//! ```yaml
//! database:
//!   default: main
//!   instances:
//!     main:
//!       url: postgres://localhost:5432/app      # a jdbc:postgresql:// URL is accepted too
//!       username: app
//!       password: ""                            # a secret
//!       pool: { max: 10, min-idle: 1, connect-timeout: 5s }
//!       migrate: true
//! ```

mod database;
pub mod migrations;

pub use database::{DatabaseDeclared, DatabaseModule, Databases, Db, DbError, Tx};
