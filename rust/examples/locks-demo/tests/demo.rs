//! The demo against real servers: starts with three instances and shuts down in the contract's
//! order (background loops first, then the shutdown actions in reverse).
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::time::Duration;

use fw_core::config::Environment;
use fw_core::{AppBuilder, AppName, Boot};
use locks_demo::{Journal, build, declare, report};
use tokio::time;

#[tokio::test]
async fn three_instances_report_and_shut_down_in_order() {
    let db = fw_test::postgres()
        .await
        .unwrap()
        .fresh_database()
        .await
        .unwrap();
    let redis = fw_test::redis().await.unwrap();
    // The committed configuration, with the servers overridden by environment variables.
    let mut app = AppBuilder::new(AppName::of("locks-demo"))
        .config_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/config"))
        .environment(Environment::from_pairs([
            ("DATABASE_INSTANCES_MAIN_URL", db.jdbc_url.as_str()),
            ("DATABASE_INSTANCES_MAIN_USERNAME", db.username.as_str()),
            ("DATABASE_INSTANCES_MAIN_PASSWORD", db.password.as_str()),
            ("REDIS_INSTANCES_DEFAULT_URL", redis.url()),
            ("LOCKS_INSTANCES_SHARED_PREFIX", &redis.unique_prefix()),
        ]));
    let declared = declare(&mut app);
    let journal = Journal::default();
    let mut locks = None;
    let built = app
        .configure()
        .await
        .unwrap()
        .build(async |boot: &mut Boot| {
            locks = Some(build(boot, &declared, journal.clone()).await?);
            Ok(())
        })
        .await
        .unwrap();
    let running = built.start().await.unwrap();
    let locks = locks.unwrap();
    let lines = report(&locks, &running.health().check().await).await;
    assert_eq!(lines.len(), 4, "{lines:?}");
    for (line, name) in lines.iter().zip(["local", "main", "shared"]) {
        assert!(
            line.starts_with(&format!("{name}: completed with token ")),
            "{line}"
        );
    }
    assert!(lines[3].starts_with("health: up"), "{}", lines[3]);
    assert!(
        lines[3].contains("database:main")
            && lines[3].contains("redis:default")
            && lines[3].contains("background:heartbeat")
    );
    time::sleep(Duration::from_millis(100)).await;
    running.shutdown().await;

    let entries = journal.entries();
    assert_eq!(entries[0], "built: lock instances local, main, shared");
    assert!(
        entries[1].starts_with("heartbeat run 1 with fencing token "),
        "{entries:?}"
    );
    let first_shutdown = entries
        .iter()
        .position(|e| e.starts_with("shutdown:"))
        .unwrap();
    assert!(
        entries[first_shutdown..]
            .iter()
            .all(|e| !e.starts_with("heartbeat")),
        "loops stop first: {entries:?}"
    );
    assert_eq!(
        &entries[first_shutdown..],
        &[
            "shutdown: locks",
            "shutdown: after the database pools' own close was queued"
        ],
        "shutdown actions run in reverse registration order"
    );
}
