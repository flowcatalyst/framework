//! Helpers shared by the conformance tests that need a database.

use std::str;

use fw_core::MigrationSet;
use fw_db::migrations::{self, checksum};
use sqlx::PgPool;

use fw_conformance::json;

/// A history row: module, version, description, checksum.
pub(crate) type Row = (String, i32, String, String);

/// The rows of a `conformance/migrations/<module>.json` vector.
pub(crate) fn vector_rows(file: &str) -> Vec<Row> {
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

/// Every history row, ordered.
pub(crate) async fn history(pool: &PgPool) -> Vec<Row> {
    sqlx::query_as("SELECT module, version, description, checksum FROM fw_schema_history ORDER BY module, version")
        .fetch_all(pool)
        .await
        .unwrap()
}

/// A connection pool on a fresh, empty database.
pub(crate) async fn fresh() -> PgPool {
    let db = fw_test::postgres()
        .await
        .unwrap()
        .fresh_database()
        .await
        .unwrap();
    PgPool::connect(&db.url).await.unwrap()
}

/// Builds the state the Java runner leaves for these sets (its history table, each file applied,
/// the vector's rows), then runs the Rust runner, which must accept it and apply nothing.
pub(crate) async fn java_migrated_database(sets: &[(MigrationSet, &str)]) -> PgPool {
    let pool = fresh().await;
    sqlx::query(migrations::CREATE_HISTORY)
        .execute(&pool)
        .await
        .unwrap();
    let mut expected = Vec::new();
    for (set, file) in sets {
        let rows = vector_rows(file);
        assert_eq!(rows.len(), set.files().len(), "{file}");
        for (f, (module, version, description, sum)) in set.files().iter().zip(&rows) {
            assert_eq!(
                *sum,
                checksum(f.bytes()),
                "{file}: the Rust copy of {} is the Java file",
                f.name()
            );
            sqlx::raw_sql(str::from_utf8(f.bytes()).unwrap())
                .execute(&pool)
                .await
                .unwrap();
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
        expected.extend(rows);
    }
    let all: Vec<MigrationSet> = sets.iter().map(|(s, _)| s.clone()).collect();
    migrations::apply("main", &pool, &all).await.unwrap();
    expected.sort();
    assert_eq!(history(&pool).await, expected, "nothing applied again");
    pool
}
