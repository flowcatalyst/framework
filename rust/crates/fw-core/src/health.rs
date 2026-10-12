//! Health checks with criticality (core contract section 1, MA item 7).

use std::collections::BTreeSet;
use std::fmt;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use futures::future::join_all;
use indexmap::IndexMap;
use tokio::time;

use crate::BoxFuture;
use crate::problem::StartupError;

/// Whether a check decides readiness.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Criticality {
    /// Not up means not ready.
    Critical,
    /// Run and reported, never decides readiness.
    Informational,
}

/// The result of one check.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CheckResult {
    /// Healthy.
    Up,
    /// Unhealthy, with a reason safe to serve unauthenticated (never a secret or a stack trace).
    Down(String),
    /// Did not answer within `app.health.timeout`.
    TimedOut,
}

/// A health check. Implemented for closures returning a future of [`CheckResult`].
pub trait HealthCheck: Send + Sync {
    /// Runs the check once.
    fn check(&self) -> BoxFuture<'_, CheckResult>;
}

impl<F, Fut> HealthCheck for F
where
    F: Fn() -> Fut + Send + Sync,
    Fut: Future<Output = CheckResult> + Send + 'static,
{
    fn check(&self) -> BoxFuture<'_, CheckResult> {
        Box::pin(self())
    }
}

struct Registered {
    check: Arc<dyn HealthCheck>,
    criticality: Criticality,
}

struct Inner {
    timeout: Duration,
    forced_critical: BTreeSet<String>,
    forced_informational: BTreeSet<String>,
    checks: Mutex<IndexMap<String, Registered>>,
}

/// The application's health checks. Cloning shares them.
#[derive(Clone)]
pub struct Health(Arc<Inner>);

impl fmt::Debug for Health {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let names: Vec<String> = self
            .0
            .checks
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .keys()
            .cloned()
            .collect();
        f.debug_struct("Health")
            .field("checks", &names)
            .field("timeout", &self.0.timeout)
            .finish()
    }
}

impl Health {
    /// Health with a per-run timeout and the configured criticality overrides.
    #[must_use]
    pub fn new(
        timeout: Duration,
        critical: impl IntoIterator<Item = String>,
        informational: impl IntoIterator<Item = String>,
    ) -> Self {
        Self(Arc::new(Inner {
            timeout,
            forced_critical: critical.into_iter().collect(),
            forced_informational: informational.into_iter().collect(),
            checks: Mutex::new(IndexMap::new()),
        }))
    }

    /// Adds a check. Configuration (`app.health.critical`, `app.health.informational`) wins over
    /// the criticality asked for here.
    ///
    /// # Errors
    ///
    /// When a check of that name exists.
    pub fn add(
        &self,
        name: impl Into<String>,
        criticality: Criticality,
        check: impl HealthCheck + 'static,
    ) -> Result<(), StartupError> {
        let name = name.into();
        let effective = if self.0.forced_critical.contains(&name) {
            Criticality::Critical
        } else if self.0.forced_informational.contains(&name) {
            Criticality::Informational
        } else {
            criticality
        };
        let mut checks = self.0.checks.lock().unwrap_or_else(PoisonError::into_inner);
        if checks.contains_key(&name) {
            return Err(StartupError::of(
                None,
                Some(&name),
                format!("health check '{name}' already exists"),
            ));
        }
        checks.insert(
            name,
            Registered {
                check: Arc::new(check),
                criticality: effective,
            },
        );
        Ok(())
    }

    /// The criticality a check runs with, if it exists.
    #[must_use]
    pub fn criticality(&self, name: &str) -> Option<Criticality> {
        self.0
            .checks
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .get(name)
            .map(|r| r.criticality)
    }

    /// Override names that match no check (logged as a warning after start-up).
    #[must_use]
    pub fn unmatched_overrides(&self) -> Vec<String> {
        let checks = self.0.checks.lock().unwrap_or_else(PoisonError::into_inner);
        self.0
            .forced_critical
            .iter()
            .chain(&self.0.forced_informational)
            .filter(|n| !checks.contains_key(*n))
            .cloned()
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect()
    }

    /// Runs every check concurrently, each bounded by the timeout.
    pub async fn check(&self) -> HealthReport {
        let snapshot: Vec<(String, Arc<dyn HealthCheck>, Criticality)> = self
            .0
            .checks
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .iter()
            .map(|(n, r)| (n.clone(), Arc::clone(&r.check), r.criticality))
            .collect();
        let timeout = self.0.timeout;
        let runs = snapshot
            .into_iter()
            .map(|(name, check, criticality)| async move {
                // Each check runs in its own task, so a panic in one is a Down result, not a crash.
                let task = tokio::spawn(async move { check.check().await });
                let result = match time::timeout(timeout, task).await {
                    Ok(Ok(r)) => r,
                    Ok(Err(join)) => CheckResult::Down(if join.is_panic() {
                        "check panicked".to_owned()
                    } else {
                        "check cancelled".to_owned()
                    }),
                    Err(_) => CheckResult::TimedOut,
                };
                (name, result, criticality)
            });
        HealthReport {
            results: join_all(runs).await,
        }
    }
}

/// The result of one run of every check.
#[derive(Debug, Clone)]
pub struct HealthReport {
    results: Vec<(String, CheckResult, Criticality)>,
}

impl HealthReport {
    /// Every check is up.
    #[must_use]
    pub fn is_up(&self) -> bool {
        self.results.iter().all(|(_, r, _)| *r == CheckResult::Up)
    }

    /// Every critical check is up.
    #[must_use]
    pub fn is_ready(&self) -> bool {
        self.results
            .iter()
            .all(|(_, r, c)| *c == Criticality::Informational || *r == CheckResult::Up)
    }

    /// Each check's name, result and criticality, in registration order.
    #[must_use]
    pub fn results(&self) -> &[(String, CheckResult, Criticality)] {
        &self.results
    }

    /// One check's result.
    #[must_use]
    pub fn result(&self, name: &str) -> Option<&CheckResult> {
        self.results
            .iter()
            .find(|(n, _, _)| n == name)
            .map(|(_, r, _)| r)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test(start_paused = true)]
    async fn criticality_decides_readiness_and_overrides_win() {
        let h = Health::new(
            Duration::from_secs(2),
            vec!["outbox".to_owned()],
            vec!["db".to_owned()],
        );
        h.add("db", Criticality::Critical, || async {
            CheckResult::Down("no".into())
        })
        .unwrap();
        h.add("outbox", Criticality::Informational, || async {
            CheckResult::Up
        })
        .unwrap();
        h.add("slow", Criticality::Informational, || async {
            time::sleep(Duration::from_secs(5)).await;
            CheckResult::Up
        })
        .unwrap();
        assert!(
            h.add("db", Criticality::Critical, || async { CheckResult::Up })
                .is_err()
        );
        assert_eq!(h.criticality("db"), Some(Criticality::Informational));
        assert_eq!(h.criticality("outbox"), Some(Criticality::Critical));
        let r = h.check().await;
        assert!(!r.is_up());
        assert!(r.is_ready());
        assert_eq!(r.result("slow"), Some(&CheckResult::TimedOut));
        assert!(h.unmatched_overrides().is_empty());
    }

    #[tokio::test]
    async fn a_panicking_check_is_down() {
        let h = Health::new(Duration::from_secs(2), Vec::new(), Vec::new());
        h.add("boom", Criticality::Critical, || async { panic!("boom") })
            .unwrap();
        let r = h.check().await;
        assert_eq!(
            r.result("boom"),
            Some(&CheckResult::Down("check panicked".into()))
        );
    }
}
