//! The lifecycle: build, started actions, loops, commands and shutdown order (core contract
//! section 1; Rust design sections 1.1 and 1.8).
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::io::Write;
use std::process::ExitCode;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use fw_core::background::LoopOptions;
use fw_core::config::Environment;
use fw_core::health::{CheckResult, Criticality};
use fw_core::{AppBuilder, AppName, Boot, BoxFuture, Command, StartupError};
use tokio::time;

type Log = Arc<Mutex<Vec<String>>>;

fn log() -> Log {
    Arc::new(Mutex::new(Vec::new()))
}

fn push(log: &Log, s: &str) {
    log.lock().unwrap().push(s.to_owned());
}

fn app() -> AppBuilder {
    let dir = tempfile::tempdir().unwrap();
    AppBuilder::new(AppName::of("lifecycle"))
        .config_dir(dir.path().join("none"))
        .environment(Environment::from_pairs([("APP_SHUTDOWN_DRAIN", "1s")]))
}

fn register(boot: &mut Boot, log: &Log, name: &'static str) {
    let l = Arc::clone(log);
    boot.on_started(name, move || async move {
        push(&l, &format!("started {name}"));
        Ok(())
    });
    let l = Arc::clone(log);
    boot.on_shutdown(name, move || async move {
        push(&l, &format!("shutdown {name}"))
    });
}

#[tokio::test(start_paused = true)]
async fn started_actions_then_loops_and_shutdown_stops_loops_first_then_reverse_actions() {
    let log = log();
    let l = Arc::clone(&log);
    let built = app()
        .configure()
        .await
        .unwrap()
        .build(async |boot: &mut Boot| {
            register(boot, &l, "a");
            register(boot, &l, "b");
            let l2 = Arc::clone(&l);
            boot.background().spawn_loop(
                "ticker",
                LoopOptions::every(Duration::from_secs(60)),
                move |tick| {
                    let l2 = Arc::clone(&l2);
                    Box::pin(async move {
                        push(&l2, &format!("run {}", tick.run()));
                        tick.stopped().await;
                        push(&l2, "loop saw stop");
                        Ok(())
                    })
                },
            )?;
            boot.health()
                .add("ok", Criticality::Critical, || async { CheckResult::Up })?;
            Ok(())
        })
        .await
        .unwrap();
    assert!(log.lock().unwrap().is_empty(), "nothing runs during build");
    let running = built.start().await.unwrap();
    time::sleep(Duration::from_millis(10)).await;
    assert!(running.health().check().await.is_ready());
    running.shutdown().await;
    assert_eq!(
        *log.lock().unwrap(),
        vec![
            "started a",
            "started b",
            "run 1",
            "loop saw stop",
            "shutdown b",
            "shutdown a"
        ]
    );
}

#[tokio::test]
async fn a_failing_build_runs_what_it_registered_in_reverse() {
    let log = log();
    let l = Arc::clone(&log);
    let err = app()
        .configure()
        .await
        .unwrap()
        .build(async |boot: &mut Boot| {
            register(boot, &l, "pool");
            register(boot, &l, "cache");
            Err(StartupError::of(
                Some("svc"),
                None,
                "database 'main' does not answer",
            ))
        })
        .await
        .unwrap_err();
    assert_eq!(
        err.to_string(),
        "Application failed to start: 1 problem\n  - module 'svc': database 'main' does not answer"
    );
    assert_eq!(
        *log.lock().unwrap(),
        vec!["shutdown cache", "shutdown pool"]
    );
}

#[tokio::test]
async fn a_failing_started_action_stops_start_up() {
    let log = log();
    let l = Arc::clone(&log);
    let built = app()
        .configure()
        .await
        .unwrap()
        .build(async |boot: &mut Boot| {
            register(boot, &l, "first");
            boot.on_started("broken", || async {
                Err(StartupError::of(None, None, "no"))
            });
            register(boot, &l, "never");
            Ok(())
        })
        .await
        .unwrap();
    assert!(built.start().await.is_err());
    assert_eq!(
        *log.lock().unwrap(),
        vec!["started first", "shutdown never", "shutdown first"]
    );
}

struct Hello;
impl Command for Hello {
    fn name(&self) -> &'static str {
        "hello"
    }
    fn run<'a>(
        &'a self,
        args: Vec<String>,
        out: &'a mut (dyn Write + Send),
    ) -> BoxFuture<'a, ExitCode> {
        Box::pin(async move {
            writeln!(out, "hello {}", args.join(" ")).unwrap();
            ExitCode::from(3)
        })
    }
}

#[tokio::test]
async fn a_command_runs_after_build_without_started_actions_or_loops() {
    let log = log();
    let l = Arc::clone(&log);
    let built = app()
        .configure()
        .await
        .unwrap()
        .build(async |boot: &mut Boot| {
            register(boot, &l, "pool");
            boot.command(Hello);
            Ok(())
        })
        .await
        .unwrap();
    assert!(built.has_command("hello"));
    let mut out = Vec::new();
    let code = built
        .run_command("hello", vec!["world".into()], &mut out)
        .await
        .unwrap();
    assert_eq!(code, ExitCode::from(3));
    assert_eq!(String::from_utf8(out).unwrap(), "hello world\n");
    assert_eq!(*log.lock().unwrap(), vec!["shutdown pool"]);
}

#[tokio::test]
async fn two_claims_of_one_command_fail_start_up() {
    let err = app()
        .configure()
        .await
        .unwrap()
        .build(async |boot: &mut Boot| {
            boot.command(Hello);
            boot.command(Hello);
            Ok(())
        })
        .await
        .unwrap_err();
    assert_eq!(
        err.problems()[0].to_string(),
        "key 'hello': command claimed twice"
    );
}

#[tokio::test]
async fn a_check_in_both_override_lists_fails_start_up() {
    let err = AppBuilder::new(AppName::of("x"))
        .config_dir("/nonexistent")
        .environment(Environment::from_pairs([
            ("APP_HEALTH_CRITICAL", "db"),
            ("APP_HEALTH_INFORMATIONAL", "db,outbox"),
        ]))
        .configure()
        .await
        .unwrap_err();
    assert_eq!(
        err.problems()[0].to_string(),
        "module 'core': key 'app.health.informational': names check 'db', which app.health.critical names too"
    );
}

#[tokio::test]
async fn launch_runs_a_command_and_returns_its_code() {
    let code = fw_core::launch(
        app(),
        ["bin".to_owned(), "hello".to_owned()],
        async |boot: &mut Boot| {
            boot.command(Hello);
            Ok(())
        },
    )
    .await;
    assert_eq!(code, ExitCode::from(3));
}

#[tokio::test]
async fn launch_reports_configuration_problems_with_a_failure_code() {
    let app = AppBuilder::new(AppName::of("x"))
        .config_dir("/nonexistent")
        .environment(Environment::from_pairs([("APP_METRICS_MAX_SERIES", "0")]));
    let code = fw_core::launch(app, ["bin".to_owned()], async |_boot: &mut Boot| Ok(())).await;
    assert_eq!(code, ExitCode::FAILURE);
}
