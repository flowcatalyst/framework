//! Modules: declaration only (Rust design section 1.1). A module declares settings, instance
//! sections and migration sets, and returns typed handles for what it declared. Building services
//! is the application's own `build` function; there is no container.

use std::borrow::Cow;
use std::fmt;
use std::marker::PhantomData;

use crate::config::{
    Declared, DeclaredSection, InstanceSection, InstancesHandle, Setting, SettingHandle,
    SettingType,
};
use crate::problem::Problem;

/// A module id, `[a-z][a-z0-9-]*`, unique in the application; checked during constant evaluation.
#[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ModuleId(&'static str);

impl ModuleId {
    /// A module id.
    ///
    /// # Panics
    ///
    /// When `text` does not match `[a-z][a-z0-9-]*`; in a `const` that is a compile error.
    #[must_use]
    pub const fn of(text: &'static str) -> Self {
        assert!(is_lower_code(text), "module id must match [a-z][a-z0-9-]*");
        Self(text)
    }

    /// The id text.
    #[must_use]
    pub const fn as_str(&self) -> &'static str {
        self.0
    }
}

impl fmt::Debug for ModuleId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.0)
    }
}

impl fmt::Display for ModuleId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.0)
    }
}

/// `[a-z][a-z0-9-]*`, in a `const fn`.
pub(crate) const fn is_lower_code(text: &str) -> bool {
    let b = text.as_bytes();
    if b.is_empty() || !b[0].is_ascii_lowercase() {
        return false;
    }
    let mut i = 1;
    while i < b.len() {
        if !(b[i].is_ascii_lowercase() || b[i].is_ascii_digit() || b[i] == b'-') {
            return false;
        }
        i += 1;
    }
    true
}

/// A part of an application: declares what it needs and hands back typed handles.
pub trait Module {
    /// The handles this module returns: proof of what it declared, read after configuration.
    type Declared;

    /// The module's id.
    fn id(&self) -> ModuleId;

    /// Declares settings, instance sections and migration sets.
    fn declare(self, d: &mut Declarations<'_>) -> Self::Declared;
}

/// One migration file: `V<version>__<description>.sql` and its bytes.
#[derive(Debug, Clone)]
pub struct MigrationFile {
    name: Cow<'static, str>,
    bytes: Cow<'static, [u8]>,
}

impl MigrationFile {
    /// A file embedded in the binary (`include_bytes!`).
    #[must_use]
    pub const fn embedded(name: &'static str, bytes: &'static [u8]) -> Self {
        Self {
            name: Cow::Borrowed(name),
            bytes: Cow::Borrowed(bytes),
        }
    }

    /// A file read at run time (tools that apply a directory of migrations).
    #[must_use]
    pub fn owned(name: String, bytes: Vec<u8>) -> Self {
        Self {
            name: Cow::Owned(name),
            bytes: Cow::Owned(bytes),
        }
    }

    /// The file name.
    #[must_use]
    pub fn name(&self) -> &str {
        &self.name
    }

    /// The file's bytes.
    #[must_use]
    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }
}

/// A module's migrations: the module id they are recorded under in `fw_schema_history`, and the
/// files (database contract section 2).
#[derive(Debug, Clone)]
pub struct MigrationSet {
    module: ModuleId,
    files: Vec<MigrationFile>,
}

impl MigrationSet {
    /// A set recorded under `module`. The id is a shared contract with the Java framework: the
    /// lock table's set is `locks-database`, the outbox's `outbox`.
    #[must_use]
    pub fn new(module: ModuleId, files: impl IntoIterator<Item = MigrationFile>) -> Self {
        Self {
            module,
            files: files.into_iter().collect(),
        }
    }

    /// The module id the history records.
    #[must_use]
    pub fn module(&self) -> ModuleId {
        self.module
    }

    /// The files.
    #[must_use]
    pub fn files(&self) -> &[MigrationFile] {
        &self.files
    }
}

/// Everything modules declared, in declaration order.
#[derive(Default)]
pub(crate) struct DeclState {
    pub(crate) modules: Vec<ModuleId>,
    pub(crate) settings: Vec<Declared>,
    pub(crate) sections: Vec<DeclaredSection>,
    pub(crate) migrations: Vec<MigrationSet>,
    pub(crate) problems: Vec<Problem>,
}

/// What a module declares through, during [`crate::AppBuilder::module`].
pub struct Declarations<'a> {
    module: ModuleId,
    state: &'a mut DeclState,
}

impl fmt::Debug for Declarations<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Declarations")
            .field("module", &self.module)
            .finish_non_exhaustive()
    }
}

impl<'a> Declarations<'a> {
    pub(crate) fn new(module: ModuleId, state: &'a mut DeclState) -> Self {
        Self { module, state }
    }

    /// The declaring module's id.
    #[must_use]
    pub fn module(&self) -> ModuleId {
        self.module
    }

    /// Declares a setting; the handle reads its value after configuration. A key declared twice
    /// fails start-up, naming both modules.
    pub fn setting<T: SettingType, P: 'static>(
        &mut self,
        setting: Setting<T, P>,
    ) -> SettingHandle<T, P> {
        self.state.settings.push(Declared {
            module: self.module.as_str().to_owned(),
            setting: Box::new(setting),
        });
        SettingHandle {
            key: setting.key(),
            presence: PhantomData,
        }
    }

    /// Declares a capability's instance section and its drivers.
    pub fn instances(&mut self, section: InstanceSection) -> InstancesHandle {
        let handle = InstancesHandle {
            section: section.section(),
        };
        self.state.sections.push(DeclaredSection {
            module: self.module.as_str().to_owned(),
            section,
        });
        handle
    }

    /// Contributes a migration set, applied in declaration order to the default database instance.
    pub fn migrations(&mut self, set: MigrationSet) {
        self.state.migrations.push(set);
    }
}
