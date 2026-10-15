<!-- types: Ref, Result, Found, NotFound, Refused, Store, StoreFactory, StoreError, CachePolicy, Resolver, Declared, BuildOption, EnvFactory, FileFactory, PlatformFactory -->
# Go: per-client secrets

Package `secrets` implements the [secrets contract](../../contracts/secrets.md): a reference per
client, resolved for the client the application is acting for, from a store named in code. The Java
page is [Per-client secrets](../secrets.md); the threat model, the reference grammar, the env
encoding and the file rules are the same.

```go
password, err := resolver.Require(ctx, cx.Client(), secrets.MustParseRef("files:sftp-password"))
```

The client always comes from the caller, never from the reference. A `Ref` is `name` or
`store:name` (`ParseRef` and `NewRef` check the grammar: the name is `[a-z0-9]+(-[a-z0-9]+)*`, at
most 100 characters, so no separators, dots or traversal). Application-wide secrets (a database
password, the platform client secret) are `config.Secret` settings, not this.

## Building the resolver

Stores are code, not configuration (config-and-code contract section 3.8): there is no `secrets`
section, and a leftover one fails start-up naming `secrets.WithStore`. From `secrets_test.go`:

```go
d := secrets.Declare(app)                                  // in main: claims the secrets component
resolver, err := secrets.Build(ctx, b, d,                  // in the build function
	secrets.WithStore("files", secrets.File("/run/secrets/clients").WithMaxBytes(4096)),
	secrets.WithStore("env", secrets.Env("CLIENT_SECRET_").WithCache(secrets.NoCache())),
	secrets.DefaultStore("files"))
```

Names match `[a-z][a-z0-9-]*`; one store is the default, several need `DefaultStore`; `Build` reports
every problem at once as a `*fw.StartupError`. A deployment-specific directory or prefix is the
application's own setting, read where `Build` is called.

- `Env(prefix)`: the variable of (client, name) is `prefix + E(client) + "__" + E(name)`, where `E`
  upper-cases and turns `-` into `_` (client `acme-corp`, name `sftp-password`, prefix
  `CLIENT_SECRET_`: `CLIENT_SECRET_ACME_CORP__SFTP_PASSWORD`). The prefix matches `[A-Z][A-Z0-9_]*_`,
  has at least 4 characters and does not start with `APP_`, `HTTP_`, `DATABASE_`, `REDIS_`,
  `LOCKS_`, `FLOWCATALYST_` or `SECRETS_`. A client that is not lowercase letters, digits and single
  hyphens is `Refused`, never approximated. Variables are read by exact name from the environment the
  application was configured with (`fw.Boot.LookupEnv`); an absent or empty one is `NotFound`.
- `File(dir)`: `dir/<client>/<name>`, an absolute existing directory. The path is resolved with
  symbolic links followed (Kubernetes' `..data` layout works); the real path must lie under the
  directory's real path and end in exactly `<client>/<name>`, with the case the file system has
  (so `ACME` cannot read `acme`'s file on a case-insensitive file system, which Go's
  `EvalSymlinks` does not canonicalise: the store compares with the directory entries). At most
  `WithMaxBytes` (default 65 536, 1 to 1 048 576), strict UTF-8, one trailing newline removed, an
  empty file is `NotFound`. A world-readable file is warned about once, not refused.
- `Platform()` still fails start-up: the platform has no endpoint that resolves a secret.
- `WithCache(CachePolicy)` on a factory: `DefaultCache()` is 5 minutes and 10 000 entries (least
  recently used first), `NoCache()` turns it off, `NewCachePolicy(ttl, max)` checks 0 to 24 h and 1
  to 1 000 000. Only found values are cached; `Resolver.Evict` and `EvictAll` drop them.

## Results and errors

`Resolver.Resolve` returns `Found{Secret}`, `NotFound{}` or `Refused{Reason}`; `Require` returns the
`config.Secret` or an error wrapping `ErrNotFound` (for NotFound and Refused; the message names the
store, client and name, never a value). A store that cannot be read (I/O error, invalid UTF-8, file
too large) is a `*StoreError` whose message has neither a value nor content; `ErrNoStore` and
`ErrUnknownStore` are for a resolver without a usable store. A custom store implements `Store` and
a `StoreFactory`.

Differences from Java: errors are values; no `zeroisation` (as Java); the case rule above is
Go-specific; `fw.Boot.LookupEnv` replaces `EnvironmentVariables`.
