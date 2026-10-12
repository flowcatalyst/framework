//! `ServiceError`: what the HTTP layer maps to the error envelope (http contract section 5, MA
//! item 8), so the HTTP crate does not depend on the use-case crate.

use std::collections::BTreeMap;

/// An error with an HTTP status, an `UPPER_SNAKE` code, a message safe to show a caller, and
/// details.
pub trait ServiceError {
    /// The HTTP status.
    fn http_status(&self) -> u16;
    /// The code, `UPPER_SNAKE`.
    fn code(&self) -> &str;
    /// The message, safe to show a caller.
    fn message(&self) -> &str;
    /// Details (a string map; empty when there are none).
    fn details(&self) -> &BTreeMap<String, String>;
}
