# Contract: platform config in the framework (secrets driver and admin pages)

Status: design 2026-10-23, Java first. Amended 2026-10-25 (section 8, "Amendments of 2026-10-25"): per-client
confinement and `CLIENT_FORBIDDEN` restored, `X-On-Behalf-Of`, strict not-found codes, cache aliases / stale-on-error /
single flight, one name rule for properties and secrets. Java side built 2026-10-23 (section 8); the UI (section 5) and the real-platform run (section 6) are
not done. Depends on the platform's secret reveal endpoint (platform repo, commit
`4d4c6c8c`, published doc `70-platform-configuration.md`) and on the SDK releases of 2026-10-23.

## 1. What it is for

The platform stores configuration per application: named values addressed by **section** and
**property**, global or **per client**, each **plain** or **secret**. Secrets are encrypted at rest and
returned as `***` by every ordinary read; the one way to read a secret is the **reveal** call, which is
audited by the platform.

An application built on the framework gets two things from it:

1. **A `platform` secrets driver.** `SecretResolver.require(client, ref)` resolves a per-client
   secret from the platform, so an application keeps no credential store of its own
   (secrets contract section 4, which was blocked on the platform until now).
2. **Admin pages for the application's own platform config**: list, add, edit, delete, and reveal, with
   secrets write-only in the forms. The page sits in the admin area beside `config` (which shows the
   application's local settings; this one shows what the platform holds for the application).

Both act **as the application's own service account** (`PlatformClient`), and both are confined to the
application's own platform application code. The platform enforces that too (it refuses another
application's config to an application-scoped account), so the framework check is the first of two.

## 2. The platform calls

| Purpose | Call | Notes |
|---|---|---|
| List an application's properties | `GET /api/platform-config/{app}` | secrets come back `value: "***"` |
| Set a property | `PUT /api/config/{app}/{section}/{property}?clientId=` body `{value, valueType: "PLAIN"\|"SECRET", description?}` | response masks a secret |
| Delete a property | `DELETE /api/config/{app}/{section}/{property}?clientId=` | idempotent, 204 |
| Reveal a secret | `GET /api/config/{app}/{section}/{property}/secret?clientId=` | `{value, updatedAt, ...}`, `Cache-Control: no-store`; `404` no such property, `400 NOT_A_SECRET`, `403` not allowed |

**On behalf of (2026-10-25).** Set, delete and reveal send the optional header `X-On-Behalf-Of: <text>` naming the
signed-in person (the principal's id, never a token; printable ASCII only, trimmed, at most 256 characters, control and
non-ASCII characters removed, omitted when nothing is left). The platform honours it only from a service-account caller
and records it as `onBehalfOf` in the audit row of the set, delete and reveal; a longer value or one with control
characters is `400 INVALID_ON_BEHALF_OF` (the framework never sends one). The list and the driver's lookups send none
(the driver acts as the application).

**Not found.** The platform's codes on these calls are exactly `Config_NOT_FOUND` (no such property) and
`Client_NOT_FOUND` (unknown client id or identifier). An unknown application is `403` for an application-scoped account.
Any other `404` (a route that does not exist, a proxy's page) is **not** a missing property.

**Client confinement.** The platform confines an application's service account to the clients the application is linked
to (its explicit client ids, else the clients the application is enabled for): other clients are `403`, and the list
returns only the rows of linked clients plus global rows. Every answer that carries a `clientId` (list rows, set, reveal)
carries the **stored** id (`clt_...`) even when the request named the client by identifier.

**Secret values.** The platform refuses `***` or an empty value as a SECRET's value (`400 SECRET_VALUE_MASKED`,
`SECRET_VALUE_REQUIRED`); the framework's own body check already refuses a blank one before a call.

Path segments are percent-encoded. `clientId` is the client's id (`clt_...`) **or its identifier** (`acme`; the
platform resolves either to the id, stores the id, and answers `404` for an unknown client), absent for the
global value. **Reveal is always a permission** (amended 2026-10-23): the application's service account needs
`platform:application-service:config:reveal`, which the platform's `platform:application-service` role carries (an
account provisioned for the application has it; check it after a platform upgrade that predates the permission).
The platform confines it to the application(s) the account is bound to and, below anchor tier, to client-scoped
values of the clients it may act for. Nothing else is needed on the platform side; the platform's role sync
(`platform:application-service` is seeded) delivers the permission to existing accounts.

## 3. The `platform` secrets driver

Lives in **`framework-flowcatalyst`** (it needs `PlatformClient`; `framework-core` cannot depend on it).
`SecretStores.platform()` in core stays, and now fails at start-up with a message pointing at the new
class, because core cannot construct it.

```java
SecretsModule.builder()
    .store("platform", PlatformSecrets.store())            // section "secrets", cache 5 min
    .store("platform-ftp", PlatformSecrets.store().section("ftp").withCache(CachePolicy.of(Duration.ofMinutes(1), 1000)))
    .defaultStore("platform")
    .build()
```

**Mapping.** `SecretRef` name `n` for client `c` is the platform property `(application, section, n)` at
**client scope `c`**. The default section is `secrets`. The application is the configured
`flowcatalyst.application`; it is never taken from a reference. A reference cannot name a client or a
section, so the confinement rules of the secrets contract (section 2) hold unchanged: the client comes
from the caller.

**No fallback to the global value.** A missing per-client value is `NotFound`, not the global one.
(Application-wide secrets are config settings of type `secret`; that ruling is separate.)

**Results.**

| Platform answer | `SecretResult` / exception |
|---|---|
| `200` | `Found(Secret)` |
| `404` with code `Config_NOT_FOUND` or `Client_NOT_FOUND` | `NotFound` (never cached) |
| any other `404` (no code, another code, not JSON) | `SecretStoreException` (no value, no body); amended 2026-10-25, it used to be `NotFound` |
| `400 NOT_A_SECRET` | `Refused("the platform property is not a secret")` |
| `403` | `SecretStoreException` naming the application and "not authorised to reveal" (never a value) |
| network error, `5xx`, undecodable body | `SecretStoreException` (no value, no body) |
| client that is neither a platform client id nor a client identifier (separators, leading or trailing `-`) | `Refused` |
| client the platform does not know | `NotFound` (the platform's `404 Client_NOT_FOUND`) |

**Caching.** The framework's standard `CachePolicy` (default 5 min TTL, bounded, LRU, never caches
not-found). `evict` and `evictAll` work as for the other drivers. The platform's `updatedAt` is not
used for invalidation in this version (it would need a second call per lookup). Amended 2026-10-25, the cache
(`CachingSecretStore` in core, for every driver) also:

- **Keys on the canonical client.** `SecretStore.canonical(client)` (default: the client itself) is the form under which
  an entry is kept. The platform driver maps an identifier to the stored id it has learned from a set or reveal answer
  (`PlatformClient.canonicalClient`, a bounded in-process map), so `acme` and `clt_0ACME` share one entry once the pair is
  known, and `evict` under either name drops it (an entry cached before the pair was known is found by comparing
  canonical forms). `env` and `file` do not override it.
- **Single flight.** Concurrent misses for one key make one call to the store; the others wait for its result or share its
  exception. An `evict` during a call keeps that call's answer out of the cache (it may predate the change).
- **Stale on error.** `CachePolicy.staleIfError` (default 5 minutes, 0 to 24 hours, 0 turns it off; `withStaleIfError`):
  when the store throws `SecretStoreException` (platform down, `5xx`, network), the last found value is served for that
  long past its TTL, with a WARN naming store, client and secret (never the value). It never applies to `NotFound` or
  `Refused`: those drop the entry. While the store keeps failing every lookup after the TTL tries the store once before
  falling back (there is no negative cache). `CachePolicy.none()` has no cache and so no stale value.
- **Admin writes evict.** The admin page's set and delete drop the entry for that (client, name) from every secret store
  of the application's `SecretResolver` (`evictEverywhere`), when a secrets module is installed; otherwise there is nothing
  to do.

**Logging.** Store name, client and secret name at most, DEBUG on success; never a value, never a
response body. `Secret` keeps its redacted `toString`.

**When the platform is disabled** (`PlatformClient.isEnabled()` false) start-up of a store that is
configured fails with the same message as any other platform feature.

## 4. The admin section `platform-config`

Section id `platform-config`, path `/admin/platform-config`, title "Platform config", icon
`i-lucide-key-round`. Module: `PlatformConfigAdminModule` in `framework-flowcatalyst`, listed after
`AdminModule` and `FlowCatalystModule`, same shape and same boot check as `PlatformUsersAdminModule`
(`flowcatalyst.application` must equal the module's application).

**Permissions** (`{app}:admin:platform-config:...`, admin contract section 3):

| Permission | Grants |
|---|---|
| `view` | opens the section; list |
| `manage` | add, change, delete |
| `reveal` | read a secret's plaintext |
| `all-clients` | name any client (added 2026-10-25, see Validation); without it only the user's own client |

`reveal` is deliberately separate from `manage`: writing a secret does not let the caller read it back.
The suggested `{app}:admin` role (`{app}:admin:*:*`) holds all four; an application may hold `reveal`
or `all-clients` back from it.

**Operations** (typed operations, ids fixed so the UI's `AdminDataApi` can name them):

| Operation id | Method and path | Permission | Body / query | Answer |
|---|---|---|---|---|
| `listAdminPlatformConfig` | `GET /api/admin/platform-config` | `view` | query `section?`, `clientId?` | `{items: ConfigItem[]}` (not paged: an application has tens of properties) |
| `setAdminPlatformConfig` | `PUT /api/admin/platform-config/{section}/{property}` | `manage` | query `clientId?`; body `SetBody` | `ConfigItem` |
| `deleteAdminPlatformConfig` | `DELETE /api/admin/platform-config/{section}/{property}` | `manage` | query `clientId?` | `204` |
| `revealAdminPlatformConfig` | `POST /api/admin/platform-config/{section}/{property}/reveal` | `reveal` | query `clientId?` | `RevealedValue`, `Cache-Control: no-store` |

`ConfigItem` = `{section, property, clientId?, valueType: "PLAIN"|"SECRET", value, description?, updatedAt}`.
For a SECRET, `value` is the literal `"***"`; the framework never puts a secret's plaintext in any
answer except `revealAdminPlatformConfig`, whatever the platform sent.
`SetBody` = `{value, valueType, description?}`; `value` is required and not blank, at most 65 536 characters;
`description` at most 500. `RevealedValue` = `{section, property, clientId?, value, updatedAt}`.

Reveal is `POST` (not `GET`) so that it is never prefetched or cached and is covered by the same
state-changing protections as the writes: it is an audited event, not a read.

**Validation.** `section` matches `[a-z][a-z0-9-]{0,63}`. `property` is exactly a **secret name**:
`[a-z0-9]+(-[a-z0-9]+)*`, at most 100 characters (`SecretRef.isValidName`, the one definition in core), so every property
the `platform` driver can resolve can be managed here and every property managed here can be resolved (amended
2026-10-25; it used to be the section rule, which refused names the driver reads, such as `1abc` or one of 65 to 100
characters, and accepted `a-` style hyphen forms no secret reference allows). Anything else is `400` with code `INVALID_NAME` and no platform call.

`clientId` (when given, an id `clt_...` or an identifier) must be a client the signed-in user may act for, else
`403 CLIENT_FORBIDDEN` and **no platform call** (restored 2026-10-25). The framework's request `Context` carries one client
(the user's own, as an identifier) and no list of the user's clients, so the rule is: a user holding
`{app}:admin:platform-config:all-clients` may name any client; any other user only the context's client, given as that
identifier or as its stored id once this process has learned the pair (from a set or reveal answer), and never when the
context has no client (`_platform`). A client-scoped user therefore first acts by identifier; the page should send the
identifier of the user's client. The platform confines the application's account again (section 2).

The list applies the same rule to what it shows: with `all-clients` every row; without it the global rows and the rows of
the user's own client (so, until the pair is known, only global rows). The `clientId` filter takes an id or an identifier and
is normalised to the stored id before it is compared with the rows, which carry the stored id; an identifier this process has
not yet learned matches no row (the platform offers no cheap lookup; the limitation is documented, not hidden). Set and
reveal echo the stored `clientId` the platform answered with.

**Errors.** `404 CONFIG_NOT_FOUND` for a missing property (reveal and delete-of-missing is `204`);
`400 NOT_A_SECRET` from reveal of a plain property; `502 PLATFORM_UNAVAILABLE` and the other
`AdminPlatform` mappings as in the other platform-projected sections. A platform `403` is reported as
`502 PLATFORM_FORBIDDEN` with a message that the application's service account is not allowed (the
signed-in user's own permission was already checked and is not the cause).

**Audit.** The platform audits every reveal and write against the service account, and since 2026-10-25 records the
signed-in person too: the module sends the principal's id as `X-On-Behalf-Of` on set, delete and reveal (section 2). The
framework adds its own INFO log line for every reveal and every write: the signed-in principal, section, property,
client and the operation; never a value.

**Sections and properties the framework itself uses** are not hidden: `secrets` is the default section
of the `platform` secrets driver (section 3), so per-client secrets appear in this page under section
`secrets`, scoped to a client. That is the intended way to manage them.

## 5. The shared UI (`@flowcatalyst/ui`)

A seventh standard page, id `platform-config`, route `/admin/platform-config`, `meta.permission`
`{app}:admin:platform-config:view`, mounted by `standardAdminPages(application, ids?)` like the other
six (admin contract section 13). `AdminDataApi` gains the four optional operations of section 4 by
operation id; the wire views are structural copies of the server's schemas.

The page: a table of properties grouped or filterable by section and by scope (global or client);
secrets show a lock and `••••••••`; the add/edit dialog has the section, property, optional client,
a plain/secret toggle and the value (a password field for a secret, **never pre-filled**, a new value
is required to change a secret); delete asks for confirmation; reveal is a button that appears only when
the session holds `reveal`, asks for confirmation ("this is recorded"), shows the value in a dialog with
Copy and Close, and **discards the value when the dialog closes**. The edit/delete controls appear only
with `manage`. A page whose operation was not passed shows the standard missing-operation alert.

## 6. Tests the build must have

- **Driver:** found, not found (not cached), not-a-secret, `403`, `5xx` and network errors; the value is
  in no exception message, log line or `toString`; the request path and `clientId` for a given
  (client, name); percent-encoding; cache hit and eviction; start-up failure when the platform is
  disabled; the section option.
- **Admin module:** each operation's permission gate (`403` without it, nothing called); `reveal` is not
  implied by `manage`; a secret's plaintext appears only in the reveal answer (a fake platform that
  returns plaintext in a list or set answer is not echoed); `INVALID_NAME`; `CLIENT_FORBIDDEN`;
  `Cache-Control: no-store` on reveal; the framework's log line carries no value.
- **UI:** the page list and permissions include `platform-config`; the secret field is never
  pre-filled; reveal is hidden without `reveal`; the plaintext is cleared on close; the missing-operation
  alert; one page mounted on a fake client.
- **Against a real platform** (isolated `fcdev`, as in `go/g77-real-platform.md`): set a per-client
  secret in the admin page, resolve it with `SecretResolver`, rotate it, evict, resolve again.

## 7. Rulings taken (defaults applied; owner to confirm)

1. The secrets driver maps a name to a **per-client** property in section `secrets`, no global fallback.
2. `reveal` is its own permission, not part of `manage`, and a `POST`.
3. The admin list is not paged and shows global and client rows together.
4. The framework logs every reveal and write with the signed-in principal.
5. Java only for now; Go and Rust get a hand-off after the Java side is confirmed against a real platform.

## 8. As built

Java side, 2026-10-23. Files: `PlatformConfigData`, `PlatformConfigApi`, `PlatformConfigAdminModule`, `PlatformSecrets`
(all `framework-flowcatalyst`), four methods on `PlatformClient`, three readers in `PlatformAdminParsing`; in core, a
public `SecretStores.cached(store, cache, lookup)` and a new `SecretsModule.BLOCKED` text. Tests: `PlatformSecretsTest` (13),
`PlatformConfigAdminTest` (16), the core placeholder test; section 6's driver and admin lists are covered, the UI and the
real-platform run are not.

Differences from the text above, and why:

1. **`clientId` and the signed-in user's clients** (rewritten 2026-10-25). `Context` carries one client, not the user's
   list (the auth module's `Principal.clients()` is not reachable from a handler), so the rule of section 4 applies:
   `all-clients` permission, else the context's own client. `CLIENT_FORBIDDEN` exists again, with no platform call. The
   platform's own refusal (`REJECTED`, for example `403 CLIENT_NOT_AVAILABLE`) still arrives as before, as the second check.
   The users section still passes `clientId` on without such a check; only this section confines it.
2. **Error codes are those of `AdminPlatform`**: `502 PLATFORM_ERROR` (not `PLATFORM_UNAVAILABLE`), and a platform `403`
   without a reason of its own is `502 PLATFORM_ERROR` with the existing "service account refused" message (not
   `PLATFORM_FORBIDDEN`), so this section reports as the other platform-projected sections do. `503
   PLATFORM_NOT_CONFIGURED` when the platform is disabled.
3. **The set answer** is built from the path, the query and the platform's timestamp; its `valueType` is the one asked for,
   and a secret is `***` whatever the platform sent. In a list, any `valueType` other than `PLAIN` is treated as a secret.
4. **List filters**: `section` is validated (`INVALID_NAME`); `clientId` keeps only that client's rows (global rows are
   not included). The platform returns the whole list, the filter is applied here.
5. **The driver takes a client id or a client identifier** (amended 2026-10-23, after the platform started resolving
   both): a `ClientId` that matches neither `clt_[A-Za-z0-9]{1,60}` nor a slug is `Refused` without a call; an
   application that names its clients by code (`acme`, `bevco`) passes the code through unchanged. An empty `value` in a `200` is an undecodable answer.
6. **Core changes**: `SecretStores.cached` became public so the driver outside core can use the cache; the placeholder
   `SecretStores.platform()` still exists and fails start-up naming `PlatformSecrets.store()`.
7. The failure cause of a driver error is dropped (a decoder's message can quote what it read); the message is the
   `PlatformException`'s, which holds the status and the platform's code only.

### Amendments of 2026-10-25

Owner-approved fixes, with the platform side (section 2: `X-On-Behalf-Of`, strict not-found codes, linked-client
confinement, stored `clientId` in answers, `***` refused as a secret value) implemented in parallel.

- **F1 Client confinement and on-behalf-of** (sections 2 and 4). `CLIENT_FORBIDDEN`, the `all-clients` permission,
  list filtering by own client, `PlatformClient.setConfigProperty` / `deleteConfigProperty` / `revealConfigSecret` gained
  overloads taking `onBehalfOf` (the old signatures delegate with null), `PlatformHttp.call` takes extra headers, and the
  list filter and the answers use the stored client id. `PlatformClient.canonicalClient(ref)` is the identifier-to-id map
  (learned from set and reveal answers; up to 10 000 pairs). Decision: the narrowest safe rule, because the framework has no
  per-user client list (see item 1 above).
- **F2 Strict not-found** (section 3). Only `Config_NOT_FOUND` and `Client_NOT_FOUND` are `NotFound`; any other `404` is a
  `SecretStoreException`. The admin reveal's `CONFIG_NOT_FOUND` mapping still takes any `404` (it reports to a person, not
  to a cache); tighten it if a proxy `404` there proves confusing.
- **F3 Cache** (section 3, Caching). Canonical client keys, single flight, stale-on-error, eviction by admin writes; core
  API: `SecretStore.canonical` (default method), `CachePolicy.staleIfError` (the two-argument constructor and `of` keep
  working, with the 5-minute default), `SecretResolver.evictEverywhere`.
- **F4 One name rule** (section 4, Validation). `SecretRef.isValidName` and `SecretRef.NAME_MAX` are public; the admin
  property rule is that definition.
- Tests: `CachingSecretStoreTest` (14, core), `PlatformSecretsTest` (18), `PlatformConfigAdminTest` (25).
