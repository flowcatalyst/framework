//! Database instances, `Db` and `Tx`.

use std::collections::BTreeMap;
use std::fmt;
use std::sync::Arc;
use std::time::Duration;

use fw_core::config::{
    DriverSpec, InstanceConfig, InstanceSection, InstancesHandle, KeySpec, KeyType,
};
use fw_core::health::{CheckResult, Criticality};
use fw_core::{Boot, Declarations, Module, ModuleId, Problem, StartupError};
use sqlx::postgres::PgPoolOptions;
use sqlx::{Executor, PgConnection, PgPool, Postgres, Transaction};

use crate::migrations;
use crate::url::parse_url;

/// A database failure, as the driver reports it.
pub type DbError = sqlx::Error;

const ID: ModuleId = ModuleId::of("database");
/// The only driver: PostgreSQL on sqlx (the Java name, so one configuration serves both).
const DRIVER: &str = "postgresql";

/// Declares the `database` section: named PostgreSQL instances.
#[derive(Debug, Default, Clone, Copy)]
pub struct DatabaseModule;

/// What [`DatabaseModule`] declared.
#[derive(Debug, Clone, Copy)]
pub struct DatabaseDeclared {
    instances: InstancesHandle,
}

impl Module for DatabaseModule {
    type Declared = DatabaseDeclared;

    fn id(&self) -> ModuleId {
        ID
    }

    fn declare(self, d: &mut Declarations<'_>) -> DatabaseDeclared {
        let section = InstanceSection::new("database", "Database")
            .default_driver(DRIVER)
            .driver(DriverSpec::new(
                DRIVER,
                [
                    KeySpec::new("url", KeyType::String).required(),
                    KeySpec::new("username", KeyType::String),
                    KeySpec::new("password", KeyType::Secret),
                    KeySpec::new("pool.max", KeyType::Integer),
                    KeySpec::new("pool.min-idle", KeyType::Integer),
                    KeySpec::new("pool.connect-timeout", KeyType::Duration),
                    KeySpec::new("migrate", KeyType::Boolean),
                ],
            ));
        DatabaseDeclared {
            instances: d.instances(section),
        }
    }
}

/// One named database instance: a pool. Cloning shares the pool.
#[derive(Clone)]
pub struct Db {
    name: Arc<str>,
    pool: PgPool,
    migrate: bool,
}

impl fmt::Debug for Db {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Db")
            .field("name", &self.name)
            .field("migrate", &self.migrate)
            .finish_non_exhaustive()
    }
}

impl Db {
    /// An instance over an existing pool (tests, tools).
    #[must_use]
    pub fn from_pool(name: &str, pool: PgPool, migrate: bool) -> Self {
        Self {
            name: Arc::from(name),
            pool,
            migrate,
        }
    }

    /// The instance name.
    #[must_use]
    pub fn name(&self) -> &str {
        &self.name
    }

    /// The pool, for reads outside use cases (screens, lists, the lock driver).
    #[must_use]
    pub fn pool(&self) -> &PgPool {
        &self.pool
    }

    /// Whether migrations may run on this instance (when it is the default one).
    #[must_use]
    pub fn migrates(&self) -> bool {
        self.migrate
    }

    /// Checks that the database answers.
    ///
    /// # Errors
    ///
    /// When it does not.
    pub async fn ping(&self) -> Result<(), DbError> {
        self.pool.execute("SELECT 1").await.map(|_| ())
    }

    /// Whether a table (or another relation) exists.
    ///
    /// # Errors
    ///
    /// On a database error.
    pub async fn table_exists(&self, qualified_name: &str) -> Result<bool, DbError> {
        sqlx::query_scalar::<_, bool>("SELECT to_regclass($1) IS NOT NULL")
            .bind(qualified_name)
            .fetch_one(&self.pool)
            .await
    }

    /// Fails start-up unless a table exists: for a module whose code needs a table on an instance
    /// that is not the default one, where migrations do not run (database contract section 2).
    ///
    /// # Errors
    ///
    /// When the table is missing, or the check fails.
    pub async fn require_table(
        &self,
        qualified_name: &str,
        needer: &str,
    ) -> Result<(), StartupError> {
        match self.table_exists(qualified_name).await {
            Ok(true) => Ok(()),
            Ok(false) => Err(StartupError::of(
                None,
                Some(&format!("database:{}", self.name)),
                format!(
                    "{needer} needs table '{qualified_name}' in database instance '{}', but it does not exist; migrations do \
                     not run on this instance, so create the table yourself or use the default database instance",
                    self.name
                ),
            )),
            Err(e) => Err(StartupError::of(
                None,
                Some(&format!("database:{}", self.name)),
                format!(
                    "cannot check for table '{qualified_name}' in database instance '{}': {e}",
                    self.name
                ),
            )),
        }
    }

    /// Begins a `READ COMMITTED` transaction.
    ///
    /// Framework-internal: only the use-case runner (and the receivers' `once`) begin
    /// transactions; application code writes through use-case plans.
    ///
    /// # Errors
    ///
    /// When the transaction cannot begin.
    #[doc(hidden)]
    pub async fn begin_read_committed(&self) -> Result<Tx, DbError> {
        let mut inner = self.pool.begin().await?;
        inner
            .execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED")
            .await?;
        Ok(Tx { inner })
    }
}

/// A transaction of the use-case runner. Rolled back when dropped without a commit (also when the
/// future holding it is cancelled).
pub struct Tx {
    inner: Transaction<'static, Postgres>,
}

impl fmt::Debug for Tx {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Tx")
    }
}

impl Tx {
    /// The connection, for repository writes: `query.execute(tx.conn())`.
    pub fn conn(&mut self) -> &mut PgConnection {
        &mut self.inner
    }

    /// Commits. Framework-internal, as [`Db::begin_read_committed`].
    ///
    /// # Errors
    ///
    /// When the commit fails.
    #[doc(hidden)]
    pub async fn commit(self) -> Result<(), DbError> {
        self.inner.commit().await
    }

    /// Rolls back. Framework-internal.
    ///
    /// # Errors
    ///
    /// When the rollback fails.
    #[doc(hidden)]
    pub async fn rollback(self) -> Result<(), DbError> {
        self.inner.rollback().await
    }
}

/// The configured database instances.
#[derive(Clone)]
pub struct Databases {
    default: Option<String>,
    by_name: BTreeMap<String, Db>,
}

impl fmt::Debug for Databases {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Databases")
            .field("default", &self.default)
            .field("names", &self.by_name.keys())
            .finish()
    }
}

fn problem(name: &str, message: impl Into<String>) -> Problem {
    Problem::new(
        Some(ID.as_str()),
        Some(&format!("database.instances.{name}")),
        message,
    )
}

impl Databases {
    /// Opens every configured instance: builds its pool, pings it (start-up fails naming the
    /// instance when it does not answer), adds the health check `database:<name>` and closes the
    /// pool at shutdown. Every instance is tried; the problems are reported together.
    ///
    /// # Errors
    ///
    /// The instances that could not be opened.
    pub async fn connect(
        boot: &mut Boot,
        declared: &DatabaseDeclared,
    ) -> Result<Self, StartupError> {
        let configured = boot.config().instances(declared.instances).clone();
        let mut problems = Vec::new();
        let mut by_name = BTreeMap::new();
        for instance in configured.iter() {
            match open(instance).await {
                Ok(db) => {
                    let pool = db.pool.clone();
                    boot.on_shutdown(format!("database:{}", db.name), move || async move {
                        pool.close().await
                    });
                    let check = db.clone();
                    boot.health().add(
                        format!("database:{}", db.name),
                        Criticality::Critical,
                        move || {
                            let db = check.clone();
                            async move {
                                match db.ping().await {
                                    Ok(()) => CheckResult::Up,
                                    Err(e) => CheckResult::Down(e.to_string()),
                                }
                            }
                        },
                    )?;
                    by_name.insert(instance.name().to_owned(), db);
                }
                Err(p) => problems.push(p),
            }
        }
        if !problems.is_empty() {
            return Err(StartupError::new(problems));
        }
        Ok(Self {
            default: configured.default_name().map(str::to_owned),
            by_name,
        })
    }

    /// Instances over existing pools (tests, tools).
    #[must_use]
    pub fn of(default: Db) -> Self {
        let name = default.name().to_owned();
        Self {
            default: Some(name.clone()),
            by_name: BTreeMap::from([(name, default)]),
        }
    }

    /// The named instance.
    #[must_use]
    pub fn get(&self, name: &str) -> Option<&Db> {
        self.by_name.get(name)
    }

    /// The default instance, if any instance is configured.
    #[must_use]
    pub fn default_db(&self) -> Option<&Db> {
        self.default.as_deref().and_then(|n| self.by_name.get(n))
    }

    /// The default instance, or a start-up problem naming `needer` when none is configured.
    ///
    /// # Errors
    ///
    /// When no database instance is configured.
    pub fn require_default(&self, needer: &str) -> Result<&Db, StartupError> {
        self.default_db().ok_or_else(|| {
            StartupError::of(
                Some(ID.as_str()),
                Some("database"),
                format!("{needer} needs a database, but no instance is configured"),
            )
        })
    }

    /// The instance names.
    pub fn names(&self) -> impl Iterator<Item = &str> {
        self.by_name.keys().map(String::as_str)
    }

    /// Applies every module's migration set (in declaration order) to the default instance, when
    /// one is configured and its `migrate` is true (database contract section 2).
    ///
    /// # Errors
    ///
    /// Every problem the runner found: changed or missing files, versions below the applied ones,
    /// duplicate versions, a failing file (rolled back).
    pub async fn migrate(&self, boot: &Boot) -> Result<(), StartupError> {
        let sets = boot.migration_sets();
        let Some(db) = self.default_db() else {
            return Ok(());
        };
        if sets.is_empty() || !db.migrates() {
            return Ok(());
        }
        migrations::apply(db.name(), db.pool(), sets).await
    }
}

async fn open(instance: &InstanceConfig) -> Result<Db, Problem> {
    let name = instance.name();
    let url = instance.string("url").unwrap_or_default();
    let mut options = parse_url(url).map_err(|e| problem(name, e))?;
    if let Some(user) = instance.string("username").filter(|u| !u.is_empty()) {
        options = options.username(user);
    }
    if let Some(password) = instance.secret("password").filter(|p| !p.is_empty()) {
        options = options.password(password.reveal());
    }
    let max = u32::try_from(instance.integer("pool.max").unwrap_or(10))
        .unwrap_or(10)
        .max(1);
    let min = u32::try_from(instance.integer("pool.min-idle").unwrap_or(1))
        .unwrap_or(1)
        .min(max);
    let timeout = instance
        .duration("pool.connect-timeout")
        .unwrap_or(Duration::from_secs(5))
        .max(Duration::from_millis(250));
    let pool = PgPoolOptions::new()
        .max_connections(max)
        .min_connections(min)
        .acquire_timeout(timeout)
        .connect_lazy_with(options);
    let db = Db {
        name: Arc::from(name),
        pool,
        migrate: instance.boolean("migrate").unwrap_or(true),
    };
    if let Err(e) = db.ping().await {
        db.pool.close().await;
        return Err(problem(
            name,
            format!("cannot connect to database instance '{name}': {e}"),
        ));
    }
    Ok(db)
}
