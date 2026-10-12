//! Setting types: how raw source text becomes a typed value (core contract section 4).

use std::time::Duration;

use super::duration;
use super::raw::Raw;
use crate::secret::Secret;
use sealed::Malformed;

pub(crate) mod sealed {
    use super::Raw;

    /// A raw value that does not have the expected shape.
    #[derive(Debug, Clone, Copy)]
    pub struct Malformed;

    /// The parsing half of [`super::SettingType`], sealed so the raw source representation stays
    /// the framework's.
    pub trait Parse: Sized {
        /// The accepted shape, for error messages (Java's `describe()`), for example "an integer".
        fn describe() -> String;
        /// Parses a raw value; `Err` means "not of the expected shape".
        fn parse(raw: &Raw) -> Result<Self, Malformed>;
        /// Whether values are secrets (never quoted in an error message).
        const SECRET: bool = false;
    }
}

/// A type a setting can have. Implemented for `String`, `i32`, `i64`, `u16`, `u32`, `u64`,
/// `bool`, `Duration`, `Vec<String>`, [`Secret`], and every [`ConfigEnum`].
pub trait SettingType: sealed::Parse + Clone + Send + Sync + 'static {
    /// The form a default takes in a `const` declaration: `&'static str` for `String`, the type
    /// itself for numbers, booleans, durations and enums, `&'static [&'static str]` for lists,
    /// [`NoDefault`] for secrets (which never have one).
    type Const: Copy + Send + Sync + 'static;

    /// Turns a declared default into a value.
    fn from_const(value: Self::Const) -> Self;
}

/// The default type of a secret setting: there is no value of it, so a secret cannot be given a
/// default.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NoDefault {}

fn text(raw: &Raw) -> Result<&str, Malformed> {
    raw.text().ok_or(Malformed)
}

impl sealed::Parse for String {
    fn describe() -> String {
        "a string".to_owned()
    }
    fn parse(raw: &Raw) -> Result<Self, Malformed> {
        text(raw).map(str::to_owned)
    }
}

impl SettingType for String {
    type Const = &'static str;
    fn from_const(value: &'static str) -> Self {
        value.to_owned()
    }
}

macro_rules! integer_type {
    ($t:ty, $describe:literal) => {
        impl sealed::Parse for $t {
            fn describe() -> String {
                $describe.to_owned()
            }
            fn parse(raw: &Raw) -> Result<Self, Malformed> {
                text(raw)?.trim().parse().map_err(|_| Malformed)
            }
        }

        impl SettingType for $t {
            type Const = $t;
            fn from_const(value: $t) -> Self {
                value
            }
        }
    };
}

// Five integer types with the same parsing; the descriptions of i32 and i64 are the Java ones.
integer_type!(i32, "an integer");
integer_type!(i64, "a long integer");
integer_type!(u16, "an integer from 0 to 65535");
integer_type!(u32, "an integer from 0 to 4294967295");
integer_type!(u64, "a non-negative long integer");

impl sealed::Parse for bool {
    fn describe() -> String {
        "true or false".to_owned()
    }
    fn parse(raw: &Raw) -> Result<Self, Malformed> {
        match text(raw)?.trim() {
            "true" => Ok(true),
            "false" => Ok(false),
            _ => Err(Malformed),
        }
    }
}

impl SettingType for bool {
    type Const = bool;
    fn from_const(value: bool) -> Self {
        value
    }
}

impl sealed::Parse for Duration {
    fn describe() -> String {
        "a duration such as 500ms, 30s, 5m, 2h, 1d or PT30S".to_owned()
    }
    fn parse(raw: &Raw) -> Result<Self, Malformed> {
        duration::parse(text(raw)?.trim()).ok_or(Malformed)
    }
}

impl SettingType for Duration {
    type Const = Duration;
    fn from_const(value: Duration) -> Self {
        value
    }
}

impl sealed::Parse for Vec<String> {
    fn describe() -> String {
        "a list of strings (YAML list, or comma-separated text)".to_owned()
    }
    fn parse(raw: &Raw) -> Result<Self, Malformed> {
        match raw {
            Raw::List(items) => items.iter().map(|i| text(i).map(str::to_owned)).collect(),
            Raw::Text(t) => {
                let t = t.trim();
                if t.is_empty() {
                    Ok(Vec::new())
                } else {
                    Ok(t.split(',').map(|p| p.trim().to_owned()).collect())
                }
            }
            Raw::Other => Err(Malformed),
        }
    }
}

impl SettingType for Vec<String> {
    type Const = &'static [&'static str];
    fn from_const(value: &'static [&'static str]) -> Self {
        value.iter().map(|s| (*s).to_owned()).collect()
    }
}

impl sealed::Parse for Secret {
    fn describe() -> String {
        "a string".to_owned()
    }
    fn parse(raw: &Raw) -> Result<Self, Malformed> {
        text(raw).map(Secret::new)
    }
    const SECRET: bool = true;
}

impl SettingType for Secret {
    type Const = NoDefault;
    fn from_const(value: NoDefault) -> Self {
        match value {}
    }
}

/// An enum a setting can hold: one of its members, matched ignoring case and with `-` read as `_`
/// (`batch-size` matches `BATCH_SIZE`).
///
/// ```
/// use fw_core::config::ConfigEnum;
/// #[derive(Clone, Copy, Debug, PartialEq)]
/// enum Mode { Fast, SlowStart }
/// impl ConfigEnum for Mode {
///     const VALUES: &'static [Self] = &[Mode::Fast, Mode::SlowStart];
///     fn name(self) -> &'static str {
///         match self { Mode::Fast => "FAST", Mode::SlowStart => "SLOW_START" }
///     }
/// }
/// ```
pub trait ConfigEnum: Copy + Send + Sync + 'static {
    /// Every member, in the order the error message lists them.
    const VALUES: &'static [Self];
    /// The member's name (Java's `name()`), conventionally `UPPER_SNAKE`.
    fn name(self) -> &'static str;
}

impl<E: ConfigEnum> sealed::Parse for E {
    fn describe() -> String {
        let names: Vec<&str> = E::VALUES.iter().map(|v| v.name()).collect();
        format!("one of {}", names.join(", "))
    }
    fn parse(raw: &Raw) -> Result<Self, Malformed> {
        let t = text(raw)?.trim().replace('-', "_");
        E::VALUES
            .iter()
            .copied()
            .find(|v| v.name().eq_ignore_ascii_case(&t))
            .ok_or(Malformed)
    }
}

impl<E: ConfigEnum> SettingType for E {
    type Const = E;
    fn from_const(value: E) -> Self {
        value
    }
}
