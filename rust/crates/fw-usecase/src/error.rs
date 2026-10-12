//! `UseCaseError` (write-path contract section 5).

use std::borrow::Cow;
use std::collections::BTreeMap;
use std::error::Error;
use std::fmt;
use std::sync::Arc;

use fw_core::ServiceError;

/// The kind of a use case failure, and its HTTP status.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ErrorKind {
    /// 400.
    Validation,
    /// 403.
    Authorization,
    /// 404.
    NotFound,
    /// 409.
    Conflict,
    /// 409.
    BusinessRule,
    /// 500.
    Internal,
}

impl ErrorKind {
    /// The HTTP status.
    #[must_use]
    pub const fn http_status(self) -> u16 {
        match self {
            Self::Validation => 400,
            Self::Authorization => 403,
            Self::NotFound => 404,
            Self::Conflict | Self::BusinessRule => 409,
            Self::Internal => 500,
        }
    }

    /// The name in lower case, as the `outcome` label of `fw_usecase_runs_total`.
    #[must_use]
    pub const fn label(self) -> &'static str {
        match self {
            Self::Validation => "validation",
            Self::Authorization => "authorization",
            Self::NotFound => "not_found",
            Self::Conflict => "conflict",
            Self::BusinessRule => "business_rule",
            Self::Internal => "internal",
        }
    }
}

/// An error code, `UPPER_SNAKE` (`[A-Z][A-Z0-9_]*`); [`ErrorCode::of`] checks a literal during
/// constant evaluation.
#[derive(Clone, PartialEq, Eq, Hash)]
pub struct ErrorCode(Cow<'static, str>);

pub(crate) const fn is_code(text: &str) -> bool {
    let b = text.as_bytes();
    if b.is_empty() || !b[0].is_ascii_uppercase() {
        return false;
    }
    let mut i = 1;
    while i < b.len() {
        if !(b[i].is_ascii_uppercase() || b[i].is_ascii_digit() || b[i] == b'_') {
            return false;
        }
        i += 1;
    }
    true
}

impl ErrorCode {
    /// A code from a literal.
    ///
    /// # Panics
    ///
    /// When `text` is not `UPPER_SNAKE`; in a `const` that is a compile error.
    #[must_use]
    pub const fn of(text: &'static str) -> Self {
        assert!(is_code(text), "error code must be UPPER_SNAKE");
        Self(Cow::Borrowed(text))
    }

    /// A code known only at run time.
    ///
    /// # Errors
    ///
    /// When `text` is not `UPPER_SNAKE`.
    pub fn parse(text: &str) -> Result<Self, String> {
        if is_code(text) {
            Ok(Self(Cow::Owned(text.to_owned())))
        } else {
            Err(format!("error code must be UPPER_SNAKE: {text}"))
        }
    }

    /// The code.
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Debug for ErrorCode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

/// Why a use case did not commit: kind, code, message (safe to show a caller) and details.
///
/// A phase that meets an unexpected failure (a database error while loading, say) returns
/// [`UseCaseError::failure`] (or uses `?` on a `sqlx::Error`): the runner then reports `INTERNAL`
/// with the code of the step it happened in, and logs the cause, which is never put in the message.
#[derive(Clone)]
pub struct UseCaseError {
    kind: ErrorKind,
    code: ErrorCode,
    message: Cow<'static, str>,
    details: BTreeMap<String, String>,
    cause: Option<Arc<dyn Error + Send + Sync>>,
}

impl fmt::Debug for UseCaseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("UseCaseError")
            .field("kind", &self.kind)
            .field("code", &self.code)
            .field("message", &self.message)
            .field("details", &self.details)
            .field("unexpected", &self.cause.is_some())
            .finish()
    }
}

impl fmt::Display for UseCaseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{:?} {}: {}",
            self.kind,
            self.code.as_str(),
            self.message
        )
    }
}

impl Error for UseCaseError {}

impl PartialEq for UseCaseError {
    fn eq(&self, other: &Self) -> bool {
        self.kind == other.kind
            && self.code == other.code
            && self.message == other.message
            && self.details == other.details
    }
}

impl UseCaseError {
    /// An error of any kind.
    #[must_use]
    pub fn new(kind: ErrorKind, code: ErrorCode, message: impl Into<Cow<'static, str>>) -> Self {
        Self {
            kind,
            code,
            message: message.into(),
            details: BTreeMap::new(),
            cause: None,
        }
    }

    /// A `VALIDATION` error.
    #[must_use]
    pub fn validation(code: ErrorCode, message: impl Into<Cow<'static, str>>) -> Self {
        Self::new(ErrorKind::Validation, code, message)
    }

    /// An `AUTHORIZATION` error.
    #[must_use]
    pub fn authorization(code: ErrorCode, message: impl Into<Cow<'static, str>>) -> Self {
        Self::new(ErrorKind::Authorization, code, message)
    }

    /// A `NOT_FOUND` error.
    #[must_use]
    pub fn not_found(code: ErrorCode, message: impl Into<Cow<'static, str>>) -> Self {
        Self::new(ErrorKind::NotFound, code, message)
    }

    /// A `CONFLICT` error.
    #[must_use]
    pub fn conflict(code: ErrorCode, message: impl Into<Cow<'static, str>>) -> Self {
        Self::new(ErrorKind::Conflict, code, message)
    }

    /// A `BUSINESS_RULE` error.
    #[must_use]
    pub fn business_rule(code: ErrorCode, message: impl Into<Cow<'static, str>>) -> Self {
        Self::new(ErrorKind::BusinessRule, code, message)
    }

    /// An `INTERNAL` error with fixed text naming the code: `The operation could not be completed (CODE).`
    #[must_use]
    pub fn internal(code: ErrorCode) -> Self {
        let message = format!("The operation could not be completed ({}).", code.as_str());
        Self::new(ErrorKind::Internal, code, message)
    }

    /// An unexpected failure inside a phase: the runner reports `INTERNAL` with the step's code.
    #[must_use]
    pub fn failure(cause: impl Error + Send + Sync + 'static) -> Self {
        let mut e = Self::internal(ErrorCode::of("INTERNAL"));
        e.cause = Some(Arc::new(cause));
        e
    }

    /// A copy with details.
    #[must_use]
    pub fn with_details(mut self, details: BTreeMap<String, String>) -> Self {
        self.details = details;
        self
    }

    /// The kind.
    #[must_use]
    pub fn kind(&self) -> ErrorKind {
        self.kind
    }

    /// The code.
    #[must_use]
    pub fn error_code(&self) -> &ErrorCode {
        &self.code
    }

    /// The cause of an unexpected failure, for logging.
    #[must_use]
    pub fn cause(&self) -> Option<&(dyn Error + Send + Sync + 'static)> {
        self.cause.as_deref()
    }

    pub(crate) fn is_unexpected(&self) -> bool {
        self.cause.is_some()
    }
}

impl From<sqlx::Error> for UseCaseError {
    fn from(e: sqlx::Error) -> Self {
        Self::failure(e)
    }
}

impl ServiceError for UseCaseError {
    fn http_status(&self) -> u16 {
        self.kind.http_status()
    }
    fn code(&self) -> &str {
        self.code.as_str()
    }
    fn message(&self) -> &str {
        &self.message
    }
    fn details(&self) -> &BTreeMap<String, String> {
        &self.details
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn kinds_codes_and_statuses() {
        assert!(is_code("ORDER_NOT_FOUND") && is_code("A1"));
        assert!(!is_code("order-not-found") && !is_code("_A") && !is_code(""));
        let e = UseCaseError::internal(ErrorCode::of("PERSIST"));
        assert_eq!(
            e.message(),
            "The operation could not be completed (PERSIST)."
        );
        assert_eq!(e.http_status(), 500);
        assert_eq!(
            UseCaseError::business_rule(ErrorCode::of("X"), "m").http_status(),
            409
        );
        assert_eq!(ErrorKind::NotFound.label(), "not_found");
    }
}
