//! The lock statements, scripts and migration shared with the Java framework:
//! `conformance/sql/locks/`, `conformance/lua/locks/` (written by the Java `LockConformanceTest`
//! and `LockScriptConformanceTest`) and `conformance/migrations/locks-database.json`.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::fs;

use fw_conformance::{root, text};
use fw_db::migrations;
use fw_locks::{migration_set, postgres, redis_store};

#[test]
fn statements_are_the_shared_bytes() {
    assert_eq!(postgres::ACQUIRE_SQL, text("sql/locks/acquire.sql"));
    assert_eq!(postgres::REFRESH_SQL, text("sql/locks/refresh.sql"));
    assert_eq!(postgres::RELEASE_SQL, text("sql/locks/release.sql"));
}

#[test]
fn scripts_are_the_shared_bytes() {
    assert_eq!(redis_store::ACQUIRE_LUA, text("lua/locks/acquire.lua"));
    assert_eq!(redis_store::REFRESH_LUA, text("lua/locks/refresh.lua"));
    assert_eq!(redis_store::RELEASE_LUA, text("lua/locks/release.lua"));
}

#[test]
fn the_migration_is_the_java_file() {
    let java = fs::read(root().join("../framework-locks-database/src/main/resources/db/migration/locks/V0001__create_lock_table.sql")).unwrap();
    let set = migration_set();
    assert_eq!(set.module().as_str(), "locks-database");
    assert_eq!(set.files()[0].bytes(), java.as_slice());
}

mod common;

#[tokio::test]
async fn a_rust_run_writes_the_java_history_rows() {
    let pool = common::fresh().await;
    migrations::apply("main", &pool, &[migration_set()])
        .await
        .unwrap();
    assert_eq!(
        common::history(&pool).await,
        common::vector_rows("migrations/locks-database.json")
    );
}

#[tokio::test]
async fn a_database_migrated_by_java_is_accepted() {
    common::java_migrated_database(&[(migration_set(), "migrations/locks-database.json")]).await;
}
