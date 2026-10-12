//! `conformance/config/config.json`, written by the Java `ConfigConformanceTest`.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::collections::BTreeMap;
use std::fs;
use std::time::Duration;

use fw_conformance::json;
use fw_core::config::{Config, ConfigEnum, Environment, Key, Setting, SettingType, env_name};
use fw_core::{AppBuilder, AppName, Declarations, Module, ModuleId, Secret};
use serde_json::{Value, json};

#[derive(Debug, Clone, Copy, PartialEq)]
enum Mode {
    Fast,
    SlowStart,
}

impl ConfigEnum for Mode {
    const VALUES: &'static [Self] = &[Mode::Fast, Mode::SlowStart];
    fn name(self) -> &'static str {
        match self {
            Mode::Fast => "FAST",
            Mode::SlowStart => "SLOW_START",
        }
    }
}

trait Normalise {
    fn normalise(&self) -> Value;
}
impl Normalise for String {
    fn normalise(&self) -> Value {
        json!(self)
    }
}
impl Normalise for i32 {
    fn normalise(&self) -> Value {
        json!(self)
    }
}
impl Normalise for i64 {
    fn normalise(&self) -> Value {
        json!(self)
    }
}
impl Normalise for bool {
    fn normalise(&self) -> Value {
        json!(self)
    }
}
impl Normalise for Duration {
    fn normalise(&self) -> Value {
        json!(self.as_nanos().to_string())
    }
}
impl Normalise for Vec<String> {
    fn normalise(&self) -> Value {
        json!(self)
    }
}
impl Normalise for Secret {
    fn normalise(&self) -> Value {
        json!(self.reveal())
    }
}
impl Normalise for Mode {
    fn normalise(&self) -> Value {
        json!(self.name())
    }
}

fn leak(s: &str) -> &'static str {
    Box::leak(s.to_owned().into_boxed_str())
}

type Reader = Box<dyn Fn(&Config) -> Value>;

/// Declares one setting of type `T` and returns a reader of its normalised value.
fn declare<T: SettingType + Normalise>(
    d: &mut Declarations<'_>,
    key: Key,
    default: Option<T::Const>,
    required: bool,
) -> Reader {
    let base = Setting::<T>::new(key, "vector");
    match (default, required) {
        (Some(c), _) => {
            let h = d.setting(base.default(c));
            Box::new(move |cfg| cfg.get(h).normalise())
        }
        (None, true) => {
            let h = d.setting(base.required());
            Box::new(move |cfg| cfg.get(h).normalise())
        }
        (None, false) => {
            let h = d.setting(base);
            Box::new(move |cfg| cfg.find(h).map_or(Value::Null, |v| v.normalise()))
        }
    }
}

fn declare_from(d: &mut Declarations<'_>, decl: &Value) -> Reader {
    let key = Key::parse(leak(decl["key"].as_str().unwrap())).unwrap();
    let required = decl["required"].as_bool().unwrap_or(false);
    let def = &decl["default"];
    match decl["type"].as_str().unwrap() {
        "string" => declare::<String>(d, key, def.as_str().map(leak), required),
        "integer" => declare::<i32>(
            d,
            key,
            def.as_i64().map(|n| i32::try_from(n).unwrap()),
            required,
        ),
        "long" => declare::<i64>(d, key, def.as_i64(), required),
        "boolean" => declare::<bool>(d, key, def.as_bool(), required),
        "duration" => declare::<Duration>(
            d,
            key,
            decl["defaultMillis"].as_u64().map(Duration::from_millis),
            required,
        ),
        "stringList" => {
            let def: Option<&'static [&'static str]> = def.as_array().map(|items| {
                let v: Vec<&'static str> =
                    items.iter().map(|i| leak(i.as_str().unwrap())).collect();
                &*Box::leak(v.into_boxed_slice())
            });
            declare::<Vec<String>>(d, key, def, required)
        }
        "secret" => declare::<Secret>(d, key, None, required),
        "enum" => declare::<Mode>(
            d,
            key,
            def.as_str()
                .map(|n| *Mode::VALUES.iter().find(|m| m.name() == n).unwrap()),
            required,
        ),
        other => panic!("unknown type {other}"),
    }
}

/// One module declaring a run of the scenario's declarations.
struct Dyn {
    id: ModuleId,
    decls: Vec<Value>,
}

impl Module for Dyn {
    type Declared = Vec<(String, Reader)>;
    fn id(&self) -> ModuleId {
        self.id
    }
    fn declare(self, d: &mut Declarations<'_>) -> Self::Declared {
        self.decls
            .iter()
            .map(|decl| {
                (
                    decl["key"].as_str().unwrap().to_owned(),
                    declare_from(d, decl),
                )
            })
            .collect()
    }
}

/// Configures an application from a scenario: the values read back, or every problem.
async fn run(
    env: &BTreeMap<String, String>,
    files: &[(String, String)],
    decls: &[Value],
) -> Result<Value, Vec<String>> {
    let dir = tempfile::tempdir().unwrap();
    for (name, text) in files {
        fs::write(dir.path().join(name), text).unwrap();
    }
    let mut app = AppBuilder::new(AppName::of("vectors"))
        .config_dir(dir.path())
        .environment(Environment::from_pairs(env.clone()));
    // Consecutive declarations of one module go to one module, in order.
    let mut readers = Vec::new();
    let mut i = 0;
    while i < decls.len() {
        let module = decls[i]["module"].as_str().unwrap();
        let group: Vec<Value> = decls[i..]
            .iter()
            .take_while(|d| d["module"] == module)
            .cloned()
            .collect();
        i += group.len();
        readers.extend(app.module(Dyn {
            id: ModuleId::of(leak(module)),
            decls: group,
        }));
    }
    let prefix = format!("{}/", dir.path().display());
    match app.configure().await {
        Ok(c) => {
            let mut values = serde_json::Map::new();
            for (key, read) in &readers {
                values.insert(key.clone(), read(c.config()));
            }
            Ok(Value::Object(values))
        }
        Err(e) => Err(e
            .problems()
            .iter()
            .map(|p| p.to_string().replace(&prefix, "config/"))
            .collect()),
    }
}

fn env_of(v: &Value) -> BTreeMap<String, String> {
    v.as_object()
        .unwrap()
        .iter()
        .map(|(k, v)| (k.clone(), v.as_str().unwrap().to_owned()))
        .collect()
}

#[test]
fn environment_names_match_java() {
    for case in json("config/config.json")["envNames"].as_array().unwrap() {
        let key = case["key"].as_str().unwrap();
        assert_eq!(env_name(key), case["env"].as_str().unwrap(), "{key}");
    }
}

#[tokio::test]
async fn value_parsing_matches_java() {
    for case in json("config/config.json")["values"].as_array().unwrap() {
        let ty = case["type"].as_str().unwrap();
        let text = case["text"].as_str().unwrap();
        let decl = json!({ "module": "v", "key": "v.x", "type": ty });
        let env = BTreeMap::from([("V_X".to_owned(), text.to_owned())]);
        match run(&env, &[], &[decl]).await {
            Ok(values) => assert_eq!(values["v.x"], case["value"], "{ty} {text:?}"),
            Err(problems) => {
                let expected = case["error"]
                    .as_str()
                    .unwrap_or_else(|| panic!("{ty} {text:?} failed: {problems:?}"));
                let prefix = "module 'v': key 'v.x' (from environment variable V_X): expected ";
                let line = &problems[0];
                let rest = line
                    .strip_prefix(prefix)
                    .unwrap_or_else(|| panic!("{line}"));
                let describe = rest.split(", found '").next().unwrap();
                assert_eq!(describe, expected, "{ty} {text:?}");
                assert_eq!(problems.len(), 1);
            }
        }
    }
}

#[tokio::test]
async fn scenarios_match_java() {
    for case in json("config/config.json")["scenarios"].as_array().unwrap() {
        let name = case["name"].as_str().unwrap();
        let files: Vec<(String, String)> = case["files"]
            .as_object()
            .unwrap()
            .iter()
            .map(|(k, v)| (k.clone(), v.as_str().unwrap().to_owned()))
            .collect();
        let result = run(
            &env_of(&case["env"]),
            &files,
            case["declarations"].as_array().unwrap(),
        )
        .await;
        match (result, case.get("values"), case.get("problems")) {
            (Ok(values), Some(expected), None) => assert_eq!(&values, expected, "{name}"),
            (Err(problems), None, Some(expected)) => {
                assert_eq!(json!(problems), *expected, "{name}")
            }
            (got, _, _) => panic!("{name}: Java and Rust disagree on success: {got:?}"),
        }
    }
}
