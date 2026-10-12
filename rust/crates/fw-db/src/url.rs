//! The `url` key of a database instance: the forms it accepts and how it becomes connect options.

use std::str::FromStr;

use sqlx::postgres::PgConnectOptions;

/// Parses an instance's `url`.
///
/// Accepted forms:
///
/// - `postgres://[user[:password]@]host[:port]/db[?params]` and `postgresql://...`: Rust's own
///   form (sqlx's URL syntax; libpq-style parameters such as `sslmode`, `application_name`,
///   `host`, `port`, `dbname`, `user` and `password` are read);
/// - `jdbc:postgresql://...`: a Java service's value, so a Rust service can take over its
///   configuration. The `jdbc:` prefix (any case) is dropped and the rest is read as above.
///   JDBC-only parameters (`ssl`, `currentSchema`, `ApplicationName`, ...) are not understood by
///   sqlx and are ignored with a warning in the log.
///
/// Plain `key=value` pairs (`host=... dbname=...`) are not accepted: sqlx has no parser for them.
///
/// # Errors
///
/// A message saying which forms are accepted, when the value is none of them or the URL is
/// malformed.
pub fn parse_url(url: &str) -> Result<PgConnectOptions, String> {
    let trimmed = url.trim();
    let rest = match trimmed.get(..5) {
        Some(prefix) if prefix.eq_ignore_ascii_case("jdbc:") => &trimmed[5..],
        _ => trimmed,
    };
    let scheme_ok = ["postgres://", "postgresql://"].iter().any(|s| {
        rest.get(..s.len())
            .is_some_and(|p| p.eq_ignore_ascii_case(s))
    });
    if !scheme_ok {
        let hint = if rest.contains('=') && !rest.contains("://") {
            " (key=value pairs are not supported, write the connection as a URL)"
        } else {
            ""
        };
        return Err(format!(
            "url must be a postgres:// or postgresql:// URL, or a jdbc:postgresql:// URL{hint}"
        ));
    }
    PgConnectOptions::from_str(rest).map_err(|e| format!("url is not a valid PostgreSQL URL: {e}"))
}
