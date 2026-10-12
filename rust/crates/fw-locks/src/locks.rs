//! The `locks` section, its drivers, and the registry of named lock instances.

use std::collections::BTreeMap;
use std::fmt;
use std::sync::Arc;

use fw_core::config::{DriverSpec, InstanceConfig, InstanceSection, InstancesHandle};
#[cfg(any(feature = "postgres", feature = "redis"))]
use fw_core::config::{KeySpec, KeyType};
use fw_core::{Boot, Declarations, Module, ModuleId, Problem, StartupError};

use crate::lock::Lock;
use crate::memory::MemoryLockStore;
#[cfg(feature = "postgres")]
use crate::postgres::PostgresLockStore;
#[cfg(feature = "redis")]
use crate::redis_store::{DEFAULT_PREFIX, RedisLockStore};
use crate::store::LockStore;

const ID: ModuleId = ModuleId::of("locks");

/// The migration set of the `database` driver: the table `fw_lock` and the sequence
/// `fw_lock_fence`, recorded under the Java module id `locks-database` with the Java file's bytes.
#[cfg(feature = "postgres")]
#[must_use]
pub fn migration_set() -> fw_core::MigrationSet {
    fw_core::MigrationSet::new(
        ModuleId::of("locks-database"),
        [fw_core::MigrationFile::embedded(
            "V0001__create_lock_table.sql",
            include_bytes!("../migrations/locks/V0001__create_lock_table.sql"),
        )],
    )
}

/// Declares the `locks` section with every driver compiled in: `memory` always, `database` with
/// the feature `postgres` (and its migration set), `redis` with the feature `redis`.
///
/// ```yaml
/// locks:
///   default: main
///   instances:
///     main:   { driver: database }                          # database: optional, the default instance
///     shared: { driver: redis, connection: default, prefix: "fw:lock:" }
///     local:  { driver: memory }
/// ```
#[derive(Debug, Default, Clone, Copy)]
pub struct LocksModule;

/// What [`LocksModule`] declared.
#[derive(Debug, Clone, Copy)]
pub struct LocksDeclared {
    instances: InstancesHandle,
}

impl Module for LocksModule {
    type Declared = LocksDeclared;

    fn id(&self) -> ModuleId {
        ID
    }

    fn declare(self, d: &mut Declarations<'_>) -> LocksDeclared {
        let section =
            InstanceSection::new("locks", "LockStore").driver(DriverSpec::new("memory", []));
        #[cfg(feature = "postgres")]
        let section = section.driver(DriverSpec::new(
            "database",
            [KeySpec::new("database", KeyType::String)],
        ));
        #[cfg(not(feature = "postgres"))]
        let section = section.not_compiled("database", "`postgres` of fw-locks");
        #[cfg(feature = "redis")]
        let section = section.driver(DriverSpec::new(
            "redis",
            [
                KeySpec::new("connection", KeyType::String),
                KeySpec::new("prefix", KeyType::String),
            ],
        ));
        #[cfg(not(feature = "redis"))]
        let section = section.not_compiled("redis", "`redis` of fw-locks");
        #[cfg(feature = "postgres")]
        d.migrations(migration_set());
        LocksDeclared {
            instances: d.instances(section),
        }
    }
}

/// The configured lock instances.
#[derive(Clone)]
pub struct Locks {
    default: Option<String>,
    by_name: BTreeMap<String, Lock>,
}

impl fmt::Debug for Locks {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Locks")
            .field("default", &self.default)
            .field("names", &self.by_name.keys())
            .finish()
    }
}

impl Locks {
    /// Builds the configured instances; give it the connections the drivers in use need.
    #[must_use]
    pub fn builder<'b>(boot: &'b Boot, declared: &LocksDeclared) -> LocksBuilder<'b> {
        LocksBuilder {
            boot,
            instances: declared.instances,
            #[cfg(feature = "postgres")]
            databases: None,
            #[cfg(feature = "redis")]
            redis: None,
        }
    }

    /// One instance over a driver (tests, tools).
    #[must_use]
    pub fn single(name: &str, store: Arc<dyn LockStore>) -> Self {
        Self {
            default: Some(name.to_owned()),
            by_name: BTreeMap::from([(name.to_owned(), Lock::new(store))]),
        }
    }

    /// The named instance.
    #[must_use]
    pub fn get(&self, name: &str) -> Option<&Lock> {
        self.by_name.get(name)
    }

    /// The default instance, when one is configured.
    #[must_use]
    pub fn default_lock(&self) -> Option<&Lock> {
        self.default.as_deref().and_then(|n| self.by_name.get(n))
    }

    /// The instance names.
    pub fn names(&self) -> impl Iterator<Item = &str> {
        self.by_name.keys().map(String::as_str)
    }
}

/// Builds [`Locks`] (from [`Locks::builder`]).
pub struct LocksBuilder<'b> {
    boot: &'b Boot,
    instances: InstancesHandle,
    #[cfg(feature = "postgres")]
    databases: Option<&'b fw_db::Databases>,
    #[cfg(feature = "redis")]
    redis: Option<&'b fw_redis::RedisConnections>,
}

impl fmt::Debug for LocksBuilder<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("LocksBuilder")
    }
}

fn problem(name: &str, message: impl Into<String>) -> Problem {
    Problem::new(
        Some(ID.as_str()),
        Some(&format!("locks.instances.{name}")),
        message,
    )
}

impl<'b> LocksBuilder<'b> {
    /// The database instances, for the `database` driver.
    #[cfg(feature = "postgres")]
    #[must_use]
    pub fn databases(mut self, databases: &'b fw_db::Databases) -> Self {
        self.databases = Some(databases);
        self
    }

    /// The Redis connections, for the `redis` driver.
    #[cfg(feature = "redis")]
    #[must_use]
    pub fn redis(mut self, redis: &'b fw_redis::RedisConnections) -> Self {
        self.redis = Some(redis);
        self
    }

    /// Builds every configured instance; the problems of all of them are reported together.
    ///
    /// # Errors
    ///
    /// A driver whose connections were not given, a named database or Redis instance that is not
    /// configured, a missing `fw_lock` table on a non-default database, scripts that cannot load.
    pub async fn build(self) -> Result<Locks, StartupError> {
        let configured = self.boot.config().instances(self.instances).clone();
        let mut problems = Vec::new();
        let mut by_name = BTreeMap::new();
        for instance in configured.iter() {
            match self.store(instance).await {
                Ok(store) => {
                    by_name.insert(instance.name().to_owned(), Lock::new(store));
                }
                Err(p) => problems.push(p),
            }
        }
        if !problems.is_empty() {
            return Err(StartupError::new(problems));
        }
        Ok(Locks {
            default: configured.default_name().map(str::to_owned),
            by_name,
        })
    }

    async fn store(&self, instance: &InstanceConfig) -> Result<Arc<dyn LockStore>, Problem> {
        let name = instance.name();
        match instance.driver() {
            "memory" => Ok(Arc::new(MemoryLockStore::new())),
            #[cfg(feature = "postgres")]
            "database" => {
                let dbs = self.databases.ok_or_else(|| {
                    problem(name, "uses driver 'database', but Locks::builder was not given the databases (.databases(&dbs))")
                })?;
                let db = match instance.string("database") {
                    Some(n) => dbs.get(n).ok_or_else(|| {
                        problem(
                            name,
                            format!("names database instance '{n}', which is not configured"),
                        )
                    })?,
                    None => dbs.default_db().ok_or_else(|| {
                        problem(
                            name,
                            "uses driver 'database', but no database instance is configured",
                        )
                    })?,
                };
                if dbs.default_db().is_none_or(|d| d.name() != db.name()) {
                    db.require_table(
                        "fw_lock",
                        &format!("the database lock driver (instance '{name}')"),
                    )
                    .await
                    .map_err(|e| {
                        problem(
                            name,
                            e.problems()
                                .first()
                                .map_or_else(String::new, |p| p.message().to_owned()),
                        )
                    })?;
                }
                Ok(Arc::new(PostgresLockStore::new(db.clone())))
            }
            #[cfg(feature = "redis")]
            "redis" => {
                let conns = self.redis.ok_or_else(|| {
                    problem(name, "uses driver 'redis', but Locks::builder was not given the Redis connections (.redis(&redis))")
                })?;
                let conn = match instance.string("connection") {
                    Some(n) => conns.get(n).ok_or_else(|| {
                        problem(
                            name,
                            format!("names Redis instance '{n}', which is not configured"),
                        )
                    })?,
                    None => conns.default_connection().ok_or_else(|| {
                        problem(
                            name,
                            "uses driver 'redis', but no Redis instance is configured",
                        )
                    })?,
                };
                let prefix = instance.string("prefix").unwrap_or(DEFAULT_PREFIX);
                let store = RedisLockStore::new(conn.clone(), prefix)
                    .await
                    .map_err(|e| problem(name, format!("driver 'redis' failed: {e}")))?;
                Ok(Arc::new(store))
            }
            other => Err(problem(
                name,
                format!("driver '{other}' is not built by this crate"),
            )),
        }
    }
}
