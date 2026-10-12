//! Lock keys (locks contract section 1).

use std::borrow::Cow;
use std::fmt;

use fw_core::ClientId;

use crate::LockError;

/// A lock name, `[A-Za-z0-9._:-]{1,200}`. [`LockName::of`] checks a literal during constant
/// evaluation.
#[derive(Clone, PartialEq, Eq, Hash)]
pub struct LockName(Cow<'static, str>);

const fn is_name(text: &str) -> bool {
    let b = text.as_bytes();
    if b.is_empty() || b.len() > 200 {
        return false;
    }
    let mut i = 0;
    while i < b.len() {
        let c = b[i];
        if !(c.is_ascii_alphanumeric() || matches!(c, b'.' | b'_' | b':' | b'-')) {
            return false;
        }
        i += 1;
    }
    true
}

impl LockName {
    /// A name from a literal.
    ///
    /// # Panics
    ///
    /// When `text` does not match `[A-Za-z0-9._:-]{1,200}`; in a `const` that is a compile error.
    #[must_use]
    pub const fn of(text: &'static str) -> Self {
        assert!(
            is_name(text),
            "lock name must match [A-Za-z0-9._:-]{{1,200}}"
        );
        Self(Cow::Borrowed(text))
    }

    /// A name known only at run time.
    ///
    /// # Errors
    ///
    /// When `text` does not match `[A-Za-z0-9._:-]{1,200}`.
    pub fn parse(text: &str) -> Result<Self, LockError> {
        if is_name(text) {
            Ok(Self(Cow::Owned(text.to_owned())))
        } else {
            Err(LockError::Argument(format!(
                "lock name must match [A-Za-z0-9._:-]{{1,200}}: {text}"
            )))
        }
    }

    /// The name.
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Debug for LockName {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

/// What a lock protects: a name, at service scope or within one client. Stored as `svc:<name>` or
/// `client:<clientId>:<name>`; a client key never equals a service key, and keys of different
/// clients never collide (a `ClientId` never contains `:`).
#[derive(Clone, PartialEq, Eq, Hash)]
pub struct LockKey(String);

impl LockKey {
    /// One lock per deployment.
    #[must_use]
    pub fn service(name: &LockName) -> Self {
        Self(format!("svc:{}", name.as_str()))
    }

    /// One lock per client.
    #[must_use]
    pub fn client(client: &ClientId, name: &LockName) -> Self {
        Self(format!("client:{}:{}", client.as_str(), name.as_str()))
    }

    /// The form drivers store.
    #[must_use]
    pub fn stored(&self) -> &str {
        &self.0
    }
}

impl fmt::Debug for LockKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl fmt::Display for LockKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stored_forms_never_collide() {
        let name = LockName::of("invoice-run");
        let acme = ClientId::new("acme").unwrap();
        assert_eq!(LockKey::service(&name).stored(), "svc:invoice-run");
        assert_eq!(
            LockKey::client(&acme, &name).stored(),
            "client:acme:invoice-run"
        );
        assert!(LockName::parse("a b").is_err());
        assert!(LockName::parse(&"a".repeat(201)).is_err());
        assert!(LockName::parse("x:y.z-1_2").is_ok());
    }
}
