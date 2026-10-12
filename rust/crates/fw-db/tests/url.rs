//! The accepted forms of a database instance's `url` (no server needed).
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // tests (clippy.toml)

use fw_db::parse_url;

struct Case {
    url: &'static str,
    host: &'static str,
    port: u16,
    database: &'static str,
    user: &'static str,
}

const OK: &[Case] = &[
    // Rust's own forms.
    Case {
        url: "postgres://localhost:5432/app",
        host: "localhost",
        port: 5432,
        database: "app",
        user: "",
    },
    Case {
        url: "postgresql://db.internal/app",
        host: "db.internal",
        port: 5432,
        database: "app",
        user: "",
    },
    Case {
        url: "postgres://u:p%40ss@h:6543/d?sslmode=disable",
        host: "h",
        port: 6543,
        database: "d",
        user: "u",
    },
    Case {
        url: "  postgres://h/d  ",
        host: "h",
        port: 5432,
        database: "d",
        user: "",
    },
    // A Java service's JDBC form, so its configuration can be taken over.
    Case {
        url: "jdbc:postgresql://localhost:5432/app",
        host: "localhost",
        port: 5432,
        database: "app",
        user: "",
    },
    Case {
        url: "JDBC:PostgreSQL://h:15432/d",
        host: "h",
        port: 15432,
        database: "d",
        user: "",
    },
    Case {
        url: "jdbc:postgresql://h/d?user=jane&password=x",
        host: "h",
        port: 5432,
        database: "d",
        user: "jane",
    },
    Case {
        url: "jdbc:postgresql://u:p@h/d?ssl=true&currentSchema=s",
        host: "h",
        port: 5432,
        database: "d",
        user: "u",
    },
];

#[test]
fn accepted_forms() {
    for c in OK {
        let o = parse_url(c.url).unwrap_or_else(|e| panic!("{}: {e}", c.url));
        assert_eq!(o.get_host(), c.host, "{}", c.url);
        assert_eq!(o.get_port(), c.port, "{}", c.url);
        assert_eq!(o.get_database(), Some(c.database), "{}", c.url);
        if !c.user.is_empty() {
            assert_eq!(o.get_username(), c.user, "{}", c.url);
        }
    }
}

#[test]
fn jdbc_and_native_forms_read_the_same() {
    let a = parse_url("jdbc:postgresql://h:7/d?sslmode=require").unwrap();
    let b = parse_url("postgres://h:7/d?sslmode=require").unwrap();
    assert_eq!(a.get_host(), b.get_host());
    assert_eq!(a.get_port(), b.get_port());
    assert_eq!(a.get_database(), b.get_database());
    assert_eq!(
        format!("{:?}", a.get_ssl_mode()),
        format!("{:?}", b.get_ssl_mode())
    );
}

#[test]
fn refused_forms() {
    for bad in [
        "",
        "mysql://h/d",
        "jdbc:mysql://h/d",
        "localhost:5432/app",
        "host=localhost port=5432 dbname=app",
        "jdbc:",
    ] {
        let e = parse_url(bad).unwrap_err();
        assert!(e.contains("postgres://"), "{bad}: {e}");
    }
    let e = parse_url("host=localhost dbname=app").unwrap_err();
    assert!(e.contains("key=value"), "{e}");
    assert!(parse_url("postgres://h:notaport/d").is_err());
}
