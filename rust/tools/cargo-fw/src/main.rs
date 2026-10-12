//! `cargo fw sql prepare` and `cargo fw sql check` (run as `cargo run -p cargo-fw -- sql prepare`,
//! or as `cargo fw ...` once installed): the Rust form of the Java `framework-sql-maven-plugin`
//! goals `generate` and `check` (checked-sql contract section 4).
//!
//! Both apply the migration sets listed in `fw-sql.toml` (at the workspace root) with the
//! framework's own runner to an empty PostgreSQL 18, then run `cargo sqlx prepare --workspace`
//! (with `--check` for `check`) against it, so the committed `.sqlx/` describes every
//! `sqlx::query!` of the workspace against the real migrated schema. The database is
//! `FW_SQL_DATABASE_URL` when set (it must be empty), otherwise a fresh database on the test server
//! (`FW_TEST_DATABASE_URL`, else a Testcontainers `postgres:18-alpine`). Needs `sqlx-cli` at the
//! version of the `sqlx` library (0.8.6).

use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode};

use fw_core::{MigrationFile, MigrationSet, ModuleId};
use fw_db::migrations;
use serde::Deserialize;
use sqlx::PgPool;

#[derive(Deserialize)]
struct SqlConfig {
    migrations: Vec<SetConfig>,
}

#[derive(Deserialize)]
struct SetConfig {
    module: String,
    dir: PathBuf,
}

const USAGE: &str = "usage: cargo fw sql prepare|check";

#[tokio::main]
async fn main() -> ExitCode {
    // `cargo fw ...` passes "fw" as the first argument; `cargo run -p cargo-fw -- ...` does not.
    let args: Vec<String> = env::args().skip(1).filter(|a| a != "fw").collect();
    let check = match args
        .iter()
        .map(String::as_str)
        .collect::<Vec<_>>()
        .as_slice()
    {
        ["sql", "prepare"] => false,
        ["sql", "check"] => true,
        _ => {
            eprintln!("{USAGE}");
            return ExitCode::from(2);
        }
    };
    match run(check).await {
        Ok(code) => code,
        Err(e) => {
            eprintln!("cargo fw sql: {e}");
            ExitCode::FAILURE
        }
    }
}

fn workspace_root() -> Result<PathBuf, String> {
    let mut dir = env::current_dir().map_err(|e| e.to_string())?;
    loop {
        if dir.join("fw-sql.toml").is_file() {
            return Ok(dir);
        }
        if !dir.pop() {
            return Err("no fw-sql.toml in this directory or above".to_owned());
        }
    }
}

fn read_set(root: &Path, set: &SetConfig) -> Result<MigrationSet, String> {
    let dir = root.join(&set.dir);
    let mut names: Vec<String> = fs::read_dir(&dir)
        .map_err(|e| format!("{}: {e}", dir.display()))?
        .filter_map(Result::ok)
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| n.ends_with(".sql"))
        .collect();
    names.sort();
    let mut files = Vec::new();
    for name in names {
        let bytes = fs::read(dir.join(&name)).map_err(|e| format!("{name}: {e}"))?;
        files.push(MigrationFile::owned(name, bytes));
    }
    let module: &'static str = Box::leak(set.module.clone().into_boxed_str());
    Ok(MigrationSet::new(ModuleId::of(module), files))
}

async fn database() -> Result<String, String> {
    if let Ok(url) = env::var("FW_SQL_DATABASE_URL") {
        return Ok(url);
    }
    let server = fw_test::postgres().await.map_err(|e| e.to_string())?;
    Ok(server
        .fresh_database()
        .await
        .map_err(|e| e.to_string())?
        .url)
}

async fn run(check: bool) -> Result<ExitCode, String> {
    let root = workspace_root()?;
    let text = fs::read_to_string(root.join("fw-sql.toml")).map_err(|e| e.to_string())?;
    let config: SqlConfig = toml::from_str(&text).map_err(|e| format!("fw-sql.toml: {e}"))?;
    let sets = config
        .migrations
        .iter()
        .map(|s| read_set(&root, s))
        .collect::<Result<Vec<_>, _>>()?;
    let url = database().await?;
    let pool = PgPool::connect(&url)
        .await
        .map_err(|e| format!("cannot connect: {e}"))?;
    migrations::apply("sql", &pool, &sets)
        .await
        .map_err(|e| e.to_string())?;
    pool.close().await;
    let mut cmd = Command::new(env::var("CARGO").unwrap_or_else(|_| "cargo".to_owned()));
    cmd.current_dir(&root)
        .args(["sqlx", "prepare", "--workspace"]);
    if check {
        cmd.arg("--check");
    }
    cmd.args(["--", "--all-targets", "--all-features"])
        .env("DATABASE_URL", &url)
        .env("SQLX_OFFLINE", "false");
    let status = cmd
        .status()
        .map_err(|e| format!("cannot run cargo sqlx (install sqlx-cli 0.8.6): {e}"))?;
    Ok(if status.success() {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    })
}
