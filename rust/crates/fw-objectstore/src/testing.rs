//! The contract test suite every object store driver passes (object-stores contract section 6),
//! the Rust form of the Java `ObjectStoreContract` test-jar: a driver's test file implements
//! [`ObjectStoreHarness`] and invokes [`suite!`](crate::object_store_suite), which expands to one
//! `#[tokio::test]` per case. [`run_all`] runs every case in one test, for a store that costs
//! something to set up and tear down.
//!
//! ```ignore
//! fw_objectstore::testing::suite!(harness(), #[tokio::test]);
//! ```

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // a test suite: a failed assertion is the point

use std::sync::Arc;

use crate::{MAX_KEY_LENGTH, ObjectStore, ObjectStoreError};

/// What a driver's test gives the suite.
pub trait ObjectStoreHarness: Send + Sync + 'static {
    /// The store under test: empty or private to the suite (keys are unique per run), with a size
    /// limit of at least 1 KiB. Every call returns the same store.
    fn store(&self) -> Arc<dyn ObjectStore>;

    /// The size limit of [`store`](Self::store), so the over-limit case can be tested.
    fn limit(&self) -> usize;
}

fn key(tail: &str) -> String {
    format!("contract-{:016x}/{tail}", rand::random::<u64>())
}

/// The cases, one function each.
pub mod cases {
    use super::*;

    /// A put then a get returns the bytes.
    pub async fn put_then_get_returns_the_bytes(h: &impl ObjectStoreHarness) {
        let s = h.store();
        let k = key("a.json");
        s.put(&k, b"{\"x\":1}").await.unwrap();
        assert_eq!(s.get(&k).await.unwrap().unwrap(), b"{\"x\":1}");
    }

    /// A key with no value is `None`, not an error.
    pub async fn a_missing_key_is_none_not_an_error(h: &impl ObjectStoreHarness) {
        assert_eq!(h.store().get(&key("nothing-here")).await.unwrap(), None);
    }

    /// A second put replaces the first completely.
    pub async fn a_second_put_replaces_the_first_completely(h: &impl ObjectStoreHarness) {
        let s = h.store();
        let k = key("r");
        s.put(&k, &[1, 2, 3, 4, 5]).await.unwrap();
        s.put(&k, &[9]).await.unwrap();
        assert_eq!(s.get(&k).await.unwrap().unwrap(), [9]);
    }

    /// Bytes are kept exactly, zeros and invalid UTF-8 included.
    pub async fn bytes_are_kept_exactly(h: &impl ObjectStoreHarness) {
        let s = h.store();
        let k = key("bin");
        let all: Vec<u8> = (0..=255).collect();
        s.put(&k, &all).await.unwrap();
        assert_eq!(s.get(&k).await.unwrap().unwrap(), all);
    }

    /// Empty content is a value.
    pub async fn empty_content_is_a_value(h: &impl ObjectStoreHarness) {
        let s = h.store();
        let k = key("empty");
        s.put(&k, &[]).await.unwrap();
        assert_eq!(s.get(&k).await.unwrap(), Some(Vec::new()));
    }

    /// Delete removes, and removing an absent value is not an error.
    pub async fn delete_removes_and_is_idempotent(h: &impl ObjectStoreHarness) {
        let s = h.store();
        let k = key("d");
        s.put(&k, &[1]).await.unwrap();
        s.delete(&k).await.unwrap();
        assert_eq!(s.get(&k).await.unwrap(), None);
        s.delete(&k).await.unwrap();
        s.delete(&key("never-existed")).await.unwrap();
    }

    /// Keys under one another's folders are independent values.
    pub async fn nested_keys_are_independent_values(h: &impl ObjectStoreHarness) {
        let s = h.store();
        let base = key("n");
        s.put(&format!("{base}/one"), &[1]).await.unwrap();
        s.put(&format!("{base}/two/three"), &[2]).await.unwrap();
        assert_eq!(s.get(&format!("{base}/one")).await.unwrap().unwrap(), [1]);
        assert_eq!(
            s.get(&format!("{base}/two/three")).await.unwrap().unwrap(),
            [2]
        );
        s.delete(&format!("{base}/one")).await.unwrap();
        assert_eq!(
            s.get(&format!("{base}/two/three")).await.unwrap().unwrap(),
            [2]
        );
    }

    /// Changing the buffer given to `put` or the one `get` returned does not change the value.
    pub async fn a_caller_cannot_change_a_stored_value_afterwards(h: &impl ObjectStoreHarness) {
        let s = h.store();
        let k = key("alias");
        let mut input = vec![1, 2, 3];
        s.put(&k, &input).await.unwrap();
        input[0] = 99;
        let mut out = s.get(&k).await.unwrap().unwrap();
        assert_eq!(out, [1, 2, 3]);
        out[1] = 99;
        assert_eq!(s.get(&k).await.unwrap().unwrap(), [1, 2, 3]);
    }

    /// Keys that could escape or are malformed are refused by all three operations.
    pub async fn keys_that_could_escape_or_are_malformed_are_refused(h: &impl ObjectStoreHarness) {
        let s = h.store();
        for bad in [
            "",
            "/abs",
            "a//b",
            "a/",
            "../x",
            "a/../b",
            "a/./b",
            ".hidden",
            "a/.hidden",
            "a b",
            "a\\b",
            "a\u{0}b",
            "é",
        ] {
            assert!(
                matches!(s.put(bad, &[1]).await, Err(ObjectStoreError::Argument(_))),
                "put '{bad}'"
            );
            assert!(
                matches!(s.get(bad).await, Err(ObjectStoreError::Argument(_))),
                "get '{bad}'"
            );
            assert!(
                matches!(s.delete(bad).await, Err(ObjectStoreError::Argument(_))),
                "delete '{bad}'"
            );
        }
        let long = "a".repeat(MAX_KEY_LENGTH + 1);
        assert!(matches!(
            s.put(&long, &[1]).await,
            Err(ObjectStoreError::Argument(_))
        ));
    }

    /// Content over the limit is refused and nothing is stored; content at the limit is kept.
    pub async fn content_over_the_limit_is_refused_and_nothing_is_stored(
        h: &impl ObjectStoreHarness,
    ) {
        let s = h.store();
        let k = key("big");
        assert!(matches!(
            s.put(&k, &vec![0; h.limit() + 1]).await,
            Err(ObjectStoreError::Argument(_))
        ));
        assert_eq!(s.get(&k).await.unwrap(), None);
        s.put(&k, &vec![0; h.limit()]).await.unwrap();
        assert_eq!(s.get(&k).await.unwrap().unwrap().len(), h.limit());
    }

    /// Eight writers putting to one key leave one whole value.
    pub async fn concurrent_puts_to_one_key_leave_one_whole_value(h: &impl ObjectStoreHarness) {
        let s = h.store();
        let k = key("race");
        let writers: Vec<_> = (1..=8u8)
            .map(|fill| {
                let (s, k) = (Arc::clone(&s), k.clone());
                tokio::spawn(async move {
                    for _ in 0..20 {
                        s.put(&k, &[fill; 512]).await.unwrap();
                    }
                })
            })
            .collect();
        for w in writers {
            w.await.unwrap();
        }
        let result = s.get(&k).await.unwrap().unwrap();
        assert_eq!(result.len(), 512);
        assert!(result.iter().all(|b| *b == result[0]));
    }
}

/// Runs every case, one after another, in the calling test.
pub async fn run_all(h: &impl ObjectStoreHarness) {
    cases::put_then_get_returns_the_bytes(h).await;
    cases::a_missing_key_is_none_not_an_error(h).await;
    cases::a_second_put_replaces_the_first_completely(h).await;
    cases::bytes_are_kept_exactly(h).await;
    cases::empty_content_is_a_value(h).await;
    cases::delete_removes_and_is_idempotent(h).await;
    cases::nested_keys_are_independent_values(h).await;
    cases::a_caller_cannot_change_a_stored_value_afterwards(h).await;
    cases::keys_that_could_escape_or_are_malformed_are_refused(h).await;
    cases::content_over_the_limit_is_refused_and_nothing_is_stored(h).await;
    cases::concurrent_puts_to_one_key_leave_one_whole_value(h).await;
}

/// Expands to one test per contract case, each with `$harness.await` for its harness; `$attr` is
/// the test attribute, for example `#[tokio::test(flavor = "multi_thread")]`.
#[macro_export]
macro_rules! object_store_suite {
    ($harness:expr, #[$attr:meta]) => {
        $crate::object_store_suite!(@cases $harness, #[$attr],
            put_then_get_returns_the_bytes,
            a_missing_key_is_none_not_an_error,
            a_second_put_replaces_the_first_completely,
            bytes_are_kept_exactly,
            empty_content_is_a_value,
            delete_removes_and_is_idempotent,
            nested_keys_are_independent_values,
            a_caller_cannot_change_a_stored_value_afterwards,
            keys_that_could_escape_or_are_malformed_are_refused,
            content_over_the_limit_is_refused_and_nothing_is_stored,
            concurrent_puts_to_one_key_leave_one_whole_value
        );
    };
    (@cases $harness:expr, #[$attr:meta], $($case:ident),*) => {
        $(
            #[$attr]
            async fn $case() {
                $crate::testing::cases::$case(&$harness.await).await;
            }
        )*
    };
}

pub use crate::object_store_suite as suite;
