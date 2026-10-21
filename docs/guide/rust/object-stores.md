<!-- types: ObjectStores, ObjectStoresBuilder, ObjectStore, ObjectStoreError, ObjectStoreFactory, MemoryStore, FolderStore, S3Store, MemoryObjectStore, FolderObjectStore, S3ObjectStore, ObjectStoreHarness -->
# Rust: object stores

`fw-objectstore` is the Rust counterpart of `framework-objectstore` and `framework-objectstore-s3`
([contract](../../contracts/object-stores.md), Java guide [Object stores](../object-stores.md)): named
byte values kept by key, with three operations and three drivers. `memory` and `folder` are always
built; `s3` is the Cargo feature `s3` (the AWS SDK for Rust is not compiled otherwise). Like locks,
stores are named in code; there is no configuration.

## Building the stores

`ObjectStores::builder()` takes a name and a driver per store; the build function builds them and
the registry closes them at shutdown:

```rust
let stores = ObjectStores::builder()
    .store("archive", S3Store::new("acme-archive").with_prefix("ingest/sets"))   // feature s3
    .store("scratch", FolderStore::new("/var/tmp/archive"))                       // local and test only
    .default_store("archive")
    .build(boot)
    .await?;
```

Names are `[a-z][a-z0-9-]*` and unique. With one store, that one is the default; with several, name
the default. The values of a driver (a relative folder, a bucket name S3 does not allow, a limit
below 1) are checked when the store is built. Every problem is reported together as a `StartupError`
naming the store (`object store 'archive': ...`); if any store fails, the ones built before it are
closed. `build` registers the registry's `close` on the application's shutdown, which calls
`ObjectStore::close` on every store (the default does nothing; the SDK client needs no closing).

## Using a store

`ObjectStore` has `put`, `get` and `delete` by key, all `async` and safe for concurrent use:

```rust
let archive = stores.default_store().expect("configured");
archive.put("sets/2026-10-21/apply-1.json", &bytes).await?;     // replaces any earlier value, atomically
let value: Option<Vec<u8>> = archive.get("sets/2026-10-21/apply-1.json").await?;   // None when absent
archive.delete("sets/2026-10-21/apply-1.json").await?;          // an absent value is not an error
```

A key is 1 to 512 characters, segments separated by a single `/`, each `[A-Za-z0-9][A-Za-z0-9._-]*`;
`check_key` is the rule, the same for every driver, and is held to the shared vector
`conformance/objectstore/keys.json`. A refused key, or content over the store's limit (default
64 MiB, `with_max_bytes`), is `ObjectStoreError::Argument` and nothing is stored. Any I/O or service
failure, or a stored value found over the limit, is `ObjectStoreError::Failed` naming the store and
key. The framework does not retry.

## Drivers

**memory** (`MemoryStore`). A map in this process, for tests.

**folder** (`FolderStore`). A file per value under an absolute directory, created if missing and
resolved to its real path. Allowed only when `APP_ENV` (default `local`) is `local` or `test`;
otherwise start-up fails naming the store. A `put` writes a hidden temporary file beside the target
and renames it into place; keys cannot start a segment with a dot, so a temporary file never
collides with a key and a key cannot leave the folder. A path reached through a symbolic link that
leads outside the folder is refused (`Failed`). A key that is a value cannot also be a folder.

**s3** (`S3Store`, feature `s3`). Credentials and region come from the SDK's default provider chains
(environment, shared profile, container or instance role). `with_prefix` places all keys under a
prefix (itself a valid key), `with_region` sets the region, `with_endpoint` selects an S3-compatible
service (LocalStack, MinIO): path-style addressing, and request and response checksums only when
required. At start-up the bucket is checked with `HeadBucket` unless `with_verify_at_startup(false)`;
a failure fails start-up naming the store and bucket.

## Differences from Java

`get` returns `Option<Vec<u8>>`; the Java exceptions are the two `ObjectStoreError` variants; a
driver's values are checked when the store is built rather than when the factory value is made;
stores are built with `ObjectStoresBuilder::build(boot)` instead of a module (nothing is
configured, so there is nothing for a module to declare, and a leftover `object-stores` key fails
as any unknown key does).

## Testing a driver

With the feature `testing`, `fw_objectstore::testing::suite!` expands to one test per contract case
(the Java `ObjectStoreContract`): implement `ObjectStoreHarness` (the store under test and its
limit) and invoke it. `testing::run_all` runs the same cases in one test, for a store that costs
something to set up and remove:

```rust
fw_objectstore::testing::suite!(harness(), #[tokio::test(flavor = "multi_thread")]);
```

The memory, folder and S3 drivers all pass it in this repository. The S3 driver runs against an
in-process S3 (a small HTTP server in `tests/s3.rs`, the real SDK over real HTTP); with
`FW_S3_ENDPOINT` set (for example `http://localhost:4566`) `tests/s3_endpoint.rs` creates a bucket
of its own, runs the suite and removes everything it made:

```sh
FW_S3_ENDPOINT=http://localhost:4566 AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
  cargo test -p fw-objectstore --features s3,testing --test s3_endpoint
```
