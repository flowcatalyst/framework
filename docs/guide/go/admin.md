<!-- types: Section, Declared, About, Navigation, SectionView, Check, ConfigReport, ConfigSetting, Page, SettingStatus, Catalog, Users, Activity, Part, PlatformClient, PlatformError, CallKind, Platform -->
# Go: the application admin

Package `admin` is the Go implementation of the [admin contract](../../contracts/admin.md) (the Java page
is [Application admin](../admin.md)): the registry in which an application's admin sections declare
themselves, the permission gate every admin operation passes, the navigation that shows only what the
caller may use, the about operation, and the config section. It is G6 of the Go plan; the shared
`@flowcatalyst/ui` pages call it unchanged, because the wire is the contract's. The data sections that
need the platform (event types, roles, users, activity) are in package `platformadmin` of the
`flowcatalyst` module, and add themselves to the same registry.

## Declaring the skeleton

At declaration time, next to the other operations, so `orders-demo openapi` needs no database and no
configuration:

```go
web := httpfw.Declare(app)
adm := admin.Declare(web, fw.MustApplicationCode("orders-demo")) // registers the `about` section first
adm.Config()                                                     // the config section (optional)
adm.Section(ordersAdminSection)                                  // the application's own section
```

In the build function, once, after every package declared its sections:

```go
routes, err := adm.Build(b) // checks the whole registry, binds navigation, about and config
srv, err := httpfw.NewServer(b, web, []*httpfw.Routes{mod.Routes(), routes, ...}, httpfw.WithResolver(mod.Resolver()))
```

Navigation order is registration order, with `about` first.

## Adding a section

A section is a value (`admin.NewSection(id, title, path, permission)`, then `WithIcon`,
`WithDescription` and `Serving(operations...)`). Its operations are ordinary typed operations
([typed operations](openapi.md)) with `Permission(...)`; the handler is bound as for any operation.

```go
var overviewOp = httpfw.Get[OrdersOverview]("/api/admin/orders/overview").
	ID("getAdminOrdersOverview").Summary("Count the client's orders by status").Tag("admin").
	Permission(fw.MustPermission("orders-demo:admin:orders:view")).Build()

var ordersSection = admin.NewSection("orders", "Orders overview", "/admin/orders", "orders-demo:admin:orders:view").
	WithIcon("i-lucide-chart-no-axes-column").Serving(overviewOp)
```

`Declared.Section` declares the section's operations in the OpenAPI document. A section that breaks a
rule does not panic while package variables initialise: the problem is listed by `Section.Problems`,
and `Declared.Build` (a `*fw.StartupError` naming every problem) and the `openapi` command report it.
The rules are the contract's: every operation has a permission, permissions have four segments and
start with the application code, operations are under `/api/admin/`, paths are `/admin` or under it,
ids and paths are unique. `admin.Permissions(sections)` lists every permission the registry needs.

## The three layers

1. The operation gate is `httpfw.Handle`'s permission check: a caller without the permission gets
   `403 FORBIDDEN` with the message `this needs the permission {permission}`, before anything about
   the request is read; an unauthenticated one gets `401`.
2. `GET /api/admin/navigation` (`getAdminNavigation`) lists `admin.VisibleTo(sections, cx)`: the
   sections whose permission the caller holds (the platform's wildcard rule), and needs
   `{app}:admin:console:view` itself.
3. The UI hides and guards routes from the session's permissions (courtesy only).

`GET /api/admin/about` (`getAdminAbout`) answers the application code, the environment (`APP_ENV`),
when the module started, readiness, and every health check as `UP`, `DOWN` or `TIMED_OUT` with its
criticality. A failure's message is not on the wire. The framework version is omitted in Go (the
field is optional in the contract).

## The config section

`adm.Config()` adds `GET /api/admin/config` (`listAdminConfig`) and `GET /api/admin/config/{key}`
(`getAdminConfigSetting`) behind `{app}:admin:config:view`. It reads
`config.Values.Statuses()` (`fw.Boot.SettingStatuses()`): for every declared setting, in declaration
order, the module, the type in words, whether it is a secret or required, `SET`, `DEFAULT` or `UNSET`,
and the source (`environment variable NAME`, a file, `default`, `not set`). **No secret value is ever
on the wire**: a secret has no `value` and `withheld` is true when it is set; a key that names a
credential (`secret`, `password`, `token`, `api-key`, ...) is withheld the same way even when
declared as plain text; a URL is shown without user info, query and fragment; durations are ISO-8601
(`PT30S`). `admin.WithoutValues()` withholds every value.

## Paging

`admin.PageOf(sorted, key, after, hasAfter, limit)` cuts one page out of a list sorted by a unique
key: `nextCursor` is the key of the last item, `after` returns keys greater than it, `limit` is
clamped to 1 to 200. The platform-backed lists use it.

## The sections that read the platform

Package `platformadmin` (module `flowcatalyst`) holds the sections whose data is the platform's: event
types and roles (`Catalog`), users and invitations (`Users`), events and dispatch jobs (`Activity`).
Each is declared with the registry at declaration time and bound with the application's platform
client in the build function:

```go
adm := admin.Declare(web, fw.MustApplicationCode("orders-demo"))
fcd := flowcatalyst.Declare(app)
catalog := platformadmin.DeclareCatalog(adm)    // sections event-types and roles
users := platformadmin.DeclareUsers(adm)        // section users
activity := platformadmin.DeclareActivity(adm)  // section activity
adm.Config()
fcd.AdminDefinitions(adm)                       // after every section: the permissions and the admin role for the sync
...
fc, err := flowcatalyst.Build(ctx, b, fcd)
skeleton, err := adm.Build(b)
platform, err := platformadmin.Build(b, fc.Client(), catalog, users, activity)
```

The platform is called with the application's **own service account**, after the operation gate has
checked the signed-in user's admin permission, so a refused user never reaches the platform. The
framework confines every answer to the application's own records, whatever the platform sends; a
record of another application is `404`. A platform refusal or outage is `502 PLATFORM_ERROR` with a
message that names no host, token or platform text (the cause is logged); a 403 from the platform
says the service account lacks the platform's view (or manage) permission; a platform that explains a
refusal (`EMAIL_EXISTS`, `ROLE_APP_FORBIDDEN`, ...) is passed on in its own words; a disabled
FlowCatalyst module answers `503 PLATFORM_NOT_CONFIGURED`. The invitation link is a credential: it is
in an answer only when the caller asked for it, that answer is `no-store`, and no list, error or log
carries it.

`flowcatalyst.PlatformClient` has the methods behind them (`EventTypes`, `EventType`, `Roles`, `Role`,
`Users`, `User`, `Invite`, `Invitations`, `ResendInvitation`, `RevokeInvitation`, `Events`, `Event`,
`DispatchJobs`, `DispatchJob`, `DispatchJobAttempts`, `DispatchJobsOfEvent`). They go over plain HTTP
with the service account's token, one retry after a `401` with a fresh token (the module's fcsdk
version has no methods for the application-user and scoped-read routes; the contract calls this
interim). A failure is a `*flowcatalyst.PlatformError` with `Kind()` (`KindForbidden`, `KindNotFound`,
`KindRejected` or `KindOther`), `Status()`, `Code()` and `ServerMessage()` (cleaned, at most 300
characters).

`Declared.AdminDefinitions(adm)` adds the permissions every registered section requires and one
suggested role `admin` (on the platform `{app}:admin`) that grants them one by one, because the sync
accepts no wildcard in a role. An application that declares a role `admin` itself keeps it.

`flowcatalyst/fakeplatform` is the in-process stand-in for the platform used by the tests and the
browser launcher (token endpoint, canned and routed answers, every request recorded).

## Checked by

`admin_test.go`: the section rules, the registry rules failing start-up with every problem, the gate
for both skeleton operations (401, 403, wildcard, another section's permission, another application's
console), about, and two shared vectors, `conformance/admin/navigation.json` (12 cases) and
`conformance/admin/config.json` (15 cases, including that none of the secret texts appears in an
answer). `config/status_test.go` checks the statuses. `platformadmin`: the vectors
`conformance/admin/platform-reads.json` (27 cases), `users.json` (35) and `activity.json` (20), the
schemas and paths of the sections against `conformance/openapi/orders-demo-admin.json`, the
definitions, and the rules above (confinement, link credentials, error mapping).
