<!-- types: Section, Declared, About, Navigation, SectionView, Check, ConfigReport, ConfigSetting, Page, SettingStatus -->
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

## Checked by

`admin_test.go`: the section rules, the registry rules failing start-up with every problem, the gate
for both skeleton operations (401, 403, wildcard, another section's permission, another application's
console), about, and two shared vectors, `conformance/admin/navigation.json` (12 cases) and
`conformance/admin/config.json` (15 cases, including that none of the secret texts appears in an
answer). `config/status_test.go` checks the statuses.
