<!-- types: ObjectStore, ObjectStores, ObjectKeys, ObjectStoreException, ObjectStoresModule, ObjectStoreFactory, ObjectStoreFactories, S3ObjectStores, ObjectStoreContract -->
# Object stores

An object store keeps named byte values: an archive of what was applied, an uploaded file, a
report. It does three things, and every driver does them the same way:

| Call | Does |
|---|---|
| `put(key, bytes)` | Stores the value, replacing any earlier one completely. A reader sees the old value or the new one, never a mixture |
| `get(key)` | The bytes, or empty when there is no value (absent is not an error) |
| `delete(key)` | Removes the value; removing one that is not there is not an error |

There is no listing, no metadata and no partial read, so the same code works on a folder and on S3.

## Setting up

Stores are named **in code**, each with the driver its factory chooses; there is no `object-stores`
configuration (an application can have many stores, so they are code,
[Config](config.md#what-configuration-holds)). The same pattern as [locks](locks.md).

```java
App.builder()
    .module(
        ObjectStoresModule.builder()
            .store("archive", S3ObjectStores.s3("acme-archive").withPrefix("ingest/sets"))
            .store("scratch", ObjectStoreFactories.folder(Path.of("/tmp/ingest")))
            .defaultStore("archive")
            .build());

ObjectStore archive = app.get(ObjectStores.class).get("archive");
archive.put("sets/2026-10-21/apply-1.json", bytes);
Optional<byte[]> back = archive.get("sets/2026-10-21/apply-1.json");
```

| Factory | Driver | Needs |
|---|---|---|
| `ObjectStoreFactories.memory()` (`framework-objectstore`) | `memory`: this JVM only, lost on exit | nothing; for tests |
| `ObjectStoreFactories.folder(directory)` (`framework-objectstore`) | `folder`: a file per value under an absolute directory, created if missing | `APP_ENV` `local` or `test`; **any other value fails start-up** because a folder on one machine is not shared storage |
| `S3ObjectStores.s3(bucket)` (`framework-objectstore-s3`) | `s3`: AWS SDK for Java 2.50.2 | an existing bucket; credentials and region from the SDK's default chains |

Store names match `[a-z][a-z0-9-]*`. With one store it is the default; with several,
`defaultStore(name)` is required.

### S3 options

`s3(bucket)` returns a record; `with...` methods return copies.

| Method | Meaning |
|---|---|
| `withPrefix("ingest/sets")` | Everything is stored under that prefix, so several stores can share a bucket |
| `withRegion("eu-west-1")` | Otherwise the SDK's chain decides (`AWS_REGION`, profile, role) |
| `withEndpoint(uri)` | An S3-compatible service such as LocalStack or MinIO; path-style addressing is used and checksums only where required |
| `withMaxBytes(n)` | Largest value accepted (default 64 MiB) |
| `withVerifyAtStartup(false)` | By default start-up checks the bucket with `HeadBucket`, which needs `s3:ListBucket`; turn it off if the role lacks that |

A bucket that cannot be reached fails start-up naming the store.

## Keys and limits

A key is 1 to 512 characters: segments separated by a single `/`, each starting with a letter or
digit and holding letters, digits, `.`, `-` and `_`. So there is no leading or trailing slash, no
empty segment and no `.` or `..`, which is what keeps a key inside a folder store. A key the rules
refuse is `IllegalArgumentException` from every driver, so a key that works in a test works when
deployed.

Do not use one key as both a value and the start of another (`a` and `a/b`): a folder cannot hold
both, and the folder driver reports it as an `ObjectStoreException`.

A value over the store's limit (64 MiB unless `withMaxBytes` says otherwise) is
`IllegalArgumentException` on `put` and `ObjectStoreException` on `get` (a value that was made too
large by something else). Values are held in memory as `byte[]`, so this is for small and
medium objects, not for streaming large files.

## Failures

`ObjectStoreException` means the store could not be read or written (disk, network, permissions),
naming the store and the key. The framework never retries; the caller decides.

## Lifecycle

`ObjectStores` is closed with the application, which closes every store that is `AutoCloseable`
(the S3 driver closes its client). A store that fails to build fails start-up with a problem naming
it, and stores built before it are closed.

## Writing a driver, and testing one

A driver is an `ObjectStoreFactory` that builds an `ObjectStore`. `framework-objectstore` publishes
its contract suite as a test-jar: `ObjectStoreContract` (round trip, replacement, binary-safe
values, empty values, idempotent delete, refused keys, the size limit, concurrent writers). Extend
it and supply the store; the memory, folder and S3 drivers all do. The S3 test runs against an
in-process S3 so it needs no Docker; set `FW_S3_ENDPOINT` (for example `http://localhost:4566`) to
run the same contract against LocalStack.
