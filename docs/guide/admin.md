<!-- types: AdminModule, AdminSection, Admin, AdminApi, AdminRegistry, AdminConfigModule, AdminConfigApi, PlatformAdminModule, PlatformAdminApi, PlatformClient, SettingStatus, Definitions, Operation, Context -->
# The admin area

Every application gets the same small administration area, inside its own SPA and behind its own
sign-in: a registry of **sections**, a permission gate in front of every operation, two operations
that need no platform data (the navigation and the about page), and a shared shell in
`@flowcatalyst/ui` that builds its menu from what the server says and what the session may open.
This page covers the skeleton (M4d) and the first data sections (M4e): **event types**, **roles**
and **configuration**. The sections that need platform work (users and invitations, scoped events and
dispatch jobs) are not built; they plug in the same way.

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
(summary, health, filters). Their `AdminPage` entries are in `src/admin/pages.ts` and carry the child
route with the same permission. A platform failure is a persistent alert on the page in the server's
words; the shared `platform.ts` holds that and the status colours. The generated client is made from the
committed `examples/orders-demo/web/openapi.json` (`pnpm --filter @flowcatalyst/orders-web generate`).

## Testing

`framework-admin`'s tests start an application with the module and a test resolver
(`AdminFixture`); the demo's `OrdersAdminTest` uses the real sessions, roles and mapping with two
principals (`AuthTesting.user("prn_admin", "orders-demo:admin")` and a clerk) and a fake platform
(`DemoPlatform`, on `FakePlatform` from the flowcatalyst module's test-jar; `OrdersE2e` runs the same one).
`PlatformAdminTest` and `AdminDefinitionsTest` (framework-flowcatalyst) cover the platform sections and
the sync. The shared vectors
`conformance/admin/navigation.json`, `config.json` and `platform-reads.json` are what the Go framework compares. The SPA side has vitest for
the pure functions, the gallery's Admin shell page under strict axe and CSP, and
`ui/apps/orders/e2e/admin.spec.ts` against the real Java demo.

## Not built yet

The users and invitations section and the scoped events and dispatch jobs sections wait on platform
work: the `application-service` role has no user or invitation permission and there is no endpoint to
list, resend or revoke invitations; `GET /api/events` and `GET /api/dispatch-jobs` are not confined to
an application's own records ([flowcatalyst contract](../contracts/flowcatalyst.md), section 5). Also
not built: writes from any admin page, and the database and Redis instance settings on the config page.
Not verified against a real platform or in Firefox or WebKit; the platform's real answers to
`GET /api/event-types` and `GET /api/roles` for an application service account were read from its
source, not called.
