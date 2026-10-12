//! `conformance/tsid/tsid.json`, written by the Java `IdsConformanceTest`.

use fw_conformance::json;
use fw_core::tsid;

#[test]
fn decoding_and_canonical_encoding_match_java() {
    let doc = json("tsid/tsid.json");
    let cases = doc["decode"].as_array().unwrap();
    assert!(!cases.is_empty());
    for case in cases {
        let text = case["text"].as_str().unwrap();
        let expected = case["value"].as_str().map(|v| v.parse::<u64>().unwrap());
        assert_eq!(tsid::decode(text), expected, "decode({text:?})");
        assert_eq!(
            tsid::is_valid(text),
            expected.is_some(),
            "is_valid({text:?})"
        );
        if case["canonical"].as_bool() == Some(true) {
            assert_eq!(
                tsid::encode(expected.unwrap()),
                text,
                "encode of {text:?}'s value"
            );
        }
    }
}
