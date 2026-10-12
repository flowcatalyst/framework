//! Test support for the framework's own tests and for applications' tests.
//!
//! One PostgreSQL 18 server and one Redis server per test binary, shared by every test in it, and a
//! **fresh database per test**, so tests run in parallel without seeing each other's rows
//! (milestones section 2). The servers come from, in order:
//!
//! 1. `FW_TEST_DATABASE_URL` / `FW_TEST_REDIS_URL`: an existing server. The PostgreSQL URL must name
//!    a role allowed to `CREATE DATABASE` (a database per test is created on it and never dropped:
//!    use a throwaway server). CI sets these to its service containers.
//! 2. Otherwise Testcontainers starts `postgres:18-alpine` / `redis:8-alpine` once per test
//!    binary. Testcontainers removes a container when its value is dropped, but a value shared by
//!    a whole test binary lives in a static and is never dropped, so the container is removed by a
//!    process-exit hook instead.
//!
//! Each `#[tokio::test]` runs its own runtime, so nothing here shares a connection pool between
//! tests: only server addresses are shared, and every test opens its own connections.

use std::error::Error;
use std::process::{self, Command};
use std::sync::Mutex;
use std::sync::PoisonError;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;
use std::{env, fmt, mem};

use rand::Rng;
use sqlx::{Connection, Executor, PgConnection};
use testcontainers::core::IntoContainerPort;
use testcontainers::runners::AsyncRunner;
use testcontainers::{ContainerAsync, Image, ImageExt};
use testcontainers_modules::postgres::Postgres;
use testcontainers_modules::redis::Redis;
use tokio::net::TcpStream;
use tokio::sync::OnceCell;
use tokio::time::{self, Instant};

/// Environment variable naming an existing PostgreSQL server to use instead of a container.
pub const DATABASE_URL_VAR: &str = "FW_TEST_DATABASE_URL";
/// Environment variable naming an existing Redis server to use instead of a container.
pub const REDIS_URL_VAR: &str = "FW_TEST_REDIS_URL";

/// Why a test server could not be provided.
#[derive(Debug)]
pub struct TestServerError(String);

impl fmt::Display for TestServerError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl Error for TestServerError {}

fn error(what: &str, cause: impl fmt::Display) -> TestServerError {
    TestServerError(format!("{what}: {cause}"))
}

/// A PostgreSQL server shared by the tests of one binary.
#[derive(Debug)]
pub struct PgServer {
    user: String,
    password: String,
    /// `host:port`.
    address: String,
    /// The database connected to for `CREATE DATABASE`.
    admin_database: String,
}

/// A database created for one test.
#[derive(Debug, Clone)]
pub struct TestDatabase {
    /// The database name, `fw_test_<pid>_<n>_<random>`.
    pub name: String,
    /// A `postgres://` URL for it, credentials included.
    pub url: String,
    /// The same database as a `jdbc:postgresql://` URL without credentials, as the Java
    /// configuration writes it (the Rust database module accepts both).
    pub jdbc_url: String,
    /// The user name.
    pub username: String,
    /// The password.
    pub password: String,
}

static PG: OnceCell<PgServer> = OnceCell::const_new();
static REDIS: OnceCell<RedisServer> = OnceCell::const_new();
static CONTAINERS: Mutex<Vec<String>> = Mutex::new(Vec::new());
static COUNTER: AtomicU64 = AtomicU64::new(0);

fn unique_suffix() -> String {
    let n = COUNTER.fetch_add(1, Ordering::Relaxed);
    let random: u32 = rand::rng().random();
    format!("{}_{n}_{random:08x}", process::id())
}

/// The PostgreSQL server for this test binary, started on first use.
///
/// # Errors
///
/// When `FW_TEST_DATABASE_URL` is malformed or no container could be started (Docker not
/// running).
pub async fn postgres() -> Result<&'static PgServer, TestServerError> {
    PG.get_or_try_init(|| async {
        if let Ok(url) = env::var(DATABASE_URL_VAR) {
            return PgServer::from_url(&url);
        }
        let container = Postgres::default()
            .with_tag("18-alpine")
            .start()
            .await
            .map_err(|e| error("cannot start postgres:18-alpine (is Docker running?)", e))?;
        let port = container
            .get_host_port_ipv4(5432.tcp())
            .await
            .map_err(|e| error("postgres container port", e))?;
        let host = container
            .get_host()
            .await
            .map_err(|e| error("postgres container host", e))?;
        keep_until_exit(container);
        Ok(PgServer {
            user: "postgres".to_owned(),
            password: "postgres".to_owned(),
            address: format!("{host}:{port}"),
            admin_database: "postgres".to_owned(),
        })
    })
    .await
}

impl PgServer {
    /// Reads `postgres://user[:password]@host:port[/database]`.
    fn from_url(url: &str) -> Result<Self, TestServerError> {
        let rest = url
            .strip_prefix("postgres://")
            .or_else(|| url.strip_prefix("postgresql://"))
            .ok_or_else(|| {
                TestServerError(format!("{DATABASE_URL_VAR} must be a postgres:// URL"))
            })?;
        let rest = rest.split('?').next().unwrap_or(rest);
        let (authority, database) = rest.split_once('/').unwrap_or((rest, ""));
        let (credentials, address) = authority.rsplit_once('@').ok_or_else(|| {
            TestServerError(format!(
                "{DATABASE_URL_VAR} must name a user: user@host:port"
            ))
        })?;
        let (user, password) = credentials.split_once(':').unwrap_or((credentials, ""));
        if address.is_empty() || user.is_empty() {
            return Err(TestServerError(format!(
                "{DATABASE_URL_VAR} has no host or user"
            )));
        }
        Ok(Self {
            user: user.to_owned(),
            password: password.to_owned(),
            address: address.to_owned(),
            admin_database: if database.is_empty() {
                "postgres".to_owned()
            } else {
                database.to_owned()
            },
        })
    }

    fn url(&self, database: &str) -> String {
        if self.password.is_empty() {
            format!("postgres://{}@{}/{database}", self.user, self.address)
        } else {
            format!(
                "postgres://{}:{}@{}/{database}",
                self.user, self.password, self.address
            )
        }
    }

    /// A `postgres://` URL for a database on this server.
    #[must_use]
    pub fn database_url(&self, database: &str) -> String {
        self.url(database)
    }

    /// Creates an empty database for one test.
    ///
    /// # Errors
    ///
    /// When the server refuses the connection or the `CREATE DATABASE`.
    pub async fn fresh_database(&self) -> Result<TestDatabase, TestServerError> {
        let name = format!("fw_test_{}", unique_suffix());
        let mut admin = PgConnection::connect(&self.url(&self.admin_database))
            .await
            .map_err(|e| error("cannot connect to the test PostgreSQL server", e))?;
        admin
            .execute(format!("CREATE DATABASE {name}").as_str())
            .await
            .map_err(|e| error("CREATE DATABASE", e))?;
        admin
            .close()
            .await
            .map_err(|e| error("closing the admin connection", e))?;
        Ok(TestDatabase {
            url: self.url(&name),
            jdbc_url: format!("jdbc:postgresql://{}/{name}", self.address),
            username: self.user.clone(),
            password: self.password.clone(),
            name,
        })
    }
}

/// A Redis server shared by the tests of one binary.
#[derive(Debug)]
pub struct RedisServer {
    url: String,
}

/// The Redis server for this test binary, started on first use.
///
/// # Errors
///
/// When no container could be started (Docker not running).
pub async fn redis() -> Result<&'static RedisServer, TestServerError> {
    REDIS
        .get_or_try_init(|| async {
            if let Ok(url) = env::var(REDIS_URL_VAR) {
                return Ok(RedisServer { url });
            }
            let container = Redis::default()
                .with_tag("8-alpine")
                .start()
                .await
                .map_err(|e| error("cannot start redis:8-alpine (is Docker running?)", e))?;
            let port = container
                .get_host_port_ipv4(6379.tcp())
                .await
                .map_err(|e| error("redis container port", e))?;
            let host = container
                .get_host()
                .await
                .map_err(|e| error("redis container host", e))?;
            keep_until_exit(container);
            // The Redis client tries only the first address of a name; use the IPv4 literal.
            let host = if host.to_string() == "localhost" {
                "127.0.0.1".to_owned()
            } else {
                host.to_string()
            };
            // The image logs "ready" before Docker Desktop forwards the published port.
            wait_for_port(&host, port).await?;
            Ok(RedisServer {
                url: format!("redis://{host}:{port}/0"),
            })
        })
        .await
}

impl RedisServer {
    /// The server's `redis://` URL.
    #[must_use]
    pub fn url(&self) -> &str {
        &self.url
    }

    /// A key prefix no other test uses, so tests share the server without sharing keys.
    #[must_use]
    pub fn unique_prefix(&self) -> String {
        format!("fwtest:{}:", unique_suffix())
    }
}

async fn wait_for_port(host: &str, port: u16) -> Result<(), TestServerError> {
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        match TcpStream::connect((host, port)).await {
            Ok(_) => return Ok(()),
            Err(e) if Instant::now() >= deadline => {
                return Err(error(
                    &format!("{host}:{port} never accepted a connection"),
                    e,
                ));
            }
            Err(_) => time::sleep(Duration::from_millis(100)).await,
        }
    }
}

/// Keeps a container for the life of the test binary and removes it when the process exits.
fn keep_until_exit<I: Image>(container: ContainerAsync<I>) {
    let id = container.id().to_owned();
    // Never dropped: dropping it would remove the container while other tests use it.
    mem::forget(container);
    let mut ids = CONTAINERS.lock().unwrap_or_else(PoisonError::into_inner);
    if ids.is_empty() {
        register_exit_hook();
    }
    ids.push(id);
}

extern "C" fn remove_containers() {
    let ids = CONTAINERS
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .clone();
    for id in ids {
        // Best effort: the process is exiting and there is nobody to report a failure to.
        if Command::new("docker")
            .args(["rm", "-f", "-v", &id])
            .output()
            .is_err()
        {
            eprintln!("fw-test: could not remove container {id}");
        }
    }
}

#[expect(
    unsafe_code,
    reason = "atexit is the only hook that runs when a test binary returns from main"
)]
fn register_exit_hook() {
    // SAFETY: `remove_containers` is an `extern "C" fn()` taking nothing, which is what atexit
    // expects; it never unwinds (every error is handled) and only touches a static.
    let rc = unsafe { atexit(remove_containers) };
    if rc != 0 {
        eprintln!("fw-test: could not register the container clean-up hook");
    }
}

#[expect(unsafe_code, reason = "declaration of the C library's atexit")]
unsafe extern "C" {
    fn atexit(f: extern "C" fn()) -> i32;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_a_server_url() {
        let s = PgServer::from_url("postgres://postgres@127.0.0.1:25433").unwrap();
        assert_eq!(s.address, "127.0.0.1:25433");
        assert_eq!(s.admin_database, "postgres");
        assert_eq!(s.url("x"), "postgres://postgres@127.0.0.1:25433/x");
        let s = PgServer::from_url("postgresql://u:p@h:1/admin?sslmode=disable").unwrap();
        assert_eq!((s.user.as_str(), s.password.as_str()), ("u", "p"));
        assert_eq!(s.admin_database, "admin");
        assert!(PgServer::from_url("mysql://x@y").is_err());
        assert!(PgServer::from_url("postgres://host:1").is_err());
    }

    #[tokio::test]
    async fn a_fresh_database_is_empty_and_separate() {
        let server = postgres().await.unwrap();
        let a = server.fresh_database().await.unwrap();
        let b = server.fresh_database().await.unwrap();
        assert_ne!(a.name, b.name);
        let mut c = PgConnection::connect(&a.url).await.unwrap();
        c.execute("CREATE TABLE t (x int)").await.unwrap();
        let mut d = PgConnection::connect(&b.url).await.unwrap();
        let n: i64 = sqlx::query_scalar("SELECT count(*) FROM pg_tables WHERE tablename = 't'")
            .fetch_one(&mut d)
            .await
            .unwrap();
        assert_eq!(n, 0);
        let v: String = sqlx::query_scalar("SHOW server_version")
            .fetch_one(&mut c)
            .await
            .unwrap();
        assert!(v.starts_with("18"), "PostgreSQL 18 expected, got {v}");
    }

    #[tokio::test]
    async fn redis_answers() {
        let server = redis().await.unwrap();
        let client = ::redis::Client::open(server.url()).unwrap();
        let mut c = client.get_multiplexed_async_connection().await.unwrap();
        let prefix = server.unique_prefix();
        let _: () = ::redis::cmd("SET")
            .arg(format!("{prefix}k"))
            .arg("v")
            .query_async(&mut c)
            .await
            .unwrap();
        let v: String = ::redis::cmd("GET")
            .arg(format!("{prefix}k"))
            .query_async(&mut c)
            .await
            .unwrap();
        assert_eq!(v, "v");
    }
}
