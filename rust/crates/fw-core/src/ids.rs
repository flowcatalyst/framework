//! Id generation (write-path contract section 8) and typed aggregate ids.

use std::cmp::Ordering;
use std::fmt;
use std::hash::{Hash, Hasher};
use std::marker::PhantomData;
use std::str::FromStr;
use std::sync::atomic::{AtomicU64, Ordering as AtomicOrdering};
use std::sync::{Arc, Mutex, PoisonError};

use rand::rngs::StdRng;
use rand::{Rng, SeedableRng};
use serde::de::Error as DeError;
use serde::{Deserialize, Deserializer, Serialize, Serializer};

use crate::clock::Clock;
use crate::tsid;

/// A TSID prefix, `[a-z][a-z0-9]*`; `ord` gives ids such as `ord_0ABCDEFGHJKMN`.
///
/// `IdPrefix::of` checks the pattern during constant evaluation, so a bad literal in a `const` is
/// a compile error.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct IdPrefix(&'static str);

impl IdPrefix {
    /// A prefix.
    ///
    /// # Panics
    ///
    /// When `text` does not match `[a-z][a-z0-9]*`; in a `const` that is a compile error.
    #[must_use]
    pub const fn of(text: &'static str) -> Self {
        assert!(is_prefix(text), "TSID prefix must match [a-z][a-z0-9]*");
        Self(text)
    }

    /// The prefix text.
    #[must_use]
    pub const fn as_str(&self) -> &'static str {
        self.0
    }
}

const fn is_prefix(text: &str) -> bool {
    let b = text.as_bytes();
    if b.is_empty() || !b[0].is_ascii_lowercase() {
        return false;
    }
    let mut i = 1;
    while i < b.len() {
        if !(b[i].is_ascii_lowercase() || b[i].is_ascii_digit()) {
            return false;
        }
        i += 1;
    }
    true
}

/// The kind of a typed id: names the prefix. Implemented by the aggregate (or any marker type).
///
/// ```
/// use fw_core::{Id, IdKind, IdPrefix};
/// struct Order;
/// impl IdKind for Order { const PREFIX: IdPrefix = IdPrefix::of("ord"); }
/// type OrderId = Id<Order>;
/// assert!("ord_0ABCDEFGHJKMN".parse::<OrderId>().is_ok());
/// ```
pub trait IdKind: 'static {
    /// The prefix of every id of this kind.
    const PREFIX: IdPrefix;
}

/// Why a text is not an id.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("'{text}' is not an id of the form {prefix}_<13-character TSID>")]
pub struct IdError {
    text: String,
    prefix: &'static str,
}

/// A typed id: `<prefix>_<TSID>`. `Id<Order>` and `Id<Customer>` are different types.
pub struct Id<K: IdKind> {
    text: String,
    kind: PhantomData<fn() -> K>,
}

impl<K: IdKind> Id<K> {
    /// Parses `<prefix>_<TSID>`, with the prefix of `K`.
    ///
    /// # Errors
    ///
    /// When the prefix differs or the rest is not a 13-character TSID.
    pub fn parse(text: &str) -> Result<Self, IdError> {
        let ok = text
            .strip_prefix(K::PREFIX.as_str())
            .and_then(|rest| rest.strip_prefix('_'))
            .is_some_and(tsid::is_valid);
        if ok {
            Ok(Self {
                text: text.to_owned(),
                kind: PhantomData,
            })
        } else {
            Err(IdError {
                text: text.to_owned(),
                prefix: K::PREFIX.as_str(),
            })
        }
    }

    fn from_generated(text: String) -> Self {
        Self {
            text,
            kind: PhantomData,
        }
    }

    /// The id's text.
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.text
    }

    /// The id's text, owned.
    #[must_use]
    pub fn into_string(self) -> String {
        self.text
    }
}

impl<K: IdKind> Clone for Id<K> {
    fn clone(&self) -> Self {
        Self {
            text: self.text.clone(),
            kind: PhantomData,
        }
    }
}

impl<K: IdKind> PartialEq for Id<K> {
    fn eq(&self, other: &Self) -> bool {
        self.text == other.text
    }
}

impl<K: IdKind> Eq for Id<K> {}

impl<K: IdKind> PartialOrd for Id<K> {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl<K: IdKind> Ord for Id<K> {
    fn cmp(&self, other: &Self) -> Ordering {
        self.text.cmp(&other.text)
    }
}

impl<K: IdKind> Hash for Id<K> {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.text.hash(state);
    }
}

impl<K: IdKind> fmt::Debug for Id<K> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.text)
    }
}

impl<K: IdKind> fmt::Display for Id<K> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.text)
    }
}

impl<K: IdKind> AsRef<str> for Id<K> {
    fn as_ref(&self) -> &str {
        &self.text
    }
}

impl<K: IdKind> FromStr for Id<K> {
    type Err = IdError;
    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Self::parse(s)
    }
}

impl<K: IdKind> TryFrom<String> for Id<K> {
    type Error = IdError;
    fn try_from(s: String) -> Result<Self, Self::Error> {
        Self::parse(&s)
    }
}

impl<K: IdKind> Serialize for Id<K> {
    fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&self.text)
    }
}

impl<'de, K: IdKind> Deserialize<'de> for Id<K> {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let text = String::deserialize(d)?;
        Self::parse(&text).map_err(DeError::custom)
    }
}

#[cfg(feature = "sqlx")]
mod sqlx_impls {
    use sqlx::encode::IsNull;
    use sqlx::error::BoxDynError;
    use sqlx::postgres::{PgArgumentBuffer, PgTypeInfo, PgValueRef};
    use sqlx::{Decode, Encode, Postgres, Type};

    use super::{Id, IdKind};

    impl<K: IdKind> Type<Postgres> for Id<K> {
        fn type_info() -> PgTypeInfo {
            <String as Type<Postgres>>::type_info()
        }
        fn compatible(ty: &PgTypeInfo) -> bool {
            <String as Type<Postgres>>::compatible(ty)
        }
    }

    impl<K: IdKind> Encode<'_, Postgres> for Id<K> {
        fn encode_by_ref(&self, buf: &mut PgArgumentBuffer) -> Result<IsNull, BoxDynError> {
            <&str as Encode<'_, Postgres>>::encode(self.as_str(), buf)
        }
    }

    impl<'r, K: IdKind> Decode<'r, Postgres> for Id<K> {
        fn decode(value: PgValueRef<'r>) -> Result<Self, BoxDynError> {
            let text = <&str as Decode<'r, Postgres>>::decode(value)?;
            Ok(Self::parse(text)?)
        }
    }
}

const MS_MASK: u64 = 0x3FF_FFFF_FFFF;
const SEQ_BITS: u32 = 12;
const SEQ_MASK: u64 = 0xFFF;
const RANDOM_BITS: u32 = 10;

/// Generates TSIDs: 13 Crockford base-32 characters, time-ordered, optionally prefixed.
///
/// Layout, the Java framework's and the platform's: bits 63..22 the milliseconds since the Unix
/// epoch (42 bits), 21..10 a sequence, 9..0 random. Monotonic within one `Ids` (and its clones):
/// the last issued (millisecond, sequence) pair only moves forward, by compare-and-swap; when the
/// clock does not advance or goes back the sequence continues, and 4096 ids within one millisecond
/// borrow the next one. One per application, from [`crate::Boot::ids`].
#[derive(Clone)]
pub struct Ids(Arc<IdsInner>);

struct IdsInner {
    clock: Arc<dyn Clock>,
    /// Bits 63..12 the last issued millisecond, 11..0 the last issued sequence.
    state: AtomicU64,
    seeded: Option<Mutex<StdRng>>,
}

impl fmt::Debug for Ids {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Ids")
            .field("clock", &self.0.clock)
            .finish_non_exhaustive()
    }
}

impl Ids {
    /// A generator reading `clock`, with random bits from the thread's generator.
    #[must_use]
    pub fn new(clock: Arc<dyn Clock>) -> Self {
        Self(Arc::new(IdsInner {
            clock,
            state: AtomicU64::new(0),
            seeded: None,
        }))
    }

    /// A generator whose random bits come from a seeded generator, so a test gets the same ids
    /// every run (with a fixed clock).
    #[must_use]
    pub fn seeded(clock: Arc<dyn Clock>, seed: u64) -> Self {
        Self(Arc::new(IdsInner {
            clock,
            state: AtomicU64::new(0),
            seeded: Some(Mutex::new(StdRng::seed_from_u64(seed))),
        }))
    }

    fn random(&self, bound: u64) -> u64 {
        match &self.0.seeded {
            Some(rng) => rng
                .lock()
                .unwrap_or_else(PoisonError::into_inner)
                .random_range(0..bound),
            None => rand::rng().random_range(0..bound),
        }
    }

    /// A raw TSID.
    #[must_use]
    pub fn generate(&self) -> String {
        // The clock reads before the epoch as zero, as Java's unsigned shift would not.
        let now = u64::try_from(self.0.clock.millis()).unwrap_or(0);
        loop {
            let old = self.0.state.load(AtomicOrdering::Acquire);
            let last_ms = old >> SEQ_BITS;
            let last_seq = old & SEQ_MASK;
            let (ms, seq) = if now > last_ms {
                (now, self.random(SEQ_MASK + 1))
            } else if last_seq < SEQ_MASK {
                (last_ms, last_seq + 1)
            } else {
                (last_ms + 1, self.random(SEQ_MASK + 1))
            };
            let new = (ms << SEQ_BITS) | seq;
            if self
                .0
                .state
                .compare_exchange(old, new, AtomicOrdering::AcqRel, AtomicOrdering::Acquire)
                .is_ok()
            {
                let random = self.random(1 << RANDOM_BITS);
                return tsid::encode(
                    ((ms & MS_MASK) << (SEQ_BITS + RANDOM_BITS)) | (seq << RANDOM_BITS) | random,
                );
            }
        }
    }

    /// A prefixed TSID, `<prefix>_<TSID>`.
    #[must_use]
    pub fn generate_prefixed(&self, prefix: IdPrefix) -> String {
        format!("{}_{}", prefix.as_str(), self.generate())
    }

    /// A prefixed TSID for a prefix known only at run time.
    ///
    /// # Errors
    ///
    /// When the prefix does not match `[a-z][a-z0-9]*`.
    pub fn generate_with(&self, prefix: &str) -> Result<String, IdPrefixError> {
        if is_prefix(prefix) {
            Ok(format!("{prefix}_{}", self.generate()))
        } else {
            Err(IdPrefixError(prefix.to_owned()))
        }
    }

    /// A new typed id, with the prefix of `K`.
    #[must_use]
    pub fn next<K: IdKind>(&self) -> Id<K> {
        Id::from_generated(self.generate_prefixed(K::PREFIX))
    }
}

/// A run-time prefix that does not match `[a-z][a-z0-9]*`.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("TSID prefix must match [a-z][a-z0-9]*: {0}")]
pub struct IdPrefixError(String);

#[cfg(test)]
mod tests {
    use std::collections::HashSet;

    use chrono::{TimeZone, Utc};

    use super::*;
    use std::thread;

    use crate::clock::{FixedClock, ManualClock, SystemClock};

    struct Order;
    impl IdKind for Order {
        const PREFIX: IdPrefix = IdPrefix::of("ord");
    }
    struct Line;
    impl IdKind for Line {
        const PREFIX: IdPrefix = IdPrefix::of("lin");
    }

    fn fixed(ms: i64) -> Arc<dyn Clock> {
        Arc::new(FixedClock::at(Utc.timestamp_millis_opt(ms).unwrap()))
    }

    #[test]
    fn shape_and_prefixes() {
        let ids = Ids::new(fixed(1_760_000_000_000));
        let raw = ids.generate();
        assert!(tsid::is_valid(&raw));
        let id: Id<Order> = ids.next();
        assert!(id.as_str().starts_with("ord_"));
        assert!(Id::<Order>::parse(id.as_str()).is_ok());
        assert!(Id::<Line>::parse(id.as_str()).is_err());
        assert!(ids.generate_with("Ord").is_err());
        assert!(ids.generate_with("ord2").unwrap().starts_with("ord2_"));
    }

    #[test]
    fn strictly_increasing_at_a_stopped_clock_and_borrows_the_next_millisecond() {
        let ms = 1_760_000_000_000_u64;
        let ids = Ids::seeded(fixed(ms as i64), 7);
        let mut last = 0;
        let mut seen_next_ms = false;
        for _ in 0..5000 {
            let v = tsid::decode(&ids.generate()).unwrap();
            assert!(v > last);
            last = v;
            if v >> 22 == ms + 1 {
                seen_next_ms = true;
            }
        }
        assert!(
            seen_next_ms,
            "more than 4096 ids in one millisecond borrow the next"
        );
    }

    #[test]
    fn a_clock_going_back_does_not_go_back() {
        let clock = Arc::new(ManualClock::starting_at(
            Utc.timestamp_millis_opt(2_000_000).unwrap(),
        ));
        let ids = Ids::new(clock.clone());
        let a = tsid::decode(&ids.generate()).unwrap();
        clock.set(Utc.timestamp_millis_opt(1_000_000).unwrap());
        let b = tsid::decode(&ids.generate()).unwrap();
        assert!(b > a);
    }

    #[test]
    fn unique_across_threads() {
        let ids = Ids::new(Arc::new(SystemClock));
        let handles: Vec<_> = (0..8)
            .map(|_| {
                let ids = ids.clone();
                thread::spawn(move || (0..2000).map(|_| ids.generate()).collect::<Vec<_>>())
            })
            .collect();
        let mut all = HashSet::new();
        for h in handles {
            for id in h.join().unwrap() {
                assert!(all.insert(id));
            }
        }
    }

    #[test]
    fn seeded_generators_repeat() {
        let a = Ids::seeded(fixed(5), 42);
        let b = Ids::seeded(fixed(5), 42);
        assert_eq!(a.generate(), b.generate());
    }
}
