//! The `database` driver against the contract suite, on a real PostgreSQL 18 (real time, short
//! leases), plus the shared statements and migration history.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::sync::Arc;

use fw_db::{Db, migrations};
use fw_locks::postgres::PostgresLockStore;
use fw_locks::testing::LockHarness;
use fw_locks::{Lock, migration_set};
use sqlx::PgPool;
use sqlx::postgres::PgPoolOptions;

struct Pg {
    db: Db,
}

impl LockHarness for Pg {
    fn lock(&self) -> Lock {
        Lock::new(Arc::new(PostgresLockStore::new(self.db.clone())))
    }
    fn foreign_lock(&self) -> Lock {
        Lock::new(Arc::new(PostgresLockStore::new(self.db.clone())))
    }
}

async fn harness() -> Pg {
    let test_db = fw_test::postgres()
        .await
        .unwrap()
        .fresh_database()
        .await
        .unwrap();
    let pool = PgPoolOptions::new()
        .max_connections(16)
        .connect(&test_db.url)
        .await
        .unwrap();
    migrations::apply("main", &pool, &[migration_set()])
        .await
        .unwrap();
    Pg {
        db: Db::from_pool("main", pool, true),
    }
}

fw_locks::testing::suite!(harness(), #[tokio::test(flavor = "multi_thread", worker_threads = 4)]);

#[tokio::test]
async fn the_lock_table_is_the_java_one() {
    let h = harness().await;
    let pool: &PgPool = h.db.pool();
    let cols: Vec<(String, String, String)> = sqlx::query_as(
        "SELECT column_name::text, data_type::text, is_nullable::text FROM information_schema.columns WHERE table_name = 'fw_lock' ORDER BY ordinal_position",
    )
    .fetch_all(pool)
    .await
    .unwrap();
    assert_eq!(
        cols,
        vec![
            ("lock_key".into(), "text".into(), "NO".into()),
            ("owner_token".into(), "text".into(), "YES".into()),
            ("fencing_token".into(), "bigint".into(), "NO".into()),
            (
                "expires_at".into(),
                "timestamp with time zone".into(),
                "NO".into()
            ),
        ]
    );
}
