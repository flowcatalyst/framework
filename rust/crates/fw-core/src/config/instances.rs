//! Named instances of a capability (core contract section 2), validated at configure time.
//!
//! A capability declares its section (`locks`, `database`, `redis`), its drivers and, for each
//! driver, the keys an instance section may hold. `configure` then checks every configured
//! instance against its driver's keys, so an unknown driver, a malformed or missing value and an
//! unknown key are all reported in the one start-up report, before anything is built.

use std::borrow::Cow;
use std::collections::BTreeMap;
use std::time::Duration;

use super::Sources;
use super::key::env_name;
use super::raw::Raw;
use super::types::sealed::Parse;
use crate::problem::Problem;
use crate::secret::Secret;

/// The type of a key in an instance section.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyType {
    /// Text.
    String,
    /// A 32-bit integer.
    Integer,
    /// `true` or `false`.
    Boolean,
    /// A duration (`500ms`, `30s`, `PT30S`, ...).
    Duration,
    /// A secret text.
    Secret,
    /// A list of strings.
    StringList,
}

/// One key a driver reads from its instance section, as a path below the instance
/// (`url`, `pool.max`).
#[derive(Debug, Clone, Copy)]
pub struct KeySpec {
    path: &'static str,
    ty: KeyType,
    required: bool,
}

impl KeySpec {
    /// An optional key.
    #[must_use]
    pub const fn new(path: &'static str, ty: KeyType) -> Self {
        Self {
            path,
            ty,
            required: false,
        }
    }

    /// The key is required.
    #[must_use]
    pub const fn required(mut self) -> Self {
        self.required = true;
        self
    }
}

/// A driver of a capability and the keys its instances may hold (`driver` itself excluded).
#[derive(Debug, Clone)]
pub struct DriverSpec {
    name: &'static str,
    keys: Vec<KeySpec>,
}

impl DriverSpec {
    /// A driver named as configuration names it (`memory`, `database`, `redis`).
    #[must_use]
    pub fn new(name: &'static str, keys: impl IntoIterator<Item = KeySpec>) -> Self {
        Self {
            name,
            keys: keys.into_iter().collect(),
        }
    }

    /// The driver's name.
    #[must_use]
    pub fn name(&self) -> &'static str {
        self.name
    }
}

/// A capability's configuration section.
#[derive(Debug, Clone)]
pub struct InstanceSection {
    section: &'static str,
    capability: &'static str,
    default_driver: Option<&'static str>,
    drivers: Vec<DriverSpec>,
    not_compiled: Vec<(&'static str, &'static str)>,
}

impl InstanceSection {
    /// A section, for example `locks` for the capability `LockStore`.
    #[must_use]
    pub fn new(section: &'static str, capability: &'static str) -> Self {
        Self {
            section,
            capability,
            default_driver: None,
            drivers: Vec::new(),
            not_compiled: Vec::new(),
        }
    }

    /// The driver an instance without a `driver` key uses (capabilities with one obvious driver:
    /// `postgresql` for databases, `redis` for Redis connections).
    #[must_use]
    pub fn default_driver(mut self, name: &'static str) -> Self {
        self.default_driver = Some(name);
        self
    }

    /// Registers a driver.
    #[must_use]
    pub fn driver(mut self, driver: DriverSpec) -> Self {
        self.drivers.push(driver);
        self
    }

    /// Names a driver that exists but whose Cargo feature was not compiled in, so naming it gives a
    /// problem that says how to fix it (`feature` like "`redis` of fw-locks").
    #[must_use]
    pub fn not_compiled(mut self, driver: &'static str, feature: &'static str) -> Self {
        self.not_compiled.push((driver, feature));
        self
    }

    /// The section name.
    #[must_use]
    pub fn section(&self) -> &'static str {
        self.section
    }
}

/// The proof that a section was declared; reads its instances from [`super::Config::instances`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct InstancesHandle {
    pub(crate) section: &'static str,
}

/// A value read from an instance section.
#[derive(Debug, Clone)]
enum Value {
    Text(String),
    Integer(i32),
    Boolean(bool),
    Duration(Duration),
    Secret(Secret),
    List(Vec<String>),
}

/// One configured instance, its values checked against its driver's keys.
#[derive(Debug, Clone)]
pub struct InstanceConfig {
    name: String,
    driver: String,
    path: String,
    values: BTreeMap<&'static str, Value>,
}

impl InstanceConfig {
    /// The instance name.
    #[must_use]
    pub fn name(&self) -> &str {
        &self.name
    }

    /// The driver name.
    #[must_use]
    pub fn driver(&self) -> &str {
        &self.driver
    }

    /// The section path, for example `locks.instances.shared`.
    #[must_use]
    pub fn path(&self) -> &str {
        &self.path
    }

    /// A text value.
    #[must_use]
    pub fn string(&self, key: &str) -> Option<&str> {
        match self.values.get(key) {
            Some(Value::Text(t)) => Some(t),
            _ => None,
        }
    }

    /// An integer value.
    #[must_use]
    pub fn integer(&self, key: &str) -> Option<i32> {
        match self.values.get(key) {
            Some(Value::Integer(n)) => Some(*n),
            _ => None,
        }
    }

    /// A boolean value.
    #[must_use]
    pub fn boolean(&self, key: &str) -> Option<bool> {
        match self.values.get(key) {
            Some(Value::Boolean(b)) => Some(*b),
            _ => None,
        }
    }

    /// A duration value.
    #[must_use]
    pub fn duration(&self, key: &str) -> Option<Duration> {
        match self.values.get(key) {
            Some(Value::Duration(d)) => Some(*d),
            _ => None,
        }
    }

    /// A secret value.
    #[must_use]
    pub fn secret(&self, key: &str) -> Option<&Secret> {
        match self.values.get(key) {
            Some(Value::Secret(s)) => Some(s),
            _ => None,
        }
    }

    /// A string-list value.
    #[must_use]
    pub fn list(&self, key: &str) -> Option<&[String]> {
        match self.values.get(key) {
            Some(Value::List(l)) => Some(l),
            _ => None,
        }
    }
}

/// The instances configured in one section, checked.
#[derive(Debug, Clone, Default)]
pub struct ConfiguredInstances {
    default: Option<String>,
    instances: Vec<InstanceConfig>,
}

impl ConfiguredInstances {
    pub(crate) const EMPTY: Self = Self {
        default: None,
        instances: Vec::new(),
    };

    /// The default instance's name: `default`, or the only instance.
    #[must_use]
    pub fn default_name(&self) -> Option<&str> {
        self.default.as_deref()
    }

    /// Every instance, in the order the files list them.
    pub fn iter(&self) -> impl Iterator<Item = &InstanceConfig> {
        self.instances.iter()
    }

    /// One instance by name.
    #[must_use]
    pub fn get(&self, name: &str) -> Option<&InstanceConfig> {
        self.instances.iter().find(|i| i.name == name)
    }

    /// The instance names, in order.
    #[must_use]
    pub fn names(&self) -> Vec<&str> {
        self.instances.iter().map(|i| i.name.as_str()).collect()
    }

    /// Whether no instance is configured.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.instances.is_empty()
    }
}

struct Found<'a> {
    raw: Cow<'a, Raw>,
    source: String,
}

/// Java's `InstanceConfig.find`: the environment variable of the full path, then the files.
fn find<'a>(sources: &'a Sources, full: &str) -> Option<Found<'a>> {
    let env = env_name(full);
    if let Some(v) = sources.env.get(&env) {
        return Some(Found {
            raw: Cow::Owned(Raw::Text(v)),
            source: format!("environment variable {env}"),
        });
    }
    let leaf = sources.leaves.get(full)?;
    let raw = leaf.value.as_ref()?;
    Some(Found {
        raw: Cow::Borrowed(raw),
        source: leaf.source.clone(),
    })
}

fn source_of(sources: &Sources, full: &str) -> String {
    find(sources, full).map_or_else(|| "not set".to_owned(), |f| f.source)
}

fn malformed(module: &str, full: &str, found: &Found<'_>, describe: &str, secret: bool) -> Problem {
    let message = if secret {
        format!("expected {describe}")
    } else {
        format!("expected {describe}, found '{}'", found.raw)
    };
    Problem::new(Some(module), Some(full), message).with_source(found.source.clone())
}

fn missing(module: &str, full: &str) -> Problem {
    Problem::new(
        Some(module),
        Some(full),
        format!(
            "missing required key; set it in a config file or as environment variable {}",
            env_name(full)
        ),
    )
}

fn read_string(
    module: &str,
    sources: &Sources,
    full: &str,
    problems: &mut Vec<Problem>,
) -> Option<String> {
    let found = find(sources, full)?;
    match String::parse(&found.raw) {
        Ok(v) => Some(v),
        Err(_) => {
            problems.push(malformed(module, full, &found, &String::describe(), false));
            None
        }
    }
}

fn read_value(
    module: &str,
    sources: &Sources,
    full: &str,
    ty: KeyType,
    problems: &mut Vec<Problem>,
) -> Option<Value> {
    let found = find(sources, full)?;
    let (parsed, describe, secret) = match ty {
        KeyType::String => (
            String::parse(&found.raw).ok().map(Value::Text),
            String::describe(),
            false,
        ),
        KeyType::Integer => (
            i32::parse(&found.raw).ok().map(Value::Integer),
            i32::describe(),
            false,
        ),
        KeyType::Boolean => (
            bool::parse(&found.raw).ok().map(Value::Boolean),
            bool::describe(),
            false,
        ),
        KeyType::Duration => (
            Duration::parse(&found.raw).ok().map(Value::Duration),
            Duration::describe(),
            false,
        ),
        KeyType::Secret => (
            Secret::parse(&found.raw).ok().map(Value::Secret),
            Secret::describe(),
            true,
        ),
        KeyType::StringList => (
            Vec::<String>::parse(&found.raw).ok().map(Value::List),
            Vec::<String>::describe(),
            false,
        ),
    };
    if parsed.is_none() {
        problems.push(malformed(module, full, &found, &describe, secret));
    }
    parsed
}

/// The names directly under `prefix.` in the files, in first-seen order.
fn child_names(sources: &Sources, prefix: &str) -> Vec<String> {
    let dotted = format!("{prefix}.");
    let mut names: Vec<String> = Vec::new();
    for full in sources.leaves.keys() {
        if let Some(rest) = full.strip_prefix(&dotted) {
            let name = rest.split('.').next().unwrap_or(rest).to_owned();
            if !names.contains(&name) {
                names.push(name);
            }
        }
    }
    names
}

/// Checks one declared section against the sources (Java's `Instances.build`, less the building).
pub(crate) fn validate(
    module: &str,
    decl: &InstanceSection,
    sources: &Sources,
    problems: &mut Vec<Problem>,
) -> ConfiguredInstances {
    let section = decl.section;
    let default_key = format!("{section}.default");
    let default_name = read_string(module, sources, &default_key, problems);

    // Stray top-level keys (anything but `default` and the `instances` subtree).
    let dotted = format!("{section}.");
    for full in sources.leaves.keys() {
        let Some(rest) = full.strip_prefix(&dotted) else {
            continue;
        };
        if rest == "default" || rest == "instances" || rest.starts_with("instances.") {
            continue;
        }
        problems.push(
            Problem::new(
                Some(module),
                Some(full),
                format!("unknown key in section '{section}'"),
            )
            .with_source(source_of(sources, full)),
        );
    }

    let instances_path = format!("{section}.instances");
    let names = child_names(sources, &instances_path);
    let mut built = Vec::new();
    for name in &names {
        let path = format!("{instances_path}.{name}");
        let driver_key = format!("{path}.driver");
        let driver = match (
            read_string(module, sources, &driver_key, problems),
            decl.default_driver,
        ) {
            (Some(d), _) => d,
            (None, Some(d)) if find(sources, &driver_key).is_none() => d.to_owned(),
            (None, Some(_)) => continue, // malformed, reported
            (None, None) => {
                if find(sources, &driver_key).is_none() {
                    problems.push(missing(module, &driver_key));
                }
                continue;
            }
        };
        let Some(spec) = decl.drivers.iter().find(|d| d.name == driver) else {
            let known: Vec<&str> = decl.drivers.iter().map(|d| d.name).collect();
            let known = if known.is_empty() {
                "(none)".to_owned()
            } else {
                known.join(", ")
            };
            let message = match decl.not_compiled.iter().find(|(d, _)| *d == driver) {
                Some((_, feature)) => format!(
                    "unknown driver '{driver}' for {}; it needs the Cargo feature {feature}; registered: {known}",
                    decl.capability
                ),
                None => format!(
                    "unknown driver '{driver}' for {}; registered: {known}",
                    decl.capability
                ),
            };
            problems.push(Problem::new(Some(module), Some(&driver_key), message));
            continue;
        };
        let mut read = vec![driver_key.clone()];
        let mut values = BTreeMap::new();
        for key in &spec.keys {
            let full = format!("{path}.{}", key.path);
            read.push(full.clone());
            match read_value(module, sources, &full, key.ty, problems) {
                Some(v) => {
                    values.insert(key.path, v);
                }
                None => {
                    if key.required && find(sources, &full).is_none() {
                        problems.push(missing(module, &full));
                    }
                }
            }
        }
        let instance_prefix = format!("{path}.");
        for full in sources.leaves.keys() {
            if full.starts_with(&instance_prefix) && !read.contains(full) {
                problems.push(
                    Problem::new(
                        Some(module),
                        Some(full),
                        format!("unknown key for driver '{driver}'"),
                    )
                    .with_source(source_of(sources, full)),
                );
            }
        }
        built.push(InstanceConfig {
            name: name.clone(),
            driver,
            path,
            values,
        });
    }

    let default = match default_name {
        Some(d) if names.contains(&d) => Some(d),
        Some(d) => {
            let known = if names.is_empty() {
                "(none)".to_owned()
            } else {
                names.join(", ")
            };
            problems.push(Problem::new(
                Some(module),
                Some(&default_key),
                format!("names instance '{d}', which is not configured; known: {known}"),
            ));
            None
        }
        None if names.len() == 1 => names.first().cloned(),
        None if names.len() > 1 => {
            problems.push(Problem::new(
                Some(module),
                Some(&default_key),
                format!(
                    "missing; several instances are configured ({}) so one must be named as the default",
                    names.join(", ")
                ),
            ));
            None
        }
        None => None,
    };
    ConfiguredInstances {
        default,
        instances: built,
    }
}
