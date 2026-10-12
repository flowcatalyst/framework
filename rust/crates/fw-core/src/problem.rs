//! Start-up problems: everything that stops an application from starting, collected and reported
//! together (core contract sections 1 and 4).

use std::fmt;

/// One start-up problem, naming its module, key and source where it has them.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Problem {
    module: Option<String>,
    key: Option<String>,
    source: Option<String>,
    message: String,
}

impl Problem {
    /// A problem with a module, a key and a message.
    #[must_use]
    pub fn new(module: Option<&str>, key: Option<&str>, message: impl Into<String>) -> Self {
        Self {
            module: module.map(str::to_owned),
            key: key.map(str::to_owned),
            source: None,
            message: message.into(),
        }
    }

    /// The same problem, naming where the offending value came from.
    #[must_use]
    pub fn with_source(mut self, source: impl Into<String>) -> Self {
        self.source = Some(source.into());
        self
    }

    /// The module that declared what the problem is about.
    #[must_use]
    pub fn module(&self) -> Option<&str> {
        self.module.as_deref()
    }

    /// The dotted key, when it concerns one.
    #[must_use]
    pub fn key(&self) -> Option<&str> {
        self.key.as_deref()
    }

    /// Where the value came from, when it has one.
    #[must_use]
    pub fn source(&self) -> Option<&str> {
        self.source.as_deref()
    }

    /// What is wrong; never the text of a secret.
    #[must_use]
    pub fn message(&self) -> &str {
        &self.message
    }
}

/// The Java `ConfigProblem.toString()` form: `module 'm': key 'k' (from s): message`.
impl fmt::Display for Problem {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if let Some(m) = &self.module {
            write!(f, "module '{m}': ")?;
        }
        if let Some(k) = &self.key {
            write!(f, "key '{k}'")?;
            if let Some(s) = &self.source {
                write!(f, " (from {s})")?;
            }
            f.write_str(": ")?;
        } else if let Some(s) = &self.source {
            write!(f, "{s}: ")?;
        }
        f.write_str(&self.message)
    }
}

/// The application could not start. Lists every problem found.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub struct StartupError {
    problems: Vec<Problem>,
}

impl StartupError {
    /// An error with these problems.
    #[must_use]
    pub fn new(problems: Vec<Problem>) -> Self {
        Self { problems }
    }

    /// An error with one problem.
    #[must_use]
    pub fn single(problem: Problem) -> Self {
        Self {
            problems: vec![problem],
        }
    }

    /// An error with one problem built from a module, a key and a message.
    #[must_use]
    pub fn of(module: Option<&str>, key: Option<&str>, message: impl Into<String>) -> Self {
        Self::single(Problem::new(module, key, message))
    }

    /// Every problem found.
    #[must_use]
    pub fn problems(&self) -> &[Problem] {
        &self.problems
    }

    /// Adds the problems of another error.
    pub fn extend(&mut self, other: Self) {
        self.problems.extend(other.problems);
    }
}

impl fmt::Display for StartupError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let n = self.problems.len();
        write!(
            f,
            "Application failed to start: {n} {}",
            if n == 1 { "problem" } else { "problems" }
        )?;
        for p in &self.problems {
            write!(f, "\n  - {p}")?;
        }
        Ok(())
    }
}
