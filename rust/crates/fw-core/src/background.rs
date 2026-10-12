//! Background loops (background-and-metrics contract section 1).
//!
//! A loop is named work run again and again, owned by the application's lifecycle: registered
//! during build, started after every started action, stopped **first** at shutdown. Each run is its
//! own task, so a panic ends the loop (Java's `Error`) without taking the application down, and an
//! `Err` is logged and the loop continues.

use std::error::Error;
use std::fmt;
use std::mem;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use tokio::task::AbortHandle;
use tokio::time::{self, Instant};
use tokio_util::sync::{CancellationToken, WaitForCancellationFuture};
use tokio_util::task::TaskTracker;

use crate::BoxFuture;
use crate::health::{CheckResult, Criticality, Health};
use crate::metrics::{Counter, Metrics, MetricsError, Timer};
use crate::module::is_lower_code;
use crate::problem::StartupError;

/// What a failed run returns.
pub type LoopError = Box<dyn Error + Send + Sync>;

/// A loop's task: called once per run.
pub type LoopTask =
    Arc<dyn Fn(LoopTick) -> BoxFuture<'static, Result<(), LoopError>> + Send + Sync>;

/// The task of one run, handed to a gate, which calls it (or not) with a fencing token.
pub type GatedRun =
    Box<dyn FnOnce(Option<i64>) -> BoxFuture<'static, Result<(), LoopError>> + Send>;

const MIN_INTERVAL: Duration = Duration::from_millis(10);
const MAX_INTERVAL: Duration = Duration::from_secs(24 * 3600);

/// Decides, before each run, whether it runs (background contract section 1, "Gate").
pub trait LoopGate: Send + Sync {
    /// Runs `run` (passing a fencing token if it has one), or skips it.
    fn admit(&self, run: GatedRun) -> BoxFuture<'_, GateOutcome>;
}

/// What a gate did with one run.
#[derive(Debug)]
pub enum GateOutcome {
    /// The task ran; its result.
    Ran(Result<(), LoopError>),
    /// Not run this time (for example another replica holds the lock); not a failure.
    Skipped(String),
    /// The task ran but lost exclusion while running; counts as a failure.
    Lost,
}

/// How a loop runs.
#[derive(Clone)]
pub struct LoopOptions {
    initial_delay: Duration,
    interval: Duration,
    gate: Option<Arc<dyn LoopGate>>,
    criticality: Criticality,
    down_after_failures: u32,
    stuck_after: Option<Duration>,
}

impl fmt::Debug for LoopOptions {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("LoopOptions")
            .field("initial_delay", &self.initial_delay)
            .field("interval", &self.interval)
            .field("gated", &self.gate.is_some())
            .field("criticality", &self.criticality)
            .field("down_after_failures", &self.down_after_failures)
            .field("stuck_after", &self.stuck_after)
            .finish()
    }
}

impl LoopOptions {
    /// Runs with `interval` between the end of one run and the start of the next (10 ms to 24 h).
    #[must_use]
    pub fn every(interval: Duration) -> Self {
        Self {
            initial_delay: Duration::ZERO,
            interval,
            gate: None,
            criticality: Criticality::Informational,
            down_after_failures: 3,
            stuck_after: None,
        }
    }

    /// Waits before the first run.
    #[must_use]
    pub fn initial_delay(mut self, delay: Duration) -> Self {
        self.initial_delay = delay;
        self
    }

    /// Runs only when the gate admits it (for example a lock gate).
    #[must_use]
    pub fn gated_by(mut self, gate: Arc<dyn LoopGate>) -> Self {
        self.gate = Some(gate);
        self
    }

    /// Makes the loop's health check critical (it is informational by default).
    #[must_use]
    pub fn critical(mut self) -> Self {
        self.criticality = Criticality::Critical;
        self
    }

    /// The health check goes down after this many consecutive failed runs (1 to 1000, default 3).
    #[must_use]
    pub fn down_after_failures(mut self, n: u32) -> Self {
        self.down_after_failures = n;
        self
    }

    /// The health check goes down while a run lasts longer than this.
    #[must_use]
    pub fn stuck_after(mut self, d: Duration) -> Self {
        self.stuck_after = Some(d);
        self
    }
}

/// What one run is told.
#[derive(Debug, Clone)]
pub struct LoopTick {
    name: Arc<str>,
    run: u64,
    fencing: Option<i64>,
    stop: CancellationToken,
}

impl LoopTick {
    /// The loop's name.
    #[must_use]
    pub fn loop_name(&self) -> &str {
        &self.name
    }

    /// The run number: 1, 2, ...
    #[must_use]
    pub fn run(&self) -> u64 {
        self.run
    }

    /// The fencing token, when a lock gate admitted the run.
    #[must_use]
    pub fn fencing_token(&self) -> Option<i64> {
        self.fencing
    }

    /// True once shutdown began; a long task should check it and return.
    #[must_use]
    pub fn stopping(&self) -> bool {
        self.stop.is_cancelled()
    }

    /// Completes when shutdown begins.
    pub fn stopped(&self) -> WaitForCancellationFuture<'_> {
        self.stop.cancelled()
    }
}

struct LoopState {
    name: Arc<str>,
    consecutive_failures: AtomicU32,
    ended: AtomicBool,
    running_since: Mutex<Option<(u64, Instant)>>,
    current: Mutex<Option<AbortHandle>>,
}

struct Spec {
    state: Arc<LoopState>,
    options: LoopOptions,
    task: LoopTask,
}

struct Inner {
    health: Health,
    runs: Counter,
    durations: Timer,
    token: CancellationToken,
    tracker: TaskTracker,
    started: AtomicBool,
    closing: AtomicBool,
    pending: Mutex<Vec<Spec>>,
    loops: Mutex<Vec<Arc<LoopState>>>,
}

/// The application's background loops. Cloning shares them.
#[derive(Clone)]
pub struct Background(Arc<Inner>);

impl fmt::Debug for Background {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let names: Vec<String> = self
            .0
            .loops
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .iter()
            .map(|l| l.name.to_string())
            .collect();
        f.debug_struct("Background")
            .field("loops", &names)
            .finish_non_exhaustive()
    }
}

impl Background {
    /// Background loops that report to `health` and `metrics`.
    ///
    /// # Errors
    ///
    /// When the loop metrics cannot be registered (a different family of the same name exists).
    pub fn new(health: Health, metrics: &Metrics) -> Result<Self, StartupError> {
        let metric_error = |e: MetricsError| StartupError::of(Some("core"), None, e.to_string());
        let runs = metrics
            .counter(
                "fw_background_runs_total",
                "Background loop runs by loop and outcome.",
                &["loop", "outcome"],
            )
            .map_err(metric_error)?;
        let durations = metrics
            .timer(
                "fw_background_run_duration_seconds",
                "Background loop run duration.",
                &["loop"],
            )
            .map_err(metric_error)?;
        Ok(Self(Arc::new(Inner {
            health,
            runs,
            durations,
            token: CancellationToken::new(),
            tracker: TaskTracker::new(),
            started: AtomicBool::new(false),
            closing: AtomicBool::new(false),
            pending: Mutex::new(Vec::new()),
            loops: Mutex::new(Vec::new()),
        })))
    }

    /// Registers a loop. Registered during build, it starts after every started action; registered
    /// later, at once. Its health check is `background:<name>`.
    ///
    /// # Errors
    ///
    /// A name that is not `[a-z][a-z0-9-]*` of at most 100 characters or is taken, an interval
    /// outside 10 ms to 24 h, `down_after_failures` outside 1 to 1000, or registering after
    /// shutdown began.
    pub fn spawn_loop<F>(
        &self,
        name: &str,
        options: LoopOptions,
        task: F,
    ) -> Result<(), StartupError>
    where
        F: Fn(LoopTick) -> BoxFuture<'static, Result<(), LoopError>> + Send + Sync + 'static,
    {
        let problem = |m: String| StartupError::of(None, Some(&format!("background:{name}")), m);
        if self.0.closing.load(Ordering::Acquire) {
            return Err(problem("the application is closing".to_owned()));
        }
        if !is_lower_code(name) || name.len() > 100 {
            return Err(problem(
                "loop name must match [a-z][a-z0-9-]* and be at most 100 characters".to_owned(),
            ));
        }
        if !(MIN_INTERVAL..=MAX_INTERVAL).contains(&options.interval) {
            return Err(problem("interval must be from 10ms to 24h".to_owned()));
        }
        if !(1..=1000).contains(&options.down_after_failures) {
            return Err(problem("down_after_failures must be 1 to 1000".to_owned()));
        }
        let state = Arc::new(LoopState {
            name: Arc::from(name),
            consecutive_failures: AtomicU32::new(0),
            ended: AtomicBool::new(false),
            running_since: Mutex::new(None),
            current: Mutex::new(None),
        });
        {
            let mut loops = self.0.loops.lock().unwrap_or_else(PoisonError::into_inner);
            if loops.iter().any(|l| &*l.name == name) {
                return Err(problem(format!("a loop named '{name}' exists")));
            }
            loops.push(Arc::clone(&state));
        }
        let check_state = Arc::clone(&state);
        let down_after = options.down_after_failures;
        let stuck_after = options.stuck_after;
        self.0.health.add(
            format!("background:{name}"),
            options.criticality,
            move || {
                let r = health_of(&check_state, down_after, stuck_after);
                async move { r }
            },
        )?;
        let spec = Spec {
            state,
            options,
            task: Arc::new(task),
        };
        if self.0.started.load(Ordering::Acquire) {
            self.launch(spec);
        } else {
            self.0
                .pending
                .lock()
                .unwrap_or_else(PoisonError::into_inner)
                .push(spec);
        }
        Ok(())
    }

    /// Starts every loop registered so far (after the started actions).
    pub(crate) fn start(&self) {
        self.0.started.store(true, Ordering::Release);
        let pending = mem::take(
            &mut *self
                .0
                .pending
                .lock()
                .unwrap_or_else(PoisonError::into_inner),
        );
        for spec in pending {
            self.launch(spec);
        }
    }

    fn launch(&self, spec: Spec) {
        let inner = Arc::clone(&self.0);
        self.0.tracker.spawn(run_loop(inner, spec));
    }

    /// Stops every loop: waiting loops end at once, a running task gets `drain` to return, then it
    /// is aborted and logged as an overrun.
    pub(crate) async fn stop(&self, drain: Duration) {
        self.0.closing.store(true, Ordering::Release);
        self.0.token.cancel();
        self.0.tracker.close();
        if time::timeout(drain, self.0.tracker.wait()).await.is_err() {
            for l in self
                .0
                .loops
                .lock()
                .unwrap_or_else(PoisonError::into_inner)
                .iter()
            {
                if let Some(h) = l
                    .current
                    .lock()
                    .unwrap_or_else(PoisonError::into_inner)
                    .take()
                {
                    tracing::warn!(loop_name = %l.name, ?drain, "background loop overran the shutdown drain; aborting its run");
                    h.abort();
                }
            }
            if time::timeout(Duration::from_secs(1), self.0.tracker.wait())
                .await
                .is_err()
            {
                tracing::warn!("background loops did not stop after being aborted; left behind");
            }
        }
    }
}

fn health_of(state: &LoopState, down_after: u32, stuck_after: Option<Duration>) -> CheckResult {
    if state.ended.load(Ordering::Acquire) {
        return CheckResult::Down("the loop ended: a run panicked".to_owned());
    }
    let failures = state.consecutive_failures.load(Ordering::Acquire);
    if failures >= down_after {
        return CheckResult::Down(format!("the last {failures} runs failed"));
    }
    if let Some(limit) = stuck_after
        && let Some((run, since)) = *state
            .running_since
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
        && since.elapsed() > limit
    {
        return CheckResult::Down(format!(
            "run {run} has been running for longer than {limit:?}"
        ));
    }
    CheckResult::Up
}

enum Outcome {
    Success,
    Failure,
    Skipped,
    Lost,
    Panicked,
}

async fn run_loop(inner: Arc<Inner>, spec: Spec) {
    let Spec {
        state,
        options,
        task,
    } = spec;
    let stop = inner.token.clone();
    if !options.initial_delay.is_zero() {
        tokio::select! {
            () = stop.cancelled() => return,
            () = time::sleep(options.initial_delay) => {}
        }
    }
    let mut run: u64 = 0;
    loop {
        if stop.is_cancelled() {
            return;
        }
        run += 1;
        let started = Instant::now();
        *state
            .running_since
            .lock()
            .unwrap_or_else(PoisonError::into_inner) = Some((run, started));
        let tick = LoopTick {
            name: Arc::clone(&state.name),
            run,
            fencing: None,
            stop: stop.clone(),
        };
        let handle = match &options.gate {
            None => {
                let fut = task(tick);
                tokio::spawn(async move { GateOutcome::Ran(fut.await) })
            }
            Some(gate) => {
                let gate = Arc::clone(gate);
                let task = Arc::clone(&task);
                let gated: GatedRun = Box::new(move |fencing| task(LoopTick { fencing, ..tick }));
                tokio::spawn(async move { gate.admit(gated).await })
            }
        };
        *state.current.lock().unwrap_or_else(PoisonError::into_inner) = Some(handle.abort_handle());
        let outcome = match handle.await {
            Ok(GateOutcome::Ran(Ok(()))) => Outcome::Success,
            Ok(GateOutcome::Ran(Err(e))) => {
                tracing::error!(loop_name = %state.name, run, error = %e, "background loop run failed");
                Outcome::Failure
            }
            Ok(GateOutcome::Skipped(reason)) => {
                tracing::debug!(loop_name = %state.name, run, %reason, "background loop run skipped");
                Outcome::Skipped
            }
            Ok(GateOutcome::Lost) => {
                tracing::warn!(loop_name = %state.name, run, "background loop run lost its lock while running");
                Outcome::Lost
            }
            Err(join) if join.is_panic() => {
                tracing::error!(loop_name = %state.name, run, "background loop run panicked; the loop ends");
                Outcome::Panicked
            }
            Err(_) => {
                // Aborted at shutdown after the drain.
                *state
                    .running_since
                    .lock()
                    .unwrap_or_else(PoisonError::into_inner) = None;
                return;
            }
        };
        *state.current.lock().unwrap_or_else(PoisonError::into_inner) = None;
        *state
            .running_since
            .lock()
            .unwrap_or_else(PoisonError::into_inner) = None;
        let label = match outcome {
            Outcome::Success => {
                state.consecutive_failures.store(0, Ordering::Release);
                "success"
            }
            Outcome::Failure | Outcome::Panicked => {
                state.consecutive_failures.fetch_add(1, Ordering::AcqRel);
                "failure"
            }
            Outcome::Lost => {
                state.consecutive_failures.fetch_add(1, Ordering::AcqRel);
                "lost"
            }
            Outcome::Skipped => "skipped",
        };
        if let Ok(s) = inner.runs.labels(&[&state.name, label]) {
            s.inc();
        }
        if !matches!(outcome, Outcome::Skipped)
            && let Ok(s) = inner.durations.labels(&[&state.name])
        {
            s.record(started.elapsed());
        }
        if matches!(outcome, Outcome::Panicked) {
            state.ended.store(true, Ordering::Release);
            return;
        }
        tokio::select! {
            () = stop.cancelled() => return,
            () = time::sleep(options.interval) => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::AtomicU64;

    use super::*;
    use crate::metrics::prometheus_text;

    fn background() -> (Background, Health, Metrics) {
        let health = Health::new(Duration::from_secs(2), Vec::new(), Vec::new());
        let metrics = Metrics::memory(100).unwrap();
        (
            Background::new(health.clone(), &metrics).unwrap(),
            health,
            metrics,
        )
    }

    #[tokio::test(start_paused = true)]
    async fn fixed_delay_failures_and_health() {
        let (bg, health, _m) = background();
        let runs = Arc::new(AtomicU64::new(0));
        let seen = Arc::clone(&runs);
        bg.spawn_loop(
            "sweeper",
            LoopOptions::every(Duration::from_secs(1)).down_after_failures(2),
            move |tick| {
                let seen = Arc::clone(&seen);
                Box::pin(async move {
                    seen.store(tick.run(), Ordering::SeqCst);
                    if tick.run() >= 2 {
                        Err("nope".into())
                    } else {
                        Ok(())
                    }
                })
            },
        )
        .unwrap();
        assert!(
            bg.spawn_loop(
                "sweeper",
                LoopOptions::every(Duration::from_secs(1)),
                |_| Box::pin(async { Ok(()) })
            )
            .is_err()
        );
        assert!(
            bg.spawn_loop("Bad", LoopOptions::every(Duration::from_secs(1)), |_| {
                Box::pin(async { Ok(()) })
            })
            .is_err()
        );
        assert!(
            bg.spawn_loop("fast", LoopOptions::every(Duration::from_millis(1)), |_| {
                Box::pin(async { Ok(()) })
            })
            .is_err()
        );
        time::sleep(Duration::from_millis(100)).await;
        assert_eq!(
            runs.load(Ordering::SeqCst),
            0,
            "loops start only with start()"
        );
        bg.start();
        time::sleep(Duration::from_millis(1500)).await;
        assert_eq!(runs.load(Ordering::SeqCst), 2);
        assert_eq!(
            health.check().await.result("background:sweeper"),
            Some(&CheckResult::Up)
        );
        time::sleep(Duration::from_secs(1)).await;
        assert!(matches!(
            health.check().await.result("background:sweeper"),
            Some(CheckResult::Down(_))
        ));
        bg.stop(Duration::from_secs(1)).await;
    }

    #[tokio::test(start_paused = true)]
    async fn a_panic_ends_the_loop() {
        let (bg, health, _m) = background();
        bg.spawn_loop("boom", LoopOptions::every(Duration::from_secs(1)), |_| {
            Box::pin(async { panic!("boom") })
        })
        .unwrap();
        bg.start();
        time::sleep(Duration::from_millis(10)).await;
        assert_eq!(
            health.check().await.result("background:boom"),
            Some(&CheckResult::Down("the loop ended: a run panicked".into()))
        );
        bg.stop(Duration::from_secs(1)).await;
    }

    #[tokio::test(start_paused = true)]
    async fn stop_aborts_a_run_that_ignores_it_after_the_drain() {
        let (bg, _h, _m) = background();
        let finished = Arc::new(AtomicBool::new(false));
        let f = Arc::clone(&finished);
        bg.spawn_loop(
            "stubborn",
            LoopOptions::every(Duration::from_secs(1)),
            move |_| {
                let f = Arc::clone(&f);
                Box::pin(async move {
                    time::sleep(Duration::from_secs(3600)).await;
                    f.store(true, Ordering::SeqCst);
                    Ok(())
                })
            },
        )
        .unwrap();
        bg.start();
        time::sleep(Duration::from_millis(10)).await;
        bg.stop(Duration::from_secs(2)).await;
        assert!(!finished.load(Ordering::SeqCst));
        assert!(
            bg.spawn_loop("late", LoopOptions::every(Duration::from_secs(1)), |_| {
                Box::pin(async { Ok(()) })
            })
            .is_err()
        );
    }

    struct Skip;
    impl LoopGate for Skip {
        fn admit(&self, _run: GatedRun) -> BoxFuture<'_, GateOutcome> {
            Box::pin(async { GateOutcome::Skipped("held elsewhere".into()) })
        }
    }

    #[tokio::test(start_paused = true)]
    async fn a_skipped_run_is_counted_not_timed() {
        let (bg, _h, metrics) = background();
        bg.spawn_loop(
            "gated",
            LoopOptions::every(Duration::from_secs(1)).gated_by(Arc::new(Skip)),
            |_| Box::pin(async { Ok(()) }),
        )
        .unwrap();
        bg.start();
        time::sleep(Duration::from_millis(10)).await;
        bg.stop(Duration::from_secs(1)).await;
        let text = prometheus_text(&metrics.snapshot());
        assert!(
            text.contains("fw_background_runs_total{loop=\"gated\",outcome=\"skipped\"} 1"),
            "{text}"
        );
        assert!(
            !text.contains("fw_background_run_duration_seconds_count{loop=\"gated\"}"),
            "{text}"
        );
    }
}
