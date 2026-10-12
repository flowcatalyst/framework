//! `Context`: who is acting, for which client, in which application (core contract section 3).
//!
//! A value passed explicitly to use cases and handlers; the framework never stores it in a static,
//! a thread-local or a task-local.

use std::fmt;
use std::str::FromStr;

use serde::de::Error as DeError;
use serde::{Deserialize, Deserializer, Serialize, Serializer};

/// A text that is not a valid value of one of the context's id types.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("{kind} {rule}: '{text}'")]
pub struct IdTextError {
    kind: &'static str,
    rule: &'static str,
    text: String,
}

fn is_client_id(s: &str) -> bool {
    let n = s.chars().count();
    (1..=100).contains(&n)
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-'))
}

fn is_application_code(s: &str) -> bool {
    let b = s.as_bytes();
    !b.is_empty()
        && b[0].is_ascii_lowercase()
        && b.iter()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'-')
}

fn not_blank(s: &str) -> bool {
    !s.trim().is_empty()
}

// Six newtypes over validated text with identical conversions; the macro states the impls once:
// the constructor and the std, serde and (feature `sqlx`) sqlx impls.
macro_rules! text_id {
    ($name:ident, $check:path, $rule:literal) => {
        impl $name {
            /// Validates and wraps a text.
            ///
            /// # Errors
            ///
            #[doc = concat!("When the text ", $rule, ".")]
            pub fn new(text: impl Into<String>) -> Result<Self, IdTextError> {
                let text = text.into();
                if $check(&text) {
                    Ok(Self(text))
                } else {
                    Err(IdTextError {
                        kind: stringify!($name),
                        rule: $rule,
                        text,
                    })
                }
            }

            /// The text.
            #[must_use]
            pub fn as_str(&self) -> &str {
                &self.0
            }
        }

        impl fmt::Display for $name {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str(&self.0)
            }
        }

        impl fmt::Debug for $name {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                write!(f, "{}({:?})", stringify!($name), self.0)
            }
        }

        impl AsRef<str> for $name {
            fn as_ref(&self) -> &str {
                &self.0
            }
        }

        impl FromStr for $name {
            type Err = IdTextError;
            fn from_str(s: &str) -> Result<Self, Self::Err> {
                Self::new(s)
            }
        }

        impl TryFrom<String> for $name {
            type Error = IdTextError;
            fn try_from(s: String) -> Result<Self, Self::Error> {
                Self::new(s)
            }
        }

        impl TryFrom<&str> for $name {
            type Error = IdTextError;
            fn try_from(s: &str) -> Result<Self, Self::Error> {
                Self::new(s)
            }
        }

        impl Serialize for $name {
            fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
                s.serialize_str(&self.0)
            }
        }

        impl<'de> Deserialize<'de> for $name {
            fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
                Self::new(String::deserialize(d)?).map_err(DeError::custom)
            }
        }

        #[cfg(feature = "sqlx")]
        impl sqlx::Type<sqlx::Postgres> for $name {
            fn type_info() -> sqlx::postgres::PgTypeInfo {
                <String as sqlx::Type<sqlx::Postgres>>::type_info()
            }
            fn compatible(ty: &sqlx::postgres::PgTypeInfo) -> bool {
                <String as sqlx::Type<sqlx::Postgres>>::compatible(ty)
            }
        }

        #[cfg(feature = "sqlx")]
        impl sqlx::Encode<'_, sqlx::Postgres> for $name {
            fn encode_by_ref(
                &self,
                buf: &mut sqlx::postgres::PgArgumentBuffer,
            ) -> Result<sqlx::encode::IsNull, sqlx::error::BoxDynError> {
                <&str as sqlx::Encode<'_, sqlx::Postgres>>::encode(self.as_str(), buf)
            }
        }

        #[cfg(feature = "sqlx")]
        impl<'r> sqlx::Decode<'r, sqlx::Postgres> for $name {
            fn decode(
                value: sqlx::postgres::PgValueRef<'r>,
            ) -> Result<Self, sqlx::error::BoxDynError> {
                let text = <&str as sqlx::Decode<'r, sqlx::Postgres>>::decode(value)?;
                Ok(Self::new(text)?)
            }
        }
    };
}

/// Identifies a client (the tenant), `[A-Za-z0-9._-]{1,100}`. Always present in a [`Context`].
///
/// It never contains `:`, so it can be embedded in composite keys (locks, cache) without two
/// clients producing the same key.
#[derive(Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct ClientId(String);

text_id!(ClientId, is_client_id, "must match [A-Za-z0-9._-]{1,100}");

/// Identifies the user or service account acting. Not blank.
#[derive(Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct PrincipalId(String);

text_id!(PrincipalId, not_blank, "must not be blank");

/// The application's code, `[a-z][a-z0-9-]*`.
#[derive(Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct ApplicationCode(String);

text_id!(
    ApplicationCode,
    is_application_code,
    "must match [a-z][a-z0-9-]*"
);

/// Follows a request into logs, jobs and events. Not blank; generated when absent.
#[derive(Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct CorrelationId(String);

text_id!(CorrelationId, not_blank, "must not be blank");

/// The event or message that caused this work. Not blank.
#[derive(Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct CausationId(String);

text_id!(CausationId, not_blank, "must not be blank");

/// The job or run this work belongs to. Not blank.
#[derive(Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct ExecutionId(String);

text_id!(ExecutionId, not_blank, "must not be blank");

impl CorrelationId {
    /// A new random correlation id (a UUID, as the Java framework generates).
    #[must_use]
    pub fn generate() -> Self {
        Self(uuid::Uuid::new_v4().to_string())
    }
}

/// Who is acting, for which client, in which application.
#[derive(Clone, Debug, PartialEq, Eq)]
#[non_exhaustive]
pub struct Context {
    client: ClientId,
    principal: Option<PrincipalId>,
    application: Option<ApplicationCode>,
    correlation: CorrelationId,
    causation: Option<CausationId>,
    execution: Option<ExecutionId>,
}

impl Context {
    /// A context for a client, with a generated correlation id.
    #[must_use]
    pub fn new(client: ClientId) -> Self {
        Self {
            client,
            principal: None,
            application: None,
            correlation: CorrelationId::generate(),
            causation: None,
            execution: None,
        }
    }

    /// Copies with a principal.
    #[must_use]
    pub fn with_principal(mut self, principal: PrincipalId) -> Self {
        self.principal = Some(principal);
        self
    }

    /// Copies with an application.
    #[must_use]
    pub fn with_application(mut self, application: ApplicationCode) -> Self {
        self.application = Some(application);
        self
    }

    /// Copies with a correlation id, for example one received from a caller.
    #[must_use]
    pub fn with_correlation(mut self, correlation: CorrelationId) -> Self {
        self.correlation = correlation;
        self
    }

    /// Copies with a causation id.
    #[must_use]
    pub fn with_causation(mut self, causation: CausationId) -> Self {
        self.causation = Some(causation);
        self
    }

    /// Copies with an execution id.
    #[must_use]
    pub fn with_execution(mut self, execution: ExecutionId) -> Self {
        self.execution = Some(execution);
        self
    }

    /// The client.
    #[must_use]
    pub fn client(&self) -> &ClientId {
        &self.client
    }

    /// The principal, when one is acting.
    #[must_use]
    pub fn principal(&self) -> Option<&PrincipalId> {
        self.principal.as_ref()
    }

    /// The application, when known.
    #[must_use]
    pub fn application(&self) -> Option<&ApplicationCode> {
        self.application.as_ref()
    }

    /// The correlation id.
    #[must_use]
    pub fn correlation(&self) -> &CorrelationId {
        &self.correlation
    }

    /// The causation id, when known.
    #[must_use]
    pub fn causation(&self) -> Option<&CausationId> {
        self.causation.as_ref()
    }

    /// The execution id, when known.
    #[must_use]
    pub fn execution(&self) -> Option<&ExecutionId> {
        self.execution.as_ref()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn client_id_pattern() {
        assert!(ClientId::new("acme-01.eu_x").is_ok());
        assert!(ClientId::new("a".repeat(100)).is_ok());
        assert!(ClientId::new("a".repeat(101)).is_err());
        assert!(ClientId::new("").is_err());
        assert!(ClientId::new("a:b").is_err());
        assert!(ClientId::new("é").is_err());
    }

    #[test]
    fn application_code_pattern() {
        assert!(ApplicationCode::new("orders-demo2").is_ok());
        assert!(ApplicationCode::new("Orders").is_err());
        assert!(ApplicationCode::new("2orders").is_err());
        assert!(ApplicationCode::new("").is_err());
    }

    #[test]
    fn blank_ids_are_refused() {
        assert!(PrincipalId::new(" ").is_err());
        assert!(CorrelationId::new("").is_err());
        assert!(CausationId::new("evt").is_ok());
    }

    #[test]
    fn context_carries_every_field() {
        let ctx = Context::new(ClientId::new("acme").unwrap())
            .with_principal(PrincipalId::new("u1").unwrap())
            .with_application(ApplicationCode::new("orders").unwrap())
            .with_causation(CausationId::new("evt").unwrap())
            .with_execution(ExecutionId::new("run").unwrap());
        assert_eq!(ctx.client().as_str(), "acme");
        assert_eq!(ctx.principal().unwrap().as_str(), "u1");
        assert_eq!(ctx.application().unwrap().as_str(), "orders");
        assert!(!ctx.correlation().as_str().is_empty());
        assert_eq!(ctx.causation().unwrap().as_str(), "evt");
        assert_eq!(ctx.execution().unwrap().as_str(), "run");
    }

    #[test]
    fn serde_validates() {
        let ok: ClientId = serde_json::from_str("\"acme\"").unwrap();
        assert_eq!(ok.as_str(), "acme");
        assert!(serde_json::from_str::<ClientId>("\"a:b\"").is_err());
    }
}
