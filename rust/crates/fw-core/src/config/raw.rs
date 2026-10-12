//! Raw values from the sources: YAML files flattened into dotted leaves, and the environment.

use std::fmt;
use std::fs;
use std::path::Path;

use indexmap::IndexMap;
use serde_norway::Value;

use crate::problem::Problem;

/// A raw source value: text (a YAML scalar or an environment variable), a YAML list, or something
/// that is neither (a mapping inside a list).
#[derive(Debug, Clone, PartialEq)]
pub enum Raw {
    /// A scalar as text: strings as written, booleans `true`/`false`, numbers in decimal.
    Text(String),
    /// A YAML list.
    List(Vec<Raw>),
    /// Anything else.
    Other,
}

impl Raw {
    /// The scalar text, or `None` for a list or anything else.
    #[must_use]
    pub fn text(&self) -> Option<&str> {
        match self {
            Raw::Text(t) => Some(t),
            Raw::List(_) | Raw::Other => None,
        }
    }
}

/// How a raw value is quoted in an error message (`found '...'`): a list is "a list".
impl fmt::Display for Raw {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Raw::Text(t) => f.write_str(t),
            Raw::List(_) => f.write_str("a list"),
            Raw::Other => f.write_str("a mapping"),
        }
    }
}

/// One flattened value from a file; `None` marks an explicit null or an empty mapping.
#[derive(Debug, Clone)]
pub(crate) struct Leaf {
    pub(crate) value: Option<Raw>,
    pub(crate) source: String,
}

/// Flattened file contents, later files already applied, in first-seen order.
pub(crate) type Leaves = IndexMap<String, Leaf>;

/// Reads one YAML file if it exists, flattening it into `into`; problems are appended.
pub(crate) fn read_yaml(file: &Path, into: &mut Leaves, problems: &mut Vec<Problem>) {
    if !file.is_file() {
        return;
    }
    let source = file.display().to_string();
    let text = match fs::read_to_string(file) {
        Ok(t) => t,
        Err(e) => {
            problems.push(
                Problem::new(None, None, format!("cannot read file: {e}")).with_source(source),
            );
            return;
        }
    };
    let root: Value = match serde_norway::from_str(&text) {
        Ok(v) => v,
        Err(e) => {
            let first = e.to_string().lines().next().unwrap_or_default().to_owned();
            problems.push(
                Problem::new(None, None, format!("invalid YAML: {first}")).with_source(source),
            );
            return;
        }
    };
    match untag(root) {
        Value::Null => {}
        Value::Mapping(map) => flatten("", map, &source, into),
        Value::Bool(_)
        | Value::Number(_)
        | Value::String(_)
        | Value::Sequence(_)
        | Value::Tagged(_) => {
            problems.push(
                Problem::new(None, None, "the top level must be a mapping").with_source(source),
            );
        }
    }
}

fn untag(v: Value) -> Value {
    match v {
        Value::Tagged(t) => untag(t.value),
        other @ (Value::Null
        | Value::Bool(_)
        | Value::Number(_)
        | Value::String(_)
        | Value::Sequence(_)
        | Value::Mapping(_)) => other,
    }
}

fn key_text(k: &Value) -> String {
    match k {
        Value::String(s) => s.clone(),
        Value::Null => "null".to_owned(),
        Value::Bool(b) => b.to_string(),
        Value::Number(n) => n.to_string(),
        Value::Sequence(_) | Value::Mapping(_) => format!("{k:?}"),
        Value::Tagged(t) => key_text(&t.value),
    }
}

fn flatten(prefix: &str, map: serde_norway::Mapping, source: &str, into: &mut Leaves) {
    for (k, v) in map {
        let k = key_text(&k);
        let path = if prefix.is_empty() {
            k
        } else {
            format!("{prefix}.{k}")
        };
        put(path, untag(v), source, into);
    }
}

fn put(path: String, value: Value, source: &str, into: &mut Leaves) {
    let leaf = match value {
        Value::Mapping(m) if !m.is_empty() => {
            flatten(&path, m, source, into);
            return;
        }
        Value::Mapping(_) | Value::Null => None,
        other @ (Value::Bool(_)
        | Value::Number(_)
        | Value::String(_)
        | Value::Sequence(_)
        | Value::Tagged(_)) => Some(raw_of(other)),
    };
    if leaf.is_none() && into.contains_key(&path) {
        return; // a null never hides a value from an earlier file
    }
    into.insert(
        path,
        Leaf {
            value: leaf,
            source: source.to_owned(),
        },
    );
}

fn raw_of(v: Value) -> Raw {
    match untag(v) {
        Value::String(s) => Raw::Text(s),
        Value::Bool(b) => Raw::Text(b.to_string()),
        Value::Number(n) => Raw::Text(n.to_string()),
        Value::Sequence(items) => Raw::List(items.into_iter().map(raw_of).collect()),
        Value::Null | Value::Mapping(_) | Value::Tagged(_) => Raw::Other,
    }
}
