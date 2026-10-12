//! Redis connections for the framework (database contract section 3): one standalone Redis or
//! Valkey server per named instance, a start-up ping, the health check `redis:<name>`.
//!
//! ```yaml
//! redis:
//!   default: default
//!   instances:
//!     default: { url: "redis://localhost:6379/0", timeout: 2s }   # password: a secret, wins over the URL's
//! ```

use std::collections::BTreeMap;
use std::fmt;
use std::sync::Arc;
use std::time::Duration;

use fw_core::config::{
    DriverSpec, InstanceConfig, InstanceSection, InstancesHandle, KeySpec, KeyType,
};
use fw_core::health::{CheckResult, Criticality};
use fw_core::{Boot, Declarations, Module, ModuleId, Problem, StartupError};
use redis::aio::{ConnectionManager, ConnectionManagerConfig};
use redis::{Client, IntoConnectionInfo, RedisError};

const ID: ModuleId = ModuleId::of("redis");
const DRIVER: &str = "redis";

/// Declares the `redis` section: named Redis connections.
#[derive(Debug, Default, Clone, Copy)]
pub struct RedisModule;

/// What [`RedisModule`] declared.
#[derive(Debug, Clone, Copy)]
pub struct RedisDeclared {
    instances: InstancesHandle,
}

impl Module for RedisModule {
    type Declared = RedisDeclared;

    fn id(&self) -> ModuleId {
        ID
    }

    fn declare(self, d: &mut Declarations<'_>) -> RedisDeclared {
        let section = InstanceSection::new("redis", "RedisConnection")
            .default_driver(DRIVER)
            .driver(DriverSpec::new(
                DRIVER,
                [
                    KeySpec::new("url", KeyType::String).required(),
                    KeySpec::new("password", KeyType::Secret),
                    KeySpec::new("timeout", KeyType::Duration),
                ],
            ));
        RedisDeclared {
            instances: d.instances(section),
        }
    }
}

/// One named connection: a `ConnectionManager` (reconnecting, multiplexed). Cloning shares it.
#[derive(Clone)]
pub struct RedisConnection {
    name: Arc<str>,
    manager: ConnectionManager,
}

impl fmt::Debug for RedisConnection {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("RedisConnection")
            .field("name", &self.name)
            .finish_non_exhaustive()
    }
}

impl RedisConnection {
    /// A connection over an existing manager (tests, tools).
    #[must_use]
    pub fn from_manager(name: &str, manager: ConnectionManager) -> Self {
        Self {
            name: Arc::from(name),
            manager,
        }
    }

    /// The instance name.
    #[must_use]
    pub fn name(&self) -> &str {
        &self.name
    }

    /// The connection, for commands and scripts (clone it per use; clones share one connection).
    #[must_use]
    pub fn manager(&self) -> ConnectionManager {
        self.manager.clone()
    }

    /// Sends `PING`.
    ///
    /// # Errors
    ///
    /// When the server does not answer.
    pub async fn ping(&self) -> Result<(), RedisError> {
        let mut c = self.manager.clone();
        redis::cmd("PING")
            .query_async::<String>(&mut c)
            .await
            .map(|_| ())
    }
}

/// The configured Redis connections.
#[derive(Clone)]
pub struct RedisConnections {
    default: Option<String>,
    by_name: BTreeMap<String, RedisConnection>,
}

impl fmt::Debug for RedisConnections {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("RedisConnections")
            .field("default", &self.default)
            .field("names", &self.by_name.keys())
            .finish()
    }
}

impl RedisConnections {
    /// Opens every configured instance and pings it (start-up fails naming the instance when it
    /// does not answer) and adds the health check `redis:<name>`.
    ///
    /// # Errors
    ///
    /// The instances that could not be opened.
    pub async fn connect(boot: &mut Boot, declared: &RedisDeclared) -> Result<Self, StartupError> {
        let configured = boot.config().instances(declared.instances).clone();
        let mut problems = Vec::new();
        let mut by_name = BTreeMap::new();
        for instance in configured.iter() {
            match open(instance).await {
                Ok(c) => {
                    let check = c.clone();
                    boot.health().add(
                        format!("redis:{}", c.name),
                        Criticality::Critical,
                        move || {
                            let c = check.clone();
                            async move {
                                match c.ping().await {
                                    Ok(()) => CheckResult::Up,
                                    Err(e) => CheckResult::Down(e.to_string()),
                                }
                            }
                        },
                    )?;
                    by_name.insert(instance.name().to_owned(), c);
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

    /// Connections over existing managers (tests, tools).
    #[must_use]
    pub fn of(default: RedisConnection) -> Self {
        let name = default.name().to_owned();
        Self {
            default: Some(name.clone()),
            by_name: BTreeMap::from([(name, default)]),
        }
    }

    /// The named connection.
    #[must_use]
    pub fn get(&self, name: &str) -> Option<&RedisConnection> {
        self.by_name.get(name)
    }

    /// The default connection, if any is configured.
    #[must_use]
    pub fn default_connection(&self) -> Option<&RedisConnection> {
        self.default.as_deref().and_then(|n| self.by_name.get(n))
    }

    /// The instance names.
    pub fn names(&self) -> impl Iterator<Item = &str> {
        self.by_name.keys().map(String::as_str)
    }
}

fn problem(name: &str, message: impl Into<String>) -> Problem {
    Problem::new(
        Some(ID.as_str()),
        Some(&format!("redis.instances.{name}")),
        message,
    )
}

async fn open(instance: &InstanceConfig) -> Result<RedisConnection, Problem> {
    let name = instance.name();
    let url = instance.string("url").unwrap_or_default();
    let mut info = url
        .into_connection_info()
        .map_err(|e| problem(name, format!("url is not a redis:// URL: {e}")))?;
    if let Some(password) = instance.secret("password").filter(|p| !p.is_empty()) {
        info.redis.password = Some(password.reveal().to_owned());
    }
    let timeout = instance
        .duration("timeout")
        .unwrap_or(Duration::from_secs(2));
    let client =
        Client::open(info).map_err(|e| problem(name, format!("url is not usable: {e}")))?;
    let config = ConnectionManagerConfig::new()
        .set_connection_timeout(timeout)
        .set_response_timeout(timeout)
        .set_number_of_retries(1);
    let manager = ConnectionManager::new_with_config(client, config)
        .await
        .map_err(|e| {
            problem(
                name,
                format!("cannot connect to Redis instance '{name}': {e}"),
            )
        })?;
    let connection = RedisConnection {
        name: Arc::from(name),
        manager,
    };
    connection.ping().await.map_err(|e| {
        problem(
            name,
            format!("cannot connect to Redis instance '{name}': {e}"),
        )
    })?;
    Ok(connection)
}
