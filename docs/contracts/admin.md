# Contract: the application admin skeleton (M4d)

Status: written 2026-10-16 with the build of M4d. Language-neutral: the wire (section 4), the
permission model (section 3) and the UI contract (section 6) are the same for the Java and the Go
framework (Go milestone G6 builds the server half against the vector of section 8). Java
specifics are marked. Owner questions are collected in section 9; the build proceeds on the
recommended defaults, each recorded there.

Builds on: [core](core.md) (`Context.permissions`, section 3), [http](http.md) (the error
envelope), [openapi](openapi.md) (typed operations, `x-fw-permission`), [sessions and
login](sessions-and-login.md) (the session's `permissions`, 4.5 client rules, section 7.3 the
permission mapping), [flowcatalyst](flowcatalyst.md) (definitions sync; section 5 platform work).

## 1. What an application admin is

The **platform console** (the FlowCatalyst platform's own UI, `/ui` in `flowcatalyst-go`)
administers the platform: every client, application, role, subscription and connection. It exists
and is not re-implemented.

The **application admin** is the framework's skeleton for administering **one application's own
slice**, inside that application, behind that application's sign-in, in its own SPA:

| Section (M4e) | What it shows | Source |
|---|---|---|
| users and invitations | the users who hold this application's roles; invite, resend, revoke (built, section 12) | the platform, as the application's service account |
| event types | this application's event types and their payload specification versions (built, section 11) | the platform |
| roles | this application's roles and their permissions, as projected from the platform (built, section 11) | the platform |
| application config | the application's config and secret *status* (never a secret value; built, section 10) | the application itself |
| scoped events and dispatch jobs | the application's own events and delivery attempts (built, section 12) | the platform (the scoped reads of platform change F20) |
| health | readiness and the checks | the application itself (`/api/admin/about`, part of this skeleton) |

M4d builds **none** of the data sections. It builds what every one of them needs: the registry in
which sections declare themselves, the permission gate, the two data-free operations, and the
shell that turns the registry and the session into navigation. M4e fills sections in, one slice
at a time, and each is a pure addition (a section, its operations, its page).

Out of scope: platform-wide administration (the console), end-user features of the application,
and any bypass of the platform's own authorisation (an admin page never grants what the platform
refuses; the application's service account is the caller, and the signed-in user's permissions are
enforced by the framework before it is used, flowcatalyst.md section 5, item 2).

## 2. The registry

A **section** is a value:

| Field | Meaning |
|---|---|
| `id` | `[a-z][a-z0-9-]*`, unique; the UI page registry uses the same id |
| `title` | the navigation label |
| `path` | the SPA route of the section, an absolute path under `/admin` (`/admin/users`); not a server route |
| `permission` | the permission that shows the section and is required by **every** operation of the section (section 3); four `:`-separated segments |
| `icon` | optional Lucide icon name for the navigation |
| `description` | optional one sentence |
| `operations` | the typed operations the section serves (server side only, never on the wire) |

Registration is in `register` (like `OpenApi.declare`, so the `openapi` command sees it without
configuration): Java `Admin.section(app, section)`. Registering also declares the section's
operations in the OpenAPI document. Handlers are bound in `boot` with `Routes.bind`, as for any
operation.

**Start-up rules** (a violation fails start-up, and the `openapi` command, before anything
listens; Java: `IllegalArgumentException` from the section's constructor in `register`, and a
`StartupException` listing every problem from the module's `boot` check):

1. every operation of a section has a required permission (an admin operation that anyone signed in
   can call is a defect, not a default);
2. a section's `permission` and every operation's permission is a four-segment permission of the
   application (`{app}:admin:{section}:{action}` by convention; the application segment must equal
   the admin module's application code);
3. section ids and section paths are unique, and every path is under `/admin`;
4. every operation's pattern is under `/api/admin/` (so the API prefix `/api` is already derived
   and a SPA route `/admin` never collides, static-assets contract section 5).

The framework's own two operations are registered through the same registry (section 4), so the
rules cover them.

## 3. The permission model

Application permissions come from **M4c's permission mapping** (sessions contract 7.3): code that
maps the signed-in principal's roles to permission strings; `Context.permissions` holds the result;
`Context.hasPermission` applies the platform's wildcard rule (same number of segments, `*` in a held
segment matches).

Naming (recommended default, decision 1): `{app}:admin:{section}:{action}`, four segments, which
is the shape the platform's definition sync accepts (flowcatalyst.md section 2).

| Permission | Grants |
|---|---|
| `{app}:admin:console:view` | the admin area at all: the navigation and the about operation. Held by every admin role |
| `{app}:admin:{section}:view` | reading a section (M4e: `event-types`, `roles`, `config`, `users`, `activity`) |
| `{app}:admin:users:invite`, `:resend`, `:revoke` | inviting a user, sending an invitation again, revoking one (section 12) |
| `{app}:admin:{section}:manage` | changing a section |
| `{app}:admin:platform-config:view`, `:manage`, `:reveal`, `:all-clients` | the platform-config section: listing, writing, reading a secret's plaintext, and naming any client rather than only the user's own (section 14) |

One suggested role, `{app}:admin`, grants `{app}:admin:*:*`. The application maps its platform role
to it in its permission mapping (Java: `PermissionMapper.byRole(Map.of("orders-demo:admin",
Set.of("orders-demo:admin:*:*")))`) and declares the role and permissions to the platform with
`Definitions` (the registry exposes `Admin.permissions(..)` for that). Permissions reach the
platform only through roles that grant them.

**Three layers, in this order** (the server is the only authority):

1. **The operation gate.** Every operation checks its permission before parsing anything (OpenAPI
   contract section 3): a caller without it gets `403` and the standard error envelope with code
   `FORBIDDEN`:

   ```json
   {"error": {"code": "FORBIDDEN", "message": "this needs the permission orders-demo:admin:console:view"}}
   ```

   The exact envelope is the http contract's; the message names the permission (it is not a
   secret: the session already lists the caller's permissions). An unauthenticated caller gets
   `401 UNAUTHENTICATED` first (sessions contract 7.4).
2. **The navigation filter.** `GET /api/admin/navigation` returns only the sections whose
   `permission` the caller holds. A section is never advertised to someone who cannot call it.
3. **The UI.** The shell shows and hides from the session's `permissions` and the route guard
   refuses a route whose `meta.permission` the session lacks (sessions contract 4.5, rule 5). This
   is courtesy: layers 1 and 2 decide.

## 4. The skeleton's operations

Both are typed operations (tag `admin`), security `session` and `bearer`, under the application's
admin application code. Components are fixed here and recorded in `conformance/openapi/` once the
Go side builds them; `@Nullable`/optional means "may be absent" (OpenAPI contract section 6).

### 4.1 `GET /api/admin/navigation` (`getAdminNavigation`)

Permission: `{app}:admin:console:view`. `200`:

```json
{"sections": [
  {"id": "about", "title": "About", "path": "/admin/about", "permission": "orders-demo:admin:console:view", "icon": "i-lucide-info"}
]}
```

`AdminNavigation`: `sections` (array of `AdminSectionView`, always present, in registration order
with the framework's `about` first). `AdminSectionView`: `id`, `title`, `path`, `permission`
(strings), `icon`, `description` (strings, may be absent). Only sections the caller may use
(section 3, layer 2). The response is never cached (`Cache-Control: no-store` is the framework's
default for typed operations' authenticated answers; the contract does not add a header).

### 4.2 `GET /api/admin/about` (`getAdminAbout`)

Permission: `{app}:admin:console:view`. `200`:

```json
{"application": "orders-demo", "framework": "0.1.0-SNAPSHOT", "environment": "test",
 "startedAt": "2026-10-16T08:00:00Z", "ready": true,
 "checks": [{"name": "database:main", "status": "UP", "critical": true}]}
```

`AdminAbout`: `application` (string), `framework` (string, may be absent: the framework version when
known), `environment` (string: `APP_ENV`), `startedAt` (`date-time`), `ready` (boolean: the same
decision `/health/ready` makes), `checks` (array of `AdminCheck`: `name`, `status` (enum `UP`,
`DOWN`, `TIMED_OUT`), `critical` (boolean)). A failure's *message* is deliberately not on the wire:
it can carry connection strings and host names; the logs have it.

### 4.3 Errors

Both document `401` and `403`. A section's operations document their own.

## 5. Where it lives (Java)

A **new module `framework-admin`**, depending on `framework-core` and `framework-http` only (no
database, no write path, no auth module: the gate uses `Context`, which any `ContextResolver`
fills). `framework-http` stays on core only (the Maven Enforcer rule is unchanged and `framework-admin`
is covered by the same rule). Not in `framework-auth`: an application with its own resolver gets
the admin area too. Classes: `AdminSection`, `Admin` (registration, lookup, validation, permission
export), `AdminModule` (binds the two operations, validates at boot), `AdminApi` (the wire types
and operations).

Go: a package `admin` of the same shape (G6); the vector of section 8 is the check.

## 6. The shared UI (`@flowcatalyst/ui`)

Backend-agnostic: the package holds no generated client. The application loads the navigation
with its own generated `getAdminNavigation` and passes the result in.

- **`AdminPage`** (the page-kit contract M4e pages fill): `{ id, title, icon, permission, path
  (absolute, `/admin` or under it, equal to the server section's `path`), component: () => Promise<Component>, children? }`. A page is built from the kit's
  patterns: `FwListPage` + `FwRecordDrawer` (+ `useDrawerRoute`) + `FwFormDialog`
  (+ `useDirtyForm`, `useConfirm`, `useListState`); every page's route carries `meta.permission`
  and the page's `permission` must equal its server section's.
- **`adminRoutes(pages)`** builds the vue-router records under `/admin` (the overview at `/admin`
  included); a page without a permission is refused when the routes are built. The routes carry
  `meta.permission`, `meta.title` and `meta.breadcrumb`, so `installSessionGuard` (api-runtime)
  enforces the permission and sends the user to the application's not-permitted page: the **route
  guard** is the existing one, fed by the registry.
- **`adminNavigation(pages, sections, can)`**: the navigation items. A page is shown when the
  server listed its section (`sections`; the server already filtered by permission, layer 2) and
  `can(page.permission)` (layer 3). A server section without a page is not shown (reported by
  `console.warn` once, in development); a page without a server section is not shown either.
- **`useAdminNavigation(load)`**: loads once, exposes `sections`, `loading`, `failed` (a failed load
  shows the shell with an inline error and an empty navigation, never a blank page).
- **`FwAdminShell`**: `FwAppShell` with the admin navigation, a **"Back to the application"** link,
  **breadcrumbs** (`Admin` > section title > record title, from `meta.breadcrumb` of the matched
  routes, the last one not a link, `aria-current="page"`), and the user menu and colour mode of
  `FwAppShell`. The standalone pages of U4 (`FwSignedOutPage`, `FwSignInFailedPage`,
  `FwUnavailablePage`) stay outside the shell and are reused as they are; the not-permitted page is
  the application's.
- Behaviour rows (the gallery checklist, `behaviours.ts`): navigation limited to permitted
  sections; a section the server did not list is absent; a route guard refusal; breadcrumbs;
  the failed-load state.

## 7. Tests the build must have

Java, in `framework-admin`: section validation (each start-up rule above); the registry in
registration order; the gate (a caller without the permission gets `403 FORBIDDEN` with the envelope
shape, with it `200`; wildcard grants; a caller with a *different* section's permission is refused);
the navigation filter; about (checks, readiness); the OpenAPI document contains both operations with
`x-fw-permission`; the vector (section 8). **Mutation check:** a build of the operation gate that
skips the permission check must fail a test (done 2026-10-16: three server mutants, the gate in
`OperationRoute`, the navigation filter and the foreign-application check, each failed tests; three
UI mutants, the `can()` filter, the route permission rule and `meta.permission`, each failed a vitest or
a Playwright test). In
`orders-demo`: two principals, an administrator and a clerk; the committed `web/openapi.json`. UI:
vitest for the pure functions, the gallery page under strict axe and CSP in both colour modes at
desktop and 390 px, and an orders end-to-end run against the real Java demo for both principals.

## 8. Conformance vector

`conformance/admin/navigation.json`, written by the Java test `AdminConformanceTest`: a set of
sections (id, title, path, permission, icon) and, per case, the caller's permission set and the
expected response (`200` with the section ids, or `403` with the error code). Matching rules are
the wildcard rule of core section 3. Go compares.

## 8a. As built (2026-10-16)

- The checked registry is a service, `AdminRegistry` (provided by `AdminModule`), built when the module's
  `boot` asks for it, after every module registered; the registry-wide rules (3) run there and fail
  start-up with a `StartupException`. The per-section rules (1, 2 without the application check, 4 and
  the path prefix) are in `AdminSection`'s constructor, so they fail in `register` and in the `openapi`
  command too. "About first" holds when `AdminModule` is listed before the modules that add sections.
- `FwAdminAbout` is a presentational partial (the application loads `getAdminAbout` and passes the answer
  in); `adminNavigation` lists a page when the server listed its section **and** the session holds both
  the section's and the page's permission; an icon named in `.ts` data goes in the Vite plugin's `icons`
  from a data-only file (the config cannot import `.vue`).
- The demo (`orders-demo`) has an administrator role (`orders-demo:admin` grants `orders-demo:admin:*:*`)
  and one section of its own, `orders` (`orders-demo:admin:orders:view`). Its committed document includes
  the admin operations (vector `openapi/orders-demo-admin.json`); the orders-only document stays the
  shared vector `openapi/orders-demo.json` until the Go framework builds admin (G6).

## 9. Rulings (defaults applied; owner-decided ones marked)

1. **Permission names:** `{app}:admin:{section}:{action}` with `{app}:admin:console:view` for entry
   and one suggested role `{app}:admin`. Alternative: three-segment `{app}:admin:{action}`; not
   chosen because the platform's definition sync expects four segments.
2. **Navigation is gated** by `console:view` (a non-admin gets `403`, not an empty list). Alternative:
   open to every signed-in user, answering an empty list; not chosen so the shell can tell "not an
   admin" from "an admin with nothing yet".
3. **Module placement:** a new `framework-admin` module (section 5), not additions to `framework-http`
   or `framework-auth`.
4. **How admin data reaches the platform (M4e):** the application's service account, with the user's
   permissions enforced in the framework first (flowcatalyst.md section 5, item 2). Alternative: an
   `apiAccess` OAuth client per user. **RULED by the owner 2026-10-16: the service account**, after the
   application has enforced the signed-in user's admin permission (sections 3 and 11).
5. **The about answer omits failure messages.** Alternative: include them for holders of a stronger
   permission. Not built.
6. **The admin area is mounted per application** (the application lists `AdminModule` and the
   shell's routes); the framework never mounts it by itself.

## 10. The config section (M4e slice 3, built 2026-10-16)

Section `config`, path `/admin/config`, permission `{app}:admin:config:view` (module `AdminConfigModule`
in `framework-admin`; it reads the application's own `Config`, no platform). It shows which settings the
application declares, whether each has a value, where the value came from and the application's
health. **No secret value is ever on the wire.**

| Operation | Id | Query | Answers |
|---|---|---|---|
| `GET /api/admin/config` | `listAdminConfig` | `state` (`SET`, `DEFAULT`, `UNSET`), `secret` (boolean), `q` (case-insensitive substring of key or module) | `AdminConfig` |
| `GET /api/admin/config/{key}` | `getAdminConfigSetting` | | `AdminConfigSetting`, or `404 SETTING_NOT_FOUND` |

`AdminConfig`: `environment`; `total` (matching), `set`, `defaulted`, `unset` (all settings); `settings`
(declaration order); `health` (`ready`, `checks`: the about page's shape). `AdminConfigSetting`: `key`,
`module`, `type` (human text), `secret`, `required`, `state`, `source` (`default`, `not set`, `environment
variable NAME` or the file), `value` (optional), `withheld` (boolean), `description` (optional).

Masking (the rule the vector `conformance/admin/config.json` pins; the core's `SettingStatus` holds it):

1. a secret setting has **no** `value`, `withheld` is true when it is set;
2. a setting whose key contains a credential word (`secret`, `password`, `passwd`, `passphrase`, `token`,
   `credential`, `api-key`, `private-key`, `access-key`, `signing-key`, `salt`) is withheld the same way,
   even when declared as plain text (a second line of defence, not a substitute for declaring secrets);
3. a URL value is shown without user info and without query (`jdbc:` URLs too);
4. `AdminConfigModule.withoutValues()` withholds every value (state and source only).

Not shown, and why: the database and Redis *instance* sections (they are read through `InstanceConfig`,
not declared settings, and carry passwords); per-client secrets of `SecretResolver` (looked up by client
and name); range-check results (a value out of range fails start-up, so every setting shown has passed
its module's checks; the page says nothing about them). Owner question: whether non-secret values
should be shown at all (default: shown, with the masking above).

## 11. The platform-projected sections (M4e slice 2, built 2026-10-16)

Sections `event-types` (`/admin/event-types`, `{app}:admin:event-types:view`) and `roles`
(`/admin/roles`, `{app}:admin:roles:view`), module `PlatformAdminModule` in `framework-flowcatalyst`
(it needs `PlatformClient`). Read-only. The platform is called with the **application's service
account** (owner ruling, section 9 item 4) **after** the operation gate checked the signed-in user's
permission (a refused caller never reaches the platform); the framework adds the confinement the
platform does not guarantee (below).

| Operation | Id | Query | Answers |
|---|---|---|---|
| `GET /api/admin/event-types` | `listAdminEventTypes` | `status` (`CURRENT`, `ARCHIVED`), `subdomain`, `aggregate`, `q`, `limit` (50), `after` | `Page<AdminEventType>` |
| `GET /api/admin/event-types/{id}` | `getAdminEventType` | | `AdminEventTypeDetail` (`eventType`, `versions[{version,status,schema?}]`), `404 EVENT_TYPE_NOT_FOUND` |
| `GET /api/admin/roles` | `listAdminRoles` | `source`, `q`, `limit` (50), `after` | `Page<AdminRole>` |
| `GET /api/admin/roles/{name}` | `getAdminRole` | | `AdminRoleDetail` (`role`, `permissions` sorted), `404 ROLE_NOT_FOUND` |

`Page<T>`: `items`, `nextCursor` (optional). The platform does not page these two lists, so the
framework sorts (event types by code, roles by name) and pages by **keyset cursor**: `nextCursor` is the
key of the last item, `after` returns keys greater than it, `limit` is clamped to 1 to 200. Every
operation also documents `502 PLATFORM_ERROR` and `503 PLATFORM_NOT_CONFIGURED`.

Rules (vector `conformance/admin/platform-reads.json`):

1. only the application's own records are returned, whatever the platform sends (event types by their
   `application`, roles by `{application}:` prefix and `applicationCode`); a record of another
   application is `404`, and a role name outside the application is refused **without** calling the
   platform;
2. the filters are sent to the platform and applied again locally;
3. a platform refusal (403), failure or outage is `502 PLATFORM_ERROR` with a message that names no host,
   token or platform text (the cause is logged); a 403 says the service account needs the platform's view
   permission; a platform 404 of a single record is `404`; a disabled FlowCatalyst module is `503`.

Reads used (what the platform's `application-service` role allows today): `GET /api/event-types[/{id}]`
(`event-type:view`; the platform confines an application service account to its own applications) and
`GET /api/roles[/{name}]` (`role:view`). **Platform gap:** `GET /api/roles` is not confined for an
application service account, it answers with every role of every application; the framework filters,
but the platform should (flowcatalyst.md section 5, item 7).

**Definition sync.** The registry's permissions (`Admin.permissions(..)`) and one suggested role `admin`
(on the platform `{app}:admin`, display name "Administrator") reach the platform through the definition
sync without the application declaring them. The role lists the permissions one by one (the sync accepts no
wildcard in a role; the application's *permission mapping* may still use `{app}:admin:*:*`). An application
that declares its own role `admin` gets nothing added. Role and permission names obey the platform rules
(letters, digits and `_.-` per colon-separated segment, no whitespace; the sync adds the `{app}:` prefix).

## 12. Users, invitations and activity (M4e slices 1 and 4, built 2026-10-16)

The platform side exists since its change F20 (`flowcatalyst-go`, `docs/platform-bug-fixes.md` F20,
`docs/wire-contract.md` "Application admin"): the built-in `platform:application-service` role carries
`event:view`, `dispatch-job:view`, `user:view`, `user:create`, `invitation:view` and `invitation:manage`,
every one confined to the applications the credential is bound to. **Every application service account must
mint a new token** before these work (tokens carry permissions from mint time). Modules (in
`framework-flowcatalyst`, list them after `AdminModule` and `FlowCatalystModule`):
`PlatformUsersAdminModule` and `PlatformActivityAdminModule`.

**Transport (interim).** The Java SDK this framework pins (0.0.11) has no methods for these routes (the
platform's SDK change is unreleased). `PlatformClient` therefore calls them over plain HTTP with the same
service-account token (one retry after a `401` with a fresh token), and the other framework languages call
the same routes. When an SDK carrying the resources is released, the methods can move onto it; the
module and the wire do not change.

### 12.1 Users section: `users`, path `/admin/users`

| Operation | Id | Permission | Platform call |
|---|---|---|---|
| `GET /api/admin/users` | `listAdminUsers` | `{app}:admin:users:view` | `GET /api/applications/{app}/users` |
| `GET /api/admin/users/{id}` | `getAdminUser` | `users:view` | `.../users/{id}` |
| `POST /api/admin/users` (201) | `inviteAdminUser` | `{app}:admin:users:invite` | `POST .../users` |
| `GET /api/admin/invitations` | `listAdminInvitations` | `users:view` | `GET .../invitations` |
| `POST /api/admin/invitations/{id}/resend` | `resendAdminInvitation` | `{app}:admin:users:resend` | `POST .../invitations/{id}/resend` |
| `DELETE /api/admin/invitations/{id}` (204) | `revokeAdminInvitation` | `{app}:admin:users:revoke` | `DELETE .../invitations/{id}` |

Lists answer `Page<T>` (`items`, `nextCursor`); the cursor is the next platform page number (the platform pages
by number): `nextCursor` is present when `(page + 1) * limit < total`; `limit` 1 to 200 (default 50), `after` a
non-negative integer else `400 INVALID_CURSOR`. User filters: `q`, `active`, `clientId`, `roles` (CSV). Invitation
filter: `status` (`PENDING`, `EXPIRED`). `AdminUser`: `id, email, name, active, clientId, idpType, roles,
invitation?, lastLoginAt?, createdAt` (never a hash, token, link or second-factor state: the projection reads named
fields only). `AdminInvitation`: `userId, email, name, clientId, roles, status, createdAt, expiresAt?`.
Invite body: `{email, name, clientId (id or identifier), roles[1..50], sendInvitation? (default true),
returnInviteLink? (default false)}` answers `{id, invitationSent, inviteLink?}`; resend body `{returnInviteLink?}`
(false: the platform emails it) answers `{expiresAt?, inviteLink?}`.

Rules (vector `conformance/admin/users.json`):

1. **The application is the configured one.** The application code in every platform path is
   `flowcatalyst.application`; nothing the caller sends can change it. An id outside `[A-Za-z0-9_-]{1,64}` is a
   `404` (`USER_NOT_FOUND`, `INVITATION_NOT_FOUND`) without a platform call, and is percent-encoded anyway.
2. **Roles are the application's.** Roles shown are the ones prefixed `{app}:` (whatever the platform sends); a role
   filter keeps own roles only and asks nothing when none is left (a foreign role matches nothing); an invite
   naming another application's role (`app:role` with another `app`) is `403 ROLE_APP_FORBIDDEN` before the
   platform is asked (a short name without a colon is left to the platform).
3. **The invitation link is a credential.** It is in an answer only when the caller asked for it
   (`returnInviteLink`), whatever the platform sends; that answer has `Cache-Control: no-store`; no list, error
   answer, log line or exception text carries it (`InviteResult.toString` withholds it); the UI shows it once, in
   one dialog, and keeps it in no URL, storage or list.
4. **The platform's reasons reach the person in its words.** A platform `400`, `409` or `422`, a `403` with a reason
   of its own (`ROLE_APP_FORBIDDEN`, and `FORBIDDEN` "client is not available to this application", which is
   answered as `403 CLIENT_NOT_AVAILABLE` so it is not mistaken for the admin gate's `FORBIDDEN`) and
   `500 INVITE_DELIVERY_FAILED` (answered `502`) are passed on with the platform's status, code and message,
   cleaned of control characters and cut at 300 characters. Known codes: `ROLE_APP_FORBIDDEN`, `UNKNOWN_ROLE`,
   `ROLES_REQUIRED`, `EMAIL_EXISTS`, `INVITATION_DELIVERY_REQUIRED`, `CLIENT_REQUIRED`, `INVITE_DELIVERY_FAILED`.
   Every other failure, and a `403 FORBIDDEN` (the service account lacks the platform permission), is
   `502 PLATFORM_ERROR` with a message naming no host, token or platform text (for a change it says the account
   needs the platform's permission to manage the resource); a platform `404` of one record is `404`.
5. Revoke deactivates the user (platform decision 3 of F20); the user then no longer awaits an invitation.

### 12.2 Activity section: `activity`, path `/admin/activity`

One permission, `{app}:admin:activity:view`, for four operations: `GET /api/admin/events` (`listAdminEvents`;
filters `type` (exact), `subject`, `correlationId`, `clientId`, `since`, `until` (RFC 3339, else `400
INVALID_TIMESTAMP`)), `GET /api/admin/events/{id}` (`getAdminEvent`: the event, its payload as JSON text cut at
20 000 characters with `dataTruncated`, `context`, `causationId`, and this application's `dispatchJobs`), `GET
/api/admin/dispatch-jobs` (`listAdminDispatchJobs`; filters `status` (an upper-case word, else `400
INVALID_STATUS`), `code` (exact), `clientId`, `messageGroup`, `since`, `until`) and `GET
/api/admin/dispatch-jobs/{id}` (`getAdminDispatchJob`: the job, `protocol`, `timeoutSeconds`, `maxRetries`,
`retryStrategy`, `lastError` and the `attempts`, each with the subscriber's `responseBody` cut at 4 000
characters with `responseBodyTruncated`). Paging: the platform pages by `limit`/`offset`; the framework asks for
`limit + 1` rows and answers `nextCursor = offset + limit` when more came back (`after` is the offset).
Vector `conformance/admin/activity.json`.

1. The platform is asked with `applications={app}`, and the framework confines again: an event whose type does not
   start with `{app}:` and a job whose code's first segment is not the application's are dropped from lists and are
   `404` (`EVENT_NOT_FOUND`, `DISPATCH_JOB_NOT_FOUND`) by id; a job's attempts are asked for only after its code
   was checked; an event's jobs are filtered the same way. A `type` or `code` filter of another application is an
   empty page without a platform call.
2. **Cross-application deliveries (platform decision 2 of F20).** A job is the application's because its code is
   the application's event type; its target may be another application's subscriber. The pages show the job and
   its `targetUrl` under the heading "Delivery target" with a sentence saying so. `targetUrl` loses user info,
   query and fragment (they often carry the subscriber's credential); its path is shown as is. The platform sends
   no subscriber application on a job, so the label cannot name it (platform gap).
3. Payloads (`data`) and subscriber responses are shown to the holders of `activity:view`; both are bounded.

### 12.3 Rulings taken (defaults applied; owner to confirm)

**Owner 2026-10-16: ruling 1 confirmed** (application-wide; the permission is the gate). Rulings 2 to 5 are still defaults.

1. The admin pages are **application-wide**: they are not narrowed to the signed-in user's client. The permission
   is the gate; the service account reaches every client the application is enabled for. (Narrowing by the
   session's client would be a one-line filter if wanted.)
2. Users, invitations, events and jobs are one section each (`users`, `activity`) rather than four; invitations
   are read with `users:view`.
3. `inviteRedirectUri` is not exposed (the invitee lands on the platform's default page after setting a
   password); add it as an optional body field when the first application wants it.
4. Event payloads and attempt bodies are shown (bounded). An option to hide them is a small addition.
5. Admin writes are not audited by the framework: the platform records them against the service account, not
   against the signed-in user. (Platform gap: no way to name the acting user.)

## 13. The shared data pages (owner ruling 2026-10-18, friction F18)

Sections 10 to 12 define the wire. This section defines the **pages** in `@flowcatalyst/ui` that
show them, so that an application mounts the admin area instead of copying 1,500 lines of Vue.

**Pages.** The package ships six pages, each the page of the server section with the same id:
`about` (`{app}:admin:console:view`), `event-types`, `roles`, `users`, `activity` and `config`
(`{app}:admin:{id}:view`), with their drawers (event type, role, user, event, dispatch job) as child
routes, the invite form and the invitation-link dialog. `standardAdminPages(application, ids?)`
returns them as `AdminPage` values (section 6) in navigation order; `ids` picks a subset, because an
application mounts only the sections its server has. Every drawer route carries the same
`meta.permission` as its page, so the route guard (section 6) covers it.

**The port.** The package holds no generated client. The application passes the operations of its own
generated client once, as a plugin option:
`app.use(flowcatalystUi, { api, admin: { application: "orders-demo", api: sdk, pageSize? } })` where `sdk`
is `import * as sdk from "./api/orders/sdk.gen.ts"`. `AdminDataApi` names the calls by **operation id**
(`listAdminEventTypes`, `getAdminEventType`, `listAdminRoles`, `getAdminRole`, `listAdminUsers`,
`getAdminUser`, `inviteAdminUser`, `listAdminInvitations`, `resendAdminInvitation`,
`revokeAdminInvitation`, `listAdminEvents`, `getAdminEvent`, `listAdminDispatchJobs`,
`getAdminDispatchJob`, `listAdminConfig`, `getAdminAbout`); every member is optional, because the
application mounts the sections it has. The wire views the pages read (`AdminEventTypeView` and so
on) are structural copies of the server's schemas, and **TypeScript checks at the one place the
application passes `sdk` that each generated function takes and answers what the pages expect**: a
server change that breaks a page is a compile error in the application, not a run-time surprise.
A page whose call was not passed shows an alert naming the missing operation. The permissions are
derived from the application code (`adminPermissions`), so they equal the server's section names
(section 3); the invite form's Zod rules are the server's `InviteBody` constants.

**Replacing.** An application that wants its own version of a page gives it the same id:
`replaceAdminPage(pages, ownPage)` puts it in place of the standard one (or appends it); the
navigation, the routes and the breadcrumbs follow from the list as for any `AdminPage`. An application's
own section (the demo's `orders`, the trial's `tickets`) is simply another entry.

**What stays out.** Pages for sections the framework does not define stay in the application. The
page size (`pageSize`, default 25, the server clamps to 1 to 200) is the application's.

**Tests the build has:** the page list, its permissions and the shell's route rules (vitest); the
drawer routes' permissions; subsets and replacement; the missing-call error; one page mounted on a
fake client (page size, the rows); and the orders and tickets browser suites against the real Java
servers (the orders suite exercises all six pages; the tickets suite mounts `about` and `config`).

## 14. Platform config (2026-10-23)

The section `platform-config` (`/admin/platform-config`, "Platform config") shows and edits the configuration and
secrets the application keeps on the platform, as the application's own service account, with `PlatformConfigAdminModule`
in `framework-flowcatalyst`. Operations `listAdminPlatformConfig`, `setAdminPlatformConfig`, `deleteAdminPlatformConfig`
and `revealAdminPlatformConfig`; permissions `view`, `manage`, `reveal` (reveal is separate and is a `POST`) and
`all-clients` (added 2026-10-25: without it a user acts for the global values and their own client only, else `403
CLIENT_FORBIDDEN`). The wire, the rules and what was built are in [platform-config.md](platform-config.md) (sections 4
and 8). The shared page (a seventh standard page) is section 5 of that contract.
