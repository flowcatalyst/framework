//! Duration texts: `500ms`, `30s`, `5m`, `2h`, `1d`, or ISO-8601 as `java.time.Duration.parse`
//! reads it (`PT30S`, `P1DT2H`, `PT0.5S`), never negative.

use std::time::Duration;

/// Parses a duration text (already stripped of surrounding whitespace).
pub(crate) fn parse(text: &str) -> Option<Duration> {
    simple(text).or_else(|| iso(text))
}

/// `(\d{1,15})(ms|s|m|h|d)`.
fn simple(text: &str) -> Option<Duration> {
    let digits = text.bytes().take_while(u8::is_ascii_digit).count();
    if !(1..=15).contains(&digits) {
        return None;
    }
    let n: u64 = text[..digits].parse().ok()?;
    let secs = |factor: u64| n.checked_mul(factor).map(Duration::from_secs);
    match &text[digits..] {
        "ms" => Some(Duration::from_millis(n)),
        "s" => Some(Duration::from_secs(n)),
        "m" => secs(60),
        "h" => secs(3600),
        "d" => secs(86_400),
        _ => None,
    }
}

/// `[-+]?P(n D)?(T(n H)?(n M)?(n([.,]f{0,9})? S)?)?`, case-insensitive, at least one part, `T`
/// followed by at least one part; the total must not be negative.
fn iso(text: &str) -> Option<Duration> {
    let upper = text.to_ascii_uppercase();
    let mut s = upper.as_str();
    let negate = if let Some(rest) = s.strip_prefix('-') {
        s = rest;
        true
    } else {
        if let Some(rest) = s.strip_prefix('+') {
            s = rest;
        }
        false
    };
    s = s.strip_prefix('P')?;
    let mut total: i128 = 0; // nanoseconds
    let mut any = false;
    if let Some((n, rest)) =
        signed_number(s).and_then(|(n, rest)| rest.strip_prefix('D').map(|r| (n, r)))
    {
        total += n * 86_400 * 1_000_000_000;
        any = true;
        s = rest;
    }
    if let Some(rest) = s.strip_prefix('T') {
        s = rest;
        let mut any_time = false;
        if let Some((n, rest)) =
            signed_number(s).and_then(|(n, rest)| rest.strip_prefix('H').map(|r| (n, r)))
        {
            total += n * 3600 * 1_000_000_000;
            any_time = true;
            s = rest;
        }
        if let Some((n, rest)) =
            signed_number(s).and_then(|(n, rest)| rest.strip_prefix('M').map(|r| (n, r)))
        {
            total += n * 60 * 1_000_000_000;
            any_time = true;
            s = rest;
        }
        if let Some((secs, rest)) = signed_number(s) {
            let negative_secs = s.starts_with('-');
            let (fraction, rest) = match rest.strip_prefix('.').or_else(|| rest.strip_prefix(',')) {
                Some(after) => {
                    let digits = after.bytes().take_while(u8::is_ascii_digit).count();
                    if digits > 9 {
                        return None;
                    }
                    let mut nanos: i128 = if digits == 0 {
                        0
                    } else {
                        after[..digits].parse().ok()?
                    };
                    for _ in digits..9 {
                        nanos *= 10;
                    }
                    (if negative_secs { -nanos } else { nanos }, &after[digits..])
                }
                None => (0, rest),
            };
            s = rest.strip_prefix('S')?;
            total += secs * 1_000_000_000 + fraction;
            any_time = true;
        }
        if !any_time {
            return None;
        }
        any = true;
    }
    if !any || !s.is_empty() {
        return None;
    }
    if negate {
        total = -total;
    }
    if total < 0 || total > i128::from(i64::MAX) * 1_000_000_000 + 999_999_999 {
        return None;
    }
    let secs = u64::try_from(total / 1_000_000_000).ok()?;
    let nanos = u32::try_from(total % 1_000_000_000).ok()?;
    Some(Duration::new(secs, nanos))
}

/// `[-+]?[0-9]+` at the start of `s`, at most 19 digits (a Java `long`).
fn signed_number(s: &str) -> Option<(i128, &str)> {
    let (sign, body) = match s.as_bytes().first() {
        Some(b'-') => (-1, &s[1..]),
        Some(b'+') => (1, &s[1..]),
        _ => (1, s),
    };
    let digits = body.bytes().take_while(u8::is_ascii_digit).count();
    if digits == 0 || digits > 19 {
        return None;
    }
    let n: i128 = body[..digits].parse().ok()?;
    if n > i128::from(i64::MAX) {
        return None;
    }
    Some((sign * n, &body[digits..]))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn simple_forms() {
        assert_eq!(parse("500ms"), Some(Duration::from_millis(500)));
        assert_eq!(parse("30s"), Some(Duration::from_secs(30)));
        assert_eq!(parse("5m"), Some(Duration::from_secs(300)));
        assert_eq!(parse("2h"), Some(Duration::from_secs(7200)));
        assert_eq!(parse("1d"), Some(Duration::from_secs(86_400)));
        assert_eq!(parse("0s"), Some(Duration::ZERO));
        assert_eq!(parse("1w"), None);
        assert_eq!(parse("1.5s"), None);
        assert_eq!(parse("-1s"), None);
        assert_eq!(parse(""), None);
    }

    #[test]
    fn iso_forms() {
        assert_eq!(parse("PT30S"), Some(Duration::from_secs(30)));
        assert_eq!(parse("pt30s"), Some(Duration::from_secs(30)));
        assert_eq!(parse("PT0.5S"), Some(Duration::from_millis(500)));
        assert_eq!(parse("PT0,25S"), Some(Duration::from_millis(250)));
        assert_eq!(parse("P1DT2H"), Some(Duration::from_secs(93_600)));
        assert_eq!(parse("PT1H-30M"), Some(Duration::from_secs(1800)));
        assert_eq!(parse("+PT1S"), Some(Duration::from_secs(1)));
        assert_eq!(parse("-PT1S"), None);
        assert_eq!(parse("PT-1S"), None);
        assert_eq!(parse("P"), None);
        assert_eq!(parse("PT"), None);
        assert_eq!(parse("P1D T1H"), None);
        assert_eq!(parse("PT1.1234567891S"), None);
    }
}
