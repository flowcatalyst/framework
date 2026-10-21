//! The key rules (object-stores contract section 2), the same for every driver.

use crate::store::ObjectStoreError;

/// The longest key, in characters.
pub const MAX_KEY_LENGTH: usize = 512;

/// Checks a key: 1 to 512 characters, segments separated by a single `/`, each segment starting
/// with a letter or digit and holding only ASCII letters, digits, `.`, `-` and `_`. So no leading
/// or trailing slash, no empty segment, and no `.` or `..` (which is what keeps a key inside a
/// folder store).
///
/// # Errors
///
/// [`ObjectStoreError::Argument`] for a key that is not allowed.
pub fn check_key(key: &str) -> Result<&str, ObjectStoreError> {
    if key.is_empty() || key.chars().count() > MAX_KEY_LENGTH {
        return Err(ObjectStoreError::Argument(format!(
            "key must be 1 to {MAX_KEY_LENGTH} characters, was {}",
            key.chars().count()
        )));
    }
    if key.split('/').all(is_segment) {
        Ok(key)
    } else {
        Err(ObjectStoreError::Argument(format!(
            "key '{}' has an invalid segment; segments are letters, digits, '.', '-' and '_', starting with a letter or digit, separated by '/'",
            printable(key)
        )))
    }
}

fn is_segment(segment: &str) -> bool {
    let mut bytes = segment.bytes();
    bytes.next().is_some_and(|b| b.is_ascii_alphanumeric())
        && bytes.all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_'))
}

/// The first 80 characters, control characters shown as `?`, so a refused key can be logged.
fn printable(key: &str) -> String {
    key.chars()
        .take(80)
        .map(|c| if c.is_control() { '?' } else { c })
        .collect()
}
