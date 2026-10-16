<!-- types: AdminModule, AdminSection, Admin, AdminApi, AdminRegistry, Operation, Context -->
# The admin area

Every application gets the same small administration area, inside its own SPA and behind its own
sign-in: a registry of **sections**, a permission gate in front of every operation, two operations
that need no platform data (the navigation and the about page), and a shared shell in
`@flowcatalyst/ui` that builds its menu from what the server says and what the session may open.
This page is the skeleton (M4d). The data sections (users and invitations, roles, config, scoped
events and dispatch jobs) are M4e and plug into it the same way.

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
| `FwAdminShell`, `FwAdminAbout`, `adminRoutes`, `adminNavigation`, `useAdminNavigation`, `AdminPage` | `@flowcatalyst/ui` |

`framework-admin` depends on `framework-core` and `framework-http` only: no database, no write path
and no auth module (the gate reads `Context.permissions`, which any `ContextResolver` fills).

## Wiring it (Java)

```java
// the demo: examples/orders-demo OrdersWeb
.module(AdminModule.of(new ApplicationCode("orders-demo")))   // the skeleton: navigation and about
.module(new OrdersAdminModule())                              // your own section(s)
```

and map a platform role to the admin permissions in the auth module's permission mapping:

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

An M4e page is a `FwListPage` with a `FwRecordDrawer` child route and a `FwFormDialog`, over
generated operations; its `AdminPage` goes in the list and its section on the server.

## Testing

`framework-admin`'s tests start an application with the module and a test resolver
(`AdminFixture`); the demo's `OrdersAdminTest` uses the real sessions, roles and mapping with two
principals (`AuthTesting.user("prn_admin", "orders-demo:admin")` and a clerk). The shared vector
`conformance/admin/navigation.json` is what the Go framework compares. The SPA side has vitest for
the pure functions, the gallery's Admin shell page under strict axe and CSP, and
`ui/apps/orders/e2e/admin.spec.ts` against the real Java demo.

## Not built yet

The data sections (M4e), the admin permissions' roles in the definition sync (the registry gives
you the list; wiring it to `Definitions` is yours until the first slice), and the scoped reads of
events and dispatch jobs, which need platform work ([flowcatalyst contract](../contracts/flowcatalyst.md),
section 5). Not verified against a real platform or in Firefox or WebKit.
