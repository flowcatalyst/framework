//! Configuration (core contract section 4): declared settings, sources in a fixed order, typed
//! values read through the handles declaring returned, and instance sections.
//!
//! Sources, lowest to highest: the declaration's default; `config/application.yaml`, then
//! `config/application-{env}.yaml`; `config/secrets.local.yaml` when `APP_ENV` is `local` or
//! `test`; environment variables named after the key. `APP_ENV` defaults to `local`. Every problem
//! (malformed or missing values, duplicate declarations, two keys reading one environment
//! variable, instance sections) is collected into one start-up report.

mod duration;
mod instances;
mod key;
mod raw;
mod setting;
mod types;

use std::any::Any;
use std::collections::{BTreeMap, HashMap};
use std::env;
use std::fmt;
use std::path::Path;
use std::sync::Arc;

pub use instances::{
    ConfiguredInstances, DriverSpec, InstanceConfig, InstanceSection, InstancesHandle, KeySpec,
    KeyType,
};
pub use key::{Key, KeyError, env_name};
pub use setting::{Optional, Present, Setting, SettingHandle};
pub use types::{ConfigEnum, NoDefault, SettingType};

pub(crate) use setting::{ErasedSetting, Resolved};

use crate::problem::Problem;
use raw::{Leaves, Raw, read_yaml};

/// Where environment variables come from: the process, or a fixed map (tests). Variables are read
/// by exact name only; the environment is never listed.
#[derive(Clone, Default)]
pub struct Environment(Option<Arc<BTreeMap<String, String>>>);

impl Environment {
    /// The process environment.
    #[must_use]
    pub fn process() -> Self {
        Self(None)
    }

    /// A fixed set of variables, nothing else.
    #[must_use]
    pub fn from_pairs<K: Into<String>, V: Into<String>>(
        pairs: impl IntoIterator<Item = (K, V)>,
    ) -> Self {
        Self(Some(Arc::new(
            pairs
                .into_iter()
                .map(|(k, v)| (k.into(), v.into()))
                .collect(),
        )))
    }

    /// One variable, by exact name.
    #[must_use]
    pub fn get(&self, name: &str) -> Option<String> {
        match &self.0 {
            None => env::var(name).ok(),
            Some(map) => map.get(name).cloned(),
        }
    }
}

impl fmt::Debug for Environment {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match &self.0 {
            None => f.write_str("Environment(process)"),
            Some(map) => write!(f, "Environment({} fixed variables)", map.len()),
        }
    }
}

/// The merged file sources and the environment.
pub(crate) struct Sources {
    pub(crate) leaves: Leaves,
    pub(crate) env: Environment,
}

/// One declaration with the module that made it.
pub(crate) struct Declared {
    pub(crate) module: String,
    pub(crate) setting: Box<dyn ErasedSetting>,
}

/// One declared instance section with the module that declared it.
pub(crate) struct DeclaredSection {
    pub(crate) module: String,
    pub(crate) section: InstanceSection,
}

/// Resolved configuration: typed values for declared settings and checked instance sections.
/// Values do not change while running.
pub struct Config {
    environment: String,
    values: HashMap<&'static str, Box<dyn Any + Send + Sync>>,
    secrets: Vec<&'static str>,
    sections: HashMap<&'static str, ConfiguredInstances>,
}

impl fmt::Debug for Config {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let mut keys: Vec<&&str> = self.values.keys().collect();
        keys.sort();
        f.debug_struct("Config")
            .field("environment", &self.environment)
            .field("keys", &keys)
            .field("secret_keys", &self.secrets)
            .finish_non_exhaustive()
    }
}

impl Config {
    /// `APP_ENV`, default `local`.
    #[must_use]
    pub fn environment(&self) -> &str {
        &self.environment
    }

    /// Reads a setting that always has a value (it has a default or is required).
    #[must_use]
    pub fn get<T: SettingType>(&self, handle: SettingHandle<T, Present>) -> T {
        #[expect(
            clippy::expect_used,
            reason = "a Present handle comes from declaring the setting, and configure only succeeds when every \
                      declared setting with a default or marked required has a value of its type"
        )]
        self.lookup(handle.key)
            .expect("a declared setting with a default or marked required has a value")
    }

    /// Reads an optional setting.
    #[must_use]
    pub fn find<T: SettingType>(&self, handle: SettingHandle<T, Optional>) -> Option<T> {
        self.lookup(handle.key)
    }

    fn lookup<T: SettingType>(&self, key: Key) -> Option<T> {
        self.values
            .get(key.as_str())
            .and_then(|v| v.downcast_ref::<T>())
            .cloned()
    }

    /// The instances of a declared section.
    #[must_use]
    pub fn instances(&self, handle: InstancesHandle) -> &ConfiguredInstances {
        static EMPTY: ConfiguredInstances = ConfiguredInstances::EMPTY;
        self.sections.get(handle.section).unwrap_or(&EMPTY)
    }
}

/// Loads and validates configuration. Every problem is returned.
pub(crate) fn load(
    directory: &Path,
    environment: &Environment,
    declarations: &[Declared],
    sections: &[DeclaredSection],
) -> Result<Config, Vec<Problem>> {
    let mut problems = Vec::new();
    let env_name_value = environment
        .get("APP_ENV")
        .filter(|e| !e.trim().is_empty())
        .unwrap_or_else(|| "local".to_owned());

    let mut leaves = Leaves::new();
    read_yaml(
        &directory.join("application.yaml"),
        &mut leaves,
        &mut problems,
    );
    read_yaml(
        &directory.join(format!("application-{env_name_value}.yaml")),
        &mut leaves,
        &mut problems,
    );
    if env_name_value == "local" || env_name_value == "test" {
        read_yaml(
            &directory.join("secrets.local.yaml"),
            &mut leaves,
            &mut problems,
        );
    }
    let sources = Sources {
        leaves,
        env: environment.clone(),
    };

    let mut declared_by: HashMap<&'static str, &str> = HashMap::new();
    let mut values: HashMap<&'static str, Box<dyn Any + Send + Sync>> = HashMap::new();
    let mut secrets = Vec::new();
    for d in declarations {
        let key = d.setting.key();
        if let Some(first) = declared_by.get(key.as_str()) {
            problems.push(Problem::new(
                Some(&d.module),
                Some(key.as_str()),
                format!("declared twice (first by module '{first}')"),
            ));
            continue;
        }
        declared_by.insert(key.as_str(), &d.module);
        if d.setting.is_secret() {
            secrets.push(key.as_str());
        }
        resolve(d, &sources, &mut values, &mut problems);
    }

    check_environment_names(declarations, &sources.leaves, &mut problems);

    let mut configured = HashMap::new();
    let mut seen_sections: HashMap<&'static str, &str> = HashMap::new();
    for s in sections {
        let name = s.section.section();
        if let Some(first) = seen_sections.get(name) {
            problems.push(Problem::new(
                Some(&s.module),
                Some(name),
                format!("instance section declared twice (first by module '{first}')"),
            ));
            continue;
        }
        seen_sections.insert(name, &s.module);
        configured.insert(
            name,
            instances::validate(&s.module, &s.section, &sources, &mut problems),
        );
    }

    if problems.is_empty() {
        Ok(Config {
            environment: env_name_value,
            values,
            secrets,
            sections: configured,
        })
    } else {
        Err(problems)
    }
}

fn resolve(
    d: &Declared,
    sources: &Sources,
    values: &mut HashMap<&'static str, Box<dyn Any + Send + Sync>>,
    problems: &mut Vec<Problem>,
) {
    let key = d.setting.key();
    let env_var = key.env_name();
    let from_env = sources
        .env
        .get(&env_var)
        .map(|v| (Raw::Text(v), format!("environment variable {env_var}")));
    let from_file = || {
        sources
            .leaves
            .get(key.as_str())
            .and_then(|leaf| leaf.value.clone().map(|v| (v, leaf.source.clone())))
    };
    let found = from_env.or_else(from_file);
    match d.setting.resolve(found.as_ref().map(|(raw, _)| raw)) {
        Resolved::Value(v) => {
            values.insert(key.as_str(), v);
        }
        Resolved::Malformed => {
            if let Some((raw, source)) = found {
                let found_text = if d.setting.is_secret() {
                    String::new()
                } else {
                    format!(", found '{raw}'")
                };
                problems.push(
                    Problem::new(
                        Some(&d.module),
                        Some(key.as_str()),
                        format!("expected {}{found_text}", d.setting.describe()),
                    )
                    .with_source(source),
                );
            }
        }
        Resolved::Missing { required: true } => {
            let secret_hint = if d.setting.is_secret() {
                ", config/secrets.local.yaml (local and test only)"
            } else {
                ""
            };
            problems.push(Problem::new(
                Some(&d.module),
                Some(key.as_str()),
                format!(
                    "missing required setting; set it in config/application.yaml{secret_hint} or as environment variable {env_var}"
                ),
            ));
        }
        Resolved::Missing { required: false } => {}
    }
}

/// Fails when two different keys would read the same environment variable (core contract section
/// 4): every declared key against every other, and the instance keys found in the files (those
/// with an `instances` segment) against the declared keys and each other.
fn check_environment_names(
    declarations: &[Declared],
    leaves: &Leaves,
    problems: &mut Vec<Problem>,
) {
    let mut claimed: HashMap<String, (String, String)> = HashMap::new();
    let mut claim = |env: String,
                     key: &str,
                     label: String,
                     module: Option<&str>,
                     problems: &mut Vec<Problem>| {
        match claimed.get(&env) {
            Some((first_key, first_label)) if first_key != key => {
                problems.push(Problem::new(
                    module,
                    Some(key),
                    format!(
                        "reads environment variable {env}, which key '{first_key}' ({first_label}) reads too; keys may \
                         contain '_' and both '.' and '-' become '_', so rename one of them"
                    ),
                ));
            }
            Some(_) => {}
            None => {
                claimed.insert(env, (key.to_owned(), label));
            }
        }
    };
    for d in declarations {
        let key = d.setting.key();
        claim(
            key.env_name(),
            key.as_str(),
            format!("declared by module '{}'", d.module),
            Some(&d.module),
            problems,
        );
    }
    for (key, leaf) in leaves {
        if !(key.starts_with("instances.") || key.contains(".instances.")) {
            continue;
        }
        claim(
            env_name(key),
            key,
            format!("an instance setting in {}", leaf.source),
            None,
            problems,
        );
    }
}
