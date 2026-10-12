//! `conformance/outbox/outbox.json` and `conformance/migrations/outbox.json`, written by the Java
//! `OutboxConformanceTest`: the rows the outbox writer stores, byte for byte.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

mod common;

use std::fs;
use std::sync::Arc;

use chrono::{DateTime, Utc};
use fw_conformance::json;
use fw_core::{
    ApplicationCode, CausationId, ClientId, Context, CorrelationId, ExecutionId, FixedClock, Ids,
    PrincipalId, tsid,
};
use fw_db::{Db, migrations};
use fw_usecase::{
    AuditData, AuditEntry, DispatchJob, DispatchKind, DispatchMode, DispatchQueue, DomainEvent,
    ErasedEvent, EventMetadata, EventType, OutboxWriter, Sink, migration_set,
};
use serde::Serialize;
use serde_json::Value;
use sqlx::PgPool;

#[derive(Serialize)]
struct Line {
    sku: String,
    quantity: i32,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Placed {
    #[serde(skip)]
    meta: EventMetadata,
    order_id: String,
    customer: String,
    lines: Vec<Line>,
    total_cents: i64,
    note: Option<String>,
    placed_at: DateTime<Utc>,
}

impl DomainEvent for Placed {
    const TYPE: EventType = EventType::of("shop:sales:order:placed");
    fn metadata(&self) -> &EventMetadata {
        &self.meta
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Cancelled {
    #[serde(skip)]
    meta: EventMetadata,
    order_id: String,
    reason: String,
}

impl DomainEvent for Cancelled {
    const TYPE: EventType = EventType::of("shop:sales:order:cancelled");
    fn metadata(&self) -> &EventMetadata {
        &self.meta
    }
}

fn leak(s: &str) -> &'static str {
    Box::leak(s.to_owned().into_boxed_str())
}

fn s(v: &Value) -> String {
    v.as_str().unwrap().to_owned()
}

fn context(c: &Value) -> Context {
    let mut ctx = Context::new(ClientId::new(s(&c["client"])).unwrap())
        .with_correlation(CorrelationId::new(s(&c["correlation"])).unwrap());
    if let Some(p) = c["principal"].as_str() {
        ctx = ctx.with_principal(PrincipalId::new(p).unwrap());
    }
    if let Some(a) = c["application"].as_str() {
        ctx = ctx.with_application(ApplicationCode::new(a).unwrap());
    }
    if let Some(x) = c["causation"].as_str() {
        ctx = ctx.with_causation(CausationId::new(x).unwrap());
    }
    if let Some(x) = c["execution"].as_str() {
        ctx = ctx.with_execution(ExecutionId::new(x).unwrap());
    }
    ctx
}

fn event(ctx: &Context, now: DateTime<Utc>, e: &Value) -> Box<dyn ErasedEvent> {
    let mut meta = EventMetadata::new(
        s(&e["eventId"]),
        EventType::of(leak(e["type"].as_str().unwrap())),
        e["subjectId"].as_str().unwrap(),
        now,
        ctx,
    );
    if let Some(g) = e["messageGroup"].as_str() {
        meta = meta.with_message_group(g);
    }
    let f = &e["fields"];
    match e["kind"].as_str().unwrap() {
        "placed" => Box::new(Placed {
            meta,
            order_id: s(&f["orderId"]),
            customer: s(&f["customer"]),
            lines: f["lines"]
                .as_array()
                .unwrap()
                .iter()
                .map(|l| Line {
                    sku: s(&l["sku"]),
                    quantity: i32::try_from(l["quantity"].as_i64().unwrap()).unwrap(),
                })
                .collect(),
            total_cents: f["totalCents"].as_i64().unwrap(),
            note: f["note"].as_str().map(str::to_owned),
            placed_at: f["placedAt"].as_str().unwrap().parse().unwrap(),
        }),
        "cancelled" => Box::new(Cancelled {
            meta,
            order_id: s(&f["orderId"]),
            reason: s(&f["reason"]),
        }),
        other => panic!("unknown kind {other}"),
    }
}

fn dispatch_job(j: &Value) -> Result<DispatchJob, String> {
    let mut b = DispatchJob::builder(
        j["code"].as_str().unwrap(),
        j["targetUrl"].as_str().unwrap(),
    );
    let opt = |k: &str| j[k].as_str();
    if let Some(v) = opt("id") {
        b = b.id(v);
    }
    if let Some(v) = opt("kind") {
        b = b.kind(if v == "EVENT" {
            DispatchKind::Event
        } else {
            DispatchKind::Task
        });
    }
    if let Some(v) = opt("source") {
        b = b.source(v);
    }
    if let Some(v) = opt("subject") {
        b = b.subject(v);
    }
    if !j["jsonPayload"].is_null() {
        b = b.json_payload(&j["jsonPayload"]);
    }
    if let Some(v) = opt("payloadText") {
        b = b.payload(v, j["contentType"].as_str().unwrap());
    }
    if let Some(v) = j["dataOnly"].as_bool() {
        b = b.data_only(v);
    }
    if let Some(v) = opt("externalId") {
        b = b.external_id(v);
    }
    if let Some(v) = opt("eventId") {
        b = b.event_id(v);
    }
    if let Some(v) = opt("platformClientId") {
        b = b.platform_client_id(v);
    }
    if let Some(v) = opt("subscriptionId") {
        b = b.subscription_id(v);
    }
    if let Some(v) = opt("serviceAccountId") {
        b = b.service_account_id(v);
    }
    if let Some(v) = opt("dispatchPoolId") {
        b = b.dispatch_pool_id(v);
    }
    if let Some(v) = opt("messageGroup") {
        b = b.message_group(v);
    }
    if let Some(v) = opt("mode") {
        b = b.mode(match v {
            "IMMEDIATE" => DispatchMode::Immediate,
            "NEXT_ON_ERROR" => DispatchMode::NextOnError,
            _ => DispatchMode::BlockOnError,
        });
    }
    let int = |k: &str| j[k].as_i64().map(|n| i32::try_from(n).unwrap());
    if let Some(v) = int("sequence") {
        b = b.sequence(v);
    }
    if let Some(v) = int("timeoutSeconds") {
        b = b.timeout_seconds(v);
    }
    if let Some(v) = int("maxRetries") {
        b = b.max_retries(v);
    }
    if let Some(pairs) = j["metadata"].as_array() {
        for p in pairs {
            b = b.metadata(p[0].as_str().unwrap(), p[1].as_str().unwrap());
        }
    }
    if let Some(v) = opt("queue") {
        b = b.queue(if v == "HIGH_PRIORITY" {
            DispatchQueue::HighPriority
        } else {
            DispatchQueue::Default
        });
    }
    if let Some(v) = opt("descriptor") {
        b = b.descriptor(v);
    }
    b.build().map_err(|e| e.to_string())
}

fn audit(ctx: &Context, now: DateTime<Utc>, a: &Value) -> AuditEntry {
    AuditEntry {
        entity_type: s(&a["entityType"]),
        entity_id: s(&a["entityId"]),
        operation: s(&a["operation"]),
        data: a["data"].as_object().map(|d| {
            d.iter().fold(AuditData::new(), |acc, (k, v)| {
                acc.with(k.clone(), v.clone())
            })
        }),
        principal_id: ctx.principal().map(|p| p.as_str().to_owned()),
        performed_at: now,
        source: s(&a["source"]),
        correlation_id: ctx.correlation().as_str().to_owned(),
        application_code: ctx.application().map(|x| x.as_str().to_owned()),
        client: ctx.client().clone(),
    }
}

const UTC: &str = "'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'";

async fn rows(pool: &PgPool) -> Vec<Value> {
    let sql = format!(
        "SELECT id, type, message_group, status::int, retry_count::int, client_id, payload_size, headers::text, error_message, \
         to_char(created_at AT TIME ZONE 'UTC', {UTC}), to_char(updated_at AT TIME ZONE 'UTC', {UTC}), payload \
         FROM outbox_messages ORDER BY ctid"
    );
    #[expect(clippy::type_complexity, reason = "one row of the outbox table")]
    let rows: Vec<(
        String,
        String,
        Option<String>,
        i32,
        i32,
        Option<String>,
        Option<i32>,
        Option<String>,
        Option<String>,
        String,
        String,
        String,
    )> = sqlx::query_as(&sql).fetch_all(pool).await.unwrap();
    rows.into_iter()
        .map(|(id, kind, group, status, retry, client, size, headers, error, created, updated, payload)| {
            if kind == "AUDIT_LOG" {
                assert!(tsid::is_valid(&id), "the audit row's id is a TSID: {id}");
            }
            let mut parsed: Value = serde_json::from_str(&payload).unwrap();
            for nested in ["data", "operationData"] {
                if let Some(text) = parsed[nested].as_str() {
                    parsed[nested] = serde_json::from_str(text).unwrap();
                }
            }
            serde_json::json!({
                "id": if kind == "AUDIT_LOG" { "<tsid>".to_owned() } else { id },
                "type": kind, "message_group": group, "status": status, "retry_count": retry, "client_id": client,
                "payload_size": size, "headers": headers, "error_message": error, "created_at": created, "updated_at": updated,
                "payload": parsed, "payloadText": payload,
            })
        })
        .collect()
}

#[tokio::test]
async fn rows_match_java_byte_for_byte() {
    let doc = json("outbox/outbox.json");
    for case in doc["cases"].as_array().unwrap() {
        let name = case["name"].as_str().unwrap();
        let pool = common::fresh().await;
        migrations::apply("main", &pool, &[migration_set()])
            .await
            .unwrap();
        let now: DateTime<Utc> = case["now"].as_str().unwrap().parse().unwrap();
        let ctx = context(&case["context"]);
        let events: Vec<Box<dyn ErasedEvent>> = case["events"]
            .as_array()
            .unwrap()
            .iter()
            .map(|e| event(&ctx, now, e))
            .collect();
        let refs: Vec<&dyn ErasedEvent> = events.iter().map(AsRef::as_ref).collect();
        let jobs: Vec<DispatchJob> = case["dispatchJobs"]
            .as_array()
            .unwrap()
            .iter()
            .map(|j| dispatch_job(j).unwrap())
            .collect();
        let entry = audit(&ctx, now, &case["audit"]);
        let clock = Arc::new(FixedClock::at(now));
        let writer = OutboxWriter::new(Ids::new(clock.clone()), clock);
        let db = Db::from_pool("main", pool.clone(), true);
        let mut tx = db.begin_read_committed().await.unwrap();
        writer.write_events(&mut tx, &ctx, &refs).await.unwrap();
        if !jobs.is_empty() {
            writer
                .write_dispatch_jobs(&mut tx, &ctx, &jobs)
                .await
                .unwrap();
        }
        writer.write_audit(&mut tx, &entry).await.unwrap();
        tx.commit().await.unwrap();
        let got = rows(&pool).await;
        let expected = case["rows"].as_array().unwrap();
        assert_eq!(got.len(), expected.len(), "{name}");
        for (g, e) in got.iter().zip(expected) {
            assert_eq!(g["payloadText"], e["payloadText"], "{name}: payload bytes");
            assert_eq!(g, e, "{name}");
        }
    }
}

#[test]
fn invalid_dispatch_jobs_are_refused_with_the_java_messages() {
    for case in json("outbox/outbox.json")["invalidDispatchJobs"]
        .as_array()
        .unwrap()
    {
        let got = dispatch_job(case).err();
        assert_eq!(got.as_deref(), case["error"].as_str(), "{case}");
    }
}

#[tokio::test]
async fn a_rust_run_writes_the_java_outbox_history_rows() {
    let pool = common::fresh().await;
    migrations::apply("main", &pool, &[migration_set()])
        .await
        .unwrap();
    assert_eq!(
        common::history(&pool).await,
        common::vector_rows("migrations/outbox.json")
    );
}

#[tokio::test]
async fn an_outbox_migrated_by_java_is_accepted() {
    common::java_migrated_database(&[(migration_set(), "migrations/outbox.json")]).await;
}

#[test]
fn the_outbox_migration_is_the_java_file() {
    let set = migration_set();
    for f in set.files() {
        let java = fs::read(fw_conformance::root().join(format!(
            "../framework-usecase/src/main/resources/db/migration/outbox/{}",
            f.name()
        )))
        .unwrap();
        assert_eq!(f.bytes(), java.as_slice(), "{}", f.name());
    }
}
