<!-- types: SecretsModule, SecretResolver, SecretRef, SecretResult, SecretStore, SecretStoreException, SecretNotFoundException, Secret, EnvironmentVariables -->
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

List `SecretsModule` (in `framework-core`) and configure one or more stores:

```yaml
secrets:
  default: files
  instances:
    files: { driver: file, directory: /run/secrets/clients, cache-ttl: 5m }
    env:   { driver: env, prefix: CLIENT_SECRET_ }
```

`app.get(SecretResolver.class)` is then available.

| Key (every driver) | Default | Meaning |
|---|---|---|
| `cache-ttl` | `5m` | how long a found value is reused; `0s` turns caching off; at most `24h` |
| `cache-max` | `10000` | most cached entries; the least recently used goes first |

### The `file` driver

A secret is the file `<directory>/<client>/<name>`:

```
/run/secrets/clients/acme/sftp-password
/run/secrets/clients/globex/sftp-password
```

- `directory` (required) must be an absolute path to an existing directory. `max-bytes` (default
  65 536) bounds a file; a larger one is an error.
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

- `prefix` (required) matches `[A-Z][A-Z0-9_]*_`, at least 4 characters, and may not start with
  `APP_`, `HTTP_`, `DATABASE_`, `REDIS_`, `LOCKS_`, `FLOWCATALYST_` or `SECRETS_`, so no reference can
  reach a framework setting.
- Only client ids of lowercase letters, digits and single hyphens can be encoded; any other client is
  **refused** (`SecretResult.Refused`), never approximated, because an approximation could map two
  clients to one variable. Use the `file` driver for such clients.
- The variables come from the environment the application was built with (in tests, the map given
  to `AppBuilder.environment`), read by exact name; they are never listed.

### The `platform` driver: not available

FlowCatalyst has no endpoint yet that returns a per-client secret to an application, so
`driver: platform` fails start-up with a message saying so. What the platform needs to offer is in
the contract, section 4.

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
