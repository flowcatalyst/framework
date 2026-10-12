//! `RedisModule` and `RedisConnections` through the lifecycle.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::fs;

use fw_core::config::Environment;
use fw_core::health::CheckResult;
use fw_core::{AppBuilder, AppName, Boot};
use fw_redis::{RedisConnections, RedisModule};

fn app(yaml: &str) -> (AppBuilder, tempfile::TempDir) {
    let dir = tempfile::tempdir().unwrap();
    fs::write(dir.path().join("application.yaml"), yaml).unwrap();
    let app = AppBuilder::new(AppName::of("redis-test"))
        .config_dir(dir.path())
        .environment(Environment::from_pairs::<&str, &str>([]));
    (app, dir)
}

#[tokio::test]
async fn connects_pings_and_reports_health() {
    let server = fw_test::redis().await.unwrap();
    let (mut app, _dir) = app(&format!(
        "redis:\n  instances:\n    default: {{ url: \"{}\", timeout: 2s }}\n",
        server.url()
    ));
    let redis = app.module(RedisModule);
    let built = app
        .configure()
        .await
        .unwrap()
        .build(async |boot: &mut Boot| {
            let conns = RedisConnections::connect(boot, &redis).await?;
            conns.default_connection().unwrap().ping().await.unwrap();
            Ok(())
        })
        .await
        .unwrap();
    let running = built.start().await.unwrap();
    assert_eq!(
        running.health().check().await.result("redis:default"),
        Some(&CheckResult::Up)
    );
    running.shutdown().await;
}

#[tokio::test]
async fn an_instance_that_does_not_answer_fails_start_up_naming_it() {
    let (mut app, _dir) = app(
        "redis:\n  instances:\n    cache: { url: \"redis://127.0.0.1:1/0\", timeout: 250ms }\n",
    );
    let redis = app.module(RedisModule);
    let e = app
        .configure()
        .await
        .unwrap()
        .build(async |boot: &mut Boot| RedisConnections::connect(boot, &redis).await.map(|_| ()))
        .await
        .unwrap_err();
    let line = e.problems()[0].to_string();
    assert!(line.starts_with("module 'redis': key 'redis.instances.cache': cannot connect to Redis instance 'cache': "), "{line}");
}
