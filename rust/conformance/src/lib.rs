//! The conformance runner: loads the vectors in `conformance/` at the repository root, which the
//! Java framework's tests write (milestones section 3). The checks themselves are the integration
//! tests under `tests/`, one file per area; Rust only compares, it never writes a vector.

use std::fs;
use std::path::PathBuf;

use serde_json::Value;

/// The `conformance/` directory at the repository root.
#[must_use]
pub fn root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../conformance")
}

/// Reads a vector file as text.
///
/// # Panics
///
/// When the file cannot be read: a missing vector is a failed conformance test.
#[must_use]
pub fn text(relative: &str) -> String {
    let path = root().join(relative);
    match fs::read_to_string(&path) {
        Ok(text) => text,
        #[expect(
            clippy::panic,
            reason = "a missing vector fails the test that asked for it"
        )]
        Err(e) => panic!("cannot read conformance vector {}: {e}", path.display()),
    }
}

/// Reads a JSON vector file.
///
/// # Panics
///
/// When the file cannot be read or is not JSON.
#[must_use]
pub fn json(relative: &str) -> Value {
    match serde_json::from_str(&text(relative)) {
        Ok(v) => v,
        #[expect(
            clippy::panic,
            reason = "a malformed vector fails the test that asked for it"
        )]
        Err(e) => panic!("conformance vector {relative} is not JSON: {e}"),
    }
}
