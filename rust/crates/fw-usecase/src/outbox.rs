//! The outbox (write-path contract sections 7 and 7a): events, dispatch jobs and audit rows written
//! to `outbox_messages` in the attempt's transaction, in the FlowCatalyst SDK's table and payload
//! shapes, so the platform's outbox processor reads them unchanged.

use std::error::Error;
use std::fmt;
use std::sync::Arc;

use chrono::{DateTime, SecondsFormat, Utc};
use fw_core::{BoxFuture, ClientId, Clock, Context, Ids, MigrationFile, MigrationSet, ModuleId};
use fw_db::{Tx, number_placeholders};
use serde::Serialize;
use serde_json::Value;
use sqlx::types::JsonValue;

use crate::dispatch::DispatchJob;
use crate::event::ErasedEvent;

/// What a sink failure carries (logged by the runner, never shown to a caller).
pub type SinkError = Box<dyn Error + Send + Sync>;

/// The SDK's insert as shared with the Java writer (`conformance/sql/outbox/insert.sql`, JDBC `?`
/// placeholders), so a row from here and a row from the SDK have the same shape. The writer numbers
/// the placeholders (`$1`, `$2`, ...) when it is built.
pub const INSERT_SQL: &str = include_str!("sql/insert.sql");

const TYPE_EVENT: &str = "EVENT";
const TYPE_AUDIT_LOG: &str = "AUDIT_LOG";
const TYPE_DISPATCH_JOB: &str = "DISPATCH_JOB";

/// The migration set of the outbox table, recorded under the Java module id `outbox`: `V0001` is
/// the FlowCatalyst Java SDK's `001_create_outbox_messages.sql` byte for byte, `V0002` widens
/// `client_id` to 100 characters.
#[must_use]
pub fn migration_set() -> MigrationSet {
    MigrationSet::new(
        ModuleId::of("outbox"),
        [
            MigrationFile::embedded(
                "V0001__create_outbox_messages.sql",
                include_bytes!("../migrations/outbox/V0001__create_outbox_messages.sql"),
            ),
            MigrationFile::embedded(
                "V0002__widen_outbox_client_id.sql",
                include_bytes!("../migrations/outbox/V0002__widen_outbox_client_id.sql"),
            ),
        ],
    )
}

/// Command data for the audit row, in insertion order (the Java `AuditData` map).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct AuditData(Vec<(String, Value)>);

impl AuditData {
    /// No entries yet.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Adds an entry.
    #[must_use]
    pub fn with(mut self, key: impl Into<String>, value: impl Into<Value>) -> Self {
        self.0.push((key.into(), value.into()));
        self
    }

    fn to_json(&self) -> Result<String, serde_json::Error> {
        let map: serde_json::Map<String, Value> = self.0.iter().cloned().collect();
        serde_json::to_string(&map)
    }
}

/// One audit row, as a use case run produces it.
#[derive(Debug, Clone, PartialEq)]
pub struct AuditEntry {
    /// The first aggregate's type (or the first event's subject's aggregate segment).
    pub entity_type: String,
    /// The first aggregate's id (or the first event's subject's id).
    pub entity_id: String,
    /// The use case name.
    pub operation: String,
    /// The command's audit data, when the use case gives some.
    pub data: Option<AuditData>,
    /// The principal, when one acted.
    pub principal_id: Option<String>,
    /// When, from the application's clock.
    pub performed_at: DateTime<Utc>,
    /// The first event's source.
    pub source: String,
    /// The context's correlation id.
    pub correlation_id: String,
    /// The context's application, when known.
    pub application_code: Option<String>,
    /// The client.
    pub client: ClientId,
}

/// Where the runner writes events, dispatch jobs and the audit row. [`OutboxWriter`] in
/// production; tests may substitute a recording or failing sink.
pub trait Sink: Send + Sync {
    /// Writes the events, in step order.
    fn write_events<'a>(
        &'a self,
        tx: &'a mut Tx,
        ctx: &'a Context,
        events: &'a [&'a dyn ErasedEvent],
    ) -> BoxFuture<'a, Result<(), SinkError>>;

    /// Writes dispatch jobs. A sink that does not support them fails the attempt at step `OUTBOX`.
    fn write_dispatch_jobs<'a>(
        &'a self,
        _tx: &'a mut Tx,
        _ctx: &'a Context,
        _jobs: &'a [DispatchJob],
    ) -> BoxFuture<'a, Result<(), SinkError>> {
        Box::pin(async { Err::<(), SinkError>("this sink does not write dispatch jobs".into()) })
    }

    /// Writes the audit row.
    fn write_audit<'a>(
        &'a self,
        tx: &'a mut Tx,
        entry: &'a AuditEntry,
    ) -> BoxFuture<'a, Result<(), SinkError>>;
}

/// The Java `Instant.toString()`: seconds always, the fraction in groups of three digits when
/// there is one, `Z`.
#[must_use]
pub fn instant_text(t: DateTime<Utc>) -> String {
    t.to_rfc3339_opts(SecondsFormat::AutoSi, true)
}

/// Writes the outbox rows (the SDK's table and payloads).
#[derive(Clone)]
pub struct OutboxWriter {
    ids: Ids,
    clock: Arc<dyn Clock>,
    insert: String,
}

impl fmt::Debug for OutboxWriter {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("OutboxWriter")
    }
}

struct Row {
    id: String,
    kind: &'static str,
    group: Option<String>,
    payload: String,
    client_id: String,
}

#[derive(Serialize)]
struct Entry<'a> {
    key: &'a str,
    value: &'a str,
}

/// The SDK's `CreateEventDto`, in the Java writer's key order.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct EventPayload<'a> {
    spec_version: &'a str,
    #[serde(rename = "type")]
    event_type: &'a str,
    source: &'a str,
    subject: &'a str,
    data: String,
    correlation_id: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    causation_id: Option<&'a str>,
    deduplication_id: &'a str,
    message_group: &'a str,
    client_code: &'a str,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    context_data: Vec<Entry<'a>>,
}

/// The SDK's `CreateAuditLogDto`, in the Java writer's key order.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AuditPayload<'a> {
    entity_type: &'a str,
    entity_id: &'a str,
    operation: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    operation_data: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    principal_id: Option<&'a str>,
    performed_at: String,
    source: &'a str,
    correlation_id: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    application_code: Option<&'a str>,
    client_code: &'a str,
}

impl OutboxWriter {
    /// A writer: ids for audit rows and jobs without one, the clock for `created_at`.
    #[must_use]
    pub fn new(ids: Ids, clock: Arc<dyn Clock>) -> Self {
        Self {
            ids,
            clock,
            insert: number_placeholders(INSERT_SQL),
        }
    }

    fn event_row(ctx: &Context, event: &dyn ErasedEvent) -> Result<Row, serde_json::Error> {
        let meta = event.metadata();
        let mut context_data = Vec::new();
        if let Some(p) = meta.principal_id() {
            context_data.push(Entry {
                key: "principalId",
                value: p,
            });
        }
        if let Some(e) = meta.execution_id() {
            context_data.push(Entry {
                key: "executionId",
                value: e,
            });
        }
        let payload = serde_json::to_string(&EventPayload {
            spec_version: meta.spec_version(),
            event_type: meta.event_type().as_str(),
            source: meta.source(),
            subject: meta.subject(),
            data: event.data_json()?,
            correlation_id: meta.correlation_id(),
            causation_id: meta.causation_id(),
            deduplication_id: meta.event_id(),
            message_group: meta.message_group(),
            client_code: ctx.client().as_str(),
            context_data,
        })?;
        Ok(Row {
            id: meta.event_id().to_owned(),
            kind: TYPE_EVENT,
            group: Some(meta.message_group().to_owned()),
            payload,
            client_id: ctx.client().as_str().to_owned(),
        })
    }

    fn audit_payload(entry: &AuditEntry) -> Result<String, serde_json::Error> {
        serde_json::to_string(&AuditPayload {
            entity_type: &entry.entity_type,
            entity_id: &entry.entity_id,
            operation: &entry.operation,
            operation_data: entry.data.as_ref().map(AuditData::to_json).transpose()?,
            principal_id: entry.principal_id.as_deref(),
            performed_at: instant_text(entry.performed_at),
            source: &entry.source,
            correlation_id: &entry.correlation_id,
            application_code: entry.application_code.as_deref(),
            client_code: entry.client.as_str(),
        })
    }

    async fn insert(&self, tx: &mut Tx, rows: Vec<Row>) -> Result<(), SinkError> {
        let now = self.clock.now();
        for row in rows {
            let size = i32::try_from(row.payload.len())?;
            sqlx::query(&self.insert)
                .bind(&row.id)
                .bind(row.kind)
                .bind(row.group.as_deref())
                .bind(&row.payload)
                .bind(0_i16)
                .bind(now)
                .bind(now)
                .bind(&row.client_id)
                .bind(size)
                .bind(None::<JsonValue>)
                .execute(tx.conn())
                .await?;
        }
        Ok(())
    }
}

impl Sink for OutboxWriter {
    fn write_events<'a>(
        &'a self,
        tx: &'a mut Tx,
        ctx: &'a Context,
        events: &'a [&'a dyn ErasedEvent],
    ) -> BoxFuture<'a, Result<(), SinkError>> {
        Box::pin(async move {
            let rows = events
                .iter()
                .map(|e| Self::event_row(ctx, *e))
                .collect::<Result<Vec<_>, _>>()?;
            self.insert(tx, rows).await
        })
    }

    fn write_dispatch_jobs<'a>(
        &'a self,
        tx: &'a mut Tx,
        ctx: &'a Context,
        jobs: &'a [DispatchJob],
    ) -> BoxFuture<'a, Result<(), SinkError>> {
        Box::pin(async move {
            let mut rows = Vec::with_capacity(jobs.len());
            for job in jobs {
                let id = job.id().map_or_else(|| self.ids.generate(), str::to_owned);
                rows.push(Row {
                    payload: job.payload_json(&id, ctx.correlation().as_str())?,
                    id,
                    kind: TYPE_DISPATCH_JOB,
                    group: job.message_group().map(str::to_owned),
                    client_id: ctx.client().as_str().to_owned(),
                });
            }
            self.insert(tx, rows).await
        })
    }

    fn write_audit<'a>(
        &'a self,
        tx: &'a mut Tx,
        entry: &'a AuditEntry,
    ) -> BoxFuture<'a, Result<(), SinkError>> {
        Box::pin(async move {
            let row = Row {
                id: self.ids.generate(),
                kind: TYPE_AUDIT_LOG,
                group: None,
                payload: Self::audit_payload(entry)?,
                client_id: entry.client.as_str().to_owned(),
            };
            self.insert(tx, vec![row]).await
        })
    }
}

#[cfg(test)]
mod tests {
    use chrono::TimeZone;

    use super::*;

    #[test]
    fn instants_print_as_java_does() {
        let t = |ms: i64, extra_nanos: u32| {
            Utc.timestamp_millis_opt(ms).unwrap()
                + chrono::Duration::nanoseconds(i64::from(extra_nanos))
        };
        assert_eq!(
            instant_text(t(1_759_918_530_000, 0)),
            "2025-10-08T10:15:30Z"
        );
        assert_eq!(
            instant_text(t(1_759_918_531_500, 0)),
            "2025-10-08T10:15:31.500Z"
        );
        assert_eq!(
            instant_text(t(1_759_918_530_000, 123_000)),
            "2025-10-08T10:15:30.000123Z"
        );
        assert_eq!(
            instant_text(t(1_759_918_530_000, 1)),
            "2025-10-08T10:15:30.000000001Z"
        );
    }
}
