# Contract: per-client secrets (MA item 5)

Status: built 2026-10-10 for the `env` and `file` drivers; the `platform` driver built 2026-10-23
([platform-config contract](platform-config.md) section 3). Security-sensitive: read the threat model (section 2) before changing
anything here.

## 1. What it is for

An application keeps, per client, a **reference** to a credential it needs to act for that client
(an SFTP password, an API key of the client's own system), never the credential itself. At the
moment of use it asks the framework to resolve the reference **for the client it is acting for**:

```java
Secret password = resolver.require(ctx.client(), SecretRef.parse("files:sftp-password"));
```

Application-wide secrets (database password, the platform client secret) are not this: they are
config settings of type `secret` (core contract section 4). Secrets the application itself verifies
(an inbound token) use `Secret.matches(candidate)`, a constant-time comparison.

## 2. Threat model

Assets: each client's credentials. Their confidentiality between clients is the point of the design.

| Threat | Mitigation |
|---|---|
| **A reference is attacker-influenced** (a tenant edits its own settings, an injection writes a row) and points at another client's secret or at a framework secret (`DATABASE_INSTANCES_MAIN_PASSWORD`, `/etc/...`) | The **client comes from the caller (`ClientId`), never from the reference**, and every driver keys its lookup by (client, name): a reference cannot name a client. Names are `[a-z0-9]+(-[a-z0-9]+)*`, at most 100 characters: no separators, no dots, no traversal. The `env` driver reads only variables under its own prefix, which may not start with a framework component's prefix; the `file` driver reads only regular files under its directory, after resolving symbolic links. |
| Two clients map to one secret (lossy encoding, case-insensitive file systems) | `env`: the encoding is injective (section 4) and a client id it cannot represent is **refused**, not approximated. `file`: the resolved real path's last two elements must equal the client and name exactly, so `ACME` cannot read `acme`'s file on a case-insensitive file system. |
| A value leaks through logs, errors, health or serialisation | Values are only ever in `Secret` (redacted `toString`, not serialisable). No exception message, result or log line contains a value or file content; logs name the store, client and name at most, and only at DEBUG for successful lookups. |
| Timing reveals a secret the application compares | `Secret.matches(candidate)` compares SHA-256 digests with `MessageDigest.isEqual`: constant time, length not revealed. |
| A stale secret is used after rotation | The cache has a bounded TTL (default `5m`), can be evicted per (client, reference) or entirely, and never caches "not found". |
| Unbounded input from the store | Files over `max-bytes` (default 64 KiB) are refused; content must be valid UTF-8. |
| A secret file readable by every local user | Logged at WARN once per file (POSIX file systems); not refused, because container secret mounts are often mode 0644 inside a private mount namespace. |

Out of scope: a compromised host or process (anything that can read the process memory or the
store directly), the store's own access control, and wiping values from memory (`Secret` holds a
`String`, which Java cannot clear).

## 3. API

- `SecretRef` (record: `store` or null for the default instance, `name`). `SecretRef.parse(text)`:
  `name` or `store:name`; store names are instance names (`[a-z][a-z0-9-]*`). Anything else is
  `IllegalArgumentException` (the text is not echoed when it is longer than 200 characters).
- `SecretResolver` (provided by `SecretsModule`, module id `secrets`):
  - `resolve(ClientId, SecretRef)` returns `SecretResult`: `Found(Secret)`, `NotFound` or
    `Refused(reason)` (the store cannot hold a secret for this client or name, for example a client
    id the `env` driver cannot encode). A store that cannot be read (I/O error, permission denied,
    invalid UTF-8, file too large) throws `SecretStoreException` (unchecked; message without value
    or content). An unknown store name is `IllegalArgumentException`.
  - `require(ClientId, SecretRef)` returns the `Secret` or throws `SecretNotFoundException` (for
    `NotFound` and `Refused`; the message names the store, client and name).
  - `evict(ClientId, SecretRef)` and `evictAll()` drop cached values.
- `SecretStore` is the driver interface (`find(ClientId, name)`, `evict`, `evictAll`). Instances
  are configured like every capability (core contract section 2):

```yaml
secrets:
  default: files
  instances:
    files: { driver: file, directory: /run/secrets/clients, cache-ttl: 5m }
    env:   { driver: env, prefix: CLIENT_SECRET_ }
```

Every driver accepts `cache-ttl` (duration, default `5m`, zero disables caching, at most `24h`) and
`cache-max` (integer, default `10000`, 1 to 1 000 000; the least recently used entry goes first).
With no instance configured the resolver exists and every `resolve` throws
`IllegalStateException` (no store).

## 4. Drivers

*Amended 2026-10-14 ([config and code](config-and-code.md), section 3.8): stores are wired in code,
`SecretsModule.builder().store(name, SecretStores.env(prefix) | SecretStores.file(dir))`, each
with a `CachePolicy`; there is no `secrets` configuration. The YAML in section 3 is replaced by
that; the rules of each driver below are unchanged.*

### `env`

- `prefix` (required): `[A-Z][A-Z0-9_]*_`, at least 4 characters, and not starting with `APP_`,
  `HTTP_`, `DATABASE_`, `REDIS_`, `LOCKS_`, `FLOWCATALYST_` or `SECRETS_` (start-up fails).
- Variable: `prefix + E(client) + "__" + E(name)`, where `E` upper-cases and turns `-` into `_`.
  The client must match `[a-z0-9]+(-[a-z0-9]+)*` (else `Refused`). Neither part can then contain
  `__` or `_` at either end, so the variable splits back into exactly one (client, name): the
  encoding is injective. Example: client `acme-corp`, name `sftp-password`, prefix
  `CLIENT_SECRET_` → `CLIENT_SECRET_ACME_CORP__SFTP_PASSWORD`.
- Variables are read from the environment the application was built with (`AppBuilder.environment`
  or the process), by exact name through the core's `EnvironmentVariables`; the environment is never
  listed. A variable that is absent or empty is `NotFound`.

### `file`

- `directory` (required): an absolute path to an existing directory (start-up fails otherwise).
  `max-bytes` (default 65 536, 1 to 1 048 576).
- File: `directory/<client>/<name>`. The client must match `[A-Za-z0-9_-][A-Za-z0-9._-]*` (no
  leading dot, so never `.` or `..`; else `Refused`). The path is resolved to its real path (symbolic
  links followed, so Kubernetes' `..data` layout works); the real path must lie under the
  directory's real path and end in exactly `<client>/<name>`; otherwise `NotFound` (an escaping link
  is logged at WARN without its target). It must be a regular file of at most `max-bytes` bytes,
  read as strict UTF-8; one trailing `\n` or `\r\n` is removed. An empty file is `NotFound`.

### `platform`

Built 2026-10-23 as `PlatformSecrets.store()` in `framework-flowcatalyst` (core cannot depend on the platform
client); `SecretStores.platform()` stays as a placeholder whose start-up failure names it. A reference is the
platform configuration property `(application, section, name)` at the client's scope, read with the platform's
reveal call as the application's service account; the section defaults to `secrets`; no fall-back to the global
value. The design, the result table and the cache rules are in [platform-config](platform-config.md) section 3.

History: until 2026-10-23 this driver was blocked, because the platform had no endpoint that returned a per-client
secret to an application, confined to its own application, audited and encrypted at rest. The reveal endpoint
(platform commit `4d4c6c8c`) is that endpoint.

## 5. Platform findings (reported, not framework code)

1. No secret-resolution endpoint: **closed 2026-10-23** (the reveal endpoint, above).
2. **Confinement gap** (closed with the reveal endpoint: ordinary reads now return `***` for a secret, and only the
   application's own service account, or a caller holding `platform:admin:config:reveal`, may reveal). As found: `GET /api/config/{app}/{section}/{property}?clientId=` (and
   `GET /api/platform-config/{app}`) return SECRET-typed values **unmasked to any anchor-tier
   caller**, and an application's provisioned service account is anchor tier
   (`principal.NewService`), so one application's service account can read another application's
   secret config values, for any client, in plaintext (`internal/platform/platformconfig/api/api.go`;
   the values are stored in plaintext, `app_platform_configs.value`). The framework does not use
   these endpoints for secrets.

## Rulings pending owner

1. Whether service-scoped (not per-client) secrets should also go through `SecretResolver`
   (today: config settings of type `secret`).
2. Whether a world-readable secret file should be refused rather than warned about.
3. ~~The platform endpoint's shape and the confinement gap~~ done 2026-10-23.
