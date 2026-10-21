//! The object store key rules shared with the Java framework: `conformance/objectstore/keys.json`
//! (written by the Java `ObjectKeysConformanceTest`).
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use fw_conformance::json;
use fw_objectstore::check_key;

#[test]
fn every_candidate_key_is_accepted_or_refused_as_in_the_vector() {
    let vector = json("objectstore/keys.json");
    let cases = vector["cases"].as_array().unwrap();
    assert_eq!(cases.len(), 47);
    for case in cases {
        let key = match (case["repeat"].as_str(), case["length"].as_u64()) {
            (Some(text), Some(length)) => text
                .chars()
                .cycle()
                .take(usize::try_from(length).unwrap())
                .collect(),
            _ => case["key"].as_str().unwrap().to_owned(),
        };
        let valid = case["valid"].as_bool().unwrap();
        assert_eq!(
            check_key(&key).is_ok(),
            valid,
            "key {:?} should be {}",
            case["key"],
            if valid { "accepted" } else { "refused" }
        );
    }
}
