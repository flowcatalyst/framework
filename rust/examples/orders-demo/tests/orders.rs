//! The orders demo end to end on PostgreSQL 18: the Java `OrdersDemoTest` scenarios, and the demo
//! running against a database first migrated by the Java demo.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::fmt::Debug;
use std::path::PathBuf;
use std::time::Duration;
use std::{fs, str};

use fw_core::config::Environment;
use fw_core::{AppBuilder, AppName, Boot, Built, ClientId, Context, PrincipalId, ServiceError};
use fw_db::migrations;
use fw_test::TestDatabase;
use fw_usecase::{ConflictPolicy, ErrorKind, UseCaseError};
use orders_demo::domain::{OrderId, OrderStatus};
use orders_demo::repository::summaries;
use orders_demo::usecases::{AddLine, CancelOrder, PlaceOrder, Policies};
use orders_demo::{OrdersApp, build, declare};
use serde_json::Value;
use sqlx::PgPool;

struct Demo {
    app: OrdersApp,
    pool: PgPool,
    _built: Built,
    ctx: Context,
}

fn conformance(file: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../../conformance")
        .join(file)
}

async fn start_on(db: &TestDatabase, policies: Policies) -> Demo {
    let mut app = AppBuilder::new(AppName::of("orders-demo"))
        .config_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/config"))
        .environment(Environment::from_pairs([
            ("DATABASE_INSTANCES_MAIN_URL", db.jdbc_url.as_str()),
            ("DATABASE_INSTANCES_MAIN_USERNAME", db.username.as_str()),
            ("DATABASE_INSTANCES_MAIN_PASSWORD", db.password.as_str()),
        ]));
    let declared = declare(&mut app);
    let mut out = None;
    let built = app
        .configure()
        .await
        .unwrap()
        .build(async |boot: &mut Boot| {
            out = Some(build(boot, &declared, policies).await?);
            Ok(())
        })
        .await
        .unwrap();
    let ctx = Context::new(ClientId::new("acme").unwrap())
        .with_principal(PrincipalId::new("user-1").unwrap());
    Demo {
        app: out.unwrap(),
        pool: PgPool::connect(&db.url).await.unwrap(),
        _built: built,
        ctx,
    }
}

async fn start(policies: Policies) -> Demo {
    let db = fw_test::postgres()
        .await
        .unwrap()
        .fresh_database()
        .await
        .unwrap();
    start_on(&db, policies).await
}

impl Demo {
    async fn place(&self, ctx: &Context, customer: &str) -> Result<OrderId, UseCaseError> {
        Ok(self
            .app
            .use_cases
            .run(
                &self.app.orders.place_order,
                ctx,
                PlaceOrder::parse(customer)?,
            )
            .await?
            .into_inner()
            .order_id)
    }

    async fn add(&self, ctx: &Context, order: &OrderId, sku: &str) -> Result<u32, UseCaseError> {
        let cmd = AddLine::parse(order.clone(), sku, 2, 150)?;
        Ok(self
            .app
            .use_cases
            .run(&self.app.orders.add_line, ctx, cmd)
            .await?
            .into_inner()
            .line_count)
    }

    async fn cancel(&self, ctx: &Context, order: &OrderId) -> Result<u32, UseCaseError> {
        let cmd = CancelOrder::parse(order.clone(), "changed mind")?;
        Ok(self
            .app
            .use_cases
            .run(&self.app.orders.cancel_order, ctx, cmd)
            .await?
            .into_inner()
            .line_count)
    }

    async fn outbox(&self) -> Vec<(String, Value)> {
        let rows: Vec<(String, String)> =
            sqlx::query_as("SELECT type, payload FROM outbox_messages ORDER BY created_at, ctid")
                .fetch_all(&self.pool)
                .await
                .unwrap();
        rows.into_iter()
            .map(|(t, p)| (t, serde_json::from_str(&p).unwrap()))
            .collect()
    }
}

fn code(r: Result<impl Debug, UseCaseError>) -> (ErrorKind, String) {
    let e = r.expect_err("expected a rejection");
    (e.kind(), e.code().to_owned())
}

#[tokio::test]
async fn place_add_add_cancel_writes_the_order_its_lines_and_one_event_and_audit_row_per_step() {
    let d = start(Policies::default()).await;
    let order = d.place(&d.ctx, "  Acme  ").await.unwrap();
    assert_eq!(d.add(&d.ctx, &order, "BOLT-1").await.unwrap(), 1);
    assert_eq!(d.add(&d.ctx, &order, "NUT-2").await.unwrap(), 2);
    assert_eq!(d.cancel(&d.ctx, &order).await.unwrap(), 2);

    let (version, status, customer): (i64, String, String) =
        sqlx::query_as("SELECT version, status, customer FROM orders WHERE id = $1")
            .bind(order.as_str())
            .fetch_one(&d.pool)
            .await
            .unwrap();
    assert_eq!(
        (version, status.as_str(), customer.as_str()),
        (4, "CANCELLED", "Acme")
    );
    let lines: Vec<(i32, String)> = sqlx::query_as(
        "SELECT position, sku FROM order_lines WHERE order_id = $1 ORDER BY position",
    )
    .bind(order.as_str())
    .fetch_all(&d.pool)
    .await
    .unwrap();
    assert_eq!(
        lines,
        vec![(0, "BOLT-1".to_owned()), (1, "NUT-2".to_owned())]
    );

    let rows = d.outbox().await;
    let kinds: Vec<&str> = rows.iter().map(|(t, _)| t.as_str()).collect();
    assert_eq!(
        kinds,
        [
            "EVENT",
            "AUDIT_LOG",
            "EVENT",
            "AUDIT_LOG",
            "EVENT",
            "AUDIT_LOG",
            "EVENT",
            "AUDIT_LOG"
        ]
    );
    let types: Vec<&str> = rows
        .iter()
        .filter(|(t, _)| t == "EVENT")
        .map(|(_, p)| p["type"].as_str().unwrap())
        .collect();
    assert_eq!(
        types,
        [
            "orders:sales:order:placed",
            "orders:sales:order:line-added",
            "orders:sales:order:line-added",
            "orders:sales:order:cancelled"
        ]
    );
    // The data has the Java records' keys, in their order.
    let added = rows[2].1["data"].as_str().unwrap();
    let keys: Vec<String> = serde_json::from_str::<serde_json::Map<String, Value>>(added)
        .unwrap()
        .keys()
        .cloned()
        .collect();
    assert_eq!(
        keys,
        [
            "orderId",
            "lineId",
            "sku",
            "quantity",
            "unitPriceCents",
            "lineCount"
        ]
    );
    assert!(
        added.contains("\"sku\":\"BOLT-1\",\"quantity\":2,\"unitPriceCents\":150,\"lineCount\":1"),
        "{added}"
    );
    let placed = &rows[0].1;
    assert_eq!(placed["subject"], format!("sales.order.{}", order.as_str()));
    assert_eq!(
        placed["messageGroup"],
        format!("sales:order:{}", order.as_str())
    );
    assert_eq!(placed["clientCode"], "acme");
    assert_eq!(placed["contextData"][0]["value"], "user-1");
    let audit = &rows[1].1;
    assert_eq!(
        (audit["entityType"].as_str(), audit["operation"].as_str()),
        (Some("Order"), Some("place-order"))
    );
    assert_eq!(audit["operationData"], r#"{"customer":"Acme"}"#);

    let summary = summaries(d.app.use_cases.db(), d.ctx.client())
        .await
        .unwrap();
    assert_eq!(summary.len(), 1);
    assert_eq!(
        (
            summary[0].status,
            summary[0].line_count,
            summary[0].total_cents
        ),
        (OrderStatus::Cancelled, 2, 600)
    );
    assert_eq!(summary[0].first_sku.as_deref(), Some("BOLT-1"));
}

#[tokio::test]
async fn a_rejected_command_writes_nothing() {
    let d = start(Policies::default()).await;
    let order = d.place(&d.ctx, "Acme").await.unwrap();
    d.cancel(&d.ctx, &order).await.unwrap();
    let before = d.outbox().await.len();
    assert_eq!(
        code(d.cancel(&d.ctx, &order).await),
        (ErrorKind::BusinessRule, "ORDER_NOT_OPEN".into())
    );
    assert_eq!(
        code(d.add(&d.ctx, &order, "BOLT-1").await),
        (ErrorKind::BusinessRule, "ORDER_NOT_OPEN".into())
    );
    let missing: OrderId = "ord_0ABCDEFGHJKMN".parse().unwrap();
    assert_eq!(
        code(d.add(&d.ctx, &missing, "BOLT-1").await),
        (ErrorKind::NotFound, "ORDER_NOT_FOUND".into())
    );
    let anonymous = Context::new(ClientId::new("acme").unwrap());
    assert_eq!(
        code(d.place(&anonymous, "Acme").await),
        (ErrorKind::Authorization, "PRINCIPAL_REQUIRED".into())
    );
    assert_eq!(
        code(d.place(&d.ctx, "   ").await),
        (ErrorKind::Validation, "CUSTOMER_REQUIRED".into())
    );
    assert_eq!(
        code(AddLine::parse(order.clone(), "X", 0, 1)),
        (ErrorKind::Validation, "QUANTITY_INVALID".into())
    );
    assert_eq!(
        code(AddLine::parse(order.clone(), "X", 1, -1)),
        (ErrorKind::Validation, "PRICE_INVALID".into())
    );
    assert_eq!(
        code(AddLine::parse(order.clone(), " ", 1, 1)),
        (ErrorKind::Validation, "SKU_REQUIRED".into())
    );
    assert_eq!(
        code(CancelOrder::parse(order, " ")),
        (ErrorKind::Validation, "REASON_REQUIRED".into())
    );
    assert_eq!(d.outbox().await.len(), before);
}

#[tokio::test]
async fn another_client_cannot_see_or_change_the_order() {
    let d = start(Policies::default()).await;
    let order = d.place(&d.ctx, "Acme").await.unwrap();
    let other = Context::new(ClientId::new("other").unwrap())
        .with_principal(PrincipalId::new("user-2").unwrap());
    assert_eq!(
        code(d.add(&other, &order, "BOLT-1").await),
        (ErrorKind::NotFound, "ORDER_NOT_FOUND".into())
    );
    assert_eq!(
        code(d.cancel(&other, &order).await),
        (ErrorKind::NotFound, "ORDER_NOT_FOUND".into())
    );
    assert!(
        summaries(d.app.use_cases.db(), other.client())
            .await
            .unwrap()
            .is_empty()
    );
}

#[tokio::test]
async fn concurrent_line_additions_all_land_under_retry_and_the_version_counts_them() {
    let d = start(Policies {
        add_line: ConflictPolicy::retry(10, Duration::from_millis(5)),
        cancel: ConflictPolicy::Fail,
    })
    .await;
    let order = d.place(&d.ctx, "Acme").await.unwrap();
    let tasks: Vec<_> = (0..4)
        .map(|i| {
            let (app, ctx, order) = (d.app.clone(), d.ctx.clone(), order.clone());
            tokio::spawn(async move {
                let cmd = AddLine::parse(order, &format!("SKU-{i}"), 1, 10).unwrap();
                app.use_cases
                    .run(&app.orders.add_line, &ctx, cmd)
                    .await
                    .map(|_| ())
            })
        })
        .collect();
    for t in tasks {
        t.await.unwrap().unwrap();
    }
    let (version, lines): (i64, i64) = sqlx::query_as(
        "SELECT o.version, (SELECT count(*) FROM order_lines l WHERE l.order_id = o.id) FROM orders o WHERE o.id = $1",
    )
    .bind(order.as_str())
    .fetch_one(&d.pool)
    .await
    .unwrap();
    assert_eq!((version, lines), (5, 4));
}

fn history_rows(file: &str) -> Vec<(String, i32, String, String)> {
    let doc: Value = serde_json::from_str(&fs::read_to_string(conformance(file)).unwrap()).unwrap();
    let module = doc["module"].as_str().unwrap().to_owned();
    doc["rows"]
        .as_array()
        .unwrap()
        .iter()
        .map(|r| {
            (
                module.clone(),
                i32::try_from(r["version"].as_i64().unwrap()).unwrap(),
                r["description"].as_str().unwrap().to_owned(),
                r["checksum"].as_str().unwrap().to_owned(),
            )
        })
        .collect()
}

#[tokio::test]
async fn runs_against_a_database_first_migrated_by_the_java_demo() {
    // The database the Java demo leaves: its runner's history table and rows (from the vectors the
    // Java tests write), the same files applied.
    let db = fw_test::postgres()
        .await
        .unwrap()
        .fresh_database()
        .await
        .unwrap();
    let pool = PgPool::connect(&db.url).await.unwrap();
    sqlx::query(migrations::CREATE_HISTORY)
        .execute(&pool)
        .await
        .unwrap();
    let mut java_rows = Vec::new();
    for (set, file) in [
        (fw_usecase::migration_set(), "migrations/outbox.json"),
        (orders_demo::migration_set(), "migrations/orders.json"),
    ] {
        let rows = history_rows(file);
        for (f, (module, version, description, sum)) in set.files().iter().zip(&rows) {
            assert_eq!(*sum, migrations::checksum(f.bytes()), "{}", f.name());
            sqlx::raw_sql(str::from_utf8(f.bytes()).unwrap())
                .execute(&pool)
                .await
                .unwrap();
            sqlx::query(
                "INSERT INTO fw_schema_history VALUES ($1, $2, $3, $4, now() - interval '1 day')",
            )
            .bind(module)
            .bind(version)
            .bind(description)
            .bind(sum)
            .execute(&pool)
            .await
            .unwrap();
        }
        java_rows.extend(rows);
    }
    let d = start_on(&db, Policies::default()).await;
    let history: Vec<(String, i32, String, String)> =
        sqlx::query_as("SELECT module, version, description, checksum FROM fw_schema_history ORDER BY applied_at, module, version")
            .fetch_all(&pool)
            .await
            .unwrap();
    assert_eq!(
        history, java_rows,
        "the Rust runner accepted the Java history and applied nothing"
    );
    let order = d.place(&d.ctx, "Acme").await.unwrap();
    assert_eq!(d.add(&d.ctx, &order, "BOLT-1").await.unwrap(), 1);
}
