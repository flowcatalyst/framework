# OpenAPI vectors

The documents the Java emitter writes (OpenAPI contract, [`../../docs/contracts/openapi.md`](../../docs/contracts/openapi.md)
section 10). Unlike the other JSON vectors they are compared as **exact bytes** (formatting, key
order, the final newline): the Go emitter must write the same file for the same API.

| File | Written by | What |
|---|---|---|
| `fixture.json` | `framework-http` `OpenApiConformanceTest` (`OpenApiFixture`) | the fixture API below |
| `auth.json` | `framework-auth` `AuthOpenApiTest` | `getAuthSession` and `logout` with their fixed components (sessions contract section 4.5), the `session` and `bearer` schemes; info `flowcatalyst-auth` / `1` |
| `orders-demo.json` | `examples/orders-demo` `OrdersOpenApiTest` | a sample application: the orders API and the auth operations, byte-equal to `examples/orders-demo/web/openapi.json` |

Rewrite with `./mvnw -q verify -Dconformance.update=true` (the orders-demo file also needs
`-Dopenapi.update=true`, which rewrites its committed `web/openapi.json` first).

## The fixture API (`fixture.json`)

Info `fixture` / `1`. Security schemes contributed: `session` (cookie `__Host-fw_session`), `bearer`
(JWT), `basic` (HTTP basic), so the document shows the order session, bearer, then the rest by name.

Types (Java records nested in a holder class, so their names carry no prefix; Go declares
package-level types with these names):

- `Colour`: enum `RED`, `GREEN`, `BLUE`.
- `Money`: an application value type with a registered schema: string, pattern
  `^-?[0-9]+\.[0-9]{2}$`, description `an amount with two decimals` (written inline).
- `Scalars`: one component per row of the type table, in this order: `text` string, `flag`
  boolean, `maybeFlag` optional boolean, `count` int32, `maybeCount` optional int32, `big` int64,
  `maybeBig` optional int64, `ratio` double, `maybeRatio` optional double, `amount` decimal,
  `at` instant, `day` date, `timeout` duration, `uuid` UUID, `blob` bytes (base64), `anything`
  any JSON, `colour` `Colour`, `price` `Money`.
- `Ids`: `client`, `principal`, `application`, `correlation`: the framework's id value types.
- `Line`: `sku` string, `quantity` int32. Its validator: `sku` not blank, `quantity` 1 to 99.
- `Collections`: `tags` list of string, `counts` collection of int32, `colours` set of `Colour`,
  `totals` map of int64, `lines` list of `Line`, `groups` map of list of string.
- `Shape`: a sealed union with discriminator property `kind`: `circle` = `ShapeCircle` (`radius`
  double), `square` = `ShapeSquare` (`side` double, `label` optional string). Declared with
  `square` first; the document sorts by name.
- `CursorPage<T>`: `items` list of `T`, `hasMore` boolean, `nextCursor` optional string; used as
  `CursorPage<Widget>` (component `CursorPageWidget`).
- `Widget`: `id`, `name` strings, `shape` `Shape`, `scalars`, `ids`, `collections`, `note`
  optional string.
- `WidgetBody` with its validator, one field per rule: `name` notNull, notBlank, maxLength 100;
  `code` minLength 3, maxLength 12, pattern `[A-Z][A-Z0-9-]*`; `note` (optional) maxLength 500;
  `quantity` int32 range 1 to 1000; `ratio` double range 0.5 to 2.5; `tags` list of string, size 1
  to 5, each maxLength 20; `colour` string oneOf `red`, `green`; `shape` `Shape`; `line` `Line`
  (nested validator); `lines` list of `Line` (each valid); `free` string with a custom rule (documents
  nothing).
- `NoteBody`: `note` optional string, maxLength 500. `Pong`: `at` instant.

Operations:

| id | Method and path | Parameters | Body | Success | Other |
|---|---|---|---|---|---|
| `listWidgets` | `GET /api/widgets` | query `colour` optional `Colour`, `limit` int32 default 20, `cursor` optional string, `status` repeated `Colour`, `includeArchived` boolean default false | | 200 `CursorPage<Widget>` | public; summary `List widgets`; tag `widgets` |
| `getWidget` | `GET /api/widgets/{id}` | path `id` string | | 200 `Widget` | context; error 404 `WIDGET_NOT_FOUND` "no widget has this id"; summary `Read a widget`; tag `widgets` |
| `createWidget` | `POST /api/widgets` | | `WidgetBody` | 201 `Widget` | permission `fixture:widget:manage`; errors 409 `NAME_TAKEN` "a widget has this name", 409 `CODE_TAKEN` "a widget has this code"; summary `Create a widget`; tag `widgets` |
| `replaceWidget` | `PUT /api/widgets/{id}` | path `id` | `WidgetBody` | 200 `Widget` | permission as above; tag `widgets` |
| `noteWidget` | `PATCH /api/widgets/{id}/note` | path `id` | `NoteBody` | 202 `Widget` | permission as above; tag `widgets` |
| `deleteWidget` | `DELETE /api/widgets/{id}` | path `id` | | 204 | permission as above; tag `widgets` |
| `getWidgetShape` | `GET /api/widgets/{id}/shapes/{index}` | path `id` string, `index` int32; query `after` int64 (required) | | 200 `Shape` | context; tag `shapes` |
| `ping` | `POST /api/ping` | | | 200 `Pong` | public; summary `Answer at once`; description `A public operation without a body or parameters: only the default response.` |
| `getWidgetByColour` | `GET /api/colours/{colour}/{version}` | path `version` int64 and `colour` `Colour` (declared in that order; documented in pattern order) | | 200 `Widget` | context |
