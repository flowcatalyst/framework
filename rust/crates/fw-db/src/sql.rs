//! Helpers for statements shared with the Java framework.

/// Replaces each `?` with `$1`, `$2`, ... in order: the numbering the PostgreSQL JDBC driver sends
/// for the Java framework's `?` placeholders. The shared statement files hold no `?` in literals.
#[must_use]
pub fn number_placeholders(sql: &str) -> String {
    let mut out = String::with_capacity(sql.len() + 8);
    let mut n = 0;
    for c in sql.chars() {
        if c == '?' {
            n += 1;
            out.push('$');
            out.push_str(&n.to_string());
        } else {
            out.push(c);
        }
    }
    out
}
