//! The `redis` driver against the contract suite, on a real Redis (real time, short leases).
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::sync::Arc;
use std::time::Duration;

use fw_locks::redis_store::RedisLockStore;
use fw_locks::testing::LockHarness;
use fw_locks::{Acquire, LeaseLength, Lock, LockKey, LockName};
use fw_redis::RedisConnection;
use redis::aio::ConnectionManager;

struct Redis {
    store: Arc<RedisLockStore>,
    foreign: Arc<RedisLockStore>,
}

impl LockHarness for Redis {
    fn lock(&self) -> Lock {
        Lock::new(self.store.clone())
    }
    fn foreign_lock(&self) -> Lock {
        Lock::new(self.foreign.clone())
    }
}

async fn connection() -> (RedisConnection, String) {
    let server = fw_test::redis().await.unwrap();
    let manager = ConnectionManager::new(redis::Client::open(server.url()).unwrap())
        .await
        .unwrap();
    (
        RedisConnection::from_manager("default", manager),
        server.unique_prefix(),
    )
}

async fn harness() -> Redis {
    let (conn, prefix) = connection().await;
    Redis {
        store: Arc::new(RedisLockStore::new(conn.clone(), &prefix).await.unwrap()),
        foreign: Arc::new(RedisLockStore::new(conn, &prefix).await.unwrap()),
    }
}

fw_locks::testing::suite!(harness(), #[tokio::test(flavor = "multi_thread", worker_threads = 4)]);

#[tokio::test]
async fn a_flushed_script_is_loaded_again() {
    let (conn, prefix) = connection().await;
    let store = RedisLockStore::new(conn.clone(), &prefix).await.unwrap();
    let lock = Lock::new(Arc::new(store));
    let mut c = conn.manager();
    let _: () = redis::cmd("SCRIPT")
        .arg("FLUSH")
        .query_async(&mut c)
        .await
        .unwrap();
    let key = LockKey::service(&LockName::of("after-flush"));
    let got = lock
        .try_acquire(&key, LeaseLength::new(Duration::from_secs(1)).unwrap())
        .await
        .unwrap();
    assert!(matches!(got, Acquire::Acquired(_)));
}

#[tokio::test]
async fn keys_and_values_are_the_java_ones() {
    let (conn, prefix) = connection().await;
    let lock = Lock::new(Arc::new(
        RedisLockStore::new(conn.clone(), &prefix).await.unwrap(),
    ));
    let key = LockKey::service(&LockName::of("shape"));
    let Acquire::Acquired(lease) = lock
        .try_acquire(&key, LeaseLength::new(Duration::from_secs(5)).unwrap())
        .await
        .unwrap()
    else {
        panic!("busy")
    };
    let mut c = conn.manager();
    let value: String = redis::cmd("GET")
        .arg(format!("{prefix}svc:shape"))
        .query_async(&mut c)
        .await
        .unwrap();
    assert_eq!(
        value,
        format!("{}:{}", lease.owner_token(), lease.fencing_token())
    );
    let counter: i64 = redis::cmd("GET")
        .arg(format!("{prefix}__fence"))
        .query_async(&mut c)
        .await
        .unwrap();
    assert_eq!(counter, lease.fencing_token());
    let ttl: i64 = redis::cmd("PTTL")
        .arg(format!("{prefix}__fence"))
        .query_async(&mut c)
        .await
        .unwrap();
    assert_eq!(ttl, -1, "the counter never expires");
}
