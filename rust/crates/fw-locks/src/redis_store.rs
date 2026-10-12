//! The `redis` driver: one standalone Redis or Valkey server (locks contract section 5). Not
//! Redlock: if the server loses data, a lock can be held twice and fencing tokens can repeat.
//!
//! The lock lives at `<prefix><stored key>` holding `<owner>:<fence>` with a millisecond TTL; one
//! counter at `<prefix>__fence` never expires. The three Lua scripts are the files shared with the
//! Java framework (`conformance/lua/locks/`), byte for byte, so both load the same SHA-1; they run
//! with `EVALSHA`, loaded at build and reloaded on `NOSCRIPT`.

use fw_core::BoxFuture;
use fw_redis::RedisConnection;
use redis::{RedisError, Script, Value};

use crate::store::{LockStore, LockStoreError};

/// The acquire script, as shared.
pub const ACQUIRE_LUA: &str = include_str!("lua/acquire.lua");
/// The refresh script, as shared.
pub const REFRESH_LUA: &str = include_str!("lua/refresh.lua");
/// The release script, as shared.
pub const RELEASE_LUA: &str = include_str!("lua/release.lua");

/// The default key prefix.
pub const DEFAULT_PREFIX: &str = "fw:lock:";

/// Locks in Redis.
pub struct RedisLockStore {
    connection: RedisConnection,
    prefix: String,
    counter: String,
    acquire: Script,
    refresh: Script,
    release: Script,
}

fn unavailable(e: RedisError) -> LockStoreError {
    LockStoreError::unavailable("Redis lock operation failed", e)
}

impl RedisLockStore {
    /// A store on a connection, with a key prefix; loads the scripts.
    ///
    /// # Errors
    ///
    /// When the scripts cannot be loaded.
    pub async fn new(connection: RedisConnection, prefix: &str) -> Result<Self, LockStoreError> {
        let store = Self {
            connection,
            prefix: prefix.to_owned(),
            counter: format!("{prefix}__fence"),
            acquire: Script::new(ACQUIRE_LUA),
            refresh: Script::new(REFRESH_LUA),
            release: Script::new(RELEASE_LUA),
        };
        let mut c = store.connection.manager();
        for script in [&store.acquire, &store.refresh, &store.release] {
            script
                .prepare_invoke()
                .load_async(&mut c)
                .await
                .map_err(unavailable)?;
        }
        Ok(store)
    }

    /// The SHA-1 the server knows a script by.
    #[must_use]
    pub fn script_hashes(&self) -> [&str; 3] {
        [
            self.acquire.get_hash(),
            self.refresh.get_hash(),
            self.release.get_hash(),
        ]
    }
}

impl LockStore for RedisLockStore {
    fn try_acquire<'a>(
        &'a self,
        key: &'a str,
        owner: &'a str,
        lease_ms: i64,
    ) -> BoxFuture<'a, Result<Option<i64>, LockStoreError>> {
        Box::pin(async move {
            let mut c = self.connection.manager();
            let reply: Value = self
                .acquire
                .key(format!("{}{key}", self.prefix))
                .key(&self.counter)
                .arg(owner)
                .arg(lease_ms)
                .invoke_async(&mut c)
                .await
                .map_err(unavailable)?;
            #[expect(
                clippy::wildcard_enum_match_arm,
                reason = "redis::Value is a foreign non-exhaustive enum; nil is the only other reply"
            )]
            match reply {
                Value::Int(fence) => Ok(Some(fence)),
                _ => Ok(None),
            }
        })
    }

    fn refresh<'a>(
        &'a self,
        key: &'a str,
        owner: &'a str,
        fence: i64,
        lease_ms: i64,
    ) -> BoxFuture<'a, Result<bool, LockStoreError>> {
        Box::pin(async move {
            let mut c = self.connection.manager();
            let reply: i64 = self
                .refresh
                .key(format!("{}{key}", self.prefix))
                .arg(format!("{owner}:{fence}"))
                .arg(lease_ms)
                .invoke_async(&mut c)
                .await
                .map_err(unavailable)?;
            Ok(reply == 1)
        })
    }

    fn release<'a>(
        &'a self,
        key: &'a str,
        owner: &'a str,
        fence: i64,
    ) -> BoxFuture<'a, Result<bool, LockStoreError>> {
        Box::pin(async move {
            let mut c = self.connection.manager();
            let reply: i64 = self
                .release
                .key(format!("{}{key}", self.prefix))
                .arg(format!("{owner}:{fence}"))
                .invoke_async(&mut c)
                .await
                .map_err(unavailable)?;
            Ok(reply == 1)
        })
    }

    fn expire_now<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Result<(), LockStoreError>> {
        Box::pin(async move {
            let mut c = self.connection.manager();
            redis::cmd("DEL")
                .arg(format!("{}{key}", self.prefix))
                .query_async::<i64>(&mut c)
                .await
                .map(|_| ())
                .map_err(unavailable)
        })
    }
}
