//! The application lifecycle (core contract section 1; Rust design section 1.1).
//!
//! declare ([`AppBuilder::module`]) → configure ([`AppBuilder::configure`]: every config problem at
//! once) → build ([`Configured::build`]: the application's own function, given a [`Boot`]) → a
//! command ([`Built::run_command`]) or start ([`Built::start`]: started actions, then background
//! loops) → run → shutdown ([`Running::shutdown`]: loops first, then the shutdown actions in
//! reverse). [`launch`] does all of it for a binary. Each phase is its own type, so a setting
//! cannot be declared after configuration and nothing runs before every part is built.

use std::collections::HashSet;
use std::fmt;
use std::io::{self, Write};
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;
use std::time::Duration;
use std::{future, mem};

use tokio::time;
use tokio_util::sync::CancellationToken;

use crate::BoxFuture;
use crate::background::Background;
use crate::clock::{Clock, SystemClock};
use crate::config::{self, Config, ConfigEnum, Environment, Key, Present, Setting, SettingHandle};
use crate::health::Health;
use crate::ids::Ids;
use crate::metrics::{MAX_SERIES_LIMIT, Metrics};
use crate::module::{DeclState, Declarations, MigrationSet, Module, ModuleId, is_lower_code};
use crate::problem::{Problem, StartupError};

const CORE: ModuleId = ModuleId::of("core");

/// The application's name, `[a-z][a-z0-9-]*`, checked during constant evaluation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AppName(&'static str);

impl AppName {
    /// A name.
    ///
    /// # Panics
    ///
    /// When `text` does not match `[a-z][a-z0-9-]*`; in a `const` that is a compile error.
    #[must_use]
    pub const fn of(text: &'static str) -> Self {
        assert!(
            is_lower_code(text),
            "application name must match [a-z][a-z0-9-]*"
        );
        Self(text)
    }

    /// The name.
    #[must_use]
    pub const fn as_str(&self) -> &'static str {
        self.0
    }
}

/// The metrics driver (`app.metrics.driver`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MetricsDriver {
    /// In-process, readable.
    Memory,
    /// Records nothing.
    Noop,
}

impl ConfigEnum for MetricsDriver {
    const VALUES: &'static [Self] = &[Self::Memory, Self::Noop];
    fn name(self) -> &'static str {
        match self {
            Self::Memory => "MEMORY",
            Self::Noop => "NOOP",
        }
    }
}

/// The framework's own settings, declared by every application under module `core`.
pub mod settings {
    use super::{Duration, Key, MetricsDriver, Present, Setting};

    /// Time each shutdown step may take before it is logged as overrun.
    pub const SHUTDOWN_DRAIN: Setting<Duration, Present> = Setting::new(
        Key::of("app.shutdown.drain"),
        "Time each shutdown step may take before it is logged as overrun.",
    )
    .default(Duration::from_secs(10));
    /// Time each health check may take before it reports `TimedOut`.
    pub const HEALTH_TIMEOUT: Setting<Duration, Present> = Setting::new(
        Key::of("app.health.timeout"),
        "Time each health check may take before it reports TimedOut.",
    )
    .default(Duration::from_secs(2));
    /// Checks that decide readiness, whatever their module asked for.
    pub const HEALTH_CRITICAL: Setting<Vec<String>, Present> = Setting::new(
        Key::of("app.health.critical"),
        "Names of health checks that decide readiness, whatever their module asked for.",
    )
    .default_list(&[]);
    /// Checks that are reported but never make the application not ready.
    pub const HEALTH_INFORMATIONAL: Setting<Vec<String>, Present> = Setting::new(
        Key::of("app.health.informational"),
        "Names of health checks that are reported but never make the application not ready.",
    )
    .default_list(&[]);
    /// `memory` (readable) or `noop`.
    pub const METRICS_DRIVER: Setting<MetricsDriver, Present> = Setting::new(
        Key::of("app.metrics.driver"),
        "Metrics driver: memory (readable, Prometheus endpoint) or noop.",
    )
    .default(MetricsDriver::Memory);
    /// Most series one metric may hold (1 to 100000).
    pub const METRICS_MAX_SERIES: Setting<i32, Present> = Setting::new(
        Key::of("app.metrics.max-series"),
        "Most series one metric may hold (1 to 100000); further label combinations are dropped.",
    )
    .default(1000);
}

#[derive(Clone, Copy)]
struct CoreHandles {
    drain: SettingHandle<Duration, Present>,
    health_timeout: SettingHandle<Duration, Present>,
    critical: SettingHandle<Vec<String>, Present>,
    informational: SettingHandle<Vec<String>, Present>,
    metrics_driver: SettingHandle<MetricsDriver, Present>,
    max_series: SettingHandle<i32, Present>,
}

/// The declaring phase: modules declare settings, instance sections and migrations.
pub struct AppBuilder {
    name: AppName,
    environment: Environment,
    clock: Arc<dyn Clock>,
    config_dir: Option<PathBuf>,
    ids_seed: Option<u64>,
    decl: DeclState,
    core: CoreHandles,
}

impl fmt::Debug for AppBuilder {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("AppBuilder")
            .field("name", &self.name)
            .field("modules", &self.decl.modules)
            .finish_non_exhaustive()
    }
}

impl AppBuilder {
    /// A new application. Reads the process environment and the system clock unless told otherwise.
    #[must_use]
    pub fn new(name: AppName) -> Self {
        let mut decl = DeclState::default();
        decl.modules.push(CORE);
        let core = {
            let mut d = Declarations::new(CORE, &mut decl);
            CoreHandles {
                drain: d.setting(settings::SHUTDOWN_DRAIN),
                health_timeout: d.setting(settings::HEALTH_TIMEOUT),
                critical: d.setting(settings::HEALTH_CRITICAL),
                informational: d.setting(settings::HEALTH_INFORMATIONAL),
                metrics_driver: d.setting(settings::METRICS_DRIVER),
                max_series: d.setting(settings::METRICS_MAX_SERIES),
            }
        };
        Self {
            name,
            environment: Environment::process(),
            clock: Arc::new(SystemClock),
            config_dir: None,
            ids_seed: None,
            decl,
            core,
        }
    }

    /// The environment variables to read (tests: a fixed map).
    #[must_use]
    pub fn environment(mut self, environment: Environment) -> Self {
        self.environment = environment;
        self
    }

    /// The wall clock.
    #[must_use]
    pub fn clock(mut self, clock: Arc<dyn Clock>) -> Self {
        self.clock = clock;
        self
    }

    /// The configuration directory (default `config/` under the working directory).
    #[must_use]
    pub fn config_dir(mut self, dir: impl Into<PathBuf>) -> Self {
        self.config_dir = Some(dir.into());
        self
    }

    /// Seeds the id generator's random bits, so a test with a fixed clock gets the same ids.
    #[must_use]
    pub fn seeded_ids(mut self, seed: u64) -> Self {
        self.ids_seed = Some(seed);
        self
    }

    /// Adds a module: it declares what it needs and returns its typed handles. A second module with
    /// the same id fails start-up.
    pub fn module<M: Module>(&mut self, module: M) -> M::Declared {
        let id = module.id();
        if self.decl.modules.contains(&id) {
            self.decl
                .problems
                .push(Problem::new(Some(id.as_str()), None, "duplicate module id"));
        } else {
            self.decl.modules.push(id);
        }
        let mut d = Declarations::new(id, &mut self.decl);
        module.declare(&mut d)
    }

    /// Loads and checks the configuration. Every problem is reported at once.
    ///
    /// # Errors
    ///
    /// Every configuration problem: malformed, missing, unknown or duplicate keys, environment
    /// collisions, instance sections, duplicate modules.
    #[expect(
        clippy::unused_async,
        reason = "async from the start: external config stores (M5) read over the network"
    )]
    pub async fn configure(self) -> Result<Configured, StartupError> {
        let Self {
            name,
            environment,
            clock,
            config_dir,
            ids_seed,
            mut decl,
            core,
        } = self;
        let dir = config_dir.unwrap_or_else(|| PathBuf::from("config"));
        let mut problems = mem::take(&mut decl.problems);
        let config = match config::load(&dir, &environment, &decl.settings, &decl.sections) {
            Ok(c) => Some(c),
            Err(p) => {
                problems.extend(p);
                None
            }
        };
        let Some(config) = config else {
            return Err(StartupError::new(problems));
        };
        let critical = config.get(core.critical);
        let informational = config.get(core.informational);
        for name in &informational {
            if critical.contains(name) {
                problems.push(Problem::new(
                    Some(CORE.as_str()),
                    Some("app.health.informational"),
                    format!("names check '{name}', which app.health.critical names too"),
                ));
            }
        }
        let max_series = config.get(core.max_series);
        let metrics = match usize::try_from(max_series)
            .ok()
            .filter(|n| (1..=MAX_SERIES_LIMIT).contains(n))
        {
            None => {
                problems.push(Problem::new(
                    Some(CORE.as_str()),
                    Some("app.metrics.max-series"),
                    "must be 1 to 100000",
                ));
                None
            }
            Some(n) => match config.get(core.metrics_driver) {
                MetricsDriver::Memory => Metrics::memory(n)
                    .map_err(|e| problems.push(Problem::new(Some("core"), None, e.to_string())))
                    .ok(),
                MetricsDriver::Noop => Some(Metrics::noop()),
            },
        };
        let health = Health::new(config.get(core.health_timeout), critical, informational);
        let background = match metrics.as_ref().map(|m| Background::new(health.clone(), m)) {
            Some(Ok(b)) => Some(b),
            Some(Err(e)) => {
                problems.extend(e.problems().iter().cloned());
                None
            }
            None => None,
        };
        let (Some(metrics), Some(background), true) = (metrics, background, problems.is_empty())
        else {
            return Err(StartupError::new(problems));
        };
        let ids = match ids_seed {
            Some(seed) => Ids::seeded(Arc::clone(&clock), seed),
            None => Ids::new(Arc::clone(&clock)),
        };
        Ok(Configured {
            name,
            drain: config.get(core.drain),
            config: Arc::new(config),
            clock,
            ids,
            metrics,
            health,
            background,
            migrations: decl.migrations,
        })
    }
}

/// A started action, run after every part is built.
type StartedAction = Box<dyn FnOnce() -> BoxFuture<'static, Result<(), StartupError>> + Send>;
/// A shutdown action, run in reverse registration order.
type ShutdownAction = Box<dyn FnOnce() -> BoxFuture<'static, ()> + Send>;

/// The configured application: its configuration and the framework's own services, before build.
pub struct Configured {
    name: AppName,
    drain: Duration,
    config: Arc<Config>,
    clock: Arc<dyn Clock>,
    ids: Ids,
    metrics: Metrics,
    health: Health,
    background: Background,
    migrations: Vec<MigrationSet>,
}

impl fmt::Debug for Configured {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Configured")
            .field("name", &self.name)
            .field("config", &self.config)
            .finish_non_exhaustive()
    }
}

impl Configured {
    /// The configuration.
    #[must_use]
    pub fn config(&self) -> &Config {
        &self.config
    }

    /// Runs the application's build function. If it fails, the shutdown actions it registered so
    /// far run (in reverse) before the error is returned.
    ///
    /// # Errors
    ///
    /// The build function's error, or two commands claiming one name.
    pub async fn build<F>(self, build: F) -> Result<Built, StartupError>
    where
        F: AsyncFnOnce(&mut Boot) -> Result<(), StartupError>,
    {
        let mut boot = Boot {
            name: self.name,
            config: self.config,
            clock: self.clock,
            ids: self.ids,
            metrics: self.metrics,
            health: self.health,
            background: self.background,
            migrations: self.migrations,
            started: Vec::new(),
            shutdown: Vec::new(),
            commands: Vec::new(),
            token: CancellationToken::new(),
            drain: self.drain,
        };
        let result = build(&mut boot).await;
        let mut problems = Vec::new();
        if let Err(e) = result {
            problems.extend(e.problems().iter().cloned());
        }
        let mut names = HashSet::new();
        for c in &boot.commands {
            let name = c.name();
            if !is_lower_code(name) {
                problems.push(Problem::new(
                    None,
                    Some(name),
                    "command name must match [a-z][a-z0-9-]*",
                ));
            } else if !names.insert(name) {
                problems.push(Problem::new(None, Some(name), "command claimed twice"));
            }
        }
        if !problems.is_empty() {
            run_shutdown(mem::take(&mut boot.shutdown), boot.drain).await;
            return Err(StartupError::new(problems));
        }
        Ok(Built { boot })
    }
}

/// What the build function builds with: configuration, the framework's services, and the hooks for
/// started actions, shutdown actions, background loops, health checks and commands. Nothing it
/// registers runs until every part is built.
pub struct Boot {
    name: AppName,
    config: Arc<Config>,
    clock: Arc<dyn Clock>,
    ids: Ids,
    metrics: Metrics,
    health: Health,
    background: Background,
    migrations: Vec<MigrationSet>,
    started: Vec<(String, StartedAction)>,
    shutdown: Vec<(String, ShutdownAction)>,
    commands: Vec<Arc<dyn Command>>,
    token: CancellationToken,
    drain: Duration,
}

impl fmt::Debug for Boot {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Boot")
            .field("name", &self.name)
            .finish_non_exhaustive()
    }
}

impl Boot {
    /// The application's name.
    #[must_use]
    pub fn name(&self) -> AppName {
        self.name
    }

    /// The configuration; read declared settings with the handles their modules returned.
    #[must_use]
    pub fn config(&self) -> &Config {
        &self.config
    }

    /// The wall clock.
    #[must_use]
    pub fn clock(&self) -> Arc<dyn Clock> {
        Arc::clone(&self.clock)
    }

    /// The id generator (one per application).
    #[must_use]
    pub fn ids(&self) -> &Ids {
        &self.ids
    }

    /// The metrics registry.
    #[must_use]
    pub fn metrics(&self) -> &Metrics {
        &self.metrics
    }

    /// The health checks.
    #[must_use]
    pub fn health(&self) -> &Health {
        &self.health
    }

    /// The background loops; registered loops start after every started action.
    #[must_use]
    pub fn background(&self) -> &Background {
        &self.background
    }

    /// The migration sets every module contributed, in declaration order.
    #[must_use]
    pub fn migration_sets(&self) -> &[MigrationSet] {
        &self.migrations
    }

    /// `app.shutdown.drain`.
    #[must_use]
    pub fn drain(&self) -> Duration {
        self.drain
    }

    /// A token cancelled when shutdown begins (a child of the application's root token).
    #[must_use]
    pub fn shutdown_token(&self) -> CancellationToken {
        self.token.child_token()
    }

    /// Work that needs every part built (core contract, `started`). Runs in registration order on
    /// start, never for a command. An error fails start-up.
    pub fn on_started<F, Fut>(&mut self, name: impl Into<String>, action: F)
    where
        F: FnOnce() -> Fut + Send + 'static,
        Fut: Future<Output = Result<(), StartupError>> + Send + 'static,
    {
        self.started
            .push((name.into(), Box::new(move || Box::pin(action()))));
    }

    /// Work to do at shutdown (close a pool, flush a buffer). Runs in reverse registration order,
    /// each bounded by `app.shutdown.drain`; also after a command, and when a later build step
    /// fails.
    pub fn on_shutdown<F, Fut>(&mut self, name: impl Into<String>, action: F)
    where
        F: FnOnce() -> Fut + Send + 'static,
        Fut: Future<Output = ()> + Send + 'static,
    {
        self.shutdown
            .push((name.into(), Box::new(move || Box::pin(action()))));
    }

    /// Claims a command name: `my-app <name> args...` runs it after build instead of starting.
    pub fn command(&mut self, command: impl Command + 'static) {
        self.commands.push(Arc::new(command));
    }
}

/// A command run by [`launch`] instead of starting the application (core contract section 5).
pub trait Command: Send + Sync {
    /// The name claimed as the first argument, `[a-z][a-z0-9-]*`.
    fn name(&self) -> &'static str;

    /// Runs with the remaining arguments, writing to `out`.
    fn run<'a>(
        &'a self,
        args: Vec<String>,
        out: &'a mut (dyn Write + Send),
    ) -> BoxFuture<'a, ExitCode>;
}

/// The built application: everything constructed, nothing started.
pub struct Built {
    boot: Boot,
}

impl fmt::Debug for Built {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Built")
            .field("name", &self.boot.name)
            .finish_non_exhaustive()
    }
}

impl Built {
    /// Whether a command of that name was claimed.
    #[must_use]
    pub fn has_command(&self, name: &str) -> bool {
        self.boot.commands.iter().any(|c| c.name() == name)
    }

    /// Runs a claimed command (no started actions, no loops), then the shutdown actions. `None`
    /// when no command has that name.
    pub async fn run_command(
        self,
        name: &str,
        args: Vec<String>,
        out: &mut (dyn Write + Send),
    ) -> Option<ExitCode> {
        let command = self
            .boot
            .commands
            .iter()
            .find(|c| c.name() == name)
            .cloned()?;
        let code = command.run(args, out).await;
        run_shutdown(self.boot.shutdown, self.boot.drain).await;
        Some(code)
    }

    /// Starts: the started actions in order, then the background loops. A failing started action
    /// stops start-up; the shutdown actions then run.
    ///
    /// # Errors
    ///
    /// The first failing started action's problems.
    pub async fn start(self) -> Result<Running, StartupError> {
        let Boot {
            health,
            background,
            started,
            shutdown,
            token,
            drain,
            name,
            ..
        } = self.boot;
        for (action_name, action) in started {
            if let Err(e) = action().await {
                tracing::error!(action = %action_name, "started action failed");
                background.stop(drain).await;
                run_shutdown(shutdown, drain).await;
                return Err(e);
            }
        }
        background.start();
        for unmatched in health.unmatched_overrides() {
            tracing::warn!(check = %unmatched, "app.health.critical/informational names a check that does not exist");
        }
        tracing::info!(app = name.as_str(), "started");
        Ok(Running {
            health,
            background,
            shutdown,
            token,
            drain,
        })
    }
}

/// The running application.
pub struct Running {
    health: Health,
    background: Background,
    shutdown: Vec<(String, ShutdownAction)>,
    token: CancellationToken,
    drain: Duration,
}

impl fmt::Debug for Running {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Running").finish_non_exhaustive()
    }
}

/// Asks a running application to shut down (cloneable; from a test or another task).
#[derive(Debug, Clone)]
pub struct ShutdownHandle(CancellationToken);

impl ShutdownHandle {
    /// Requests shutdown.
    pub fn shutdown(&self) {
        self.0.cancel();
    }
}

impl Running {
    /// The health checks.
    #[must_use]
    pub fn health(&self) -> &Health {
        &self.health
    }

    /// A handle that requests shutdown.
    #[must_use]
    pub fn shutdown_handle(&self) -> ShutdownHandle {
        ShutdownHandle(self.token.clone())
    }

    /// Whether shutdown has been requested.
    #[must_use]
    pub fn is_shutting_down(&self) -> bool {
        self.token.is_cancelled()
    }

    /// Waits for SIGTERM, SIGINT or a [`ShutdownHandle`].
    pub async fn wait_for_shutdown(&self) {
        tokio::select! {
            () = self.token.cancelled() => {}
            () = signal() => {}
        }
    }

    /// Shuts down: background loops first (each running task gets `app.shutdown.drain`), then the
    /// shutdown actions in reverse registration order, each bounded by the drain.
    pub async fn shutdown(self) {
        self.token.cancel();
        self.background.stop(self.drain).await;
        run_shutdown(self.shutdown, self.drain).await;
    }
}

async fn run_shutdown(actions: Vec<(String, ShutdownAction)>, drain: Duration) {
    for (name, action) in actions.into_iter().rev() {
        if time::timeout(drain, action()).await.is_err() {
            tracing::warn!(action = %name, ?drain, "shutdown action overran app.shutdown.drain; continuing");
        }
    }
}

#[cfg(unix)]
async fn signal() {
    use tokio::signal::unix::{SignalKind, signal};
    match (
        signal(SignalKind::terminate()),
        signal(SignalKind::interrupt()),
    ) {
        (Ok(mut term), Ok(mut int)) => {
            tokio::select! {
                _ = term.recv() => {}
                _ = int.recv() => {}
            }
        }
        (Err(e), _) | (_, Err(e)) => {
            tracing::warn!(error = %e, "cannot listen for signals; waiting for a shutdown handle only");
            future::pending::<()>().await;
        }
    }
}

#[cfg(not(unix))]
async fn signal() {
    if tokio::signal::ctrl_c().await.is_err() {
        future::pending::<()>().await;
    }
}

/// Runs an application from `main`: configure, build, then the command named by the first
/// argument, or start and run until SIGTERM or SIGINT, then shut down. `args` as
/// `std::env::args()` gives them (program name first). Problems are logged and printed to stderr;
/// the exit code is 1 for a start-up failure.
pub async fn launch<F>(
    app: AppBuilder,
    args: impl IntoIterator<Item = String>,
    build: F,
) -> ExitCode
where
    F: AsyncFnOnce(&mut Boot) -> Result<(), StartupError>,
{
    let mut args = args.into_iter().skip(1);
    let first = args.next();
    let fail = |e: &StartupError| {
        tracing::error!("{e}");
        let mut err = io::stderr();
        // Nothing more can be done if stderr is closed.
        writeln!(err, "{e}").ok();
        ExitCode::FAILURE
    };
    let configured = match app.configure().await {
        Ok(c) => c,
        Err(e) => return fail(&e),
    };
    let built = match configured.build(build).await {
        Ok(b) => b,
        Err(e) => return fail(&e),
    };
    if let Some(name) = first.as_deref()
        && built.has_command(name)
    {
        let mut out = io::stdout();
        return built
            .run_command(name, args.collect(), &mut out)
            .await
            .unwrap_or(ExitCode::FAILURE);
    }
    let running = match built.start().await {
        Ok(r) => r,
        Err(e) => return fail(&e),
    };
    running.wait_for_shutdown().await;
    tracing::info!("shutting down");
    running.shutdown().await;
    ExitCode::SUCCESS
}
