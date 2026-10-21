//! The folder driver against the contract suite, and what only a folder can do wrong.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::fs;
#[cfg(unix)]
use std::os::unix::fs as unix_fs;
use std::sync::Arc;

use fw_objectstore::testing::ObjectStoreHarness;
use fw_objectstore::{FolderObjectStore, ObjectStore, ObjectStoreError};

struct Folder {
    dir: tempfile::TempDir,
    store: Arc<FolderObjectStore>,
}

impl ObjectStoreHarness for Folder {
    fn store(&self) -> Arc<dyn ObjectStore> {
        self.store.clone()
    }
    fn limit(&self) -> usize {
        4096
    }
}

async fn harness() -> Folder {
    let dir = tempfile::tempdir().unwrap();
    let root = fs::canonicalize(dir.path()).unwrap();
    Folder {
        store: Arc::new(FolderObjectStore::new("t", root, 4096)),
        dir,
    }
}

fw_objectstore::testing::suite!(harness(), #[tokio::test(flavor = "multi_thread")]);

#[tokio::test]
async fn a_put_leaves_no_temporary_file_behind() {
    let h = harness().await;
    h.store().put("a/b.json", &[1]).await.unwrap();
    let names: Vec<_> = fs::read_dir(h.dir.path().join("a"))
        .unwrap()
        .map(|e| e.unwrap().file_name().into_string().unwrap())
        .collect();
    assert_eq!(names, ["b.json"]);
}

#[cfg(unix)]
#[tokio::test]
async fn a_symbolic_link_out_of_the_folder_is_not_followed() {
    let h = harness().await;
    let outside = tempfile::tempdir().unwrap();
    fs::write(outside.path().join("secret"), "nope").unwrap();
    unix_fs::symlink(outside.path(), h.dir.path().join("link")).unwrap();
    let s = h.store();
    for result in [
        s.put("link/secret", &[1]).await.map(|()| None),
        s.get("link/secret").await,
        s.delete("link/secret").await.map(|()| None),
    ] {
        assert!(matches!(result, Err(ObjectStoreError::Failed { .. })));
    }
    assert_eq!(fs::read(outside.path().join("secret")).unwrap(), b"nope");
}

#[tokio::test]
async fn a_value_replaced_by_a_file_too_large_on_disk_is_refused_on_read() {
    let h = harness().await;
    let s = h.store();
    s.put("big", &[1]).await.unwrap();
    fs::write(h.dir.path().join("big"), vec![0; 5000]).unwrap();
    let e = s.get("big").await.unwrap_err();
    assert!(matches!(e, ObjectStoreError::Failed { .. }));
    assert!(e.to_string().contains("over the limit"), "{e}");
}

#[tokio::test]
async fn a_key_used_as_a_value_cannot_also_be_a_folder() {
    let h = harness().await;
    let s = h.store();
    s.put("x", &[1]).await.unwrap();
    assert!(matches!(
        s.put("x/y", &[1]).await,
        Err(ObjectStoreError::Failed { .. })
    ));
    assert_eq!(s.get("x/y").await.unwrap(), None);
}
