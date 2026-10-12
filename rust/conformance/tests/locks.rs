//! The lock statements, scripts and migration shared with the Java framework:
//! `conformance/sql/locks/`, `conformance/lua/locks/` (written by the Java `LockConformanceTest`
//! and `LockScriptConformanceTest`) and `conformance/migrations/locks-database.json`.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::{fs, str};

use fw_conformance::{json, root, text};
use fw_db::migrations::{self, checksum};
use fw_locks::{migration_set, postgres, redis_store};
use sqlx::PgPool;

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

type Row = (String, i32, String, String);

fn vector_rows(file: &str) -> Vec<Row> {
    let doc = json(file);
    let module = doc["module"].as_str().unwrap().to_owned();
    doc["rows"]
        .as_array()
        .unwrap()
        .iter()
        .map(|r| {
            (
                module.clone(),
                i32::try_from(r["version"].as_i64().unwrap()).unwrap(),
                r["description"].as_str().unwrap().to_owned(),
                r["checksum"].as_str().unwrap().to_owned(),
            )
        })
        .collect()
}

async fn history(pool: &PgPool) -> Vec<Row> {
    sqlx::query_as("SELECT module, version, description, checksum FROM fw_schema_history ORDER BY module, version")
        .fetch_all(pool)
        .await
        .unwrap()
}

async fn fresh() -> PgPool {
    let db = fw_test::postgres()
        .await
        .unwrap()
        .fresh_database()
        .await
        .unwrap();
    PgPool::connect(&db.url).await.unwrap()
}

#[tokio::test]
async fn a_rust_run_writes_the_java_history_rows() {
    let pool = fresh().await;
    migrations::apply("main", &pool, &[migration_set()])
        .await
        .unwrap();
    assert_eq!(
        history(&pool).await,
        vector_rows("migrations/locks-database.json")
    );
}

#[tokio::test]
async fn a_database_migrated_by_java_is_accepted() {
    let pool = fresh().await;
    // The state the Java runner leaves: its history table, the file applied, its rows.
    sqlx::query(migrations::CREATE_HISTORY)
        .execute(&pool)
        .await
        .unwrap();
    let set = migration_set();
    sqlx::raw_sql(str::from_utf8(set.files()[0].bytes()).unwrap())
        .execute(&pool)
        .await
        .unwrap();
    let rows = vector_rows("migrations/locks-database.json");
    for (module, version, description, sum) in &rows {
        sqlx::query(
            "INSERT INTO fw_schema_history VALUES ($1, $2, $3, $4, now() - interval '1 day')",
        )
        .bind(module)
        .bind(version)
        .bind(description)
        .bind(sum)
        .execute(&pool)
        .await
        .unwrap();
    }
    assert_eq!(rows[0].3, checksum(set.files()[0].bytes()));
    migrations::apply("main", &pool, &[set]).await.unwrap();
    assert_eq!(history(&pool).await, rows, "nothing applied again");
}
