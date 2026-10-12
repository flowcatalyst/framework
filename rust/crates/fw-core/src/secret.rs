//! `Secret`: a configuration value that is never printed (core contract section 4).

use std::fmt;

use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use zeroize::Zeroizing;

/// A secret text: a password, a token, a client secret.
///
/// It has no `Display` and no `Serialize`, so formatting it into a message or serialising it does
/// not compile; `Debug` prints `Secret[****]`. The raw value is [`Secret::reveal`]. Unlike the
/// Java `Secret`, the value is zeroised when dropped.
#[derive(Clone, PartialEq, Eq)]
pub struct Secret(Zeroizing<String>);

impl Secret {
    /// Wraps a value.
    #[must_use]
    pub fn new(value: impl Into<String>) -> Self {
        Self(Zeroizing::new(value.into()))
    }

    /// The raw value. Never log it.
    #[must_use]
    pub fn reveal(&self) -> &str {
        &self.0
    }

    /// Whether the value is empty.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    /// Compares a candidate with the value in constant time (over SHA-256 digests, so the length is
    /// not revealed either).
    #[must_use]
    pub fn matches(&self, candidate: &str) -> bool {
        let a = Sha256::digest(self.0.as_bytes());
        let b = Sha256::digest(candidate.as_bytes());
        a.ct_eq(&b).into()
    }
}

impl fmt::Debug for Secret {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Secret[****]")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn never_printed() {
        let s = Secret::new("hunter2");
        assert_eq!(format!("{s:?}"), "Secret[****]");
        assert_eq!(s.reveal(), "hunter2");
        assert!(s.matches("hunter2"));
        assert!(!s.matches("hunter3"));
    }
}
