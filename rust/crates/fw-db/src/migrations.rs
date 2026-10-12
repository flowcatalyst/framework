//! The migration runner (database contract section 2): the framework's own, not `sqlx::migrate!`,
//! because the history table `fw_schema_history`, its columns, the per-module sets and the
//! checksums (SHA-256 of the file bytes, hex) are a contract shared with the Java framework.
//!
//! The whole run holds a PostgreSQL advisory lock, so instances starting together apply each file
//! once. Everything is checked before anything is applied; each file then runs in its own
//! transaction together with its history row.

use std::collections::{BTreeMap, HashMap, HashSet};

use fw_core::{MigrationSet, Problem, StartupError};
use sha2::{Digest, Sha256};
use sqlx::{Connection, Executor, PgConnection, PgPool, Row};

/// The session-level advisory lock key: the bytes of "FW_MIGRA" (the Java runner's).
pub const ADVISORY_LOCK_KEY: i64 = 0x4657_5F4D_4947_5241;

/// The history table, exactly as the Java runner creates it.
pub const CREATE_HISTORY: &str = "CREATE TABLE IF NOT EXISTS fw_schema_history (
    module      text        NOT NULL,
    version     int         NOT NULL,
    description text        NOT NULL,
    checksum    text        NOT NULL,
    applied_at  timestamptz NOT NULL,
    PRIMARY KEY (module, version)
)";

/// One parsed migration file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Migration {
    /// The module id the history records.
    pub module: String,
    /// The version, `V<version>__`.
    pub version: i32,
    /// The description: the file name's part after `__`, underscores read as spaces.
    pub description: String,
    /// The file name.
    pub file_name: String,
    /// SHA-256 of the file bytes, lower-case hex.
    pub checksum: String,
    sql: String,
}

struct Applied {
    description: String,
    checksum: String,
}

/// SHA-256 of a file's bytes, lower-case hex (the history's checksum).
#[must_use]
pub fn checksum(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

/// `V<version>__<description>.sql` → (version, description). `None` when the name has another form.
fn parse_name(name: &str) -> Option<(&str, &str)> {
    let rest = name.strip_prefix('V')?.strip_suffix(".sql")?;
    let (digits, description) = rest.split_once("__")?;
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) || description.is_empty() {
        return None;
    }
    Some((digits, description))
}

/// Reads the sets into migrations, grouped by module in first-declaration order.
///
/// # Errors
///
/// Badly named files, versions that are not positive integers, empty sets.
pub fn read_sets(sets: &[MigrationSet]) -> Result<Vec<(String, Vec<Migration>)>, StartupError> {
    let mut problems = Vec::new();
    let mut order: Vec<String> = Vec::new();
    let mut by_module: HashMap<String, Vec<Migration>> = HashMap::new();
    for set in sets {
        let module = set.module().as_str().to_owned();
        if !order.contains(&module) {
            order.push(module.clone());
        }
        if set.files().is_empty() {
            problems.push(Problem::new(
                Some(&module),
                None,
                "migration set has no files",
            ));
        }
        for file in set.files() {
            let name = file.name();
            if !name.ends_with(".sql") {
                continue;
            }
            let Some((digits, description)) = parse_name(name) else {
                problems.push(Problem::new(
                    Some(&module),
                    Some(name),
                    "does not match V<version>__<description>.sql",
                ));
                continue;
            };
            let version = digits.parse::<i32>().unwrap_or(0);
            if version < 1 {
                problems.push(Problem::new(
                    Some(&module),
                    Some(name),
                    "version must be a positive integer",
                ));
                continue;
            }
            by_module
                .entry(module.clone())
                .or_default()
                .push(Migration {
                    module: module.clone(),
                    version,
                    description: description.replace('_', " "),
                    file_name: name.to_owned(),
                    checksum: checksum(file.bytes()),
                    sql: String::from_utf8_lossy(file.bytes()).into_owned(),
                });
        }
    }
    if !problems.is_empty() {
        return Err(StartupError::new(problems));
    }
    Ok(order
        .into_iter()
        .map(|m| {
            let files = by_module.remove(&m).unwrap_or_default();
            (m, files)
        })
        .collect())
}

/// Applies migration sets to the database behind `pool` (`db_name` names it in problems).
///
/// # Errors
///
/// Every problem found before applying anything, or the first file that failed (rolled back).
pub async fn apply(
    db_name: &str,
    pool: &PgPool,
    sets: &[MigrationSet],
) -> Result<(), StartupError> {
    let modules = read_sets(sets)?;
    let failed = |e: sqlx::Error| {
        StartupError::of(
            None,
            Some(&format!("database:{db_name}")),
            format!("migrations failed: {e}"),
        )
    };
    let mut conn = pool.acquire().await.map_err(failed)?;
    sqlx::query("SELECT pg_advisory_lock($1)")
        .bind(ADVISORY_LOCK_KEY)
        .execute(&mut *conn)
        .await
        .map_err(failed)?;
    let result = migrate(&mut conn, &modules).await;
    let unlocked = sqlx::query("SELECT pg_advisory_unlock($1)")
        .bind(ADVISORY_LOCK_KEY)
        .execute(&mut *conn)
        .await;
    if let Err(e) = unlocked {
        tracing::warn!(error = %e, "could not release the migration advisory lock; closing the connection");
        // A pooled connection must not keep the lock: closing it ends the session, which releases it.
        if let Err(e) = conn.close().await {
            tracing::warn!(error = %e, "closing the migration connection failed");
        }
    }
    match result {
        Ok(()) => Ok(()),
        Err(Failure::Problems(e)) => Err(e),
        Err(Failure::Database(e)) => Err(failed(e)),
    }
}

enum Failure {
    Problems(StartupError),
    Database(sqlx::Error),
}

impl From<sqlx::Error> for Failure {
    fn from(e: sqlx::Error) -> Self {
        Self::Database(e)
    }
}

async fn migrate(
    conn: &mut PgConnection,
    modules: &[(String, Vec<Migration>)],
) -> Result<(), Failure> {
    conn.execute(CREATE_HISTORY).await?;
    let mut applied: HashMap<String, BTreeMap<i32, Applied>> = HashMap::new();
    for row in sqlx::query("SELECT module, version, description, checksum FROM fw_schema_history")
        .fetch_all(&mut *conn)
        .await?
    {
        applied.entry(row.try_get("module")?).or_default().insert(
            row.try_get("version")?,
            Applied {
                description: row.try_get("description")?,
                checksum: row.try_get("checksum")?,
            },
        );
    }

    // Validate everything before applying anything.
    let mut problems = Vec::new();
    let mut pending = Vec::new();
    let empty = BTreeMap::new();
    for (module, files) in modules {
        let mut seen: HashMap<i32, &str> = HashMap::new();
        let mut reported = HashSet::new();
        let mut by_version: BTreeMap<i32, &Migration> = BTreeMap::new();
        for f in files {
            if let Some(other) = seen.get(&f.version) {
                if reported.insert(f.version) {
                    problems.push(Problem::new(
                        Some(module),
                        Some(&f.file_name),
                        format!("has the same version ({}) as {other}", f.version),
                    ));
                }
            } else {
                seen.insert(f.version, &f.file_name);
                by_version.insert(f.version, f);
            }
        }
        let done = applied.get(module).unwrap_or(&empty);
        let highest = done.keys().copied().max().unwrap_or(0);
        for (version, a) in done {
            match by_version.get(version) {
                None => problems.push(Problem::new(
                    Some(module),
                    Some(&format!("V{version}")),
                    format!("version {version} ({}) was applied but its file is gone", a.description),
                )),
                Some(f) if f.checksum != a.checksum => problems.push(Problem::new(
                    Some(module),
                    Some(&f.file_name),
                    format!(
                        "changed after it was applied (applied checksum {}..., file checksum {}...); applied migrations \
                         must never be edited, add a new version",
                        a.checksum.get(..12).unwrap_or(&a.checksum),
                        f.checksum.get(..12).unwrap_or(&f.checksum)
                    ),
                )),
                Some(_) => {}
            }
        }
        for (version, f) in &by_version {
            if done.contains_key(version) {
                continue;
            }
            if *version < highest {
                problems.push(Problem::new(
                    Some(module),
                    Some(&f.file_name),
                    format!(
                        "was never applied, but a higher version ({highest}) already was; versions must not be added below \
                         the applied ones"
                    ),
                ));
            } else {
                pending.push(*f);
            }
        }
    }
    if !problems.is_empty() {
        return Err(Failure::Problems(StartupError::new(problems)));
    }

    for f in pending {
        tracing::info!(file = %f.file_name, module = %f.module, "applying migration");
        let mut tx = conn.begin().await?;
        let result = apply_file(&mut tx, f).await;
        match result {
            Ok(_) => tx.commit().await?,
            Err(e) => {
                if let Err(rollback) = tx.rollback().await {
                    tracing::warn!(error = %rollback, "rollback of a failed migration failed");
                }
                return Err(Failure::Problems(StartupError::of(
                    Some(&f.module),
                    Some(&f.file_name),
                    format!("migration failed and was rolled back: {e}"),
                )));
            }
        }
    }
    Ok(())
}

async fn apply_file(tx: &mut PgConnection, f: &Migration) -> Result<(), sqlx::Error> {
    tx.execute(f.sql.as_str()).await?;
    sqlx::query(
        "INSERT INTO fw_schema_history (module, version, description, checksum, applied_at) VALUES ($1, $2, $3, $4, now())",
    )
    .bind(&f.module)
    .bind(f.version)
    .bind(&f.description)
    .bind(&f.checksum)
    .execute(&mut *tx)
    .await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_names() {
        assert_eq!(
            parse_name("V0001__create_lock_table.sql"),
            Some(("0001", "create_lock_table"))
        );
        assert_eq!(parse_name("V2__x.sql"), Some(("2", "x")));
        assert_eq!(parse_name("v1__x.sql"), None);
        assert_eq!(parse_name("V1_x.sql"), None);
        assert_eq!(parse_name("V__x.sql"), None);
        assert_eq!(parse_name("V1__.sql"), None);
        assert_eq!(parse_name("Va__x.sql"), None);
    }

    #[test]
    fn checksums_are_sha256_hex() {
        assert_eq!(
            checksum(b""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
    }
}
