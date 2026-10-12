//! The locks demo as a library, so its test builds the same application `main` runs: three lock
//! instances (database, Redis, memory) from `config/application.yaml`, a background loop gated by
//! the default lock, and a report of one `with_lock` per instance.

use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use fw_core::background::LoopOptions;
use fw_core::health::HealthReport;
use fw_core::{AppBuilder, Boot, StartupError};
use fw_db::{DatabaseDeclared, DatabaseModule, Databases};
use fw_locks::{
    LeaseLength, LockGate, LockKey, LockName, Locks, LocksDeclared, LocksModule, MaxWait, WithLock,
};
use fw_redis::{RedisConnections, RedisDeclared, RedisModule};

const DEMO: LockName = LockName::of("locks-demo");
const HEARTBEAT: LockName = LockName::of("locks-demo-heartbeat");

/// Where the demo writes what happens, in order: the binary prints it, the test reads it.
#[derive(Debug, Clone, Default)]
pub struct Journal(Arc<Mutex<Vec<String>>>);

impl Journal {
    /// Appends an entry.
    pub fn write(&self, entry: impl Into<String>) {
        self.0
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .push(entry.into());
    }

    /// Every entry so far.
    #[must_use]
    pub fn entries(&self) -> Vec<String> {
        self.0
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone()
    }
}

/// The modules' handles.
#[derive(Debug, Clone, Copy)]
pub struct Declared {
    database: DatabaseDeclared,
    redis: RedisDeclared,
    locks: LocksDeclared,
}

/// Adds the modules.
pub fn declare(app: &mut AppBuilder) -> Declared {
    Declared {
        database: app.module(DatabaseModule),
        redis: app.module(RedisModule),
        locks: app.module(LocksModule),
    }
}

/// Builds the services: databases (migrated), Redis, the three lock instances, and a heartbeat
/// loop that runs only on the replica holding the lock.
///
/// # Errors
///
/// Any start-up problem of the parts.
pub async fn build(
    boot: &mut Boot,
    declared: &Declared,
    journal: Journal,
) -> Result<Locks, StartupError> {
    let dbs = Databases::connect(boot, &declared.database).await?;
    dbs.migrate(boot).await?;
    let j = journal.clone();
    boot.on_shutdown("journal: databases", move || async move {
        j.write("shutdown: after the database pools' own close was queued")
    });
    let redis = RedisConnections::connect(boot, &declared.redis).await?;
    let locks = Locks::builder(boot, &declared.locks)
        .databases(&dbs)
        .redis(&redis)
        .build()
        .await?;
    let lease = LeaseLength::new(Duration::from_secs(10))
        .map_err(|e| StartupError::of(Some("locks-demo"), None, e.to_string()))?;
    let gate = LockGate::service(&locks, &HEARTBEAT, lease)?;
    let j = journal.clone();
    boot.background().spawn_loop(
        "heartbeat",
        LoopOptions::every(Duration::from_secs(1)).gated_by(Arc::new(gate)),
        move |tick| {
            let j = j.clone();
            Box::pin(async move {
                j.write(format!(
                    "heartbeat run {} with fencing token {}",
                    tick.run(),
                    tick.fencing_token().unwrap_or(-1)
                ));
                Ok(())
            })
        },
    )?;
    let j = journal.clone();
    boot.on_shutdown("journal: locks", move || async move {
        j.write("shutdown: locks")
    });
    journal.write(format!(
        "built: lock instances {}",
        locks.names().collect::<Vec<_>>().join(", ")
    ));
    Ok(locks)
}

/// Takes `locks-demo` on every instance with `with_lock` and reports the outcome, then health.
pub async fn report(locks: &Locks, health: &HealthReport) -> Vec<String> {
    let mut out = Vec::new();
    let key = LockKey::service(&DEMO);
    for name in locks.names() {
        let Some(lock) = locks.get(name) else {
            continue;
        };
        let (Ok(lease), Ok(wait)) = (
            LeaseLength::new(Duration::from_secs(10)),
            MaxWait::new(Duration::from_secs(5)),
        ) else {
            continue;
        };
        let outcome = match lock
            .with_lock(&key, lease, wait, async |l| {
                format!("token {}", l.fencing_token())
            })
            .await
        {
            Ok(WithLock::Completed(v)) => format!("completed with {v}"),
            Ok(WithLock::NotAcquired) => "not acquired".to_owned(),
            Ok(WithLock::Lost(_)) => "lost while running".to_owned(),
            Err(e) => format!("failed: {e}"),
        };
        out.push(format!("{name}: {outcome}"));
    }
    let names: Vec<&str> = health
        .results()
        .iter()
        .map(|(n, _, _)| n.as_str())
        .collect();
    out.push(format!(
        "health: {} {names:?}",
        if health.is_up() { "up" } else { "DOWN" }
    ));
    out
}
