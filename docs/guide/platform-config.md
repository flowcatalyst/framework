<!-- types: PlatformSecrets, PlatformConfigAdminModule, PlatformConfigApi, PlatformConfigData, PlatformClient, SecretStores, SecretsModule, CachePolicy -->
# Platform config and secrets

The platform keeps configuration for each application: named values addressed by **section** and
**property**, global or per client, each **plain** or **secret**. A secret is encrypted at rest and
shown as `***` by every ordinary read; the one way to read it is the **reveal** call, which the
platform records. `framework-flowcatalyst` gives an application two things on top of it, both acting
as the application's own service account and both confined to its own platform application code:

1. the `platform` secrets driver, so `SecretResolver` resolves a per-client secret from the platform;
2. an admin section to list, add, change, delete and reveal that configuration.

The design and the wire are in the [contract](../contracts/platform-config.md); the shared page for the
section is the `platform-config` page of `@flowcatalyst/ui`'s `standardAdminPages` ([below](#the-page)).


## The `platform` secrets driver

```java
SecretsModule.builder()
    .store("platform", PlatformSecrets.store())            // section "secrets", cache 5 min
    .store("platform-ftp", PlatformSecrets.store().section("ftp").withCache(CachePolicy.of(Duration.ofMinutes(1), 1000)))
    .defaultStore("platform")
    .build()
```

List `FlowCatalystModule` as well; the driver takes the `PlatformClient` from it, and start-up fails
if `flowcatalyst.enabled` is false.

**What the service account needs.** Every platform-config call is a permission on the platform:
`platform:application-service:config:view` (list), `:update` (set, delete) and `:reveal` (read a secret), all carried by the platform's `platform:application-service`
role, which an application's provisioned service account holds. It reaches only the application(s) the
account is bound to and, for an account below anchor tier, only the values of clients it may act for.
After upgrading a platform that predates the permission, make sure the platform's roles are synced.

- A reference `name` for client `c` is the platform property `(application, section, name)` at client
  scope `c`. The application is the configured `flowcatalyst.application`; a reference cannot name
  another one.
- The client is the platform's client id (`clt_...`) **or the client's identifier** (`acme`), so an application
  that names clients by code needs no translation; the platform resolves both to the same scope. A name that is
  neither shape is `Refused` without a call, and a client the platform does not know is `NotFound`. There is **no fall-back** to the global value: a missing per-client value is `NotFound`.
- `404` with the platform's code `Config_NOT_FOUND` or `Client_NOT_FOUND` is `NotFound` (never cached); any other
  `404` (no code, another code, a proxy's page) is a failure, not a missing secret; `400 NOT_A_SECRET` is `Refused`; a `403`, a server error, a
  network error or an undecodable answer is a `SecretStoreException` that names the store, client and
  secret and never a value or a response body.
- Found values are cached by the framework's `CachePolicy` (default 5 minutes). Rotation is seen when
  the entry expires, after `SecretResolver.evict`, or when the admin page writes or deletes that secret; the platform's
  `updatedAt` is not used. The identifier and the stored id of a client share one cache entry once the driver has seen the
  platform answer for it; concurrent misses make one platform call; and if the platform fails, the last value is served for
  `staleIfError` (default 5 minutes) past its expiry, with a WARN (see [secrets](secrets.md)).
- `SecretStores.platform()` in core is a placeholder that fails start-up naming `PlatformSecrets.store()`
  (core cannot depend on the platform client). `SecretStores.cached` is public so a driver outside
  core can use the same cache.

`PlatformClient` has the four calls behind it, if you need them directly: `configProperties()`,
`setConfigProperty(section, property, clientId, request)`, `deleteConfigProperty(...)` and
`revealConfigSecret(...)` (each also takes an `onBehalfOf` text, sent as `X-On-Behalf-Of` so the platform's audit row
names the person), with the small records in `PlatformConfigData`. A secret's plaintext is
in `Revealed` and `SetRequest` only, and both print as `value=withheld`.

## The admin section

```java
// as in PlatformConfigAdminTest
b.module(AdminModule.of(APP)).module(PlatformConfigAdminModule.of(APP)).module(new FlowCatalystModule());
```

List it after `AdminModule` and `FlowCatalystModule`; `flowcatalyst.application` must equal the code
you pass. The section id is `platform-config`, the path `/admin/platform-config`.

| Operation id | Method and path | Permission |
|---|---|---|
| `listAdminPlatformConfig` | `GET /api/admin/platform-config` (`section?`, `clientId?`) | `{app}:admin:platform-config:view` |
| `setAdminPlatformConfig` | `PUT /api/admin/platform-config/{section}/{property}` (`clientId?`) | `{app}:admin:platform-config:manage` |
| `deleteAdminPlatformConfig` | `DELETE /api/admin/platform-config/{section}/{property}` (`clientId?`) | `manage` |
| `revealAdminPlatformConfig` | `POST /api/admin/platform-config/{section}/{property}/reveal` (`clientId?`) | `{app}:admin:platform-config:reveal` |

- A section name matches `[a-z][a-z0-9-]{0,63}`; a property name is exactly a secret name
  (`[a-z0-9]+(-[a-z0-9]+)*`, at most 100 characters, `SecretRef.isValidName`), so what the driver can read is what the page
  can manage; anything else is `400 INVALID_NAME` and no platform call. A value is required, not blank, at most 65 536 characters.
- A secret's `value` is `***` in every answer except the reveal, whatever the platform sent. The
  reveal answer carries `Cache-Control: no-store`. It is a `POST` so it is never prefetched or cached.
- Deleting a property that is not there is `204`. Revealing one that is not there is
  `404 CONFIG_NOT_FOUND`; revealing a plain one is `400 NOT_A_SECRET`.
- The framework writes an INFO log line for every write, delete and reveal naming the signed-in
  principal, section, property and client, never a value.
- `clientId` must be a client the signed-in user may act for, else `403 CLIENT_FORBIDDEN` and no platform call. A
  `Context` holds one client (the user's own, as an identifier), so the rule is: a user holding
  `{app}:admin:platform-config:all-clients` may name any client, anyone else only their own (by identifier, or by stored id:
  the platform's list names each client row's identifier beside its stored id, so the pair is known from the first listing). The list shows global rows and, without `all-clients`, only the user's own
  client's rows. The platform checks again and its reason reaches the person.
- Set, delete and reveal send the signed-in principal's id as `X-On-Behalf-Of`, so the platform's audit row names the
  person as well as the service account. A set or delete also drops the cached secret of that client and name from the
  application's `SecretResolver`, when there is one.

### Permissions and roles

| Permission | Grants |
|---|---|
| `{app}:admin:platform-config:view` | the section and its list |
| `{app}:admin:platform-config:manage` | add, change, delete |
| `{app}:admin:platform-config:reveal` | read a secret's plaintext |
| `{app}:admin:platform-config:all-clients` | name any client; without it a user acts for the global values and their own client only |

`reveal` is separate from `manage` on purpose: writing a secret does not let the caller read it back.
The suggested `{app}:admin` role (`{app}:admin:*:*`) holds all four (`view`, `manage`, `reveal`, `all-clients`). To keep plaintext away from
administrators in general, grant `view` and `manage` explicitly in that role instead of the wildcard
and give `{app}:admin:platform-config:reveal` to a narrower role. Likewise, a role for a client's own administrators
should list `view` and `manage` (and `reveal` if wanted) without `all-clients`, so they manage their client's values and
the global ones only; keep `all-clients` for the operators who look after every client. The platform records each reveal
against the service account, so the application's own log line is the record of who asked.

Per-client secrets appear in this section under the secrets driver's section (`secrets` unless you
chose another), scoped to a client: that is where they are managed.

### The page

`standardAdminPages(application)` in `@flowcatalyst/ui` mounts the section's page at `/admin/platform-config` (the
seventh standard page; pass the four `*AdminPlatformConfig` operations in `admin.api`). It reads the session
(`GET /auth/session`: its `permissions` and its `client`) to decide what to show; the server checks every call again.

- **Controls follow the permissions.** Add, edit and delete appear only with `manage`; Reveal only with `reveal`, on a
  secret, after a confirmation that says the reveal is recorded. The revealed value is dropped when its dialog closes.
- **Clients follow `all-clients`.** With it, the add form has a free client field (an id `clt_...` or an identifier such
  as `acme`, empty for the global value) and rows show the stored id. Without it, the page says so in a note naming the
  permission, the form offers only *Global* or *Your client, acme* (the session's client), every call for a client row
  sends that identifier (what the module accepts before it has learned the stored id), and rows of the user's client
  show `acme (clt_...)`. A session for no particular client (`_platform`) gets global values only and no client choice.
  The list shows the user's client's rows from the first load: the platform's list names each row's client identifier
  beside its stored id. A `403 CLIENT_FORBIDDEN`
  that still arrives is shown with the same explanation.
- **Secrets are write-only.** The value of a secret is a password field that is never pre-filled; changing a secret
  needs a new value, and `***` (the platform's mask) is refused as one in the form. The platform's own refusals,
  `400 SECRET_VALUE_MASKED` and `400 SECRET_VALUE_REQUIRED`, are shown on the value field.
- **Names** are checked as the module checks them: a section `[a-z][a-z0-9-]{0,63}`, a property a secret name
  (`SecretRef.isValidName`).
