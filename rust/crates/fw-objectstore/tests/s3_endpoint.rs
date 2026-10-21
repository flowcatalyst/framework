//! The same contract against a real S3-compatible service. Runs only when `FW_S3_ENDPOINT` is set
//! (for example `http://localhost:4566` for LocalStack); it makes a bucket of its own and removes
//! everything it made. Credentials and region come from the SDK's chain (`AWS_ACCESS_KEY_ID`,
//! `AWS_SECRET_ACCESS_KEY`; the region is fixed to `us-east-1`).
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)] // helpers of a test crate, as tests (clippy.toml)

use std::env;
use std::sync::{Arc, Mutex};

use aws_sdk_s3::config::{BehaviorVersion, Builder, Region};
use fw_core::config::Environment;
use fw_core::{AppBuilder, AppName, Boot};
use fw_objectstore::testing::{ObjectStoreHarness, run_all};
use fw_objectstore::{ObjectStore, ObjectStores, S3Store};

struct Endpoint(Arc<dyn ObjectStore>);

impl ObjectStoreHarness for Endpoint {
    fn store(&self) -> Arc<dyn ObjectStore> {
        Arc::clone(&self.0)
    }
    fn limit(&self) -> usize {
        4096
    }
}

#[tokio::test]
async fn the_contract_against_a_real_s3_compatible_service() {
    let Ok(endpoint) = env::var("FW_S3_ENDPOINT") else {
        eprintln!("FW_S3_ENDPOINT is not set: skipped");
        return;
    };
    let bucket = format!("fw-objectstore-test-{:016x}", rand::random::<u64>());
    let sdk = aws_config::defaults(BehaviorVersion::latest())
        .region(Region::new("us-east-1"))
        .load()
        .await;
    let admin = aws_sdk_s3::Client::from_conf(
        Builder::from(&sdk)
            .endpoint_url(&endpoint)
            .force_path_style(true)
            .build(),
    );
    admin.create_bucket().bucket(&bucket).send().await.unwrap();

    let result = tokio::spawn(contract(endpoint.clone(), bucket.clone())).await;

    let listed = admin
        .list_objects_v2()
        .bucket(&bucket)
        .send()
        .await
        .unwrap();
    for o in listed.contents() {
        admin
            .delete_object()
            .bucket(&bucket)
            .key(o.key().unwrap())
            .send()
            .await
            .unwrap();
    }
    admin.delete_bucket().bucket(&bucket).send().await.unwrap();
    result.unwrap();
}

async fn contract(endpoint: String, bucket: String) {
    let dir = tempfile::tempdir().unwrap();
    let out = Arc::new(Mutex::new(None));
    let slot = Arc::clone(&out);
    let config = S3Store::new(bucket)
        .with_endpoint(endpoint)
        .with_region("us-east-1")
        .with_prefix("contract")
        .with_max_bytes(4096);
    let built = AppBuilder::new(AppName::of("s3-endpoint-test"))
        .config_dir(dir.path().join("none"))
        .environment(Environment::from_pairs::<&str, &str>([]))
        .configure()
        .await
        .unwrap()
        .build(async move |boot: &mut Boot| {
            *slot.lock().unwrap() = Some(
                ObjectStores::builder()
                    .store("s3", config)
                    .build(boot)
                    .await?,
            );
            Ok(())
        })
        .await
        .unwrap();
    let stores = out.lock().unwrap().take().unwrap();
    let harness = Endpoint(Arc::clone(stores.default_store().unwrap()));
    run_all(&harness).await;
    built.start().await.unwrap().shutdown().await;
}
