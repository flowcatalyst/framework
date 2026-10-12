//! Configuration through the application builder: sources and their order, problems, instance
//! sections (core contract sections 2 and 4).
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::fs;
use std::time::Duration;

use fw_core::config::{
    DriverSpec, Environment, InstanceSection, InstancesHandle, Key, KeySpec, KeyType, Optional,
    Present, Setting, SettingHandle,
};
use fw_core::{AppBuilder, AppName, Declarations, Module, ModuleId, Secret, StartupError};

const PORT: Setting<i32, Present> = Setting::new(Key::of("svc.port"), "port").default(8080);
const NAME: Setting<String, Present> = Setting::new(Key::of("svc.name"), "name").required();
const NOTE: Setting<String, Optional> = Setting::new(Key::of("svc.note"), "note");
const TIMEOUT: Setting<Duration, Present> =
    Setting::new(Key::of("svc.timeout"), "timeout").default(Duration::from_secs(1));
const PASSWORD: Setting<Secret, Optional> = Setting::new(Key::of("svc.password"), "password");
const TAGS: Setting<Vec<String>, Present> =
    Setting::new(Key::of("svc.tags"), "tags").default_list(&["a"]);

struct Svc;
struct SvcHandles {
    port: SettingHandle<i32, Present>,
    name: SettingHandle<String, Present>,
    note: SettingHandle<String, Optional>,
    timeout: SettingHandle<Duration, Present>,
    password: SettingHandle<Secret, Optional>,
    tags: SettingHandle<Vec<String>, Present>,
    stores: InstancesHandle,
}

impl Module for Svc {
    type Declared = SvcHandles;
    fn id(&self) -> ModuleId {
        ModuleId::of("svc")
    }
    fn declare(self, d: &mut Declarations<'_>) -> SvcHandles {
        SvcHandles {
            port: d.setting(PORT),
            name: d.setting(NAME),
            note: d.setting(NOTE),
            timeout: d.setting(TIMEOUT),
            password: d.setting(PASSWORD),
            tags: d.setting(TAGS),
            stores: d.instances(
                InstanceSection::new("stores", "Store")
                    .driver(DriverSpec::new("memory", []))
                    .driver(DriverSpec::new(
                        "remote",
                        [
                            KeySpec::new("url", KeyType::String).required(),
                            KeySpec::new("pool.max", KeyType::Integer),
                            KeySpec::new("password", KeyType::Secret),
                        ],
                    ))
                    .not_compiled("redis", "`redis` of fw-store"),
            ),
        }
    }
}

fn app(dir: &tempfile::TempDir, env: &[(&str, &str)]) -> (AppBuilder, SvcHandles) {
    let mut app = AppBuilder::new(AppName::of("cfg-test"))
        .config_dir(dir.path())
        .environment(Environment::from_pairs(env.iter().map(|(k, v)| (*k, *v))));
    let h = app.module(Svc);
    (app, h)
}

fn write(dir: &tempfile::TempDir, name: &str, text: &str) {
    fs::write(dir.path().join(name), text).unwrap();
}

fn lines(e: &StartupError, dir: &tempfile::TempDir) -> Vec<String> {
    let prefix = format!("{}/", dir.path().display());
    e.problems()
        .iter()
        .map(|p| p.to_string().replace(&prefix, "config/"))
        .collect()
}

#[tokio::test]
async fn sources_in_order_and_typed_reads() {
    let dir = tempfile::tempdir().unwrap();
    write(
        &dir,
        "application.yaml",
        "svc:\n  name: from-file\n  port: 1\n  note: n\n  tags: [x, y]\n",
    );
    write(&dir, "application-test.yaml", "svc:\n  port: 2\n");
    write(&dir, "secrets.local.yaml", "svc:\n  password: hunter2\n");
    let (app, h) = app(
        &dir,
        &[
            ("APP_ENV", "test"),
            ("SVC_PORT", "3"),
            ("SVC_TIMEOUT", "PT0.5S"),
        ],
    );
    let c = app.configure().await.unwrap();
    let cfg = c.config();
    assert_eq!(cfg.environment(), "test");
    assert_eq!(cfg.get(h.port), 3);
    assert_eq!(cfg.get(h.name), "from-file");
    assert_eq!(cfg.find(h.note).as_deref(), Some("n"));
    assert_eq!(cfg.get(h.timeout), Duration::from_millis(500));
    assert_eq!(cfg.find(h.password).unwrap().reveal(), "hunter2");
    assert_eq!(cfg.get(h.tags), vec!["x".to_owned(), "y".to_owned()]);
    assert!(cfg.instances(h.stores).is_empty());
}

#[tokio::test]
async fn secrets_local_is_ignored_outside_local_and_test() {
    let dir = tempfile::tempdir().unwrap();
    write(&dir, "secrets.local.yaml", "svc:\n  name: secret-file\n");
    let (app, _h) = app(&dir, &[("APP_ENV", "prod")]);
    let e = app.configure().await.unwrap_err();
    assert_eq!(
        lines(&e, &dir),
        vec![
            "module 'svc': key 'svc.name': missing required setting; set it in config/application.yaml or as environment variable SVC_NAME"
        ]
    );
}

#[tokio::test]
async fn every_problem_at_once_and_secrets_never_quoted() {
    let dir = tempfile::tempdir().unwrap();
    write(
        &dir,
        "application.yaml",
        "svc:\n  port: eighty\n  timeout: 5 minutes\n",
    );
    let (app, _h) = app(&dir, &[("SVC_PASSWORD", "")]);
    let e = app.configure().await.unwrap_err();
    assert_eq!(
        lines(&e, &dir),
        vec![
            "module 'svc': key 'svc.port' (from config/application.yaml): expected an integer, found 'eighty'",
            "module 'svc': key 'svc.name': missing required setting; set it in config/application.yaml or as environment variable SVC_NAME",
            "module 'svc': key 'svc.timeout' (from config/application.yaml): expected a duration such as 500ms, 30s, 5m, 2h, 1d or PT30S, found '5 minutes'",
        ]
    );
}

#[tokio::test]
async fn instance_sections_are_checked_at_configure() {
    let dir = tempfile::tempdir().unwrap();
    write(
        &dir,
        "application.yaml",
        "svc: { name: x }\nstores:\n  defualt: a\n  instances:\n    a: { driver: memory, extra: 1 }\n    b: { driver: remote, pool: { max: lots } }\n    c: { driver: redis }\n    d: { driver: nope }\n    e: {}\n",
    );
    let (app, _h) = app(&dir, &[]);
    let e = app.configure().await.unwrap_err();
    assert_eq!(
        lines(&e, &dir),
        vec![
            "module 'svc': key 'stores.defualt' (from config/application.yaml): unknown key in section 'stores'",
            "module 'svc': key 'stores.instances.a.extra' (from config/application.yaml): unknown key for driver 'memory'",
            "module 'svc': key 'stores.instances.b.url': missing required key; set it in a config file or as environment variable STORES_INSTANCES_B_URL",
            "module 'svc': key 'stores.instances.b.pool.max' (from config/application.yaml): expected an integer, found 'lots'",
            "module 'svc': key 'stores.instances.c.driver': unknown driver 'redis' for Store; it needs the Cargo feature `redis` of fw-store; registered: memory, remote",
            "module 'svc': key 'stores.instances.d.driver': unknown driver 'nope' for Store; registered: memory, remote",
            "module 'svc': key 'stores.instances.e.driver': missing required key; set it in a config file or as environment variable STORES_INSTANCES_E_DRIVER",
            "module 'svc': key 'stores.default': missing; several instances are configured (a, b, c, d, e) so one must be named as the default",
        ]
    );
}

#[tokio::test]
async fn instance_values_come_from_files_and_the_environment() {
    let dir = tempfile::tempdir().unwrap();
    write(
        &dir,
        "application.yaml",
        "svc: { name: x }\nstores:\n  instances:\n    main: { driver: remote, url: from-file }\n",
    );
    let (app, h) = app(
        &dir,
        &[
            ("STORES_INSTANCES_MAIN_URL", "from-env"),
            ("STORES_INSTANCES_MAIN_PASSWORD", "p"),
        ],
    );
    let c = app.configure().await.unwrap();
    let stores = c.config().instances(h.stores);
    assert_eq!(stores.default_name(), Some("main"));
    let main = stores.get("main").unwrap();
    assert_eq!(main.driver(), "remote");
    assert_eq!(main.string("url"), Some("from-env"));
    assert_eq!(main.secret("password").unwrap().reveal(), "p");
    assert_eq!(main.integer("pool.max"), None);
}

#[tokio::test]
async fn duplicate_declarations_modules_and_collisions() {
    const A: Setting<String, Optional> = Setting::new(Key::of("mail.host-name"), "a");
    const B: Setting<String, Optional> = Setting::new(Key::of("mail.host_name"), "b");
    struct Mail;
    impl Module for Mail {
        type Declared = ();
        fn id(&self) -> ModuleId {
            ModuleId::of("mail")
        }
        fn declare(self, d: &mut Declarations<'_>) {
            d.setting(A);
            d.setting(B);
            d.setting(A);
        }
    }
    struct MailAgain;
    impl Module for MailAgain {
        type Declared = ();
        fn id(&self) -> ModuleId {
            ModuleId::of("mail")
        }
        fn declare(self, _d: &mut Declarations<'_>) {}
    }
    let dir = tempfile::tempdir().unwrap();
    write(
        &dir,
        "application.yaml",
        "svc: { name: x }\nlocks:\n  instances:\n    a-b: { driver: memory }\n    a_b: { driver: memory }\n",
    );
    let (mut app, _h) = app(&dir, &[]);
    app.module(Mail);
    app.module(MailAgain);
    let e = app.configure().await.unwrap_err();
    assert_eq!(
        lines(&e, &dir),
        vec![
            "module 'mail': duplicate module id",
            "module 'mail': key 'mail.host-name': declared twice (first by module 'mail')",
            "module 'mail': key 'mail.host_name': reads environment variable MAIL_HOST_NAME, which key 'mail.host-name' (declared by module 'mail') reads too; keys may contain '_' and both '.' and '-' become '_', so rename one of them",
            "key 'locks.instances.a_b.driver': reads environment variable LOCKS_INSTANCES_A_B_DRIVER, which key 'locks.instances.a-b.driver' (an instance setting in config/application.yaml) reads too; keys may contain '_' and both '.' and '-' become '_', so rename one of them",
        ]
    );
}
