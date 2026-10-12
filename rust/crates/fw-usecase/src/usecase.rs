//! Use cases and the runner (write-path contract sections 3 and 4).

use std::collections::BTreeMap;
use std::ops::Deref;
use std::panic::AssertUnwindSafe;
use std::sync::Arc;
use std::time::Duration;
use std::{fmt, panic};

use futures::FutureExt;
use fw_core::metrics::{Counter, Metrics, MetricsError, Timer};
use fw_core::{Clock, Context, Ids};
use fw_db::{Databases, Db, Tx};
use rand::Rng;
use tokio::time::{self, Instant};

use crate::error::{ErrorCode, ErrorKind, UseCaseError};
use crate::event::{DomainEvent, ErasedEvent};
use crate::outbox::{AuditData, AuditEntry, OutboxWriter, Sink, SinkError};
use crate::plan::{Applied, Plan, StepEvent};

/// A use-case name, `[a-z][a-z0-9-]*`, for logs, metrics and the audit row; checked during
/// constant evaluation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UseCaseName(&'static str);

impl UseCaseName {
    /// A name.
    ///
    /// # Panics
    ///
    /// When `text` does not match `[a-z][a-z0-9-]*`; in a `const` that is a compile error.
    #[must_use]
    pub const fn of(text: &'static str) -> Self {
        let b = text.as_bytes();
        assert!(
            !b.is_empty() && b[0].is_ascii_lowercase(),
            "use case name must match [a-z][a-z0-9-]*"
        );
        let mut i = 1;
        while i < b.len() {
            assert!(
                b[i].is_ascii_lowercase() || b[i].is_ascii_digit() || b[i] == b'-',
                "use case name must match [a-z][a-z0-9-]*"
            );
            i += 1;
        }
        Self(text)
    }

    /// The name.
    #[must_use]
    pub const fn as_str(&self) -> &'static str {
        self.0
    }
}

/// The number of attempts of a retrying use case, 2 to 10.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Attempts(u8);

impl Attempts {
    /// The number.
    #[must_use]
    pub fn get(self) -> u8 {
        self.0
    }
}

/// What to do when an attempt finds the data changed underneath it (a stale aggregate, a
/// serialization failure, a deadlock).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConflictPolicy {
    /// Fail with `CONFLICT` / `STALE_AGGREGATE` at the first conflict (the default).
    Fail,
    /// Run the whole use case again from the start, up to `attempts` in total, waiting `backoff`
    /// (times a random factor from 0.5 to 1.5) between them.
    Retry {
        /// Attempts in total.
        attempts: Attempts,
        /// The base wait.
        backoff: Duration,
    },
}

impl ConflictPolicy {
    /// A retry policy.
    ///
    /// # Panics
    ///
    /// When `attempts` is not 2 to 10; in a `const` that is a compile error.
    #[must_use]
    pub const fn retry(attempts: u8, backoff: Duration) -> Self {
        assert!(
            attempts >= 2 && attempts <= 10,
            "retry attempts must be 2 to 10"
        );
        Self::Retry {
            attempts: Attempts(attempts),
            backoff,
        }
    }

    fn max_attempts(self) -> u8 {
        match self {
            Self::Fail => 1,
            Self::Retry { attempts, .. } => attempts.0,
        }
    }
}

/// A command that passed `validate`. Only the runner makes one.
#[derive(Debug, Clone, Copy)]
pub struct Valid<'a, C>(&'a C);

impl<C> Deref for Valid<'_, C> {
    type Target = C;
    fn deref(&self) -> &C {
        self.0
    }
}

/// A command that passed `validate` and `authorize`: what `execute` receives. Only the runner
/// makes one, so business logic never runs on an unchecked command.
#[derive(Debug, Clone, Copy)]
pub struct Authorized<'a, C>(&'a C);

impl<C> Deref for Authorized<'_, C> {
    type Target = C;
    fn deref(&self) -> &C {
        self.0
    }
}

/// What `authorize` returns when access is granted.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Grant(());

impl Grant {
    /// The operation is deliberately open (Java `Authorize.PUBLIC`): greppable.
    #[must_use]
    pub const fn public() -> Self {
        Self(())
    }

    /// The use case checked the context and allows it.
    #[must_use]
    pub const fn allowed() -> Self {
        Self(())
    }
}

/// The successful result of a run: the plan's first event. Only the runner makes one.
#[derive(Debug, Clone, PartialEq)]
pub struct Committed<E>(E);

impl<E> Committed<E> {
    /// The event.
    #[must_use]
    pub fn event(&self) -> &E {
        &self.0
    }

    /// The event, owned.
    #[must_use]
    pub fn into_inner(self) -> E {
        self.0
    }
}

/// A use case: one business operation. `authorize` has no default, so a use case without an
/// authorisation step does not compile.
pub trait UseCase: Send + Sync {
    /// The name.
    const NAME: UseCaseName;
    /// The command.
    type Command: Send + Sync;
    /// The result event.
    type Event: DomainEvent;

    /// The conflict policy (default fail).
    fn conflict_policy(&self) -> ConflictPolicy {
        ConflictPolicy::Fail
    }

    /// Checks the command's shape, without loading data.
    ///
    /// # Errors
    ///
    /// The validation error.
    fn validate(&self, _ctx: &Context, _cmd: &Self::Command) -> Result<(), UseCaseError> {
        Ok(())
    }

    /// The resource-level access check.
    fn authorize(
        &self,
        ctx: &Context,
        cmd: Valid<'_, Self::Command>,
    ) -> impl Future<Output = Result<Grant, UseCaseError>> + Send;

    /// Loads what it needs (once), applies the rules, and returns a plan or a rejection.
    fn execute<'a>(
        &'a self,
        ctx: &'a Context,
        cmd: Authorized<'a, Self::Command>,
        db: &'a Db,
    ) -> impl Future<Output = Result<Plan<'a, Self::Event>, UseCaseError>> + Send;

    /// Command data for the audit row (default none).
    fn audit_data(&self, _cmd: &Self::Command) -> Option<AuditData> {
        None
    }
}

const SERIALIZATION_FAILURE: &str = "40001";
const DEADLOCK_DETECTED: &str = "40P01";
const UNIQUE_VIOLATION: &str = "23505";

struct Inner {
    db: Db,
    sink: Arc<dyn Sink>,
    clock: Arc<dyn Clock>,
    runs: Counter,
    durations: Timer,
}

/// The runner: applies a use case's plan atomically. The only place that begins, commits or
/// rolls back a use-case transaction. Cloning shares it.
#[derive(Clone)]
pub struct UseCases(Arc<Inner>);

impl fmt::Debug for UseCases {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("UseCases")
            .field("db", &self.0.db)
            .finish_non_exhaustive()
    }
}

enum Attempt<E> {
    Done(Result<Committed<E>, UseCaseError>),
    Conflict,
}

fn fail<E>(e: UseCaseError) -> Attempt<E> {
    Attempt::Done(Err(e))
}

fn stale() -> UseCaseError {
    UseCaseError::conflict(
        ErrorCode::of("STALE_AGGREGATE"),
        "The record was changed by someone else; reload it and try again.",
    )
}

impl UseCases {
    /// A runner writing the outbox with [`OutboxWriter`], recording `fw_usecase_runs_total` and
    /// `fw_usecase_duration_seconds`.
    ///
    /// # Errors
    ///
    /// When the metrics cannot be registered.
    pub fn new(
        db: Db,
        clock: Arc<dyn Clock>,
        ids: Ids,
        metrics: &Metrics,
    ) -> Result<Self, MetricsError> {
        let sink = Arc::new(OutboxWriter::new(ids, Arc::clone(&clock)));
        Self::with_sink(db, sink, clock, metrics)
    }

    /// A runner with its own sink (tests: a recording or failing one).
    ///
    /// # Errors
    ///
    /// When the metrics cannot be registered.
    pub fn with_sink(
        db: Db,
        sink: Arc<dyn Sink>,
        clock: Arc<dyn Clock>,
        metrics: &Metrics,
    ) -> Result<Self, MetricsError> {
        let runs = metrics.counter(
            "fw_usecase_runs_total",
            "Use case runs by use case and outcome: committed, or the failure's kind.",
            &["usecase", "outcome"],
        )?;
        let durations = metrics.timer(
            "fw_usecase_duration_seconds",
            "Use case run duration, all attempts included.",
            &["usecase"],
        )?;
        Ok(Self(Arc::new(Inner {
            db,
            sink,
            clock,
            runs,
            durations,
        })))
    }

    /// The runner of an application, on its default database instance.
    ///
    /// # Errors
    ///
    /// When no database instance is configured, or the metrics cannot be registered.
    pub fn build(
        boot: &fw_core::Boot,
        databases: &Databases,
    ) -> Result<Self, fw_core::StartupError> {
        let db = databases.require_default("the use-case runner")?.clone();
        Self::new(db, boot.clock(), boot.ids().clone(), boot.metrics())
            .map_err(|e| fw_core::StartupError::of(Some("usecase"), None, e.to_string()))
    }

    /// The database instance use cases write to (and `execute` reads from).
    #[must_use]
    pub fn db(&self) -> &Db {
        &self.0.db
    }

    /// Runs a use case. Never panics for an expected outcome; a rejection, a conflict and an
    /// unexpected failure are all `Err(UseCaseError)`.
    ///
    /// # Errors
    ///
    /// The use case's rejection, `CONFLICT` (`STALE_AGGREGATE`, `UNIQUE_VIOLATION`), or `INTERNAL`
    /// naming the step that failed.
    pub async fn run<U: UseCase>(
        &self,
        uc: &U,
        ctx: &Context,
        cmd: U::Command,
    ) -> Result<Committed<U::Event>, UseCaseError> {
        let started = Instant::now();
        let result = self.attempts(uc, ctx, &cmd).await;
        let outcome = match &result {
            Ok(_) => "committed",
            Err(e) => e.kind().label(),
        };
        if let Ok(s) = self.0.runs.labels(&[U::NAME.as_str(), outcome]) {
            s.inc();
        }
        if let Ok(s) = self.0.durations.labels(&[U::NAME.as_str()]) {
            s.record(started.elapsed());
        }
        result
    }

    async fn attempts<U: UseCase>(
        &self,
        uc: &U,
        ctx: &Context,
        cmd: &U::Command,
    ) -> Result<Committed<U::Event>, UseCaseError> {
        let policy = uc.conflict_policy();
        let mut attempt: u8 = 1;
        loop {
            match self.attempt(uc, ctx, cmd).await {
                Attempt::Done(r) => return r,
                Attempt::Conflict if attempt >= policy.max_attempts() => {
                    tracing::info!(
                        use_case = U::NAME.as_str(),
                        attempt,
                        "use case conflicted; giving up"
                    );
                    return Err(stale());
                }
                Attempt::Conflict => {
                    tracing::debug!(
                        use_case = U::NAME.as_str(),
                        attempt,
                        "use case conflicted; running again"
                    );
                    if let ConflictPolicy::Retry { backoff, .. } = policy {
                        let factor = 0.5 + rand::rng().random::<f64>();
                        time::sleep(backoff.mul_f64(factor)).await;
                    }
                    attempt += 1;
                }
            }
        }
    }

    async fn attempt<U: UseCase>(
        &self,
        uc: &U,
        ctx: &Context,
        cmd: &U::Command,
    ) -> Attempt<U::Event> {
        let name = U::NAME.as_str();
        // validate
        match panic::catch_unwind(AssertUnwindSafe(|| uc.validate(ctx, cmd))) {
            Ok(Ok(())) => {}
            Ok(Err(e)) => return self.failure(name, "VALIDATE", e),
            Err(_) => return self.panicked(name, "VALIDATE"),
        }
        // authorize
        match AssertUnwindSafe(uc.authorize(ctx, Valid(cmd)))
            .catch_unwind()
            .await
        {
            Ok(Ok(_grant)) => {}
            Ok(Err(e)) => return self.failure(name, "AUTHORIZE", e),
            Err(_) => return self.panicked(name, "AUTHORIZE"),
        }
        // execute
        let plan = match AssertUnwindSafe(uc.execute(ctx, Authorized(cmd), &self.0.db))
            .catch_unwind()
            .await
        {
            Ok(Ok(plan)) => plan,
            Ok(Err(e)) => return self.failure(name, "EXECUTE", e),
            Err(_) => return self.panicked(name, "EXECUTE"),
        };
        // the client check
        if plan
            .steps
            .iter()
            .filter_map(|s| s.write.as_ref())
            .any(|w| w.client() != ctx.client())
        {
            tracing::error!(use_case = name, client = %ctx.client(), "BUG: use case planned a write for an aggregate of another client");
            return fail(UseCaseError::internal(ErrorCode::of("CROSS_CLIENT_WRITE")));
        }
        let audit = self.audit_entry(name, ctx, uc.audit_data(cmd), &plan);
        // begin
        let mut tx = match self.0.db.begin_read_committed().await {
            Ok(tx) => tx,
            Err(e) => return self.failure(name, "BEGIN", UseCaseError::failure(e)),
        };
        match AssertUnwindSafe(self.persist(ctx, &plan, &audit, &mut tx))
            .catch_unwind()
            .await
        {
            Ok(Ok(Persisted::Written)) => {}
            Ok(Ok(Persisted::Stale)) => {
                rollback(tx).await;
                return Attempt::Conflict;
            }
            Ok(Err((step, e))) => {
                rollback(tx).await;
                return self.failure(name, step, e);
            }
            Err(_) => return self.panicked(name, "PERSIST"),
        }
        match tx.commit().await {
            Ok(()) => Attempt::Done(Ok(Committed(plan.result))),
            Err(e) => self.failure(name, "COMMIT", UseCaseError::failure(e)),
        }
    }

    async fn persist<E: DomainEvent>(
        &self,
        ctx: &Context,
        plan: &Plan<'_, E>,
        audit: &AuditEntry,
        tx: &mut Tx,
    ) -> Result<Persisted, (&'static str, UseCaseError)> {
        for step in &plan.steps {
            let Some(write) = &step.write else { continue };
            match write.apply(tx).await {
                Ok(Applied::Done) => {}
                Ok(Applied::Stale) => return Ok(Persisted::Stale),
                Ok(Applied::TooMany(n)) => {
                    let (entity, id) = write.entity();
                    tracing::error!(
                        entity,
                        id,
                        rows = n,
                        "BUG: a repository write affected more than one row"
                    );
                    return Err(("PERSIST", UseCaseError::internal(ErrorCode::of("PERSIST"))));
                }
                Err(e) => return Err(("PERSIST", UseCaseError::failure(e))),
            }
        }
        let events: Vec<&dyn ErasedEvent> = plan
            .steps
            .iter()
            .map(|s| match &s.event {
                StepEvent::Result => &plan.result as &dyn ErasedEvent,
                StepEvent::Other(e) => e.as_ref(),
            })
            .collect();
        let sink = &self.0.sink;
        sink.write_events(tx, ctx, &events)
            .await
            .map_err(|e| ("OUTBOX", sink_failure(e)))?;
        if !plan.jobs.is_empty() {
            sink.write_dispatch_jobs(tx, ctx, &plan.jobs)
                .await
                .map_err(|e| ("OUTBOX", sink_failure(e)))?;
        }
        sink.write_audit(tx, audit)
            .await
            .map_err(|e| ("AUDIT", sink_failure(e)))?;
        Ok(Persisted::Written)
    }

    fn audit_entry<E: DomainEvent>(
        &self,
        operation: &str,
        ctx: &Context,
        data: Option<AuditData>,
        plan: &Plan<'_, E>,
    ) -> AuditEntry {
        let first = DomainEvent::metadata(&plan.result);
        let (entity_type, entity_id) = match plan.steps.iter().find_map(|s| s.write.as_ref()) {
            Some(w) => {
                let (t, id) = w.entity();
                (t.to_owned(), id.to_owned())
            }
            None => {
                // domain.aggregate.id, where the id may itself contain dots
                let parts: Vec<&str> = first.subject().splitn(3, '.').collect();
                match parts.as_slice() {
                    [_, aggregate, id] => ((*aggregate).to_owned(), (*id).to_owned()),
                    _ => (first.subject().to_owned(), first.subject().to_owned()),
                }
            }
        };
        AuditEntry {
            entity_type,
            entity_id,
            operation: operation.to_owned(),
            data,
            principal_id: ctx.principal().map(|p| p.as_str().to_owned()),
            performed_at: self.0.clock.now(),
            source: first.source().to_owned(),
            correlation_id: ctx.correlation().as_str().to_owned(),
            application_code: ctx.application().map(|a| a.as_str().to_owned()),
            client: ctx.client().clone(),
        }
    }

    /// An expected rejection is the result; an unexpected failure becomes a conflict (`40001`,
    /// `40P01`), `CONFLICT`/`UNIQUE_VIOLATION` (`23505`, not retried), or `INTERNAL` naming the
    /// step, with the cause logged and never put in the message.
    fn failure<E>(&self, name: &str, step: &'static str, e: UseCaseError) -> Attempt<E> {
        if !e.is_unexpected() {
            return fail(e);
        }
        if let Some(db) = e
            .cause()
            .and_then(|c| c.downcast_ref::<sqlx::Error>())
            .and_then(sqlx::Error::as_database_error)
        {
            let state = db.code();
            match state.as_deref() {
                Some(SERIALIZATION_FAILURE | DEADLOCK_DETECTED) => {
                    tracing::debug!(use_case = name, step, sqlstate = ?state, "use case hit a serialization failure or deadlock");
                    return Attempt::Conflict;
                }
                Some(UNIQUE_VIOLATION) => {
                    tracing::debug!(
                        use_case = name,
                        step,
                        "use case violated a unique constraint"
                    );
                    let mut details = BTreeMap::new();
                    if let Some(c) = db.constraint() {
                        details.insert("constraint".to_owned(), c.to_owned());
                    }
                    return fail(
                        UseCaseError::new(
                            ErrorKind::Conflict,
                            ErrorCode::of("UNIQUE_VIOLATION"),
                            "A record with the same unique value already exists.",
                        )
                        .with_details(details),
                    );
                }
                Some(_) | None => {}
            }
        }
        let cause = e.cause().map(ToString::to_string).unwrap_or_default();
        tracing::error!(use_case = name, step, %cause, "use case failed");
        fail(internal(step))
    }

    fn panicked<E>(&self, name: &str, step: &'static str) -> Attempt<E> {
        tracing::error!(use_case = name, step, "use case panicked");
        fail(internal(step))
    }
}

enum Persisted {
    Written,
    Stale,
}

fn internal(step: &'static str) -> UseCaseError {
    match ErrorCode::parse(step) {
        Ok(code) => UseCaseError::internal(code),
        Err(_) => UseCaseError::internal(ErrorCode::of("INTERNAL")),
    }
}

fn sink_failure(e: SinkError) -> UseCaseError {
    match e.downcast::<sqlx::Error>() {
        Ok(db) => UseCaseError::failure(*db),
        Err(other) => UseCaseError::failure(SinkFailure(other.to_string())),
    }
}

#[derive(Debug, thiserror::Error)]
#[error("{0}")]
struct SinkFailure(String);

async fn rollback(tx: Tx) {
    if let Err(e) = tx.rollback().await {
        tracing::warn!(error = %e, "rollback failed");
    }
}
