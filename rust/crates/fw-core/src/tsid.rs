//! TSIDs as text: 13 Crockford base-32 characters for a 64-bit value (write-path contract,
//! section 8). The layout and the alphabet are the Java framework's (`Ids`) and the FlowCatalyst
//! platform's, so ids from either implementation sort alike; `conformance/tsid/` checks it.

/// The Crockford base-32 alphabet: digits and upper-case letters without I, L, O and U.
const ALPHABET: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/// The length of a TSID's text.
pub const LENGTH: usize = 13;

/// Encodes a value as 13 Crockford base-32 characters, most significant first.
#[must_use]
pub fn encode(value: u64) -> String {
    let mut out = [0u8; LENGTH];
    let mut v = value;
    for slot in out.iter_mut().rev() {
        // `v & 0x1F` is below 32, so the index is in range.
        *slot = ALPHABET[(v & 0x1F) as usize];
        v >>= 5;
    }
    out.iter().map(|&b| char::from(b)).collect()
}

/// Decodes a 13-character TSID, ignoring case; `None` for any other text.
///
/// As in the Java code, a first character above `7` is not refused: 13 characters carry 65 bits,
/// and the top bit is shifted out.
#[must_use]
pub fn decode(text: &str) -> Option<u64> {
    let bytes = text.as_bytes();
    if bytes.len() != LENGTH {
        return None;
    }
    let mut v: u64 = 0;
    for &b in bytes {
        let digit = digit_of(b.to_ascii_uppercase())?;
        v = (v << 5) | u64::from(digit);
    }
    Some(v)
}

/// Whether a text is a well-formed raw (unprefixed) TSID.
#[must_use]
pub fn is_valid(text: &str) -> bool {
    decode(text).is_some()
}

const fn digit_of(c: u8) -> Option<u8> {
    let mut i = 0;
    while i < ALPHABET.len() {
        if ALPHABET[i] == c {
            // i < 32
            return Some(i as u8);
        }
        i += 1;
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips() {
        for v in [0, 1, 31, 32, u64::MAX >> 1, 1_792_018_530_123 << 22] {
            assert_eq!(decode(&encode(v)), Some(v));
        }
        assert_eq!(encode(0), "0000000000000");
    }
}
