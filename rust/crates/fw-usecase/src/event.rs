//! Domain events and their metadata (write-path contract section 6).

use std::fmt;
use std::sync::Arc;

use chrono::{DateTime, Utc};
use fw_core::{Clock, Context, Ids};
use serde::Serialize;
use serde_json::Value;

/// The qualified type of an event, `{application}:{domain}:{aggregate}:{action}`, each segment
/// `[a-z][a-z0-9-]*`; [`EventType::of`] checks a literal during constant evaluation.
#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub struct EventType {
    text: &'static str,
}

const fn segments_ok(text: &str) -> bool {
    let b = text.as_bytes();
    let mut segments = 0;
    let mut start = true;
    let mut i = 0;
    while i < b.len() {
        let c = b[i];
        if c == b':' {
            if start {
                return false;
            }
            segments += 1;
            start = true;
        } else if start {
            if !c.is_ascii_lowercase() {
                return false;
            }
            start = false;
        } else if !(c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-') {
            return false;
        }
        i += 1;
    }
    !start && segments == 3
}

impl EventType {
    /// An event type.
    ///
    /// # Panics
    ///
    /// When `text` is not four `[a-z][a-z0-9-]*` segments joined by `:`; in a `const` that is a
    /// compile error.
    #[must_use]
    pub const fn of(text: &'static str) -> Self {
        assert!(
            segments_ok(text),
            "event type must be {{application}}:{{domain}}:{{aggregate}}:{{action}}, each [a-z][a-z0-9-]*"
        );
        Self { text }
    }

    /// The type text.
    #[must_use]
    pub const fn as_str(&self) -> &'static str {
        self.text
    }

    fn part(&self, n: usize) -> &'static str {
        self.text.split(':').nth(n).unwrap_or_default()
    }

    /// The application segment.
    #[must_use]
    pub fn application(&self) -> &'static str {
        self.part(0)
    }

    /// The domain segment.
    #[must_use]
    pub fn domain(&self) -> &'static str {
        self.part(1)
    }

    /// The aggregate segment.
    #[must_use]
    pub fn aggregate(&self) -> &'static str {
        self.part(2)
    }

    /// The action segment.
    #[must_use]
    pub fn action(&self) -> &'static str {
        self.part(3)
    }

    /// `{application}:{domain}`.
    #[must_use]
    pub fn source(&self) -> String {
        format!("{}:{}", self.application(), self.domain())
    }

    /// `{domain}.{aggregate}.{id}`.
    #[must_use]
    pub fn subject(&self, id: &str) -> String {
        format!("{}.{}.{id}", self.domain(), self.aggregate())
    }

    /// The default message group, `{domain}:{aggregate}:{id}`, which keeps one aggregate's events
    /// in order.
    #[must_use]
    pub fn message_group(&self, id: &str) -> String {
        format!("{}:{}:{id}", self.domain(), self.aggregate())
    }
}

impl fmt::Debug for EventType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.text)
    }
}

/// What every event carries besides its data.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EventMetadata {
    event_id: String,
    event_type: EventType,
    source: String,
    subject: String,
    time: DateTime<Utc>,
    correlation_id: String,
    principal_id: Option<String>,
    causation_id: Option<String>,
    execution_id: Option<String>,
    message_group: String,
}

impl EventMetadata {
    /// Metadata with an explicit event id and time (tests and vectors; use cases use [`Events`]).
    #[must_use]
    pub fn new(
        event_id: impl Into<String>,
        event_type: EventType,
        subject_id: &str,
        time: DateTime<Utc>,
        ctx: &Context,
    ) -> Self {
        Self {
            event_id: event_id.into(),
            event_type,
            source: event_type.source(),
            subject: event_type.subject(subject_id),
            time,
            correlation_id: ctx.correlation().as_str().to_owned(),
            principal_id: ctx.principal().map(|p| p.as_str().to_owned()),
            causation_id: ctx.causation().map(|c| c.as_str().to_owned()),
            execution_id: ctx.execution().map(|e| e.as_str().to_owned()),
            message_group: event_type.message_group(subject_id),
        }
    }

    /// A copy with another message group.
    #[must_use]
    pub fn with_message_group(mut self, group: impl Into<String>) -> Self {
        self.message_group = group.into();
        self
    }

    /// The event id (a TSID).
    #[must_use]
    pub fn event_id(&self) -> &str {
        &self.event_id
    }

    /// The spec version, `1.0`.
    #[must_use]
    pub fn spec_version(&self) -> &'static str {
        "1.0"
    }

    /// The type.
    #[must_use]
    pub fn event_type(&self) -> EventType {
        self.event_type
    }

    /// `{application}:{domain}`.
    #[must_use]
    pub fn source(&self) -> &str {
        &self.source
    }

    /// `{domain}.{aggregate}.{id}`.
    #[must_use]
    pub fn subject(&self) -> &str {
        &self.subject
    }

    /// The time, from the application's clock.
    #[must_use]
    pub fn time(&self) -> DateTime<Utc> {
        self.time
    }

    /// The correlation id.
    #[must_use]
    pub fn correlation_id(&self) -> &str {
        &self.correlation_id
    }

    /// The principal id, when one acted.
    #[must_use]
    pub fn principal_id(&self) -> Option<&str> {
        self.principal_id.as_deref()
    }

    /// The causation id, when known.
    #[must_use]
    pub fn causation_id(&self) -> Option<&str> {
        self.causation_id.as_deref()
    }

    /// The execution id, when known.
    #[must_use]
    pub fn execution_id(&self) -> Option<&str> {
        self.execution_id.as_deref()
    }

    /// The message group.
    #[must_use]
    pub fn message_group(&self) -> &str {
        &self.message_group
    }
}

/// A domain event: its data is the struct's `Serialize` (metadata `#[serde(skip)]`), its type a
/// const-checked constant.
///
/// ```
/// use fw_usecase::{DomainEvent, EventMetadata, EventType};
/// #[derive(serde::Serialize)]
/// #[serde(rename_all = "camelCase")]
/// struct OrderPlaced {
///     #[serde(skip)]
///     meta: EventMetadata,
///     order_id: String,
///     customer: String,
/// }
/// impl DomainEvent for OrderPlaced {
///     const TYPE: EventType = EventType::of("orders:sales:order:placed");
///     fn metadata(&self) -> &EventMetadata { &self.meta }
/// }
/// ```
pub trait DomainEvent: Serialize + Send + Sync {
    /// The event's type.
    const TYPE: EventType;

    /// Its metadata.
    fn metadata(&self) -> &EventMetadata;
}

/// An event with its type erased, as the outbox writer sees it.
pub trait ErasedEvent: Send + Sync {
    /// Its metadata.
    fn metadata(&self) -> &EventMetadata;

    /// Its data as JSON text: the struct's fields, null fields left out (as the Java writer's
    /// `NON_NULL` inclusion does).
    ///
    /// # Errors
    ///
    /// When the event does not serialise.
    fn data_json(&self) -> Result<String, serde_json::Error>;
}

impl<E: DomainEvent> ErasedEvent for E {
    fn metadata(&self) -> &EventMetadata {
        DomainEvent::metadata(self)
    }

    fn data_json(&self) -> Result<String, serde_json::Error> {
        let mut value = serde_json::to_value(self)?;
        strip_nulls(&mut value);
        serde_json::to_string(&value)
    }
}

/// Removes null-valued members of objects, recursively (Jackson's `NON_NULL` property inclusion).
pub(crate) fn strip_nulls(value: &mut Value) {
    match value {
        Value::Object(map) => {
            map.retain(|_, v| !v.is_null());
            for v in map.values_mut() {
                strip_nulls(v);
            }
        }
        Value::Array(items) => items.iter_mut().for_each(strip_nulls),
        Value::Null | Value::Bool(_) | Value::Number(_) | Value::String(_) => {}
    }
}

/// Makes event metadata: a generated id, the time from the application's clock, the context's
/// correlation, principal, causation and execution ids.
#[derive(Clone)]
pub struct Events {
    clock: Arc<dyn Clock>,
    ids: Ids,
}

impl fmt::Debug for Events {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Events")
    }
}

impl Events {
    /// A factory.
    #[must_use]
    pub fn new(clock: Arc<dyn Clock>, ids: Ids) -> Self {
        Self { clock, ids }
    }

    /// Metadata for an event of type `E` about the aggregate with id `subject_id`.
    #[must_use]
    pub fn metadata<E: DomainEvent>(&self, ctx: &Context, subject_id: &str) -> EventMetadata {
        EventMetadata::new(
            self.ids.generate(),
            E::TYPE,
            subject_id,
            self.clock.now(),
            ctx,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn event_type_parts() {
        const T: EventType = EventType::of("orders:sales:order:line-added");
        assert_eq!(T.application(), "orders");
        assert_eq!(T.source(), "orders:sales");
        assert_eq!(T.subject("ord_1"), "sales.order.ord_1");
        assert_eq!(T.message_group("ord_1"), "sales:order:ord_1");
        assert!(!segments_ok("orders:sales:order"));
        assert!(!segments_ok("orders:Sales:order:placed"));
        assert!(!segments_ok("orders::order:placed"));
        assert!(!segments_ok("orders:sales:order:placed:"));
    }

    #[test]
    fn nulls_are_left_out() {
        let mut v =
            serde_json::json!({ "a": null, "b": { "c": null, "d": 1 }, "e": [ { "f": null } ] });
        strip_nulls(&mut v);
        assert_eq!(v.to_string(), r#"{"b":{"d":1},"e":[{}]}"#);
    }

    /// The known divergence from Jackson (milestones section 0): a null **map value** is left
    /// out here and kept there. A null **array element** is kept by both.
    #[test]
    fn a_null_map_value_is_dropped_and_a_null_array_element_kept() {
        use std::collections::BTreeMap;

        #[derive(serde::Serialize)]
        struct Data {
            labels: BTreeMap<&'static str, Option<&'static str>>,
            items: Vec<Option<&'static str>>,
            note: Option<&'static str>,
        }
        let data = Data {
            labels: [("k", None), ("j", Some("v"))].into(),
            items: vec![None, Some("z")],
            note: None,
        };
        let mut v = serde_json::to_value(&data).unwrap();
        strip_nulls(&mut v);
        assert_eq!(v.to_string(), r#"{"labels":{"j":"v"},"items":[null,"z"]}"#);
    }
}
