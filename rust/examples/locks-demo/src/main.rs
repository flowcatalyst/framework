//! `cargo run -p locks-demo` (from `rust/examples/locks-demo`, with PostgreSQL and Redis as in
//! `config/application.yaml`): builds three lock instances, prints one `with_lock` per instance
//! and the health, runs the heartbeat loop until SIGTERM or SIGINT, then shuts down.

use std::env;
use std::process::ExitCode;

use fw_core::{AppBuilder, AppName, Boot, launch};
use locks_demo::{Journal, build, declare, report};

#[tokio::main]
async fn main() -> ExitCode {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::new("info"))
        .init();
    let mut app = AppBuilder::new(AppName::of("locks-demo"));
    let declared = declare(&mut app);
    launch(app, env::args(), async |boot: &mut Boot| {
        let locks = build(boot, &declared, Journal::default()).await?;
        let health = boot.health().clone();
        boot.on_started("report", move || async move {
            for line in report(&locks, &health.check().await).await {
                println!("{line}");
            }
            Ok(())
        });
        Ok(())
    })
    .await
}
