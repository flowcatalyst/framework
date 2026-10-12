//! The runner against a real PostgreSQL 18 (write-path contract section 4): every row of the
//! failure table, conflicts and retries, rollback, client scoping, write order and metrics.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::fmt::Debug;
use std::io;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use chrono::{TimeZone, Utc};
use fw_core::metrics::{Metrics, prometheus_text};
use fw_core::{BoxFuture, ClientId, Clock, Context, FixedClock, Ids, PrincipalId};
use fw_core::{MigrationFile, MigrationSet, ModuleId, ServiceError};
use fw_db::{Db, DbError, Tx, migrations};
use fw_usecase::{
    Aggregate, AuditData, AuditEntry, Authorized, ConflictPolicy, DispatchJob, DomainEvent,
    ErasedEvent, ErrorCode, ErrorKind, EventMetadata, EventType, Events, Fresh, Grant, Loaded,
    OutboxWriter, Plan, Repository, Sink, SinkError, UseCase, UseCaseError, UseCaseName, UseCases,
    Valid, Version, VersionCheck, migration_set,
};
use serde::Serialize;
use sqlx::PgPool;
use sqlx::postgres::PgPoolOptions;

// ---- the domain -----------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq)]
struct Thing {
    id: String,
    name: String,
}

impl Aggregate for Thing {
    type Id = String;
    fn id(&self) -> &String {
        &self.id
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ThingSaved {
    #[serde(skip)]
    meta: EventMetadata,
    thing_id: String,
    name: String,
}

impl DomainEvent for ThingSaved {
    const TYPE: EventType = EventType::of("shop:stock:thing:saved");
    fn metadata(&self) -> &EventMetadata {
        &self.meta
    }
}

#[derive(Debug, Clone, Serialize)]
struct ThingNoted {
    #[serde(skip)]
    meta: EventMetadata,
    note: String,
}

impl DomainEvent for ThingNoted {
    const TYPE: EventType = EventType::of("shop:stock:thing:noted");
    fn metadata(&self) -> &EventMetadata {
        &self.meta
    }
}

/// How the repository misbehaves, for the failure rows.
#[derive(Debug, Clone, Copy, PartialEq)]
enum Fault {
    None,
    /// Raise this SQLSTATE from `update` on the first N calls.
    SqlState(&'static str, u32),
    /// Report this row count from `update`.
    Rows(u64),
    /// Fail every insert with a syntax error.
    BrokenInsert,
}

struct Things {
    fault: Mutex<Fault>,
    finds: AtomicU32,
}

impl Things {
    fn new() -> Self {
        Self {
            fault: Mutex::new(Fault::None),
            finds: AtomicU32::new(0),
        }
    }
    fn fault(&self) -> Fault {
        *self.fault.lock().unwrap()
    }
}

impl Repository<Thing> for Things {
    async fn find(
        &self,
        db: &Db,
        client: &ClientId,
        id: &String,
    ) -> Result<Option<Loaded<Thing>>, DbError> {
        self.finds.fetch_add(1, Ordering::SeqCst);
        let row: Option<(String, i64, String)> = sqlx::query_as(
            "SELECT client_id, version, name FROM things WHERE id = $1 AND client_id = $2",
        )
        .bind(id)
        .bind(client.as_str())
        .fetch_optional(db.pool())
        .await?;
        Ok(row.map(|(c, v, name)| {
            Loaded::new(
                ClientId::new(c).unwrap(),
                Version::stored(v).unwrap(),
                Thing {
                    id: id.clone(),
                    name,
                },
            )
        }))
    }

    async fn insert(&self, tx: &mut Tx, client: &ClientId, a: &Thing) -> Result<(), DbError> {
        let sql = if self.fault() == Fault::BrokenInsert {
            "INSERT INTO nowhere VALUES (1)"
        } else {
            "INSERT INTO things (id, client_id, version, name) VALUES ($1, $2, 1, $3)"
        };
        let q = sqlx::query(sql);
        let q = if self.fault() == Fault::BrokenInsert {
            q
        } else {
            q.bind(&a.id).bind(client.as_str()).bind(&a.name)
        };
        q.execute(tx.conn()).await?;
        Ok(())
    }

    async fn update(
        &self,
        tx: &mut Tx,
        client: &ClientId,
        a: &Thing,
        v: VersionCheck,
    ) -> Result<u64, DbError> {
        let fault = self.fault();
        match fault {
            Fault::SqlState(state, n) if n > 0 => {
                *self.fault.lock().unwrap() = Fault::SqlState(state, n - 1);
                sqlx::query(&format!("DO $$ BEGIN RAISE EXCEPTION 'injected' USING ERRCODE = '{state}'; END $$"))
                    .execute(tx.conn())
                    .await?;
                Ok(0)
            }
            Fault::Rows(n) => Ok(n),
            Fault::None | Fault::BrokenInsert | Fault::SqlState(..) => Ok(sqlx::query(
                "UPDATE things SET name = $1, version = $2 WHERE id = $3 AND client_id = $4 AND version = $5",
            )
            .bind(&a.name)
            .bind(v.next.get())
            .bind(&a.id)
            .bind(client.as_str())
            .bind(v.expected.get())
            .execute(tx.conn())
            .await?
            .rows_affected()),
        }
    }

    async fn delete(
        &self,
        tx: &mut Tx,
        client: &ClientId,
        a: &Thing,
        v: VersionCheck,
    ) -> Result<u64, DbError> {
        Ok(
            sqlx::query("DELETE FROM things WHERE id = $1 AND client_id = $2 AND version = $3")
                .bind(&a.id)
                .bind(client.as_str())
                .bind(v.expected.get())
                .execute(tx.conn())
                .await?
                .rows_affected(),
        )
    }
}

// ---- the use cases --------------------------------------------------------------------------

/// What a phase does, for the failure rows.
#[derive(Debug, Clone, Copy, PartialEq)]
enum Behaviour {
    Normal,
    RejectValidate,
    DenyAuthorize,
    RejectExecute,
    FailExecute,
    PanicExecute,
    ForeignClient,
    PanicValidate,
    FailAuthorize,
}

struct CreateThing {
    repo: Arc<Things>,
    events: Events,
    behaviour: Behaviour,
    dispatch: Option<DispatchJob>,
}

struct Create {
    id: String,
    name: String,
}

impl UseCase for CreateThing {
    const NAME: UseCaseName = UseCaseName::of("create-thing");
    type Command = Create;
    type Event = ThingSaved;

    fn validate(&self, _ctx: &Context, cmd: &Create) -> Result<(), UseCaseError> {
        assert!(self.behaviour != Behaviour::PanicValidate, "boom");
        if self.behaviour == Behaviour::RejectValidate || cmd.name.is_empty() {
            return Err(UseCaseError::validation(
                ErrorCode::of("NAME_REQUIRED"),
                "A name is required.",
            ));
        }
        Ok(())
    }

    async fn authorize(
        &self,
        ctx: &Context,
        _cmd: Valid<'_, Create>,
    ) -> Result<Grant, UseCaseError> {
        if self.behaviour == Behaviour::FailAuthorize {
            return Err(UseCaseError::failure(io::Error::other(
                "the permission store is down",
            )));
        }
        if self.behaviour == Behaviour::DenyAuthorize || ctx.principal().is_none() {
            return Err(UseCaseError::authorization(
                ErrorCode::of("PRINCIPAL_REQUIRED"),
                "Sign in.",
            ));
        }
        Ok(Grant::allowed())
    }

    async fn execute<'a>(
        &'a self,
        ctx: &'a Context,
        cmd: Authorized<'a, Create>,
        db: &'a Db,
    ) -> Result<Plan<'a, ThingSaved>, UseCaseError> {
        match self.behaviour {
            Behaviour::RejectExecute => {
                return Err(UseCaseError::business_rule(
                    ErrorCode::of("NOT_TODAY"),
                    "Not today.",
                ));
            }
            Behaviour::FailExecute => {
                sqlx::query("SELECT * FROM no_such_table")
                    .execute(db.pool())
                    .await?;
            }
            Behaviour::PanicExecute => panic!("boom"),
            Behaviour::Normal
            | Behaviour::RejectValidate
            | Behaviour::DenyAuthorize
            | Behaviour::ForeignClient
            | Behaviour::PanicValidate
            | Behaviour::FailAuthorize => {}
        }
        let thing = Thing {
            id: cmd.id.clone(),
            name: cmd.name.clone(),
        };
        let event = ThingSaved {
            meta: self.events.metadata::<ThingSaved>(ctx, &thing.id),
            thing_id: thing.id.clone(),
            name: thing.name.clone(),
        };
        let plan = if self.behaviour == Behaviour::ForeignClient {
            let other = Context::new(ClientId::new("other").unwrap());
            Plan::save(Fresh::new(&other, thing), self.repo.as_ref(), event)
        } else {
            let note = ThingNoted {
                meta: self.events.metadata::<ThingNoted>(ctx, &thing.id),
                note: "created".into(),
            };
            Plan::save(Fresh::new(ctx, thing), self.repo.as_ref(), event).and_emit(note)
        };
        Ok(match &self.dispatch {
            Some(job) => plan.and_dispatch(job.clone()),
            None => plan,
        })
    }

    fn audit_data(&self, cmd: &Create) -> Option<AuditData> {
        Some(AuditData::new().with("name", cmd.name.clone()))
    }
}

struct RenameThing {
    repo: Arc<Things>,
    events: Events,
    policy: ConflictPolicy,
}

struct Rename {
    id: String,
    name: String,
}

impl UseCase for RenameThing {
    const NAME: UseCaseName = UseCaseName::of("rename-thing");
    type Command = Rename;
    type Event = ThingSaved;

    fn conflict_policy(&self) -> ConflictPolicy {
        self.policy
    }

    async fn authorize(
        &self,
        _ctx: &Context,
        _cmd: Valid<'_, Rename>,
    ) -> Result<Grant, UseCaseError> {
        Ok(Grant::public())
    }

    async fn execute<'a>(
        &'a self,
        ctx: &'a Context,
        cmd: Authorized<'a, Rename>,
        db: &'a Db,
    ) -> Result<Plan<'a, ThingSaved>, UseCaseError> {
        let found = self
            .repo
            .find(db, ctx.client(), &cmd.id)
            .await?
            .ok_or_else(|| {
                UseCaseError::not_found(ErrorCode::of("THING_NOT_FOUND"), "No such thing.")
            })?;
        let renamed = found.map(|t| Thing {
            name: cmd.name.clone(),
            ..t
        });
        let event = ThingSaved {
            meta: self.events.metadata::<ThingSaved>(ctx, &cmd.id),
            thing_id: cmd.id.clone(),
            name: cmd.name.clone(),
        };
        Ok(Plan::save(renamed, self.repo.as_ref(), event))
    }
}

// ---- the harness ----------------------------------------------------------------------------

struct Harness {
    pool: PgPool,
    runner: UseCases,
    metrics: Metrics,
    events: Events,
    repo: Arc<Things>,
    ctx: Context,
}

const THINGS_SQL: &str = "CREATE TABLE things (id text PRIMARY KEY, client_id text NOT NULL, version bigint NOT NULL, name text NOT NULL UNIQUE);
CREATE TABLE thing_refs (thing_id text NOT NULL REFERENCES things (id) DEFERRABLE INITIALLY DEFERRED);";

fn clock() -> Arc<dyn Clock> {
    Arc::new(FixedClock::at(
        Utc.with_ymd_and_hms(2026, 10, 8, 10, 15, 30).unwrap(),
    ))
}

async fn harness_with(sink: Option<Arc<dyn Sink>>) -> Harness {
    let db = fw_test::postgres()
        .await
        .unwrap()
        .fresh_database()
        .await
        .unwrap();
    let pool = PgPoolOptions::new()
        .max_connections(8)
        .connect(&db.url)
        .await
        .unwrap();
    let things = MigrationSet::new(
        ModuleId::of("things"),
        [MigrationFile::embedded(
            "V1__things.sql",
            THINGS_SQL.as_bytes(),
        )],
    );
    migrations::apply("main", &pool, &[migration_set(), things])
        .await
        .unwrap();
    let metrics = Metrics::memory(100).unwrap();
    let ids = Ids::seeded(clock(), 1);
    let db = Db::from_pool("main", pool.clone(), true);
    let runner = match sink {
        Some(sink) => UseCases::with_sink(db, sink, clock(), &metrics).unwrap(),
        None => UseCases::new(db, clock(), ids.clone(), &metrics).unwrap(),
    };
    let ctx = Context::new(ClientId::new("acme").unwrap())
        .with_principal(PrincipalId::new("user-1").unwrap());
    Harness {
        pool,
        runner,
        metrics,
        events: Events::new(clock(), ids),
        repo: Arc::new(Things::new()),
        ctx,
    }
}

async fn harness() -> Harness {
    harness_with(None).await
}

impl Harness {
    fn create(&self, behaviour: Behaviour) -> CreateThing {
        CreateThing {
            repo: Arc::clone(&self.repo),
            events: self.events.clone(),
            behaviour,
            dispatch: None,
        }
    }

    fn rename(&self, policy: ConflictPolicy) -> RenameThing {
        RenameThing {
            repo: Arc::clone(&self.repo),
            events: self.events.clone(),
            policy,
        }
    }

    async fn rows(&self) -> Vec<(String, String)> {
        sqlx::query_as(
            "SELECT type, coalesce(message_group, '') FROM outbox_messages ORDER BY ctid",
        )
        .fetch_all(&self.pool)
        .await
        .unwrap()
    }

    async fn things(&self) -> i64 {
        sqlx::query_scalar("SELECT count(*) FROM things")
            .fetch_one(&self.pool)
            .await
            .unwrap()
    }
}

fn create(id: &str, name: &str) -> Create {
    Create {
        id: id.into(),
        name: name.into(),
    }
}

fn assert_error(r: Result<impl Debug, UseCaseError>, kind: ErrorKind, code: &str) -> UseCaseError {
    let e = r.expect_err("expected a failure");
    assert_eq!((e.kind(), e.code()), (kind, code), "{e:?}");
    e
}

// ---- the tests ------------------------------------------------------------------------------

#[tokio::test]
async fn commits_the_aggregate_the_events_in_step_order_the_jobs_then_one_audit_row() {
    let h = harness().await;
    let mut uc = h.create(Behaviour::Normal);
    uc.dispatch = Some(
        DispatchJob::builder("shop:stock:recount", "https://stock.example.com/recount")
            .id("job-1")
            .build()
            .unwrap(),
    );
    let done = h
        .runner
        .run(&uc, &h.ctx, create("thg_1", "bolt"))
        .await
        .unwrap();
    assert_eq!(done.event().thing_id, "thg_1");
    assert_eq!(h.things().await, 1);
    assert_eq!(
        h.rows().await,
        vec![
            ("EVENT".to_owned(), "stock:thing:thg_1".to_owned()),
            ("EVENT".to_owned(), "stock:thing:thg_1".to_owned()),
            ("DISPATCH_JOB".to_owned(), String::new()),
            ("AUDIT_LOG".to_owned(), String::new()),
        ]
    );
    let audit: String =
        sqlx::query_scalar("SELECT payload FROM outbox_messages WHERE type = 'AUDIT_LOG'")
            .fetch_one(&h.pool)
            .await
            .unwrap();
    let audit: serde_json::Value = serde_json::from_str(&audit).unwrap();
    assert_eq!(audit["entityType"], "Thing");
    assert_eq!(audit["entityId"], "thg_1");
    assert_eq!(audit["operation"], "create-thing");
    assert_eq!(audit["operationData"], r#"{"name":"bolt"}"#);
    let text = prometheus_text(&h.metrics.snapshot());
    assert!(
        text.contains("fw_usecase_runs_total{usecase=\"create-thing\",outcome=\"committed\"} 1"),
        "{text}"
    );
    assert!(
        text.contains("fw_usecase_duration_seconds_count{usecase=\"create-thing\"} 1"),
        "{text}"
    );
}

#[tokio::test]
async fn rejections_from_each_phase_are_the_result_and_write_nothing() {
    let h = harness().await;
    for (behaviour, kind, code) in [
        (
            Behaviour::RejectValidate,
            ErrorKind::Validation,
            "NAME_REQUIRED",
        ),
        (
            Behaviour::DenyAuthorize,
            ErrorKind::Authorization,
            "PRINCIPAL_REQUIRED",
        ),
        (
            Behaviour::RejectExecute,
            ErrorKind::BusinessRule,
            "NOT_TODAY",
        ),
    ] {
        assert_error(
            h.runner
                .run(&h.create(behaviour), &h.ctx, create("thg_1", "bolt"))
                .await,
            kind,
            code,
        );
    }
    assert_eq!(h.things().await, 0);
    assert!(h.rows().await.is_empty());
    let text = prometheus_text(&h.metrics.snapshot());
    assert!(text.contains("outcome=\"business_rule\"} 1"), "{text}");
}

#[tokio::test]
async fn an_unexpected_failure_or_a_panic_is_internal_naming_the_step() {
    let h = harness().await;
    for (behaviour, step) in [
        (Behaviour::PanicValidate, "VALIDATE"),
        (Behaviour::FailAuthorize, "AUTHORIZE"),
    ] {
        assert_error(
            h.runner
                .run(&h.create(behaviour), &h.ctx, create("thg_1", "bolt"))
                .await,
            ErrorKind::Internal,
            step,
        );
    }
    let e = assert_error(
        h.runner
            .run(
                &h.create(Behaviour::FailExecute),
                &h.ctx,
                create("thg_1", "bolt"),
            )
            .await,
        ErrorKind::Internal,
        "EXECUTE",
    );
    assert_eq!(
        e.message(),
        "The operation could not be completed (EXECUTE).",
        "the cause is never in the message"
    );
    assert_error(
        h.runner
            .run(
                &h.create(Behaviour::PanicExecute),
                &h.ctx,
                create("thg_1", "bolt"),
            )
            .await,
        ErrorKind::Internal,
        "EXECUTE",
    );
    h.repo
        .fault
        .lock()
        .unwrap()
        .clone_from(&Fault::BrokenInsert);
    assert_error(
        h.runner
            .run(
                &h.create(Behaviour::Normal),
                &h.ctx,
                create("thg_1", "bolt"),
            )
            .await,
        ErrorKind::Internal,
        "PERSIST",
    );
    assert_eq!(h.things().await, 0);
    assert!(h.rows().await.is_empty());
}

#[tokio::test]
async fn a_plan_with_another_clients_aggregate_is_refused() {
    let h = harness().await;
    assert_error(
        h.runner
            .run(
                &h.create(Behaviour::ForeignClient),
                &h.ctx,
                create("thg_1", "bolt"),
            )
            .await,
        ErrorKind::Internal,
        "CROSS_CLIENT_WRITE",
    );
    assert_eq!(h.things().await, 0);
}

#[tokio::test]
async fn a_unique_violation_is_a_conflict_with_the_constraint_and_is_not_retried() {
    let h = harness().await;
    h.runner
        .run(
            &h.create(Behaviour::Normal),
            &h.ctx,
            create("thg_1", "bolt"),
        )
        .await
        .unwrap();
    let e = assert_error(
        h.runner
            .run(
                &h.create(Behaviour::Normal),
                &h.ctx,
                create("thg_2", "bolt"),
            )
            .await,
        ErrorKind::Conflict,
        "UNIQUE_VIOLATION",
    );
    assert_eq!(
        e.details().get("constraint").map(String::as_str),
        Some("things_name_key")
    );
    assert_eq!(h.rows().await.len(), 3, "only the first run's rows");
}

#[tokio::test]
async fn a_stale_aggregate_fails_under_fail_and_rolls_back() {
    let h = harness().await;
    h.runner
        .run(
            &h.create(Behaviour::Normal),
            &h.ctx,
            create("thg_1", "bolt"),
        )
        .await
        .unwrap();
    *h.repo.fault.lock().unwrap() = Fault::Rows(0);
    assert_error(
        h.runner
            .run(
                &h.rename(ConflictPolicy::Fail),
                &h.ctx,
                Rename {
                    id: "thg_1".into(),
                    name: "nut".into(),
                },
            )
            .await,
        ErrorKind::Conflict,
        "STALE_AGGREGATE",
    );
    *h.repo.fault.lock().unwrap() = Fault::Rows(2);
    assert_error(
        h.runner
            .run(
                &h.rename(ConflictPolicy::Fail),
                &h.ctx,
                Rename {
                    id: "thg_1".into(),
                    name: "nut".into(),
                },
            )
            .await,
        ErrorKind::Internal,
        "PERSIST",
    );
    assert_eq!(h.rows().await.len(), 3, "nothing from the failed runs");
}

// Real time: a paused clock would fire the pool's acquire timeout while the database answers.
#[tokio::test]
async fn serialization_failures_and_deadlocks_are_conflicts_retried_from_the_start() {
    let h = harness().await;
    h.runner
        .run(
            &h.create(Behaviour::Normal),
            &h.ctx,
            create("thg_1", "bolt"),
        )
        .await
        .unwrap();
    for state in ["40001", "40P01"] {
        *h.repo.fault.lock().unwrap() = Fault::SqlState(state, 2);
        h.repo.finds.store(0, Ordering::SeqCst);
        let done = h
            .runner
            .run(
                &h.rename(ConflictPolicy::retry(3, Duration::from_millis(25))),
                &h.ctx,
                Rename {
                    id: "thg_1".into(),
                    name: format!("nut-{state}"),
                },
            )
            .await
            .unwrap();
        assert_eq!(done.event().name, format!("nut-{state}"));
        assert_eq!(
            h.repo.finds.load(Ordering::SeqCst),
            3,
            "each attempt runs from the start and loads again"
        );
    }
    *h.repo.fault.lock().unwrap() = Fault::SqlState("40001", 5);
    assert_error(
        h.runner
            .run(
                &h.rename(ConflictPolicy::retry(2, Duration::from_millis(1))),
                &h.ctx,
                Rename {
                    id: "thg_1".into(),
                    name: "x".into(),
                },
            )
            .await,
        ErrorKind::Conflict,
        "STALE_AGGREGATE",
    );
    let version: i64 = sqlx::query_scalar("SELECT version FROM things WHERE id = 'thg_1'")
        .fetch_one(&h.pool)
        .await
        .unwrap();
    assert_eq!(version, 3, "two committed renames");
}

#[tokio::test]
async fn a_concurrent_change_makes_the_older_load_stale() {
    let h = harness().await;
    h.runner
        .run(
            &h.create(Behaviour::Normal),
            &h.ctx,
            create("thg_1", "bolt"),
        )
        .await
        .unwrap();
    let db = h.runner.db().clone();
    let loaded = h
        .repo
        .find(&db, h.ctx.client(), &"thg_1".to_owned())
        .await
        .unwrap()
        .unwrap();
    h.runner
        .run(
            &h.rename(ConflictPolicy::Fail),
            &h.ctx,
            Rename {
                id: "thg_1".into(),
                name: "nut".into(),
            },
        )
        .await
        .unwrap();
    // The first load is now one version behind.
    let mut tx = db.begin_read_committed().await.unwrap();
    let rows = h
        .repo
        .update(
            &mut tx,
            loaded.client(),
            loaded.get(),
            VersionCheck {
                expected: loaded.version(),
                next: Version::stored(2).unwrap(),
            },
        )
        .await
        .unwrap();
    assert_eq!(rows, 0);
}

#[tokio::test]
async fn another_client_cannot_see_the_aggregate() {
    let h = harness().await;
    h.runner
        .run(
            &h.create(Behaviour::Normal),
            &h.ctx,
            create("thg_1", "bolt"),
        )
        .await
        .unwrap();
    let other = Context::new(ClientId::new("other").unwrap());
    assert_error(
        h.runner
            .run(
                &h.rename(ConflictPolicy::Fail),
                &other,
                Rename {
                    id: "thg_1".into(),
                    name: "x".into(),
                },
            )
            .await,
        ErrorKind::NotFound,
        "THING_NOT_FOUND",
    );
}

#[tokio::test]
async fn begin_and_commit_failures_name_their_step() {
    let h = harness().await;
    // A deferred foreign key fails at COMMIT.
    sqlx::query("CREATE FUNCTION dangling() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN INSERT INTO thing_refs VALUES ('nope'); RETURN NEW; END $$").execute(&h.pool).await.unwrap();
    sqlx::query(
        "CREATE TRIGGER dangling AFTER INSERT ON things FOR EACH ROW EXECUTE FUNCTION dangling()",
    )
    .execute(&h.pool)
    .await
    .unwrap();
    assert_error(
        h.runner
            .run(
                &h.create(Behaviour::Normal),
                &h.ctx,
                create("thg_1", "bolt"),
            )
            .await,
        ErrorKind::Internal,
        "COMMIT",
    );
    assert_eq!(h.things().await, 0);
    assert!(h.rows().await.is_empty());
    // A closed pool fails at BEGIN.
    h.runner.db().pool().close().await;
    assert_error(
        h.runner
            .run(&h.create(Behaviour::Normal), &h.ctx, create("thg_2", "nut"))
            .await,
        ErrorKind::Internal,
        "BEGIN",
    );
}

/// A sink that fails at events or at the audit row, and writes no dispatch jobs.
struct FailingSink {
    events: bool,
    audit: bool,
}

impl Sink for FailingSink {
    fn write_events<'a>(
        &'a self,
        _tx: &'a mut Tx,
        _ctx: &'a Context,
        _events: &'a [&'a dyn ErasedEvent],
    ) -> BoxFuture<'a, Result<(), SinkError>> {
        Box::pin(async move {
            if self.events {
                Err::<(), SinkError>("events refused".into())
            } else {
                Ok(())
            }
        })
    }
    fn write_audit<'a>(
        &'a self,
        _tx: &'a mut Tx,
        _entry: &'a AuditEntry,
    ) -> BoxFuture<'a, Result<(), SinkError>> {
        Box::pin(async move {
            if self.audit {
                Err::<(), SinkError>("audit refused".into())
            } else {
                Ok(())
            }
        })
    }
}

#[tokio::test]
async fn outbox_and_audit_failures_name_their_step_and_roll_back() {
    for (sink, step) in [
        (
            FailingSink {
                events: true,
                audit: false,
            },
            "OUTBOX",
        ),
        (
            FailingSink {
                events: false,
                audit: true,
            },
            "AUDIT",
        ),
    ] {
        let h = harness_with(Some(Arc::new(sink))).await;
        assert_error(
            h.runner
                .run(
                    &h.create(Behaviour::Normal),
                    &h.ctx,
                    create("thg_1", "bolt"),
                )
                .await,
            ErrorKind::Internal,
            step,
        );
        assert_eq!(
            h.things().await,
            0,
            "{step}: the aggregate write is rolled back"
        );
    }
    // A sink without dispatch-job support fails a plan that carries one at OUTBOX.
    let h = harness_with(Some(Arc::new(FailingSink {
        events: false,
        audit: false,
    })))
    .await;
    let mut uc = h.create(Behaviour::Normal);
    uc.dispatch = Some(
        DispatchJob::builder("shop:x", "https://a.example.com")
            .build()
            .unwrap(),
    );
    assert_error(
        h.runner.run(&uc, &h.ctx, create("thg_1", "bolt")).await,
        ErrorKind::Internal,
        "OUTBOX",
    );
}

#[test]
fn retry_policy_and_writer_types_are_usable_as_constants() {
    const POLICY: ConflictPolicy = ConflictPolicy::retry(3, Duration::from_millis(25));
    assert!(matches!(POLICY, ConflictPolicy::Retry { .. }));
    let writer = OutboxWriter::new(Ids::new(clock()), clock());
    assert_eq!(format!("{writer:?}"), "OutboxWriter");
}
