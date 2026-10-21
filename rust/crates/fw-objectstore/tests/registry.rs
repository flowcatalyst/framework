//! `ObjectStores` through the lifecycle: names, the default, the folder guard, failure and shutdown.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::fs;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use fw_core::config::Environment;
use fw_core::{AppBuilder, AppName, Boot, BoxFuture, Built, StartupError};
use fw_objectstore::{
    FolderStore, MemoryStore, ObjectStore, ObjectStoreError, ObjectStoreFactory, ObjectStores,
    ObjectStoresBuilder,
};

/// Runs the build phase with `APP_ENV` as given (`None`: unset) and returns the registry.
async fn build(
    app_env: Option<&str>,
    builder: ObjectStoresBuilder,
) -> Result<(ObjectStores, Built), StartupError> {
    let dir = tempfile::tempdir().unwrap();
    let environment = match app_env {
        Some(e) => Environment::from_pairs([("APP_ENV", e)]),
        None => Environment::from_pairs::<&str, &str>([]),
    };
    let out = Arc::new(Mutex::new(None));
    let slot = Arc::clone(&out);
    let built = AppBuilder::new(AppName::of("objectstore-test"))
        .config_dir(dir.path().join("none"))
        .environment(environment)
        .configure()
        .await?
        .build(async move |boot: &mut Boot| {
            *slot.lock().unwrap() = Some(builder.build(boot).await?);
            Ok(())
        })
        .await?;
    let stores = out.lock().unwrap().take().unwrap();
    Ok((stores, built))
}

fn message(e: &StartupError) -> String {
    e.to_string()
        + &e.problems()
            .iter()
            .map(|p| p.to_string())
            .collect::<String>()
}

/// A store that records that it was closed.
struct Closing(Arc<AtomicBool>);

impl ObjectStore for Closing {
    fn name(&self) -> &str {
        "c"
    }
    fn put<'a>(&'a self, _: &'a str, _: &'a [u8]) -> BoxFuture<'a, Result<(), ObjectStoreError>> {
        Box::pin(async { Ok(()) })
    }
    fn get<'a>(&'a self, _: &'a str) -> BoxFuture<'a, Result<Option<Vec<u8>>, ObjectStoreError>> {
        Box::pin(async { Ok(None) })
    }
    fn delete<'a>(&'a self, _: &'a str) -> BoxFuture<'a, Result<(), ObjectStoreError>> {
        Box::pin(async { Ok(()) })
    }
    fn close(&self) -> BoxFuture<'_, ()> {
        self.0.store(true, Ordering::SeqCst);
        Box::pin(async {})
    }
}

impl ObjectStoreFactory for Closing {
    fn create<'a>(
        &'a self,
        _: &'a str,
        _: &'a str,
    ) -> BoxFuture<'a, Result<Arc<dyn ObjectStore>, String>> {
        Box::pin(async { Ok(Arc::new(Self(Arc::clone(&self.0))) as Arc<dyn ObjectStore>) })
    }
}

struct Failing(&'static str);

impl ObjectStoreFactory for Failing {
    fn create<'a>(
        &'a self,
        _: &'a str,
        _: &'a str,
    ) -> BoxFuture<'a, Result<Arc<dyn ObjectStore>, String>> {
        Box::pin(async { Err(self.0.to_owned()) })
    }
}

#[tokio::test]
async fn stores_are_named_in_code_and_keep_separate_values() {
    let builder = ObjectStores::builder()
        .store("one", MemoryStore::new())
        .store("two", MemoryStore::new())
        .default_store("two");
    let (stores, _built) = build(None, builder).await.unwrap();
    assert_eq!(stores.names().collect::<Vec<_>>(), ["one", "two"]);
    stores.get("one").unwrap().put("k", &[1]).await.unwrap();
    assert_eq!(stores.get("two").unwrap().get("k").await.unwrap(), None);
    assert!(Arc::ptr_eq(
        stores.default_store().unwrap(),
        stores.get("two").unwrap()
    ));
    assert_eq!(stores.get("one").unwrap().name(), "one");
    assert!(stores.get("three").is_none());
}

#[tokio::test]
async fn a_single_store_is_the_default_and_several_need_one() {
    let (stores, _b) = build(
        None,
        ObjectStores::builder().store("only", MemoryStore::new()),
    )
    .await
    .unwrap();
    assert_eq!(stores.default_store().unwrap().name(), "only");

    let several = ObjectStores::builder()
        .store("a", MemoryStore::new())
        .store("b", MemoryStore::new());
    let e = build(None, several).await.unwrap_err();
    assert!(message(&e).contains("default_store"), "{e}");

    let e = build(
        None,
        ObjectStores::builder()
            .store("a", MemoryStore::new())
            .default_store("zzz"),
    )
    .await
    .unwrap_err();
    assert!(message(&e).contains("'zzz'"), "{e}");

    let e = build(
        None,
        ObjectStores::builder().store("Bad Name", MemoryStore::new()),
    )
    .await
    .unwrap_err();
    assert!(message(&e).contains("Bad Name"), "{e}");

    let twice = ObjectStores::builder()
        .store("a", MemoryStore::new())
        .store("a", MemoryStore::new());
    let e = build(None, twice).await.unwrap_err();
    assert!(message(&e).contains("already given"), "{e}");

    let (none, _b) = build(None, ObjectStores::builder()).await.unwrap();
    assert!(none.default_store().is_none());
    assert_eq!(none.names().count(), 0);
}

#[tokio::test]
async fn the_folder_driver_starts_when_local_or_test_and_refuses_elsewhere() {
    let dir = tempfile::tempdir().unwrap();
    let folder = dir.path().join("objects");
    for ok in [None, Some("local"), Some("test")] {
        let builder = ObjectStores::builder().store("f", FolderStore::new(&folder));
        let (stores, _b) = build(ok, builder).await.unwrap();
        stores.get("f").unwrap().put("a/b", &[7]).await.unwrap();
        assert_eq!(fs::read(folder.join("a/b")).unwrap(), [7]);
    }
    for env in ["prod", "staging", "np", "production"] {
        let builder = ObjectStores::builder().store("f", FolderStore::new(&folder));
        let e = build(Some(env), builder).await.unwrap_err();
        let m = message(&e);
        assert!(m.contains("object store 'f'"), "{m}");
        assert!(m.contains("local or test"), "{m}");
        assert!(m.contains(env), "{m}");
    }
}

#[tokio::test]
async fn relative_folders_and_bad_limits_fail_start_up() {
    let e = build(
        None,
        ObjectStores::builder().store("f", FolderStore::new("relative/dir")),
    )
    .await
    .unwrap_err();
    assert!(message(&e).contains("absolute"), "{e}");
    let e = build(
        None,
        ObjectStores::builder().store("m", MemoryStore::new().with_max_bytes(0)),
    )
    .await
    .unwrap_err();
    assert!(message(&e).contains("object store 'm'"), "{e}");
}

#[tokio::test]
async fn a_store_that_needs_closing_is_closed_when_the_application_stops() {
    let closed = Arc::new(AtomicBool::new(false));
    let builder = ObjectStores::builder().store("c", Closing(Arc::clone(&closed)));
    let (_stores, built) = build(None, builder).await.unwrap();
    let running = built.start().await.unwrap();
    assert!(!closed.load(Ordering::SeqCst));
    running.shutdown().await;
    assert!(closed.load(Ordering::SeqCst));
}

#[tokio::test]
async fn a_factory_failure_names_the_store_and_earlier_stores_are_closed() {
    let closed = Arc::new(AtomicBool::new(false));
    let builder = ObjectStores::builder()
        .store("good", Closing(Arc::clone(&closed)))
        .store("bad", Failing("no bucket"))
        .default_store("good");
    let e = build(None, builder).await.unwrap_err();
    let m = message(&e);
    assert!(
        m.contains("object store 'bad'") && m.contains("no bucket"),
        "{m}"
    );
    assert!(closed.load(Ordering::SeqCst));
}
