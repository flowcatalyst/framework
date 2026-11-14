# Contract: object stores

Status: built 2026-10-21 (Java). Drivers: `memory`, `folder`, `s3`. Requested by the ingest control
plane, which had built its own `SetArchive` (put, get, delete by key; a folder and an S3 driver on
AWS SDK 2.59.1) and will switch to this.

An object store keeps named byte values. It follows the lock-store pattern (config-and-code
contract, section 3.4): stores are named in code, each with a driver chosen by its factory; there is
no configuration.

## 1. Operations

| Operation | Result |
|---|---|
| `put(key, content)` | Stores the value, replacing any earlier value completely and atomically for readers (old value or new, never a mixture). Empty content is a value |
| `get(key)` | `Optional<byte[]>`: empty when there is no value |
| `delete(key)` | Removes the value; removing an absent value is not an error |
| put with a content type | Stores the value with its content type (amended 2026-11-07) |
| get with the content type | The value and its content type as stored (amended 2026-11-07) |

*Amended 2026-11-07 (fulfil-go fixes, items B3 and the integration rulings; owner-approved):* the
content type is the one piece of metadata. Both frameworks apply the same rule: an empty one (Java
`null` or `""`, Go `""`) stores `application/octet-stream`; anything else must be at most 255
printable ASCII characters (spaces allowed, no line breaks) and a media type as Go's
`mime.ParseMediaType` reads one (a type, optionally `/subtype`, then `; name=value` parameters whose
value is a token or a quoted string, no parameter twice with different values), else it is refused
like a bad key (Java `IllegalArgumentException`, Go `ErrInvalidArgument`) and nothing is stored. It is
stored as given. A value stored without one reads back as `application/octet-stream`; a put replaces
the type with the bytes; delete removes both. The s3 driver keeps it as the object's `Content-Type`
(Java reads S3's own `binary/octet-stream` as `application/octet-stream`); the folder driver in a
hidden file `.<name>.content-type` beside the value (Go writes it after the value, Java before it, so
a reader racing a type change may briefly see the new bytes with the old type, or the old bytes with
the new type; a type file that holds no media type is a store failure, not a guess); the memory driver beside the bytes. Java: `put(key, bytes,
contentType)` and `read(key)` → `StoredObject(content, contentType)`, `StoredObject.DEFAULT_CONTENT_TYPE`,
`StoredObject.checkContentType`; Go: `PutObject(ctx, key, Object{Content, ContentType})`,
`GetObject(ctx, key) (Object, bool, error)`, `DefaultContentType`, `CheckContentType`. The Java tests
hold Go's verdicts on about fifty edge cases (`MediaTypeTable`).

Nothing else: no listing, other metadata, conditional writes or partial reads, so every driver can
honour the contract the same way. Returned and stored arrays are copies.

## 2. Keys

1 to 512 characters; segments separated by a single `/`; each segment `[A-Za-z0-9][A-Za-z0-9._-]*`.
So no leading or trailing slash, no empty segment, no segment starting with `.` (no `.` or `..`),
no space, backslash, control character or non-ASCII. A refused key is `IllegalArgumentException`
from `put`, `get` and `delete` on every driver. A key must not be both a value and the start of
another key; the folder driver reports it as `ObjectStoreException`.

The rules are also a shared vector, `conformance/objectstore/keys.json`: 47 candidate keys and whether each is
accepted (a long key is written as `repeat` and `length`: the text repeated and cut to that length). Java writes it;
Go and Rust compare their own checks against it.

## 3. Limits and errors

`maxBytes` per store, default 64 MiB (`withMaxBytes`). Over the limit: `put` throws
`IllegalArgumentException` and stores nothing; `get` of a value that is over the limit
(changed behind the store's back) throws `ObjectStoreException`. Any I/O or service failure is
`ObjectStoreException` naming the store and key. The framework does not retry.

## 4. Drivers

**memory.** A concurrent map in the JVM. For tests.

**folder.** A file per value under an absolute directory (created if missing; resolved to its real
path). Allowed only when `APP_ENV` (default `local`) is `local` or `test`; otherwise the store
fails to build and start-up fails naming it. A `put` writes a hidden temporary file beside the target
and moves it into place (atomic move where the file system has one). Keys cannot start a segment with
a dot, so temporary files never collide with keys and a key cannot leave the directory; a path
reached through a symbolic link that leads outside the directory is refused.

**s3** (`framework-objectstore-s3`, AWS SDK for Java 2.59.1, the synchronous client without
the Netty client). Credentials from the SDK's default provider chain; region from `withRegion` or the
chain. `withPrefix` places all keys under a prefix (itself a valid key). `withEndpoint` selects an
S3-compatible service: path-style addressing, and request and response checksums only when required
(not every compatible service accepts the trailer). At start-up the bucket is checked with
`HeadBucket` unless `withVerifyAtStartup(false)`; a failure fails start-up naming the store and
bucket. The SDK client is closed with the application.

## 5. Lifecycle

`ObjectStores` (the registry: `get(name)`, `defaultInstance()`, `names()`) is closed when the
application stops and closes every store that is `AutoCloseable`. If any store fails to build,
stores built before it are closed and start-up fails with the problems.

## 6. Tests

`ObjectStoreContract` is published in the `framework-objectstore` test-jar and extended by every
driver's test. The S3 driver is tested against an in-process S3 (the real SDK over HTTP) and, when
`FW_S3_ENDPOINT` is set, against a real S3-compatible service (verified against LocalStack on
2026-10-21).

*Amended 2026-11-07:* `ObjectStoreContract` also covers content types (section 1).

## 7. Not in this contract

Streaming reads and writes, listing, metadata other than the content type, conditional puts,
multipart uploads, pre-signed URLs and encryption settings.

## 8. Go

The Go framework implements this contract (guide: `guide/go/object-stores.md`, status G8): package
`objectstore` with the `memory` and `folder` drivers, `objectstore/s3store` on the AWS SDK for Go v2, and
`objectstore/objectstoretest`, the contract suite. It is the second runtime; the key rules are checked
against the shared vector `conformance/objectstore/keys.json`. Go-shaped differences: `get` returns
`(value, found, error)`, a refused key or an oversized value is an error wrapping
`ErrInvalidArgument`, and any I/O or service failure wraps `ErrStore`. The S3 test also ran against
LocalStack on 2026-10-21.

## 9. Rust

The Rust framework implements this contract (guide: `guide/rust/object-stores.md`, status RA-13): crate
`fw-objectstore` with the `memory` and `folder` drivers and the `s3` driver behind the Cargo feature
`s3` (AWS SDK for Rust), and `fw_objectstore::testing`, the contract suite. The key rules are checked
against the shared vector `conformance/objectstore/keys.json`. Rust-shaped differences: `get` returns
`Option<Vec<u8>>`, a refused key or an oversized value is `ObjectStoreError::Argument`, any I/O or service
failure is `ObjectStoreError::Failed`, stores are built with `ObjectStores::builder()...build(boot)`
(no module), and the S3 client has nothing to close. The S3 test also ran against LocalStack on
2026-10-21.
