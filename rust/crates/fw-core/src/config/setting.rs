//! Setting declarations and the handles that read them.

use std::any::Any;
use std::fmt;
use std::marker::PhantomData;

use super::key::Key;
use super::raw::Raw;
use super::types::SettingType;

/// Presence marker: the setting may have no value; read it with [`super::Config::find`].
#[derive(Debug, Clone, Copy)]
pub struct Optional;

/// Presence marker: the setting always has a value (it has a default, or is required); read it
/// with [`super::Config::get`], which cannot fail.
#[derive(Debug, Clone, Copy)]
pub struct Present;

/// A declared setting: key, type, default, whether it is required, and a description. Declared as
/// a `const`, so the key is checked when the crate compiles:
///
/// ```
/// use std::time::Duration;
/// use fw_core::config::{Key, Present, Setting};
/// const PORT: Setting<u16, Present> = Setting::new(Key::of("http.port"), "port to listen on").default(8080);
/// const DRAIN: Setting<Duration, Present> =
///     Setting::new(Key::of("app.shutdown.drain"), "time each shutdown step may take").default(Duration::from_secs(10));
/// ```
pub struct Setting<T: SettingType, P = Optional> {
    key: Key,
    description: &'static str,
    default: Option<T::Const>,
    required: bool,
    presence: PhantomData<fn() -> (T, P)>,
}

impl<T: SettingType, P> Clone for Setting<T, P> {
    fn clone(&self) -> Self {
        *self
    }
}

impl<T: SettingType, P> Copy for Setting<T, P> {}

impl<T: SettingType, P> fmt::Debug for Setting<T, P> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Setting")
            .field("key", &self.key)
            .field("required", &self.required)
            .field("has_default", &self.default.is_some())
            .finish()
    }
}

impl<T: SettingType> Setting<T, Optional> {
    /// An optional setting with no default.
    #[must_use]
    pub const fn new(key: Key, description: &'static str) -> Self {
        Self {
            key,
            description,
            default: None,
            required: false,
            presence: PhantomData,
        }
    }

    /// Gives the setting a default. A secret's default type has no values, so a secret cannot get
    /// one.
    #[must_use]
    pub const fn default(self, value: T::Const) -> Setting<T, Present> {
        Setting {
            key: self.key,
            description: self.description,
            default: Some(value),
            required: self.required,
            presence: PhantomData,
        }
    }

    /// Marks the setting required: start-up fails when no source gives it a value.
    #[must_use]
    pub const fn required(self) -> Setting<T, Present> {
        Setting {
            key: self.key,
            description: self.description,
            default: self.default,
            required: true,
            presence: PhantomData,
        }
    }
}

impl Setting<Vec<String>, Optional> {
    /// Gives a list setting a default; a separate method because an array literal does not coerce
    /// to a slice through the generic [`Setting::default`].
    #[must_use]
    pub const fn default_list(
        self,
        value: &'static [&'static str],
    ) -> Setting<Vec<String>, Present> {
        self.default(value)
    }
}

impl<T: SettingType, P> Setting<T, P> {
    /// The key.
    #[must_use]
    pub const fn key(&self) -> Key {
        self.key
    }

    /// The description, for tooling.
    #[must_use]
    pub const fn description(&self) -> &'static str {
        self.description
    }

    /// Whether the setting is required.
    #[must_use]
    pub const fn is_required(&self) -> bool {
        self.required
    }

    /// Whether values are secrets.
    #[must_use]
    pub fn is_secret(&self) -> bool {
        T::SECRET
    }
}

/// The proof that a setting was declared: returned by
/// [`crate::Declarations::setting`], and the only way to read the value.
pub struct SettingHandle<T: SettingType, P> {
    pub(crate) key: Key,
    pub(crate) presence: PhantomData<fn() -> (T, P)>,
}

impl<T: SettingType, P> Clone for SettingHandle<T, P> {
    fn clone(&self) -> Self {
        *self
    }
}

impl<T: SettingType, P> Copy for SettingHandle<T, P> {}

impl<T: SettingType, P> fmt::Debug for SettingHandle<T, P> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "SettingHandle({})", self.key)
    }
}

impl<T: SettingType, P> SettingHandle<T, P> {
    /// The key.
    #[must_use]
    pub fn key(&self) -> Key {
        self.key
    }
}

/// What resolving a setting against its sources gave.
pub(crate) enum Resolved {
    Value(Box<dyn Any + Send + Sync>),
    Missing { required: bool },
    Malformed,
}

/// A declaration with its type erased, so declarations of every type live in one list.
pub(crate) trait ErasedSetting: Send + Sync {
    fn key(&self) -> Key;
    fn is_secret(&self) -> bool;
    fn describe(&self) -> String;
    fn resolve(&self, raw: Option<&Raw>) -> Resolved;
}

impl<T: SettingType, P: 'static> ErasedSetting for Setting<T, P> {
    fn key(&self) -> Key {
        self.key
    }
    fn is_secret(&self) -> bool {
        T::SECRET
    }
    fn describe(&self) -> String {
        T::describe()
    }
    fn resolve(&self, raw: Option<&Raw>) -> Resolved {
        match raw {
            Some(raw) => match T::parse(raw) {
                Ok(v) => Resolved::Value(Box::new(v)),
                Err(_) => Resolved::Malformed,
            },
            None => match self.default {
                Some(c) => Resolved::Value(Box::new(T::from_const(c))),
                None => Resolved::Missing {
                    required: self.required,
                },
            },
        }
    }
}
