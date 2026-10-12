//! Dispatch jobs a plan writes to the outbox (write-path contract section 7a): the platform's
//! outbox processor sends each row's payload, unchanged, to `POST /api/dispatch-jobs/batch`.
//!
//! Every rule the platform would enforce by refusing the whole batch is checked when the job is
//! built, with the Java builder's messages, so a job that could never be accepted is never written.

use serde::Serialize;

/// The platform's job kinds.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// Fanned out from an event (the platform's default).
    Event,
    /// Created by an application for its own work (the framework's default).
    Task,
}

impl Kind {
    fn as_str(self) -> &'static str {
        match self {
            Self::Event => "EVENT",
            Self::Task => "TASK",
        }
    }
}

/// How a message group behaves when a delivery fails.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    /// No ordering.
    Immediate,
    /// Continue with the next job of the group after a failure (the platform's default).
    NextOnError,
    /// Hold the group until the failed job succeeds.
    BlockOnError,
}

impl Mode {
    fn as_str(self) -> &'static str {
        match self {
            Self::Immediate => "IMMEDIATE",
            Self::NextOnError => "NEXT_ON_ERROR",
            Self::BlockOnError => "BLOCK_ON_ERROR",
        }
    }
}

/// The platform's dispatch queues.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Queue {
    /// The normal queue.
    Default,
    /// Delivered ahead of the normal queue.
    HighPriority,
}

impl Queue {
    fn as_str(self) -> &'static str {
        match self {
            Self::Default => "DEFAULT",
            Self::HighPriority => "HIGH_PRIORITY",
        }
    }
}

/// A job the platform would refuse, with the Java builder's message.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("{0}")]
pub struct DispatchJobError(String);

const TEXT_MAX: usize = 255;
const METADATA_MAX: usize = 100;

/// A validated dispatch job.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DispatchJob {
    id: Option<String>,
    kind: Kind,
    code: String,
    source: String,
    subject: Option<String>,
    target_url: String,
    payload: String,
    payload_content_type: String,
    data_only: bool,
    external_id: Option<String>,
    event_id: Option<String>,
    platform_client_id: Option<String>,
    subscription_id: Option<String>,
    service_account_id: Option<String>,
    dispatch_pool_id: Option<String>,
    message_group: Option<String>,
    mode: Option<Mode>,
    sequence: Option<i32>,
    timeout_seconds: Option<i32>,
    max_retries: Option<i32>,
    metadata: Vec<(String, String)>,
    queue: Option<Queue>,
    descriptor: Option<String>,
}

/// Builds a [`DispatchJob`]; [`DispatchJobBuilder::build`] validates.
#[derive(Debug, Clone)]
pub struct DispatchJobBuilder {
    job: DispatchJob,
    source_set: bool,
    payload_error: Option<String>,
}

fn len(s: &str) -> usize {
    s.chars().count()
}

fn is_line_terminator(c: char) -> bool {
    matches!(c, '\n' | '\r' | '\u{85}' | '\u{2028}' | '\u{2029}')
}

fn is_java_space(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r')
}

/// `[a-z][a-z0-9-]*:\S.*` (Java's `.` stops at a line terminator).
fn code_ok(code: &str) -> bool {
    let Some((app, rest)) = code.split_once(':') else {
        return false;
    };
    let mut app_chars = app.chars();
    let app_ok = app_chars.next().is_some_and(|c| c.is_ascii_lowercase())
        && app_chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-');
    let mut rest_chars = rest.chars();
    app_ok
        && rest_chars.next().is_some_and(|c| !is_java_space(c))
        && !rest.chars().any(is_line_terminator)
}

/// An absolute `http` or `https` URL with a host and no user info.
fn target_ok(url: &str) -> bool {
    let Some((scheme, rest)) = url.split_once("://") else {
        return false;
    };
    if !(scheme.eq_ignore_ascii_case("http") || scheme.eq_ignore_ascii_case("https")) {
        return false;
    }
    if url.chars().any(|c| c.is_whitespace() || c.is_control()) {
        return false;
    }
    let authority = rest.split(['/', '?', '#']).next().unwrap_or_default();
    if authority.contains('@') {
        return false;
    }
    let host = if authority.starts_with('[') {
        authority
            .split(']')
            .next()
            .unwrap_or_default()
            .trim_start_matches('[')
    } else {
        authority.split(':').next().unwrap_or_default()
    };
    !host.is_empty()
}

fn platform_id_ok(id: &str) -> bool {
    (1..=17).contains(&id.len())
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

impl DispatchJob {
    /// Starts a job: `code` is `{application}:{rest}`; `target_url` an absolute http(s) URL.
    #[must_use]
    pub fn builder(code: &str, target_url: &str) -> DispatchJobBuilder {
        DispatchJobBuilder {
            job: DispatchJob {
                id: None,
                kind: Kind::Task,
                code: code.to_owned(),
                source: String::new(),
                subject: None,
                target_url: target_url.to_owned(),
                payload: "{}".to_owned(),
                payload_content_type: "application/json".to_owned(),
                data_only: false,
                external_id: None,
                event_id: None,
                platform_client_id: None,
                subscription_id: None,
                service_account_id: None,
                dispatch_pool_id: None,
                message_group: None,
                mode: None,
                sequence: None,
                timeout_seconds: None,
                max_retries: None,
                metadata: Vec::new(),
                queue: None,
                descriptor: None,
            },
            source_set: false,
            payload_error: None,
        }
    }

    /// The job's id, when one was given (else the writer generates a TSID).
    #[must_use]
    pub fn id(&self) -> Option<&str> {
        self.id.as_deref()
    }

    /// The message group, when one was given.
    #[must_use]
    pub fn message_group(&self) -> Option<&str> {
        self.message_group.as_deref()
    }

    fn validate(&self) -> Result<(), DispatchJobError> {
        let e = |m: String| Err(DispatchJobError(m));
        if len(&self.code) > TEXT_MAX || !code_ok(&self.code) {
            return e(format!(
                "dispatch job code must be {{application}}:{{rest}} (application [a-z][a-z0-9-]*), at most {TEXT_MAX} characters: {}",
                self.code
            ));
        }
        if !target_ok(&self.target_url) {
            return e("dispatch job targetUrl must be an absolute http(s) URL with a host and no user info".to_owned());
        }
        let text = |field: &str, v: &str| {
            if v.trim().is_empty() || len(v) > TEXT_MAX {
                Err(DispatchJobError(format!(
                    "{field} must be 1 to {TEXT_MAX} characters"
                )))
            } else {
                Ok(())
            }
        };
        text("source", &self.source)?;
        for (field, v) in [
            ("subject", &self.subject),
            ("externalId", &self.external_id),
            ("messageGroup", &self.message_group),
            ("descriptor", &self.descriptor),
        ] {
            if let Some(v) = v {
                text(field, v)?;
            }
        }
        if self.payload_content_type.trim().is_empty() || len(&self.payload_content_type) > 100 {
            return e("payloadContentType must be 1 to 100 characters".to_owned());
        }
        for (field, v) in [
            ("id", &self.id),
            ("eventId", &self.event_id),
            ("platformClientId", &self.platform_client_id),
            ("subscriptionId", &self.subscription_id),
            ("serviceAccountId", &self.service_account_id),
            ("dispatchPoolId", &self.dispatch_pool_id),
        ] {
            if v.as_deref().is_some_and(|v| !platform_id_ok(v)) {
                return e(format!("{field} must match [0-9A-Za-z_-]{{1,17}}"));
            }
        }
        for (field, v) in [
            ("sequence", self.sequence),
            ("timeoutSeconds", self.timeout_seconds),
            ("maxRetries", self.max_retries),
        ] {
            if v.is_some_and(|v| v < 1) {
                return e(format!(
                    "{field} must be at least 1 (the platform turns 0 into its default)"
                ));
            }
        }
        if self.metadata.len() > METADATA_MAX {
            return e(format!("at most {METADATA_MAX} metadata entries"));
        }
        if self.metadata.iter().any(|(k, _)| k.trim().is_empty()) {
            return e("metadata keys must not be blank".to_owned());
        }
        Ok(())
    }

    /// The batch item the platform reads (Go `BatchItem`), with only the fields it reads, in the
    /// Java writer's order; `metadata` is an array of `{key, value}`, never an object.
    pub(crate) fn payload_json(
        &self,
        id: &str,
        correlation_id: &str,
    ) -> Result<String, serde_json::Error> {
        #[derive(Serialize)]
        #[serde(rename_all = "camelCase")]
        struct Entry<'a> {
            key: &'a str,
            value: &'a str,
        }
        #[derive(Serialize)]
        #[serde(rename_all = "camelCase")]
        struct Item<'a> {
            id: &'a str,
            kind: &'a str,
            code: &'a str,
            source: &'a str,
            #[serde(skip_serializing_if = "Option::is_none")]
            subject: Option<&'a str>,
            target_url: &'a str,
            payload: &'a str,
            payload_content_type: &'a str,
            data_only: bool,
            #[serde(skip_serializing_if = "Option::is_none")]
            external_id: Option<&'a str>,
            #[serde(skip_serializing_if = "Option::is_none")]
            event_id: Option<&'a str>,
            correlation_id: &'a str,
            #[serde(skip_serializing_if = "Option::is_none")]
            client_id: Option<&'a str>,
            #[serde(skip_serializing_if = "Option::is_none")]
            subscription_id: Option<&'a str>,
            #[serde(skip_serializing_if = "Option::is_none")]
            service_account_id: Option<&'a str>,
            #[serde(skip_serializing_if = "Option::is_none")]
            dispatch_pool_id: Option<&'a str>,
            #[serde(skip_serializing_if = "Option::is_none")]
            message_group: Option<&'a str>,
            #[serde(skip_serializing_if = "Option::is_none")]
            mode: Option<&'a str>,
            #[serde(skip_serializing_if = "Option::is_none")]
            sequence: Option<i32>,
            #[serde(skip_serializing_if = "Option::is_none")]
            timeout_seconds: Option<i32>,
            #[serde(skip_serializing_if = "Option::is_none")]
            max_retries: Option<i32>,
            #[serde(skip_serializing_if = "Option::is_none")]
            metadata: Option<Vec<Entry<'a>>>,
            #[serde(skip_serializing_if = "Option::is_none")]
            queue: Option<&'a str>,
            #[serde(skip_serializing_if = "Option::is_none")]
            descriptor: Option<&'a str>,
        }
        serde_json::to_string(&Item {
            id,
            kind: self.kind.as_str(),
            code: &self.code,
            source: &self.source,
            subject: self.subject.as_deref(),
            target_url: &self.target_url,
            payload: &self.payload,
            payload_content_type: &self.payload_content_type,
            data_only: self.data_only,
            external_id: self.external_id.as_deref(),
            event_id: self.event_id.as_deref(),
            correlation_id,
            client_id: self.platform_client_id.as_deref(),
            subscription_id: self.subscription_id.as_deref(),
            service_account_id: self.service_account_id.as_deref(),
            dispatch_pool_id: self.dispatch_pool_id.as_deref(),
            message_group: self.message_group.as_deref(),
            mode: self.mode.map(Mode::as_str),
            sequence: self.sequence,
            timeout_seconds: self.timeout_seconds,
            max_retries: self.max_retries,
            metadata: (!self.metadata.is_empty()).then(|| {
                self.metadata
                    .iter()
                    .map(|(k, v)| Entry { key: k, value: v })
                    .collect()
            }),
            queue: self.queue.map(Queue::as_str),
            descriptor: self.descriptor.as_deref(),
        })
    }
}

impl DispatchJobBuilder {
    /// The job's id, `[0-9A-Za-z_-]{1,17}`; without one the writer generates a TSID. Being in the
    /// payload, it makes a re-sent batch create a job with the same id.
    #[must_use]
    pub fn id(mut self, id: &str) -> Self {
        self.job.id = Some(id.to_owned());
        self
    }

    /// `EVENT` or `TASK` (default `TASK`).
    #[must_use]
    pub fn kind(mut self, kind: Kind) -> Self {
        self.job.kind = kind;
        self
    }

    /// The source (default the code's application segment).
    #[must_use]
    pub fn source(mut self, source: &str) -> Self {
        self.job.source = source.to_owned();
        self.source_set = true;
        self
    }

    /// The subject.
    #[must_use]
    pub fn subject(mut self, subject: &str) -> Self {
        self.job.subject = Some(subject.to_owned());
        self
    }

    /// A payload as text with its content type.
    #[must_use]
    pub fn payload(mut self, text: &str, content_type: &str) -> Self {
        self.job.payload = text.to_owned();
        self.job.payload_content_type = content_type.to_owned();
        self
    }

    /// A JSON payload (`application/json`).
    #[must_use]
    pub fn json_payload(mut self, value: &impl Serialize) -> Self {
        match serde_json::to_string(value) {
            Ok(text) => {
                self.job.payload = text;
                self.job.payload_content_type = "application/json".to_owned();
            }
            Err(e) => {
                self.payload_error = Some(format!("the JSON payload does not serialise: {e}"))
            }
        }
        self
    }

    /// Deliver the payload alone instead of the platform envelope (default false).
    #[must_use]
    pub fn data_only(mut self, yes: bool) -> Self {
        self.job.data_only = yes;
        self
    }

    /// An external id.
    #[must_use]
    pub fn external_id(mut self, v: &str) -> Self {
        self.job.external_id = Some(v.to_owned());
        self
    }

    /// The platform event id this job belongs to.
    #[must_use]
    pub fn event_id(mut self, v: &str) -> Self {
        self.job.event_id = Some(v.to_owned());
        self
    }

    /// The platform's client **id** (written as `clientId`); never derived from the `Context`.
    #[must_use]
    pub fn platform_client_id(mut self, v: &str) -> Self {
        self.job.platform_client_id = Some(v.to_owned());
        self
    }

    /// A subscription id.
    #[must_use]
    pub fn subscription_id(mut self, v: &str) -> Self {
        self.job.subscription_id = Some(v.to_owned());
        self
    }

    /// A service account id.
    #[must_use]
    pub fn service_account_id(mut self, v: &str) -> Self {
        self.job.service_account_id = Some(v.to_owned());
        self
    }

    /// A dispatch pool id.
    #[must_use]
    pub fn dispatch_pool_id(mut self, v: &str) -> Self {
        self.job.dispatch_pool_id = Some(v.to_owned());
        self
    }

    /// A message group.
    #[must_use]
    pub fn message_group(mut self, v: &str) -> Self {
        self.job.message_group = Some(v.to_owned());
        self
    }

    /// The group mode.
    #[must_use]
    pub fn mode(mut self, mode: Mode) -> Self {
        self.job.mode = Some(mode);
        self
    }

    /// The sequence (at least 1).
    #[must_use]
    pub fn sequence(mut self, v: i32) -> Self {
        self.job.sequence = Some(v);
        self
    }

    /// The delivery timeout in seconds (at least 1).
    #[must_use]
    pub fn timeout_seconds(mut self, v: i32) -> Self {
        self.job.timeout_seconds = Some(v);
        self
    }

    /// The most retries (at least 1).
    #[must_use]
    pub fn max_retries(mut self, v: i32) -> Self {
        self.job.max_retries = Some(v);
        self
    }

    /// A metadata pair; a second value for a key replaces the first, keeping its position.
    #[must_use]
    pub fn metadata(mut self, key: &str, value: &str) -> Self {
        match self.job.metadata.iter_mut().find(|(k, _)| k == key) {
            Some(entry) => entry.1 = value.to_owned(),
            None => self.job.metadata.push((key.to_owned(), value.to_owned())),
        }
        self
    }

    /// The queue.
    #[must_use]
    pub fn queue(mut self, queue: Queue) -> Self {
        self.job.queue = Some(queue);
        self
    }

    /// A descriptor.
    #[must_use]
    pub fn descriptor(mut self, v: &str) -> Self {
        self.job.descriptor = Some(v.to_owned());
        self
    }

    /// Validates and builds.
    ///
    /// # Errors
    ///
    /// The first rule broken, with the Java builder's message.
    pub fn build(self) -> Result<DispatchJob, DispatchJobError> {
        let mut job = self.job;
        if let Some(e) = self.payload_error {
            return Err(DispatchJobError(e));
        }
        if !self.source_set {
            job.source = match job.code.split_once(':') {
                Some((app, _)) if !app.is_empty() => app.to_owned(),
                _ => job.code.clone(),
            };
        }
        job.validate()?;
        Ok(job)
    }
}
