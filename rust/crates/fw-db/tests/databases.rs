//! `DatabaseModule` and `Databases` through the application lifecycle.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::fs;

use fw_core::config::Environment;
use fw_core::health::CheckResult;
use fw_core::{
    AppBuilder, AppName, Boot, MigrationFile, MigrationSet, Module, ModuleId, StartupError,
};
use fw_db::{DatabaseModule, Databases};

struct Things;
impl Module for Things {
    type Declared = ();
    fn id(&self) -> ModuleId {
        ModuleId::of("things")
    }
    fn declare(self, d: &mut fw_core::Declarations<'_>) {
        d.migrations(MigrationSet::new(
            ModuleId::of("things"),
            [MigrationFile::embedded(
                "V1__things.sql",
                b"CREATE TABLE things (id int);",
            )],
        ));
    }
}

fn config(dir: &tempfile::TempDir, yaml: &str) -> AppBuilder {
    fs::write(dir.path().join("application.yaml"), yaml).unwrap();
    AppBuilder::new(AppName::of("db-test"))
        .config_dir(dir.path())
        .environment(Environment::from_pairs::<&str, &str>([]))
}

#[tokio::test]
async fn connects_migrates_reports_health_and_closes() {
    let db = fw_test::postgres()
        .await
        .unwrap()
        .fresh_database()
        .await
        .unwrap();
    let dir = tempfile::tempdir().unwrap();
    // The Java form of the URL, credentials as separate keys: one file serves both implementations.
    let yaml = format!(
        "database:\n  instances:\n    main:\n      url: {}\n      username: {}\n      password: \"{}\"\n      pool: {{ max: 4, min-idle: 0, connect-timeout: 2s }}\n",
        db.jdbc_url, db.username, db.password
    );
    let mut app = config(&dir, &yaml);
    let database = app.module(DatabaseModule);
    app.module(Things);
    let built = app
        .configure()
        .await
        .unwrap()
        .build(async |boot: &mut Boot| {
            let dbs = Databases::connect(boot, &database).await?;
            dbs.migrate(boot).await?;
            let main = dbs.require_default("test")?;
            assert!(main.table_exists("things").await.unwrap());
            Ok(())
        })
        .await
        .unwrap();
    let running = built.start().await.unwrap();
    let report = running.health().check().await;
    assert_eq!(report.result("database:main"), Some(&CheckResult::Up));
    running.shutdown().await;
}

#[tokio::test]
async fn an_instance_that_does_not_answer_fails_start_up_naming_it() {
    let dir = tempfile::tempdir().unwrap();
    let mut app = config(
        &dir,
        "database:\n  instances:\n    main: { url: \"postgres://nobody@127.0.0.1:1/x\", pool: { connect-timeout: 250ms } }\n",
    );
    let database = app.module(DatabaseModule);
    let e = app
        .configure()
        .await
        .unwrap()
        .build(async |boot: &mut Boot| Databases::connect(boot, &database).await.map(|_| ()))
        .await
        .unwrap_err();
    let line = e.problems()[0].to_string();
    assert!(
        line.starts_with("module 'database': key 'database.instances.main': cannot connect to database instance 'main': "),
        "{line}"
    );
}

#[tokio::test]
async fn unknown_keys_and_a_missing_url_fail_configure() {
    let dir = tempfile::tempdir().unwrap();
    let mut app = config(
        &dir,
        "database:\n  instances:\n    main: { usrname: app, pool: { size: 3 } }\n",
    );
    app.module(DatabaseModule);
    let e: StartupError = app.configure().await.unwrap_err();
    let prefix = format!("{}/", dir.path().display());
    let got: Vec<String> = e
        .problems()
        .iter()
        .map(|p| p.to_string().replace(&prefix, "config/"))
        .collect();
    assert_eq!(
        got,
        vec![
            "module 'database': key 'database.instances.main.url': missing required key; set it in a config file or as environment variable DATABASE_INSTANCES_MAIN_URL",
            "module 'database': key 'database.instances.main.usrname' (from config/application.yaml): unknown key for driver 'postgresql'",
            "module 'database': key 'database.instances.main.pool.size' (from config/application.yaml): unknown key for driver 'postgresql'",
        ]
    );
}

#[tokio::test]
async fn a_table_on_a_non_default_instance_is_checked() {
    let server = fw_test::postgres().await.unwrap();
    let (a, b) = (
        server.fresh_database().await.unwrap(),
        server.fresh_database().await.unwrap(),
    );
    let dir = tempfile::tempdir().unwrap();
    let mut app = config(
        &dir,
        &format!(
            "database:\n  default: a\n  instances:\n    a: {{ url: \"{}\" }}\n    b: {{ url: \"{}\" }}\n",
            a.url, b.url
        ),
    );
    let database = app.module(DatabaseModule);
    let e = app
        .configure()
        .await
        .unwrap()
        .build(async |boot: &mut Boot| {
            let dbs = Databases::connect(boot, &database).await?;
            dbs.get("b")
                .unwrap()
                .require_table("fw_lock", "the database lock driver (instance 'x')")
                .await
        })
        .await
        .unwrap_err();
    assert!(
        e.problems()[0]
            .to_string()
            .contains("needs table 'fw_lock' in database instance 'b', but it does not exist")
    );
}

#[tokio::test]
async fn the_native_url_form_connects_with_credentials_in_the_url() {
    let db = fw_test::postgres()
        .await
        .unwrap()
        .fresh_database()
        .await
        .unwrap();
    let dir = tempfile::tempdir().unwrap();
    // Rust's own form, no separate username/password keys.
    let yaml = format!(
        "database:\n  instances:\n    main:\n      url: \"{}\"\n      pool: {{ max: 2, min-idle: 0, connect-timeout: 2s }}\n",
        db.url
    );
    let mut app = config(&dir, &yaml);
    let database = app.module(DatabaseModule);
    let built = app
        .configure()
        .await
        .unwrap()
        .build(async |boot: &mut Boot| {
            let dbs = Databases::connect(boot, &database).await?;
            dbs.require_default("test")?.ping().await.unwrap();
            Ok(())
        })
        .await
        .unwrap();
    let running = built.start().await.unwrap();
    let report = running.health().check().await;
    assert_eq!(report.result("database:main"), Some(&CheckResult::Up));
    running.shutdown().await;
}

#[tokio::test]
async fn a_url_in_no_accepted_form_fails_start_up_naming_the_forms() {
    let dir = tempfile::tempdir().unwrap();
    let mut app = config(
        &dir,
        "database:\n  instances:\n    main: { url: \"host=localhost dbname=app\" }\n",
    );
    let database = app.module(DatabaseModule);
    let e = app
        .configure()
        .await
        .unwrap()
        .build(async |boot: &mut Boot| Databases::connect(boot, &database).await.map(|_| ()))
        .await
        .unwrap_err();
    let line = e.problems()[0].to_string();
    assert!(
        line.starts_with("module 'database': key 'database.instances.main': url must be a postgres:// or postgresql:// URL, or a jdbc:postgresql:// URL"),
        "{line}"
    );
}
