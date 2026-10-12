//! `LocksModule` through the lifecycle: three drivers from configuration, problems, the lock gate.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::fs;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use fw_core::background::LoopOptions;
use fw_core::config::Environment;
use fw_core::{AppBuilder, AppName, Boot};
use fw_db::{DatabaseModule, Databases};
use fw_locks::{
    LeaseLength, LockGate, LockKey, LockName, Locks, LocksModule, MaxWait, MemoryLockStore,
    WithLock,
};
use fw_redis::{RedisConnections, RedisModule};
use tokio::time;

fn app(yaml: &str) -> (AppBuilder, tempfile::TempDir) {
    let dir = tempfile::tempdir().unwrap();
    fs::write(dir.path().join("application.yaml"), yaml).unwrap();
    (
        AppBuilder::new(AppName::of("locks-test"))
            .config_dir(dir.path())
            .environment(Environment::from_pairs::<&str, &str>([])),
        dir,
    )
}

#[tokio::test]
async fn three_drivers_from_configuration() {
    let db = fw_test::postgres()
        .await
        .unwrap()
        .fresh_database()
        .await
        .unwrap();
    let redis = fw_test::redis().await.unwrap();
    let (mut app, _dir) = app(&format!(
        "database:\n  instances:\n    main: {{ url: \"{}\" }}\nredis:\n  instances:\n    default: {{ url: \"{}\" }}\nlocks:\n  default: main\n  instances:\n    main: {{ driver: database }}\n    shared: {{ driver: redis, connection: default, prefix: \"{}\" }}\n    local: {{ driver: memory }}\n",
        db.url,
        redis.url(),
        redis.unique_prefix()
    ));
    let (database, redis_decl, locks_decl) = (
        app.module(DatabaseModule),
        app.module(RedisModule),
        app.module(LocksModule),
    );
    let tokens = Arc::new(Mutex::new(Vec::new()));
    let t = Arc::clone(&tokens);
    app.configure()
        .await
        .unwrap()
        .build(async |boot: &mut Boot| {
            let dbs = Databases::connect(boot, &database).await?;
            dbs.migrate(boot).await?;
            let redis = RedisConnections::connect(boot, &redis_decl).await?;
            let locks = Locks::builder(boot, &locks_decl)
                .databases(&dbs)
                .redis(&redis)
                .build()
                .await?;
            let key = LockKey::service(&LockName::of("demo"));
            for name in ["main", "shared", "local"] {
                let lock = locks.get(name).unwrap();
                let r = lock
                    .with_lock(
                        &key,
                        LeaseLength::new(Duration::from_secs(5)).unwrap(),
                        MaxWait::ZERO,
                        async |l| l.fencing_token(),
                    )
                    .await
                    .unwrap();
                let WithLock::Completed(token) = r else {
                    panic!("{name}: {r:?}")
                };
                t.lock().unwrap().push(token);
            }
            assert!(locks.default_lock().is_some());
            Ok(())
        })
        .await
        .unwrap();
    assert_eq!(tokens.lock().unwrap().len(), 3);
}

#[tokio::test]
async fn missing_connections_and_unknown_names_are_start_up_problems() {
    let (mut app, _dir) = app(
        "locks:\n  instances:\n    a: { driver: database }\n    b: { driver: redis, connection: nope }\n    c: { driver: memory }\n  default: c\n",
    );
    let locks_decl = app.module(LocksModule);
    let e = app
        .configure()
        .await
        .unwrap()
        .build(async |boot: &mut Boot| Locks::builder(boot, &locks_decl).build().await.map(|_| ()))
        .await
        .unwrap_err();
    let lines: Vec<String> = e.problems().iter().map(ToString::to_string).collect();
    assert_eq!(
        lines,
        vec![
            "module 'locks': key 'locks.instances.a': uses driver 'database', but Locks::builder was not given the databases (.databases(&dbs))",
            "module 'locks': key 'locks.instances.b': uses driver 'redis', but Locks::builder was not given the Redis connections (.redis(&redis))",
        ]
    );
}

#[tokio::test(start_paused = true)]
async fn the_lock_gate_runs_one_replica_and_skips_the_other() {
    // Two "replicas" share one store; each runs a loop gated by the same service lock.
    let locks = Locks::single("local", Arc::new(MemoryLockStore::new()));
    let ran = Arc::new(AtomicUsize::new(0));
    let mut runnings = Vec::new();
    for _ in 0..2 {
        let (app, _dir) = app("");
        let locks = locks.clone();
        let ran = Arc::clone(&ran);
        let built = app
            .configure()
            .await
            .unwrap()
            .build(async |boot: &mut Boot| {
                let gate = LockGate::service(
                    &locks,
                    &LockName::of("sweeper"),
                    LeaseLength::new(Duration::from_secs(5)).unwrap(),
                )?;
                boot.background().spawn_loop(
                    "sweeper",
                    LoopOptions::every(Duration::from_secs(60)).gated_by(Arc::new(gate)),
                    move |tick| {
                        let ran = Arc::clone(&ran);
                        Box::pin(async move {
                            assert!(tick.fencing_token().is_some());
                            ran.fetch_add(1, Ordering::SeqCst);
                            time::sleep(Duration::from_secs(1)).await;
                            Ok(())
                        })
                    },
                )?;
                Ok(())
            })
            .await
            .unwrap();
        runnings.push(built.start().await.unwrap());
    }
    time::sleep(Duration::from_millis(500)).await;
    assert_eq!(
        ran.load(Ordering::SeqCst),
        1,
        "the second replica's run was skipped"
    );
    for r in runnings {
        r.shutdown().await;
    }
}
