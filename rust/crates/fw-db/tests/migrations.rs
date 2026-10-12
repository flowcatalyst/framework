//! The migration runner against a real PostgreSQL 18 (database contract section 2).
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use fw_core::{MigrationFile, MigrationSet, ModuleId};
use fw_db::migrations::{self, checksum};
use sqlx::PgPool;

async fn pool() -> PgPool {
    let db = fw_test::postgres()
        .await
        .unwrap()
        .fresh_database()
        .await
        .unwrap();
    PgPool::connect(&db.url).await.unwrap()
}

fn set(module: &'static str, files: &[(&'static str, &'static str)]) -> MigrationSet {
    MigrationSet::new(
        ModuleId::of(module),
        files
            .iter()
            .map(|(n, s)| MigrationFile::embedded(n, s.as_bytes())),
    )
}

async fn history(pool: &PgPool) -> Vec<(String, i32, String, String)> {
    sqlx::query_as("SELECT module, version, description, checksum FROM fw_schema_history ORDER BY module, version")
        .fetch_all(pool)
        .await
        .unwrap()
}

fn messages(e: &fw_core::StartupError) -> Vec<String> {
    e.problems().iter().map(ToString::to_string).collect()
}

#[tokio::test]
async fn applies_sets_in_order_records_history_and_is_idempotent() {
    let pool = pool().await;
    let a = set(
        "alpha",
        &[
            ("V1__create_a.sql", "CREATE TABLE a (x int);"),
            (
                "V2__add_b.sql",
                "ALTER TABLE a ADD COLUMN b text;\nINSERT INTO a VALUES (1, 'x');",
            ),
            ("README.md", "ignored"),
        ],
    );
    let b = set(
        "beta",
        &[("V1__uses_a.sql", "CREATE VIEW v AS SELECT b FROM a;")],
    );
    migrations::apply("main", &pool, &[a.clone(), b.clone()])
        .await
        .unwrap();
    let rows = history(&pool).await;
    assert_eq!(
        rows,
        vec![
            (
                "alpha".into(),
                1,
                "create a".into(),
                checksum(b"CREATE TABLE a (x int);")
            ),
            (
                "alpha".into(),
                2,
                "add b".into(),
                checksum(b"ALTER TABLE a ADD COLUMN b text;\nINSERT INTO a VALUES (1, 'x');")
            ),
            (
                "beta".into(),
                1,
                "uses a".into(),
                checksum(b"CREATE VIEW v AS SELECT b FROM a;")
            ),
        ]
    );
    migrations::apply("main", &pool, &[a, b]).await.unwrap();
    assert_eq!(history(&pool).await.len(), 3);
    let n: i64 = sqlx::query_scalar("SELECT count(*) FROM v")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(n, 1);
}

#[tokio::test]
async fn refuses_changed_missing_out_of_order_and_duplicate_files() {
    let pool = pool().await;
    migrations::apply(
        "main",
        &pool,
        &[set(
            "m",
            &[("V1__a.sql", "SELECT 1;"), ("V3__c.sql", "SELECT 3;")],
        )],
    )
    .await
    .unwrap();
    let e = migrations::apply(
        "main",
        &pool,
        &[set(
            "m",
            &[
                ("V1__a.sql", "SELECT 11;"),
                ("V2__b.sql", "SELECT 2;"),
                ("V4__d.sql", "SELECT 4;"),
                ("V0004__dd.sql", "SELECT 4;"),
            ],
        )],
    )
    .await
    .unwrap_err();
    let got = messages(&e);
    assert_eq!(got.len(), 4, "{got:#?}");
    assert_eq!(
        got[0],
        "module 'm': key 'V0004__dd.sql': has the same version (4) as V4__d.sql"
    );
    assert!(
        got[1].starts_with(
            "module 'm': key 'V1__a.sql': changed after it was applied (applied checksum "
        ),
        "{}",
        got[1]
    );
    assert_eq!(
        got[2],
        "module 'm': key 'V3': version 3 (c) was applied but its file is gone"
    );
    assert_eq!(
        got[3],
        "module 'm': key 'V2__b.sql': was never applied, but a higher version (3) already was; versions must not be added below the applied ones"
    );
    assert_eq!(
        history(&pool).await.len(),
        2,
        "nothing applied when a problem is found"
    );
}

#[tokio::test]
async fn a_failing_file_rolls_back_with_its_history_row() {
    let pool = pool().await;
    let e = migrations::apply(
        "main",
        &pool,
        &[set(
            "m",
            &[
                ("V1__ok.sql", "CREATE TABLE ok (x int);"),
                ("V2__bad.sql", "CREATE TABLE half (x int); SELECT nonsense;"),
            ],
        )],
    )
    .await
    .unwrap_err();
    let got = messages(&e);
    assert!(
        got[0].starts_with("module 'm': key 'V2__bad.sql': migration failed and was rolled back: "),
        "{got:?}"
    );
    assert_eq!(history(&pool).await.len(), 1);
    let half: bool = sqlx::query_scalar("SELECT to_regclass('half') IS NOT NULL")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert!(!half);
}

#[tokio::test]
async fn bad_names_and_versions_are_refused_before_connecting() {
    let pool = pool().await;
    let e = migrations::apply(
        "main",
        &pool,
        &[set(
            "m",
            &[("create.sql", "SELECT 1;"), ("V0__zero.sql", "SELECT 1;")],
        )],
    )
    .await
    .unwrap_err();
    assert_eq!(
        messages(&e),
        vec![
            "module 'm': key 'create.sql': does not match V<version>__<description>.sql",
            "module 'm': key 'V0__zero.sql': version must be a positive integer",
        ]
    );
}

#[tokio::test]
async fn concurrent_runners_apply_each_file_once() {
    let pool = pool().await;
    let s = set(
        "m",
        &[
            (
                "V1__t.sql",
                "CREATE TABLE t (x int); INSERT INTO t VALUES (1);",
            ),
            ("V2__u.sql", "INSERT INTO t VALUES (2);"),
        ],
    );
    let runs: Vec<_> = (0..4)
        .map(|_| {
            let pool = pool.clone();
            let s = s.clone();
            tokio::spawn(async move { migrations::apply("main", &pool, &[s]).await })
        })
        .collect();
    for r in runs {
        r.await.unwrap().unwrap();
    }
    let n: i64 = sqlx::query_scalar("SELECT count(*) FROM t")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(n, 2);
}
