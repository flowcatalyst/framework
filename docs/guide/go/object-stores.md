<!-- types: Store, StoreFactory, Option, BuildOption, Declared, Stores, Harness -->
# Go: object stores

Package `objectstore` implements the [object stores contract](../../contracts/object-stores.md): named
byte values, three operations, the same on every driver. The memory and folder drivers are in
`objectstore`; the S3 driver is `objectstore/s3store` (AWS SDK for Go v2), compiled only when
imported. The [Java page](../object-stores.md) describes the same capability; the key rules are one
shared vector, `conformance/objectstore/keys.json`.

| Call | Does |
|---|---|
| `Put(ctx, key, content)` | Stores the value, replacing any earlier one completely. A reader sees the old value or the new one, never a mixture. Empty content is a value |
| `Get(ctx, key)` | `([]byte, bool, error)`: the bytes, and `false` with a nil error when there is no value |
| `Delete(ctx, key)` | Removes the value; removing one that is not there is not an error |

There is no listing, no metadata and no partial read. Stored and returned slices are copies.

## Declaring and building

Stores are code, not configuration (config-and-code contract section 3.4): there is no
`object-stores` section, and a leftover one fails start-up. Declare the module, then name the stores
where they are built, the same pattern as [locks](locks.md):

```go
d := objectstore.Declare(app) // in main
```

```go
all, err := objectstore.Build(ctx, b, d,
	objectstore.WithStore("archive", s3store.Bucket("acme-archive", s3store.Prefix("ingest/sets"))),
	objectstore.WithStore("scratch", objectstore.Folder("/var/tmp/ingest")),
	objectstore.DefaultStore("archive"))
archive, _ := all.Default()
err = archive.Put(ctx, "sets/2026-10-21/apply-1.json", data)
data, found, err := archive.Get(ctx, "sets/2026-10-21/apply-1.json")
```

Names match `[a-z][a-z0-9-]*` and are unique. One store is the default; with several,
`objectstore.DefaultStore` must name one. `all.Get(name)` returns a store or an error naming the known
stores; `all.Names()` lists them in the order given.

| Factory | Driver | Needs |
|---|---|---|
| `objectstore.Memory()` | `memory`: this process only | nothing; for tests |
| `objectstore.Folder(dir)` | `folder`: a file per value under an absolute directory, created if missing | `APP_ENV` `local` (the default) or `test`; **any other value fails start-up** naming the store, because a folder on one machine is not shared storage |
| `s3store.Bucket(bucket, ...)` | `s3`: AWS SDK for Go v2 | an existing bucket; credentials and region from the SDK's default chains |

`objectstore.MaxBytes(n)` sets the largest value a store accepts (at least 1; default 64 MiB). A bad
argument (a relative folder, a limit of 0) fails `Build` naming the store.

### S3 options

| Option | Meaning |
|---|---|
| `s3store.Prefix("ingest/sets")` | Everything is stored under that prefix, so several stores can share a bucket; itself a valid key |
| `s3store.Region("eu-west-1")` | Otherwise the SDK's chain decides (`AWS_REGION`, profile, role) |
| `s3store.Endpoint("http://localhost:4566")` | An S3-compatible service such as LocalStack or MinIO; path-style addressing, and request and response checksums only where required |
| `s3store.MaxBytes(n)` | Largest value accepted (default 64 MiB) |
| `s3store.VerifyAtStartup(false)` | By default start-up checks the bucket with `HeadBucket`, which needs `s3:ListBucket`; turn it off if the role lacks that |

A bucket that cannot be reached fails start-up naming the store and the bucket. The SDK's own retry
policy applies; the framework adds none.

## Keys, limits and errors

`objectstore.CheckKey` is the key rule: 1 to 512 characters, segments separated by a single `/`, each
starting with an ASCII letter or digit and holding letters, digits, `.`, `-` and `_`. So no leading
or trailing slash, no empty segment, no `.` or `..`. A key the rule refuses is an error wrapping
`objectstore.ErrInvalidArgument` from `Put`, `Get` and `Delete` on every driver. A value over the
limit is the same error from `Put`, and nothing is stored.

Any input/output or service failure wraps `objectstore.ErrStore` and names the store and key; so
does `Get` of a value found over the limit (changed behind the store's back). Do not use one key as
both a value and the start of another (`a` and `a/b`): the folder driver reports it as `ErrStore`.
Values are held in memory, so this is for small and medium objects.

The folder driver writes a hidden temporary file beside the target and renames it into place. The
folder is opened with `os.Root`, so a path that leaves it, through `..` or a symbolic link, is refused
as `ErrStore` (it is not read as "absent").

## Lifecycle

`Build` registers the closing of the stores at shutdown. `Stores.Close` closes every store that
implements `io.Closer` (the folder driver does), joins the failures, and does not stop at the first.
If any store fails to build, the stores built before it are closed and `Build` returns every failure
(each names its store); nothing is returned.

## The contract suite

`objectstoretest.Run(t, harness)` is the contract's test suite, one subtest per case (round trip,
missing key, replacement, binary-safe values, empty values, idempotent delete, nested keys, no
aliasing, refused keys, the size limit, eight concurrent writers). Every driver's tests call it:

```go
objectstoretest.Run(t, objectstoretest.Harness{Limit: 4096, New: func(t *testing.T) objectstore.Store {
	return store // empty or private to the test; the suite's keys are unique per run
}})
```

The memory, folder and S3 drivers run it. The S3 driver runs it against an in-process S3 (a fake built
on `net/http/httptest`, with the real SDK over HTTP) and, when `FW_S3_ENDPOINT` is set (for example
`http://localhost:4566`), against a real S3-compatible service: that test creates a bucket of its own
with a unique name and removes it with everything in it.
