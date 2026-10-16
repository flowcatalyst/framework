<!-- types: AdminModule, AdminSection, Admin, AdminApi, AdminRegistry, AdminConfigModule, AdminConfigApi, PlatformAdminModule, PlatformAdminApi, PlatformUsersAdminModule, PlatformUsersApi, PlatformActivityAdminModule, PlatformActivityApi, PlatformAdminData, PlatformClient, SettingStatus, Definitions, Operation, Context -->
# The admin area

Every application gets the same small administration area, inside its own SPA and behind its own
sign-in: a registry of **sections**, a permission gate in front of every operation, two operations
that need no platform data (the navigation and the about page), and a shared shell in
`@flowcatalyst/ui` that builds its menu from what the server says and what the session may open.
This page covers the skeleton (M4d) and the data sections (M4e): **event types**, **roles**,
**configuration**, **users and invitations** and **activity** (the application's events and dispatch jobs).
They plug in the same way.

The specification, language-neutral and shared with the Go framework, is
[contracts/admin.md](../contracts/admin.md).

## What it is, and what it is not

The **platform console** administers the platform: every client, application, role and
subscription. It exists. The **application admin** administers one application's own slice, from
inside that application. M4d ships none of the data sections; it ships what every one of them
needs.

| Piece | Artifact |
|---|---|
| `AdminModule`, `AdminSection`, `Admin` (the registry), the two operations | `framework-admin` |
| `AdminConfigModule`: the config section (reads the application's own `Config`) | `framework-admin` |
| `PlatformAdminModule`: the event types and roles sections (read the platform) | `framework-flowcatalyst` |
| `PlatformUsersAdminModule`: users, invitations, invite / resend / revoke | `framework-flowcatalyst` |
| `PlatformActivityAdminModule`: events and dispatch jobs with attempts | `framework-flowcatalyst` |
| `FwAdminShell`, `FwAdminAbout`, `adminRoutes`, `adminNavigation`, `useAdminNavigation`, `AdminPage` | `@flowcatalyst/ui` |

`framework-admin` depends on `framework-core` and `framework-http` only: no database, no write path
and no auth module (the gate reads `Context.permissions`, which any `ContextResolver` fills).
`framework-flowcatalyst` depends on it for the sections that read the platform.

## Wiring it (Java)

```java
// the demo: examples/orders-demo OrdersWeb
.module(AdminModule.of(APPLICATION))                 // the skeleton: navigation and about
.module(new FlowCatalystModule())                    // the platform connection the next line reads
.module(PlatformAdminModule.of(APPLICATION))         // event types and roles, from the platform
.module(PlatformUsersAdminModule.of(APPLICATION))    // users and invitations, through the service account
.module(PlatformActivityAdminModule.of(APPLICATION)) // events and dispatch jobs
.module(AdminConfigModule.of(APPLICATION))           // configuration and secret status
.module(new OrdersAdminModule())                     // your own section(s)
```

Registration order is navigation order, with `about` first. `PlatformAdminModule` needs the
`flowcatalyst` module enabled (it answers `503 PLATFORM_NOT_CONFIGURED` when it is not) and its
`flowcatalyst.application` must equal the code you pass. Map a platform role to the admin permissions
in the auth module's permission mapping:

```java
"orders-demo:admin", Set.of("orders-demo:admin:*:*")
```

`AdminModule.consoleView(application)` is the permission that opens the area at all:
`orders-demo:admin:console:view`.

## Permissions

Names are `{application}:admin:{section}:{action}`: four segments, which is what the platform's
definition sync accepts. The platform's wildcard rule applies (same number of segments, `*` in a
held segment matches), so `orders-demo:admin:*:*` is the administrator.

The server decides, in three layers:

1. **The operation.** Every operation checks its permission before it parses anything. A caller
   without it gets `403` and the standard error envelope with code `FORBIDDEN`; an unauthenticated
   one gets `401` first.
2. **The navigation.** `GET /api/admin/navigation` lists only the sections the caller may use, and
   needs `{app}:admin:console:view` itself.
3. **The UI.** The shell hides what the session cannot open and the router refuses the route. This is
   courtesy; layers 1 and 2 decide.

## Adding a section

A section is a value; its operations are ordinary typed operations ([openapi](openapi.md)) that
must carry a permission and live under `/api/admin/`. Register it in `register`, bind the handler in
`boot`:

```java
// OrdersAdminModule
public static final Operation<NoBody, OrdersOverview> OVERVIEW =
    Operation.get("/api/admin/orders/overview", OrdersOverview.class)
        .id("getAdminOrdersOverview").tag("admin").permission(VIEW).build();

static final AdminSection SECTION =
    AdminSection.of("orders", "Orders overview", "/admin/orders", VIEW)
        .icon("i-lucide-chart-no-axes-column").serving(OVERVIEW);

public void register(AppBuilder app) { Admin.section(app, SECTION); }       // also declares OVERVIEW in the document
public void boot(App app) { app.get(Routes.class).forModule(ID).bind(OVERVIEW, r -> Outcome.ok(...)); }
```

Start-up fails, and so does the `openapi` command, when an operation of a section has no
permission or sits outside `/api/admin/`, a permission does not have four segments or belongs to
another application, or two sections share an id or a path. `Admin.permissions(..)` lists every
permission the registry needs, for `Definitions.permission` and a role that grants them.

## Permissions and roles on the platform

Each section has its own view permission: `{app}:admin:event-types:view`, `{app}:admin:roles:view`,
`{app}:admin:config:view`, besides `{app}:admin:console:view`. You do not declare them. When the
FlowCatalyst module is enabled and admin sections are registered, the definition sync (`java -jar
app.jar sync`, or `--dry-run`) sends every admin permission and **one role, `admin`** (on the platform
`orders-demo:admin`), that grants them one by one: the sync accepts no wildcard inside a role, though
your permission mapping above may use one. Role and permission names follow the platform's rules
(letters, digits and `_.-` in each colon-separated segment, no whitespace). Declare your own role
named `admin` with `Definitions` and nothing is added: it is yours then. The demo's
`OrdersAdminTest.theAdminRoleAndItsPermissionsAreInTheDefinitionSync` shows the dry run.

## Event types and roles (from the platform)

Read-only, in the application's own words: `GET /api/admin/event-types[/{id}]` and `GET
/api/admin/roles[/{name}]`. The application calls the platform **as its own service account**, after
the operation gate has checked the signed-in user's admin permission; a refused user never reaches the
platform. What the service account is allowed to read decides what the pages can show:

| Page | Platform read | The framework adds |
|---|---|---|
| Event types | `GET /api/event-types` (`event-type:view`), `GET /api/event-types/{id}` | own application only; filters `status`, `subdomain`, `aggregate`, `q`; sorted by code; cursor paging |
| Roles | `GET /api/roles` (`role:view`), `GET /api/roles/{name}` | own application only (the platform answers with every role, see the contract); filter `source`, `q`; sorted by name; cursor paging |

Paging is by keyset cursor (`nextCursor` is the key of the last item; pass it as `after`; `limit` 1 to
200, default 50). A platform refusal, outage or error is `502 PLATFORM_ERROR` with a message that names
no host and no token; a 403 from the platform tells the admin the service account lacks the platform's
view permission, so the page shows a missing grant instead of an empty list. Another application's
record is `404`.

## Users and invitations

Section `users` (`/admin/users`): `GET /api/admin/users[/{id}]`, `GET /api/admin/invitations`, `POST
/api/admin/users` (invite), `POST /api/admin/invitations/{id}/resend`, `DELETE /api/admin/invitations/{id}`.
Permissions: `{app}:admin:users:view` (lists and reads), `:invite`, `:resend`, `:revoke`; give an auditor only
`view` and the pages offer nothing to change (the demo's `orders-demo:auditor`). Behind them the application
calls the platform's application-user routes as its own service account, **over plain HTTP** until the
SDK that has them is released (the pinned SDK is 0.0.11; the interim is `PlatformHttp`, one retry after a `401`).
The platform's `application-service` role needs the user and invitation permissions of platform change F20, and
**every application service account must mint a new token** to carry them.

What the framework adds, and tests (`PlatformUsersAdminTest`, with mutation checks):

- The application code in every platform path is the configured one; an id that is not `[A-Za-z0-9_-]{1,64}` is a
  `404` without a call. Only the application's roles are shown, filtered by, or accepted on an invitation (another
  application's role is `403 ROLE_APP_FORBIDDEN` here as well).
- **The invite link is a credential.** Ask for it with `returnInviteLink: true` on the invitation (or the resend);
  it is then in that one answer (`Cache-Control: no-store`) and nowhere else: not in lists, errors or logs, and
  the page keeps it in one dialog ("Invitation link", shown once) and drops it when the dialog closes. If you add
  code around these operations, never log an `InviteResult` field or a platform response; `PlatformHttp` logs
  nothing for that reason.
- The platform's reasons reach the admin in its own words with its status and code: `EMAIL_EXISTS`,
  `ROLE_APP_FORBIDDEN`, `UNKNOWN_ROLE`, `ROLES_REQUIRED`, `INVITATION_DELIVERY_REQUIRED`, `CLIENT_NOT_AVAILABLE`,
  `INVITE_DELIVERY_FAILED` (the invite form shows them in its error region). Anything else is `502
  PLATFORM_ERROR` with no platform text.
- Revoking an invitation deactivates the user on the platform; a console action reactivates.

The pages: `AdminUsersPage` (search, a "Show" filter: everyone, invitation pending, invitation expired,
inactive; the Invite user form in `FwFormDialog` with `FwPickList` for the roles and a switch for "show me the
link"), `AdminUserDrawer` (fields, roles, the invitation's state, Resend by email, Get a new link, Revoke, each
asking first with `useConfirm`), and `InviteLinkDialog`.

## Activity: events and dispatch jobs

Section `activity` (`/admin/activity`), one permission `{app}:admin:activity:view`: `GET /api/admin/events[/{id}]`
and `GET /api/admin/dispatch-jobs[/{id}]`, read-only, newest first, paged by an offset cursor. The platform is
asked for this application's records and the framework drops anything else again (an event type must start with
`{app}:`, a job code's first segment must be the application). A job's attempts are asked for only after its code
was checked. The event drawer lists the event's payload (cut at 20 000 characters) and its jobs; the job drawer lists
the attempts with the subscriber's answer (cut at 4 000). **A job belongs to the application because its code is
the application's event type; its target may be another application's subscriber**, so the drawer shows the
target under "Delivery target" and says so. The target URL is shown without user info and query. Payloads and
answers are visible to holders of `activity:view`. Tests: `PlatformActivityAdminTest` (with mutation checks).

## Configuration and secret status

`GET /api/admin/config` (and `/{key}`) lists the settings your modules declare: where each value came
from (`environment variable NAME`, a file, `default` or `not set`), whether it is set, and the
application's health. **A secret is shown as set or not set, never its value.** The same holds for a
setting whose key names a credential (`password`, `token`, `api-key`, ...) even if declared as plain
text, and URLs lose their user info and query. `AdminConfigModule.of(app).withoutValues()` shows state
and source only. Not shown: the database and Redis instance sections (they carry passwords and are not
declared settings), per-client secrets, and range-check results (a value out of range fails start-up,
so everything shown has passed). Demo: `AdminConfigTest` (framework-admin) and
`OrdersAdminTest.theConfigPageShowsTheFlowcatalystSettingsAndNeverTheClientSecret`.

## The two operations

| Operation | Id | Answers |
|---|---|---|
| `GET /api/admin/navigation` | `getAdminNavigation` | `{sections: [{id, title, path, permission, icon?, description?}]}`, only those the caller may use |
| `GET /api/admin/about` | `getAdminAbout` | `{application, framework?, environment, startedAt, ready, checks: [{name, status, critical}]}` |

The about answer leaves failure messages out on purpose: they can carry connection strings. Both
are in the committed document (`examples/orders-demo/web/openapi.json`), so the SPA's client has
them.

## The UI

The package holds no generated client; the application passes its own.

```ts
// ui/apps/orders/src/admin/pages.ts: the page-kit contract M4e pages fill
export const adminPages: readonly AdminPage[] = [
  { id: "about", title: "About", icon: "i-lucide-info", permission: ADMIN_CONSOLE, path: "/admin/about",
    component: () => import("../pages/admin/AdminAboutPage.vue").then((m) => m.default) },
  { id: "orders", title: "Orders overview", icon: "...", permission: ADMIN_ORDERS_VIEW, path: "/admin/orders", component: ... },
];
// router.ts:   ...adminRoutes(adminPages)         every route carries meta.permission; the session guard enforces it
// App.vue:     <FwAdminShell app-name="Orders admin" :pages="adminPages" :load="loadAdminNavigation" back-to="/orders">
```

- `adminRoutes(pages)` refuses a page without a four-segment permission, a bad id, a path outside
  `/admin` and duplicates, and redirects `/admin` to the `about` page.
- `adminNavigation(pages, sections, can)` shows a page only when the server listed its section and the
  session holds the permission. A listed section without a page is not shown (a development warning
  names it); a failed or missing navigation shows nothing.
- `FwAdminShell` is `FwAppShell` with that navigation, a Back link, breadcrumbs (Admin, section,
  record; the current page is text with `aria-current`), and an inline "could not be loaded" error with
  Try again. The U4 standalone pages stay outside any shell.
- `FwAdminAbout` is the about page, presentational: load `getAdminAbout` and pass the answer in.
- Icons named in `.ts` data go in the Vite plugin's `icons` option (a `.vue` file cannot be imported by
  the config: keep the list in a data-only file).

The M4e pages (`ui/apps/orders/src/pages/admin/`) are `FwListPage` + `FwRecordDrawer` as child routes
over the generated operations: `AdminEventTypesPage` and its drawer (fields, specification versions with
the schema in `FwCodeBlock`), `AdminRolesPage` and its drawer (the permissions), `AdminConfigPage`
(summary, health, filters), `AdminUsersPage`/`AdminUserDrawer`, `AdminActivityPage` with `AdminEventDrawer` and
`AdminJobDrawer` (the activity page has an Events / Dispatch jobs switch and two drawer routes). Their `AdminPage` entries are in `src/admin/pages.ts` and carry the child
route with the same permission. A platform failure is a persistent alert on the page in the server's
words; the shared `platform.ts` holds that and the status colours. The generated client is made from the
committed `examples/orders-demo/web/openapi.json` (`pnpm --filter @flowcatalyst/orders-web generate`).

## Testing

`framework-admin`'s tests start an application with the module and a test resolver
(`AdminFixture`); the demo's `OrdersAdminTest` uses the real sessions, roles and mapping with two
principals (`AuthTesting.user("prn_admin", "orders-demo:admin")` and a clerk) and a fake platform
(`DemoPlatform`, on `FakePlatform` from the flowcatalyst module's test-jar; `OrdersE2e` runs the same one).
`PlatformAdminTest`, `PlatformUsersAdminTest`, `PlatformActivityAdminTest` and `AdminDefinitionsTest` (framework-flowcatalyst) cover the platform sections and
the sync. The shared vectors
`conformance/admin/navigation.json`, `config.json`, `platform-reads.json`, `users.json` and `activity.json` are what the Go framework compares.
The demo's `DemoDirectory` is a stateful fake of the platform's user and invitation routes, events and jobs
(`/control/reset` puts it back; `/control/platform-reads?value=403` makes everything refuse). The SPA side has vitest for
the pure functions, the gallery's Admin shell page under strict axe and CSP, and
`ui/apps/orders/e2e/admin.spec.ts` and `admin-data.spec.ts` against the real Java demo.

## Not built yet

Writes from the event types, roles and config pages (read-only by design), `inviteRedirectUri` on an invitation,
a way to hide event payloads, and the database and Redis instance settings on the config page. Not verified
against a real platform (the routes were taken from the platform's F20 source and its wire contract, not called),
Firefox or WebKit. See [contracts/admin.md](../contracts/admin.md) section 12 for the rulings and platform gaps.
