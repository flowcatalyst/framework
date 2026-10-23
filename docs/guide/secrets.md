<!-- types: SecretsModule, SecretStores, SecretStoreFactory, CachePolicy, SecretResolver, SecretRef, SecretResult, SecretStore, SecretStoreException, SecretNotFoundException, Secret, EnvironmentVariables -->
# Per-client secrets

Some credentials belong to a **client**, not to the application: the SFTP password of a client's
server, an API key for a client's own system. Store a **reference** to it with the client's settings
and resolve it when you act for that client:

```java
Secret password = resolver.require(ctx.client(), SecretRef.parse("files:sftp-password"));
```

The client always comes from the caller (normally the `Context`), **never from the reference**, so a
reference cannot reach another client's secret even if someone can edit it. Application-wide secrets
(database password, the platform client secret) are not this: they are config settings of type
`secret` ([Config](config.md#secrets)).

The specification, with the threat model, is [contracts/secrets.md](../contracts/secrets.md).

## Setting it up

List `SecretsModule` (in `framework-core`) with one or more stores, named in code with the driver
each uses (there is no `secrets` configuration; an application can have many stores, so they are
code, [Config](config.md#what-configuration-holds)). From `SecretsTest`:

```java
SecretsModule.Builder two =
    SecretsModule.builder().store("files", SecretStores.file(secrets)).store("env", SecretStores.env("CLIENT_SECRET_"));
App app = start(two.defaultStore("files").build(), Map.of());
```

`app.get(SecretResolver.class)` is then available. Store names match `[a-z][a-z0-9-]*`; with one
store it is the default, with several `defaultStore(name)` must name one (`build()` throws
otherwise). Each store has a `CachePolicy` (`withCache`):

| `CachePolicy` | Meaning |
|---|---|
| `defaults()` | found values reused for 5 minutes, at most 10 000 entries (the least recently used goes first) |
| `of(ttl, maxEntries)` | ttl 0 to 24 h, entries 1 to 1 000 000 |
| `none()` | no cache: every lookup reads the store |

A `SecretStoreFactory` receives the lookup, so a directory or prefix that differs per deployment is
a setting of your own, read there (from `SecretsTest`):

```java
SecretStoreFactory fromSetting =
    (name, lookup) ->
        SecretStores.file(Path.of(lookup.get(io.flowcatalyst.framework.config.Config.class).get(DIRECTORY)))
            .create(name, lookup);
```

### The `file` driver

A secret is the file `<directory>/<client>/<name>`:

```
/run/secrets/clients/acme/sftp-password
/run/secrets/clients/globex/sftp-password
```

- `SecretStores.file(directory)`: the path must be absolute (checked when built) and a directory
  at start-up. `withMaxBytes(int)` (default 65 536, 1 B to 1 MiB) bounds a file; a larger one is an
  error.
- Content is read as UTF-8; **one** trailing newline (`\n` or `\r\n`) is removed, so
  `echo secret > file` works. An empty file is "not found".
- Symbolic links are followed (Kubernetes secret volumes use them), but the real file must be inside
  the directory and end in exactly `<client>/<name>`; anything else is "not found" with a WARN. So a
  link out of the directory, a link to another client's file, or a client id differing only by case
  on a case-insensitive file system never reads someone else's file.
- A client id starting with `.` is refused. A file readable by every local user is logged at WARN
  once.

### The `env` driver

A secret is the variable `prefix + CLIENT + "__" + NAME`, upper-cased with `-` turned into `_`:
client `acme-corp`, name `sftp-password`, prefix `CLIENT_SECRET_` →
`CLIENT_SECRET_ACME_CORP__SFTP_PASSWORD`.

- `SecretStores.env(prefix)`: the prefix (checked when built) matches `[A-Z][A-Z0-9_]*_`, at least 4 characters, and may not start with
  `APP_`, `HTTP_`, `DATABASE_`, `REDIS_`, `LOCKS_`, `FLOWCATALYST_` or `SECRETS_`, so no reference can
  reach a framework setting.
- Only client ids of lowercase letters, digits and single hyphens can be encoded; any other client is
  **refused** (`SecretResult.Refused`), never approximated, because an approximation could map two
  clients to one variable. Use the `file` driver for such clients.
- The variables come from the environment the application was built with (in tests, the map given
  to `AppBuilder.environment`), read by exact name; they are never listed.

### The `platform` driver

`PlatformSecrets.store()` in `framework-flowcatalyst` reads a client's secret from the platform's
configuration with the reveal call, as the application's service account. It is described on
[Platform config and secrets](platform-config.md). `SecretStores.platform()` in this module is only a
placeholder: it fails start-up with a message naming `PlatformSecrets.store()`.

## Using it

- `SecretRef.parse("name")` uses the default store; `SecretRef.parse("store:name")` a named one.
  Names match `[a-z0-9]+(-[a-z0-9]+)*` (at most 100 characters): no dots, slashes or underscores.
  A malformed reference is `IllegalArgumentException`.
- `resolver.resolve(client, ref)` returns `SecretResult.Found(secret)`, `NotFound` or
  `Refused(reason)`. `resolver.require(client, ref)` returns the `Secret` or throws
  `SecretNotFoundException` (for both not found and refused).
- A store that cannot be read (permissions, I/O, a file that is too large or not UTF-8) throws
  `SecretStoreException`.
- After rotating a secret, `resolver.evict(client, ref)` or `resolver.evictAll()` makes the next call
  read the store; otherwise the new value is seen within `cache-ttl`.

Values only ever appear inside a `Secret`: `toString()` prints `Secret[****]`, and no exception
message, result or log line contains a value or file content. Call `reveal()` only where you hand
the value to the library that needs it, and never log it.

To check a credential a caller presents against one you hold, use `secret.matches(candidate)`: it
compares SHA-256 digests in constant time, so timing reveals neither the content nor the length.

## Testing

Point a `file` store at a `@TempDir`, or give `AppBuilder.environment(...)` the variables for an
`env` store; `SecretResolver.of(Map.of("files", store), "files")` builds a resolver over stores you
make yourself. `framework-core`'s `SecretsTest` covers every abuse case listed above.
