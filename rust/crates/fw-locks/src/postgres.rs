//! The `database` driver: PostgreSQL, expiry on the database's clock (locks contract section 5).
//!
//! The three statements are the files shared with the Java framework (`conformance/sql/locks/`),
//! byte for byte; JDBC's `?` placeholders are numbered (`$1`, `$2`, ...) here, as the PostgreSQL JDBC
//! driver numbers them before sending. Each statement runs on its own (autocommit) and uses
//! `clock_timestamp()`. Fencing tokens come from one sequence for all keys.

use fw_core::BoxFuture;
use fw_db::Db;

use crate::store::{LockStore, LockStoreError};

/// The acquire statement, as shared.
pub const ACQUIRE_SQL: &str = include_str!("sql/acquire.sql");
/// The refresh statement, as shared.
pub const REFRESH_SQL: &str = include_str!("sql/refresh.sql");
/// The release statement, as shared.
pub const RELEASE_SQL: &str = include_str!("sql/release.sql");

/// Replaces each `?` with `$1`, `$2`, ... in order (the statements hold no `?` in literals).
fn numbered(sql: &str) -> String {
    let mut out = String::with_capacity(sql.len() + 8);
    let mut n = 0;
    for c in sql.chars() {
        if c == '?' {
            n += 1;
            out.push('$');
            out.push_str(&n.to_string());
        } else {
            out.push(c);
        }
    }
    out
}

/// Locks in a PostgreSQL database (table `fw_lock`, sequence `fw_lock_fence`).
pub struct PostgresLockStore {
    db: Db,
    acquire: String,
    refresh: String,
    release: String,
}

impl PostgresLockStore {
    /// A store on a database instance. The table must exist (the `locks-database` migration set on
    /// the default instance; checked at build for another instance).
    #[must_use]
    pub fn new(db: Db) -> Self {
        Self {
            db,
            acquire: numbered(ACQUIRE_SQL),
            refresh: numbered(REFRESH_SQL),
            release: numbered(RELEASE_SQL),
        }
    }
}

fn unavailable(what: &str, e: sqlx::Error) -> LockStoreError {
    LockStoreError::unavailable(format!("database lock {what} failed"), e)
}

impl LockStore for PostgresLockStore {
    fn try_acquire<'a>(
        &'a self,
        key: &'a str,
        owner: &'a str,
        lease_ms: i64,
    ) -> BoxFuture<'a, Result<Option<i64>, LockStoreError>> {
        Box::pin(async move {
            sqlx::query_scalar::<_, i64>(&self.acquire)
                .bind(key)
                .bind(owner)
                .bind(lease_ms)
                .fetch_optional(self.db.pool())
                .await
                .map_err(|e| unavailable("acquire", e))
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
            sqlx::query(&self.refresh)
                .bind(lease_ms)
                .bind(key)
                .bind(owner)
                .bind(fence)
                .execute(self.db.pool())
                .await
                .map(|r| r.rows_affected() == 1)
                .map_err(|e| unavailable("refresh", e))
        })
    }

    fn release<'a>(
        &'a self,
        key: &'a str,
        owner: &'a str,
        fence: i64,
    ) -> BoxFuture<'a, Result<bool, LockStoreError>> {
        Box::pin(async move {
            sqlx::query(&self.release)
                .bind(key)
                .bind(owner)
                .bind(fence)
                .execute(self.db.pool())
                .await
                .map(|r| r.rows_affected() == 1)
                .map_err(|e| unavailable("release", e))
        })
    }

    fn expire_now<'a>(&'a self, key: &'a str) -> BoxFuture<'a, Result<(), LockStoreError>> {
        Box::pin(async move {
            sqlx::query("UPDATE fw_lock SET expires_at = clock_timestamp() - interval '1 millisecond' WHERE lock_key = $1")
                .bind(key)
                .execute(self.db.pool())
                .await
                .map(|_| ())
                .map_err(|e| unavailable("expire", e))
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn placeholders_are_numbered_in_order() {
        assert!(numbered(REFRESH_SQL).contains("clock_timestamp() + $1 * interval"));
        assert!(
            numbered(REFRESH_SQL)
                .contains("lock_key = $2 AND owner_token = $3 AND fencing_token = $4")
        );
        assert!(
            numbered(ACQUIRE_SQL)
                .contains("VALUES ($1, $2, nextval('fw_lock_fence'), clock_timestamp() + $3 *")
        );
    }
}
