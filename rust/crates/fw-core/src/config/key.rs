//! Setting keys: dotted lowercase, `[a-z][a-z0-9_-]*(\.[a-z][a-z0-9_-]*)*`.

use std::fmt;

/// A setting key. [`Key::of`] checks the grammar during constant evaluation, so a malformed literal
/// in a `const` is a compile error.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Key(&'static str);

impl Key {
    /// A key.
    ///
    /// # Panics
    ///
    /// When `text` is not a key; in a `const` that is a compile error.
    #[must_use]
    pub const fn of(text: &'static str) -> Self {
        assert!(
            is_key(text),
            "setting key must match [a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)*"
        );
        Self(text)
    }

    /// A key from a text known only at run time.
    ///
    /// # Errors
    ///
    /// When `text` is not a key.
    pub fn parse(text: &'static str) -> Result<Self, KeyError> {
        if is_key(text) {
            Ok(Self(text))
        } else {
            Err(KeyError(text))
        }
    }

    /// The key text.
    #[must_use]
    pub const fn as_str(&self) -> &'static str {
        self.0
    }

    /// The environment variable that overrides it: upper case, `.` and `-` replaced by `_`.
    #[must_use]
    pub fn env_name(&self) -> String {
        env_name(self.0)
    }
}

impl fmt::Debug for Key {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.0)
    }
}

impl fmt::Display for Key {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.0)
    }
}

/// A text that is not a setting key.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("setting key '{0}' must match [a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)*")]
pub struct KeyError(&'static str);

/// The environment variable name for a dotted path (core contract section 4).
#[must_use]
pub fn env_name(dotted: &str) -> String {
    dotted
        .chars()
        .map(|c| match c {
            '.' | '-' => '_',
            other => other.to_ascii_uppercase(),
        })
        .collect()
}

pub(crate) const fn is_key(text: &str) -> bool {
    let b = text.as_bytes();
    let mut i = 0;
    let mut segment_start = true;
    if b.is_empty() {
        return false;
    }
    while i < b.len() {
        let c = b[i];
        if segment_start {
            if !c.is_ascii_lowercase() {
                return false;
            }
            segment_start = false;
        } else if c == b'.' {
            segment_start = true;
        } else if !(c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'_' || c == b'-') {
            return false;
        }
        i += 1;
    }
    !segment_start
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn grammar() {
        for ok in [
            "a",
            "http.port",
            "database.instances.main.url",
            "a_b.c-d",
            "a1.b2",
        ] {
            assert!(is_key(ok), "{ok}");
        }
        for bad in ["", "A", "1a", "a.", ".a", "a..b", "a.B", "a.1", "a b"] {
            assert!(!is_key(bad), "{bad}");
        }
    }

    #[test]
    fn env_names() {
        assert_eq!(
            env_name("database.instances.main.url"),
            "DATABASE_INSTANCES_MAIN_URL"
        );
        assert_eq!(env_name("mail.host-name"), "MAIL_HOST_NAME");
    }
}
