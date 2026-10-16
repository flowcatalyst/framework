<!-- types: AppBuilder, AuthModule, AuthDatabaseModule, AuthContextResolver, ContextResolver, PermissionMapper, ClientSelector, LoginFailureHandler, Operation, OperationRequest, Outcome, PathParam, QueryParam, Validator, ValidationException, FieldError, OpenApi, OpenApiTesting, SecurityScheme, Spa, Csp, SpaTesting, Routes, RouteOptions, TestClient, TestOidcProvider, AuthTesting, PostgresFixture, PlatformTokens, SecretsModule, SecretStores, Secret, JettyHttpModule, DatabaseModule, MigrationSet -->
# Migrating the ingest control plane

The first application built on the framework is `inhance/mono-repo`: `apps/ingest-runtime` (a
Camel-based ingestor) and `apps/ingest-control-plane`, which holds the ingestions, tells runtimes
which are active and records what they report. This page is the migration guide for the control
plane's **operator side**: the `/api/v1/ingestions` API and the pages an operator uses. It takes the
control plane from plain routes, a hand-written `AdminAuth`, its own token library and a placeholder
`GET /` page to typed operations with a committed OpenAPI document, a `web/` single-page application
on `@flowcatalyst/ui`, sessions from `framework-auth`, and the application served under the strict
Content Security Policy. It is milestone **MC** of the [plan](../implementation-plan.md).

It is written as steps. Each step says what to do, where it lives in the control plane, what the
tree holds today, and **how to check it**. The framework side of each topic has its own page; this
one only says how they fit together here ([Typed operations and OpenAPI](openapi.md), [Sessions and
login](sessions-and-login.md), [Static assets and the SPA](static-assets-and-spa.md), [Migrating
configuration to code](migrating-config-to-code.md), [Migrating from
flowcatalyst-token](migrating-from-flowcatalyst-token.md)).

## What was found (2026-10-16)

Read before the steps: **the control plane's tree already contains almost all of this migration.**
`inhance/mono-repo` has one commit (`c3bdfa5`, "Initial commit"), so the before-and-after cannot be
shown as a diff. The "before" in this page is taken from the earlier migration page (the fixed
bearer, the basic login, `AdminAuth` as a route middleware, `admin-api.flowcatalyst.*` settings, a
placeholder page, `libs/java/flowcatalyst-token`); the "after" is what the files hold now. The
handover and the plan (row MC) still describe the control plane as serving a placeholder page; that
is out of date.

Read from the tree, without building or running anything there:

| Step | Subject | Tree today |
|---|---|---|
| 1 | Dependencies and modules | **Done** |
| 2 | `flowcatalyst-token` retired | **Done** (no reference to the library or its types remains; only a `FlowCatalystToken` auth variant in the ingest contract, which is a contract word) |
| 3 | Configuration and the secrets store | **Done**; the `secrets` section is gone from the runtime, and the control plane never had a moved key |
| 4 | Sessions, platform tokens, permissions | **Done**, with the control plane's two own credentials kept (step 14 recommends retiring them) |
| 5 | The operator API as typed operations | **Done** (five operations in `IngestionsApi`, handlers in `AdminApi`) |
| 6 | The committed OpenAPI document | **Done** (`web/openapi.json`, `OpenApiDocumentTest`); the generated client has **no drift check** |
| 7 | The `web/` workspace | **Done** |
| 8 | Pages | **Done**, except that the source is edited as JSON (not a form per kind) |
| 9 | SPA served under the strict CSP | **Done** (`WebModule`, `WebModuleTest`); since 2026-10-16 the hash comes from the build's `csp-hashes.json`: delete the copied constant (see below) |
| 10 | Sign-in pages and the failure handler | **Done** |
| 11 | Java tests | **Done** |
| 12 | Playwright end-to-end tests | **Not started** |
| 13 | CI order | **Not started** (the mono-repo has no pipeline) |
| 14 | Retire the fixed bearer and the basic login | **Open owner decision** |
| 15 | One run against the real platform | **Not done** |

So the page reads two ways. A newcomer can follow the steps to see how each piece is built and what
proves it. The owner can read steps 12 to 15 and the lists at the end for what is left.

## 1. Dependencies and modules

**Do.** The control plane's `pom.xml` lists, with versions from `framework-bom` (imported by the
mono-repo's root `pom.xml`): `framework-auth`, `framework-auth-database`, `framework-http-jetty`,
`framework-database`, `framework-usecase` (only for `Jdbc` and `Row`, which `IngestionStore` uses;
the control plane does not run use cases), and in test scope the `framework-database` and
`framework-auth` test-jars and Testcontainers. The framework is not published: install it first with
`./mvnw -q -DskipTests install` in `flowcatalyst-framework` ([Getting
started](getting-started.md)).

**Where.** `apps/ingest-control-plane/pom.xml`, `ControlPlane.builder()`:

```java
App.builder()
    .module(new DatabaseModule())
    .module(new AuthDatabaseModule())      // sessions in fw_session
    .module(AuthModule.builder()...build())
    .module(new IngestionsModule())        // tables, runtime API, the operator operations
    .module(new WebModule())               // the SPA
    .module(new JettyHttpModule())         // last: the port opens after every other module started
```

**Check.** `./mvnw -o -q -pl apps/ingest-control-plane test -Dtest=ControlPlaneTest` starts it
against a real PostgreSQL (Docker).

### What the control plane still does by hand

These are deliberate and stay:

- **The runtime API** (`/runtime/v1/poll`, `/reports`, `/state/{ingestion}/{key}`): plain `Routes`
  with `RuntimeApi`, a `Validator` per body and `RuntimeToken`, a `Middleware` that compares one
  shared bearer token (`runtime-api.token`) with `Secret.matches`. It is machine-to-machine, between
  two services that share a secret, and is **not** in the OpenAPI document. Moving it to typed
  operations is possible (the framework has no objection) but gains nothing for the pages; see the
  decisions at the end.
- **`IngestionStore`**: SQL through `Jdbc`, with the optimistic-lock `version` and the lease logic.
  The operator's writes do **not** go through the use-case runner, so they write no event and no
  audit row (decision 4).
- **`AdminAuth`**: the fixed bearer token and the basic login (step 4 and step 14).

## 2. Retire `flowcatalyst-token`

**Do.** Replace the library `libs/java/flowcatalyst-token` (`TokenVerifier`, `VerifiedToken`,
`InvalidTokenException`, `FakeIssuer`) with `framework-auth` in both applications, using the table
in [Migrating from flowcatalyst-token](migrating-from-flowcatalyst-token.md). Delete the library and
its `<module>` line in the root `pom.xml`.

**Tree today.** `libs/java` holds only `ingest-contract`; the root `pom.xml` lists three modules
(`ingest-contract`, `ingest-runtime`, `ingest-control-plane`). The runtime's `HttpIngest` switches on
`TokenResult` (`Valid`, `Invalid`, `Unavailable`) and its `HttpIngestTest` uses `TestOidcProvider`.
The control plane's `AdminApiTest` uses two `TestOidcProvider`s (the second plays the stranger whose
tokens fail on `iss`).

**Check.** `grep -rIl "flowcatalyst-token\|TokenVerifier\|FakeIssuer"` over the repository (outside
`node_modules`, `target`, `.git`) finds only the contract's `HttpAuth` variant name and the
generated client built from it. The runtime's and the control plane's tests pass.

## 3. Configuration and the secrets store

**The rule.** Settings of the service and of connections are configuration; things an application
can have many of (lock stores, secret stores, named HTTP clients, rate limiters) are code
([Migrating configuration to code](migrating-config-to-code.md)).

**The control plane's keys** (`apps/ingest-control-plane/config/application.yaml`, the `Setting`s of
`IngestionsModule`, and what `AuthModule` and `JettyHttpModule` read):

| Key | Environment variable | Kind | Notes |
|---|---|---|---|
| `http.port` | `HTTP_PORT` | configuration | 8090 |
| `database.default`, `database.instances.main.{url,username,password,pool,migrate}` | `DATABASE_INSTANCES_MAIN_*` | configuration | no `driver` key (it was removed in the config-to-code change; a leftover one fails start-up) |
| `auth.issuer` | `AUTH_ISSUER` | configuration | replaces `admin-api.flowcatalyst.issuer`; also the browser-facing URL |
| `auth.public-base-url` | `AUTH_PUBLIC_BASE_URL` | configuration | the origin as a browser sees it |
| `auth.client-id` | `AUTH_CLIENT_ID` | configuration, **required** | the control plane does not start without it |
| `auth.client-secret` | `AUTH_CLIENT_SECRET` | secret | only for a confidential login client |
| `runtime-api.token` | `RUNTIME_API_TOKEN` | secret, required | shared with the runtime's `INGEST_CONTROL_PLANE_TOKEN` |
| `runtime-api.lease` | `RUNTIME_API_LEASE` | configuration | default 3 minutes |
| `admin-api.token` | `ADMIN_API_TOKEN` | secret, optional | the fixed bearer; unset turns it off |
| `admin-api.basic.username`, `admin-api.basic.password` | `ADMIN_API_BASIC_USERNAME`, `ADMIN_API_BASIC_PASSWORD` | optional | the basic login; unset turns it off |

Gone: `admin-api.flowcatalyst.issuer` (became `auth.issuer`) and `admin-api.flowcatalyst.scope` (the
permission is the constant `AdminAuth.MANAGE`, so the start-up warning that "any access token may
change ingestions" cannot occur). Nothing in the control plane's configuration moved to code: it has
no `locks`, `secrets`, `http-clients` or `rate-limits` section, and no instance `driver`.

**The secrets-store edit** belongs to the runtime, which resolves per-client secrets for
ingestions. `new SecretsModule()` no longer exists; `IngestRuntime.builder()` lists

```java
.module(SecretsModule.builder().store("env", SecretStores.env("INGEST_SECRET_")).build())
```

and `apps/ingest-runtime/config/application.yaml` has no `secrets:` section (it says so in a comment).
Client `acme`'s `sftp-password` is still `INGEST_SECRET_ACME__SFTP_PASSWORD`. A directory or prefix
that must differ per deployment becomes a setting of the runtime's own, read where the store is
built ([Per-client secrets](secrets.md#setting-it-up)).

**Check.** Start the control plane with a deliberately stale key (`secrets:` or `driver: postgresql`
in a file): start-up must fail naming the key and the code that replaces it. Start it with a
misspelt `auth.isuer`: start-up fails as unknown. `grep -n "secrets:" apps/ingest-runtime/config/*.yaml`
finds only comments.

## 4. Sessions, platform tokens and permissions

**Target.** One `Context` per request, whichever way the caller proved itself:

| Way in | Who | How it maps |
|---|---|---|
| A **browser session** | a person holding the platform role `ingestor:operator`, signed in at `/auth/login` | `AuthModule`; the role becomes the permission through `PermissionMapper.byRole` |
| A **platform access token** (client credentials) | a service account, scope `ingestor:admin:ingestion:manage` | `AuthModule`'s bearer path; the scope becomes the permission through `PermissionMapper.scopes()` |
| The **fixed bearer token** | an operator tool holding `ADMIN_API_TOKEN` | `AdminAuth` (the control plane's own `ContextResolver`) |
| The **basic login** | a person or script holding the shared user and password | `AdminAuth` |

**Where.** `ControlPlane.builder()`:

```java
AuthModule.builder()
    .application(new ApplicationCode("ingestor"))
    .postLogoutRedirect("/signed-out")
    .loginFailure(LoginFailureHandler.redirectTo("/sign-in-failed"))
    .permissions(PermissionMapper.scopes()
        .and(PermissionMapper.byRole(Map.of("ingestor:operator", Set.of(AdminAuth.MANAGE)))))
    .provideContextResolver(false)   // IngestionsModule provides it: its own credentials come first
    .build()
```

`IngestionsModule.register` provides the `ContextResolver`: an `AdminAuth` that checks the two own
credentials and hands everything else to `lookup.get(AuthContextResolver.class)`. This is the
pattern of [Sessions and login: your own resolver](sessions-and-login.md#your-own-resolver). The
own-credential `Context` is `Context.of(ClientSelector.PLATFORM)`, principal `operator`, permissions
`{ingestor:admin:ingestion:manage}`.

Things that follow from this:

- A session has **no permission until the role is mapped**; the mapping above is what lets
  `ingestor:operator` do anything. A person without the role signs in and sees "Not permitted".
- A platform token's invalid signature or wrong issuer is `401`, never a fall-through to the cookie;
  an unreachable platform is `503 UNAVAILABLE`, not `401`.
- A request authenticated by the **session cookie** must be same-origin (`403 CROSS_ORIGIN_REQUEST`)
  and its body JSON (`415`); the page's own `fetch` satisfies both. There is no CSRF token to send.
- A session keeps no platform token and cannot refresh: a role removed at the platform takes effect
  at the next sign-in, at most the absolute timeout (8 hours by default) later.
- `AdminAuth` returns one fixed principal, `operator`, for both own credentials. Nothing identifies
  *which* person or tool acted. This matters only if the operator's writes ever record an actor
  (decision 4).

**The platform side** (an anchor administrator; nothing in the code proves it): register a login
client for the control plane (`POST /api/applications/{id}/provision-login-client`) with redirect URI
`<auth.public-base-url>/auth/callback` and post-logout redirect `<auth.public-base-url>/signed-out`,
and create the role `ingestor:operator` on the `ingestor` application. Plain `http` is accepted only
while `APP_ENV` is `local` or `test`.

**Check.** `AdminApiTest.eachConfiguredCredentialIsAcceptedAndNothingElse` (fixed bearer, basic, a
platform token with the scope: `200`; no credential, a wrong bearer, a wrong password, a token
signed by someone else's key: `401`; a token without the scope: `403`; the runtime's token on the
operator API and the operator's on the runtime API: `401`) and
`aSignedInOperatorMayManageIngestionsFromTheControlPlanesOwnPages` (role holder `200`, another role
`403`, same-origin write `201`, cross-site write `403`). Run with `APP_ENV=test` (the stand-in
platform is plain `http`, and `AuthTesting` works only there).

## 5. The operator API as typed operations

**Target.** Each endpoint is an `Operation` value: method, path, typed `PathParam` and `QueryParam`,
the body's `Validator`, the permission, the declared errors. The handler receives an
`OperationRequest` and returns an `Outcome`.

**Where.** `IngestionsApi` declares five operations, `AdminApi` implements them,
`IngestionsModule` declares them in `register` (`OpenApi.declare`) and binds them in `boot`
(`routes.bind`):

| Operation id | Method and path | Params | Body | Permission | Declared errors |
|---|---|---|---|---|---|
| `listIngestions` | `GET /api/v1/ingestions` | query `clientId` (optional) | | `ingestor:admin:ingestion:manage` | |
| `getIngestion` | `GET /api/v1/ingestions/{id}` | path `id` | | same | `404 NOT_FOUND` |
| `createIngestion` | `POST /api/v1/ingestions` (status `201`) | | `IngestionBody` | same | `409 CODE_TAKEN` |
| `changeIngestion` | `PUT /api/v1/ingestions/{id}` | path `id` | `IngestionBody` with `version` | same | `404 NOT_FOUND`, `409 CODE_TAKEN`, `409 STALE` |
| `deleteIngestion` | `DELETE /api/v1/ingestions/{id}` (`204`) | path `id` | | same | `404 NOT_FOUND` |

What the framework adds to those without being asked: `400` (body or parameter), `401` and `403`
(an operation with a permission needs a context), `413` and `415` (operations with a body), and a
`default` response, all pointing at the one `Error` envelope.

Points specific to this API:

- **Two layers of validation.** `Validator.of(IngestionBody.class)` gives the document the shape
  constraints. `IngestionRules.check` applies the rules that depend on the source's kind
  (hosts, directories, regions, queue names, poll intervals, table and column names, the split
  against the source) and returns every problem as a `FieldError` with a dotted path
  (`target.code`, `source.host`); `AdminApi.checked` throws `ValidationException` with all of them,
  so the page can mark each field. Those rules are not in the document; the server has the last
  word. The README says the same: the body is checked for shape only, and the control plane does
  not ask the platform whether the client or the dispatch pool exist.
- **The sealed types.** `Source`, `Split`, `HttpAuth` and `DispatchTarget` are the ingest
  contract's (`libs/java/ingest-contract`) sealed interfaces with Jackson type names. The emitter
  turns them into `oneOf` with a `discriminator` (`kind`), so the generated TypeScript narrows on
  `source.kind`. `IngestionBody.version` is `@Nullable`: "may be absent"; required on `PUT`
  (`AdminApi.change` refuses a null `version` with a field error) and ignored on `POST`.
- **Optimistic locking.** `IngestionStore.update(id, body, version)` returns `DONE`, `STALE`,
  `MISSING` or `TAKEN`; `STALE` is `409 STALE` ("read it again"). `409 CODE_TAKEN` is the unique
  `(client, code)` rule.
- **Nulls.** Typed operations leave `null` out of their JSON (an owner ruling), so optional fields
  such as `routes[].error` are absent rather than `null`; the contract's own records keep Jackson's
  behaviour on the runtime API, which is unchanged.

**Check.** `AdminApiTest`: `aCreatedIngestionIsGivenToRuntimesAndItsCodeIsUniqueWithinTheClient`,
`anIngestionThatCouldNotRunIsRefusedWithEveryProblemNamed`,
`aChangeRaisesTheVersionAndIsRefusedWhenMadeAgainstAnOlderOne`,
`aDeactivatedOrRemovedIngestionIsNoLongerGivenToRuntimes`,
`anIngestionIsReadWithWhatEachRuntimeLastReported`. A wire check by hand for anyone leaving the
old routes: compare the answers against the table above (`201` on create, `204` on delete, `409
STALE`, the error envelope's `code`).

## 6. The committed OpenAPI 3.1 document

**Do.** Name the application (`OpenApi.info(app, "ingest-control-plane", "1")`), add the one
security scheme the framework cannot know (`OpenApi.securityScheme(app, "basic",
SecurityScheme.httpBasic())`; `AuthModule` contributes `session` and `bearer`), write the document
with the declaration-phase command, commit it, and fail the build when it is stale.

```sh
java -jar ingest-control-plane.jar openapi --out web/openapi.json   # no database, no configuration
./mvnw -o -q -pl apps/ingest-control-plane test -Dtest=OpenApiDocumentTest -Dopenapi.update=true
```

**Where.** `web/openapi.json` (paths: the five operations plus the framework's `getAuthSession` and
`logout`; schemas: `IngestionBody`, `IngestionView`, `IngestionList`, `Source` with six variants,
`Split` with four, `HttpAuth`, `DispatchTarget`, `SecretRef`, `Error`, the `Auth*` components),
`OpenApiDocumentTest` (`OpenApiTesting.assertCommitted(ControlPlane.builder(), Path.of("web/openapi.json"))`).

**Check.** `OpenApiDocumentTest` fails with a line diff when an operation or a record it names
changes without the document. Then regenerate the client (step 7) and commit both.

**Known imprecision.** Every operator operation lists all three schemes (`session`, `bearer`,
`basic`) because an operation cannot name a subset (gap 4). `bearer` is described as JWT; the fixed
token is not one. The document is right about *what the API accepts*, loose about *which operation
accepts which*.

## 7. The `web/` workspace

**Target.** The pattern of `ui/apps/orders` in this repository ([U3 results](../ui/u3.md)), living
in the application's own `web/` and linking the shared packages by path.

| File | Role |
|---|---|
| `web/package.json` | scripts `dev` (Vite on 5176), `build`, `typecheck` (`tsc` 7 plus `vue-tsc`), `generate` (`openapi-ts`); `@flowcatalyst/ui` and `@flowcatalyst/api-runtime` as `link:` paths to a framework checkout; Node 24, pnpm 10.27 |
| `web/pnpm-workspace.yaml` | pnpm's settings only (`packages: []`): release-age gate, no install scripts, TypeScript 7 as the compiler and 6.0.x behind the compatibility package for `vue-tsc` and hey-api, copied from the framework's `ui/pnpm-workspace.yaml` |
| `web/vite.config.ts` | `vue()`, `@nuxt/ui/vite` with `nuxtUiOptions({ icons })`, `flowcatalystUi(options)`; `outDir: "dist"`, `sourcemap: false`; dev proxy for `/api` and `/auth` to the backend (`INGEST_BACKEND`, default `http://127.0.0.1:8090`) |
| `web/openapi-ts.config.ts` | `fwOpenApiConfig({ input: "openapi.json", output: "src/api/ingest" })`, which carries the contract's hey-api settings (`baseUrl: false`, `int64` kept a number) |
| `web/src/api/ingest/` | the generated client (`sdk.gen.ts`, `types.gen.ts`, `zod.gen.ts`, `client.gen.ts`), **committed** |
| `web/.gitignore` | `node_modules/`, `dist/` |

The `link:` paths read `../../../../../flowcatalyst-framework/ui/packages/...`: five levels up from
`web/` is the directory that holds `inhance/` and `flowcatalyst-framework/` side by side (the
mono-repo's own README says "beside this repository", which is one level off: it is beside
`inhance/`). The packages export their TypeScript and Vue source and the consumer's Vite compiles
them; until they are published to GitHub Packages (owner ruling, 2026-10-14), CI checks the
framework out at a pinned commit.

**Development loop.** `pnpm --dir web dev`, with the control plane started with
`AUTH_PUBLIC_BASE_URL=http://localhost:5176` so the cookie and the sign-in redirect are the
page's origin. Never point it at port 8080 (the platform) in a script that writes.

**Check.** `pnpm --dir web install --frozen-lockfile && pnpm --dir web typecheck && pnpm --dir web
build` succeeds and writes `web/dist/index.html` with hashed assets under `web/dist/assets/`.

## 8. The pages

All pages are in `web/src/pages`, wired by `web/src/router.ts`, `web/src/pages.ts` (the sidebar's
data, shared with the Vite config so icons are found) and `web/src/App.vue` (`FwAppShell` for the
signed-in pages, bare pages for the public ones).

| Page | File | What it does | Built from |
|---|---|---|---|
| Ingestions list, route `/ingestions` (`/` redirects) | `IngestionsPage.vue` | Code, name, client, source kind and summary, State (Active/Paused), Runtimes (worst route status). A **client filter** the server applies (`?clientId=`, debounced), a **search** of the loaded rows, refresh, clear filters, `New ingestion`. Phone: code, name and state only. Errors toast and say "Could not load the ingestions" | `FwListPage`, `useListState`, `stringFilter`, `FwStatusBadge`, `listIngestions` |
| Record drawer, route `/ingestions/:id` | `IngestionDrawer.vue` | Child route, so a deep link works and Back closes it, the list's query is kept. Sections: Ingestion (id, client, version, created, changed), **Runtimes** (what each runtime last reported: id, status, the version it built shown in warning colour when it is not the current one, when, and the error), Source (kind, where, the JSON as written), Split and dispatch, Actions: **Pause or Resume** (a change of `active` against the version read), **Remove…** | `FwRecordDrawer`, `useDrawerRoute`, `FwDetailList`, `FwJsonBlock`, `FwLocalTime`, `getIngestion` |
| Create and change form | `IngestionForm.vue` | A dialog for both. Fields named as the API names them (`target.code`, `split.header`) so a `400`'s field errors land on the field. `version` is sent with a change. **409 handling**: `STALE` is shown in the dialog (and the drawer re-reads); `CODE_TAKEN` on the `code` field. The **source is JSON**, started from a template per kind, parsed and checked against the generated `zSource` schema before it is sent | `FwFormDialog`, `useDirtyForm`, `createIngestion`, `changeIngestion`, `zIngestionBody` |
| Delete confirm | in `IngestionDrawer.vue` | `useConfirm`: "Remove code?", says runtimes stop it on their next poll and watermarks are removed with it; `Keep it` is the safe answer; `404` and other failures toast | `useConfirm`, `deleteIngestion` |
| Signed out, Sign-in failed, Unavailable | `router.ts` mounts the kit's pages | `/signed-out` (the post-logout redirect), `/sign-in-failed?error=CODE`, `/unavailable` (when `/auth/session` cannot be read) | `FwSignedOutPage`, `FwSignInFailedPage`, `FwUnavailablePage` |
| Not permitted, Not found | `NotPermittedPage.vue`, `NotFoundPage.vue` | A signed-in person without the permission is sent to Not permitted (route `meta.permission`), with Sign out | `useSession` |

`web/src/main.ts` is the whole runtime wiring: `createApiRuntime({ clients: [client], signedOutUrl:
"/signed-out" })` (same origin, the cookie is the only credential, a `401` starts sign-in, a `403`,
a `5xx` or an offline call is a toast; no `auth` callback, no CSRF header) and
`installSessionGuard(router, api.session, { notPermitted, onUnavailable })` (the session is read at
start and when the window regains focus, not on a timer).

**The one thing the pages do not do yet**: the form edits the source as JSON. Six kinds (`sftp`,
`http`, `rabbitmq`, `sqs`, `s3`, `database-poll`), each with its own credentials and options, and
two further levels (`track`, `HttpAuth`), are a discriminated-union form, and the shared UI package
has no building block for one (gap 2). The server checks the source in full either way. The
decision is whether the JSON editor is enough for operators (decision 2).

**Check.** `pnpm --dir web typecheck`; the Playwright run of step 12 is the real check. By hand:
sign in, create an `sftp` ingestion from its template, see it in the list, open it, pause it, save a
change with a stale version (change it in a second tab first) and read the message, remove it.

## 9. The SPA served under the strict CSP

**Target.** `Spa` is the router's fallback: every path that is not an API route is the application's
(client routes `/ingestions`, `/signed-out`, `/not-permitted` share **no first segment** with a
server route: `/api`, `/auth`, `/runtime`, `/health`), API prefixes keep the JSON `404`, missing
assets are `404`, `index.html` is `no-cache` with an ETag, hashed assets are `immutable`.

**Where.** `WebModule`:

```java
Spa.classpath("fw-spa").build()
```

mounted in `boot` with `app.get(Routes.class).forModule(ID).spa(spa())`, and the pom copies
`web/dist` into the jar under `fw-spa` (Listing `<resources>` replaces Maven's default, so
`src/main/resources` is listed too; Maven never runs Node).

**The strict-CSP default changed on 2026-10-15 (BREAKING, see the [handover](../handover.md)).**
`Spa...build()` with no policy now sends `Csp.strict()`: scripts, styles, images, fonts and
connections from the application's own origin only, no framing, `base-uri 'none'`, plus
`Cross-Origin-Opener-Policy` and `Permissions-Policy`. The one inline script in a page built with
`flowcatalystUi()` is the pre-paint colour-mode snippet. Since 2026-10-16 its hash is not passed by
hand: the Vite build writes `csp-hashes.json` beside `index.html` and `Spa` reads it (an explicit
`contentSecurityPolicy(Csp.strict(hash))` still works and replaces the file, so the application's
existing `COLOR_MODE_SNIPPET_HASH` can be deleted at leisure). Keep
`WebModuleTest.thePolicyAllowsExactlyTheInlineScriptTheBuildContains` (or call
`SpaTesting.assertServesBuild`, which now checks every inline script against the served policy):
it fails when the snippet and the policy disagree. An interim server-rendered page with
inline scripts would need `Csp.baseline()` instead; the pages here need neither.

**Without a build.** The jar starts only with `APP_ENV` `local` or `test` (every SPA request then
answers a `404` "Frontend not built"); in any other environment start-up fails. That is the safety
net, and it is why step 13 matters.

**Check.** `WebModuleTest` (`theBuildIsServedWithTheContractsHeaders` via
`SpaTesting.assertServesBuild`, the one-hash test, and `aPageIsTheShellAndTheApisKeepTheirJson404`).
**These tests are skipped, not failed, when `web/dist` is absent** (`assumeTrue`), so a pipeline that
forgets the web build still goes green; step 13 closes that.

## 10. Sign-in pages and the failure handler

`AuthModule` renders no login page: the platform hosts the login form. The control plane sends
people to its own pages for the two edges:

- `.postLogoutRedirect("/signed-out")`: the post-logout redirect (register the same URL on the
  platform's login client).
- `.loginFailure(LoginFailureHandler.redirectTo("/sign-in-failed"))`: a failed sign-in answers `302
  /sign-in-failed?error=<CODE>`. The codes are `INVALID_CALLBACK`, `LOGIN_NOT_STARTED_HERE`,
  `INVALID_STATE`, `LOGIN_REFUSED`, `LOGIN_FAILED`, `INVALID_ID_TOKEN`, `PLATFORM_UNAVAILABLE`, and
  since 2026-10-15 `RATE_LIMITED` (a rate-limited or unreachable-platform `/auth/login`, for a
  browser navigation only; the page's own `fetch` still gets the JSON `429` or `502`).
  `FwSignInFailedPage` has a sentence for each of the eight.

`/auth/login` and `/auth/callback` are rate limited per address by default (20 a minute). A
control plane behind a proxy shares one address across everyone: key the limiter by the forwarded
address (`KeyResolvers.forwardedFor`) or the limit will trip for a whole team ([Sessions and
login](sessions-and-login.md#rate-limits)).

**Check.** Visit `/ingestions` signed out: the page loads, the first call is `401`, the guard sends
the browser to `/auth/login?returnTo=/ingestions`, the provider signs in, and the browser lands on
the deep link. Refuse the sign-in at the provider: `/sign-in-failed?error=LOGIN_REFUSED` with its
sentence. Both are Playwright cases in step 12.

## 11. Java tests

| Test | Proves |
|---|---|
| `AdminApiTest` (real PostgreSQL, `TestOidcProvider` x2, `AuthTesting`) | every credential, the operator API's behaviour end to end, the runtime API's state calls, route reports in the read |
| `ControlPlaneTest` | the runtime API: poll, leases, reports |
| `OpenApiDocumentTest` | the committed document is the one the operations give |
| `WebModuleTest` | the jar's SPA, its headers, the one-hash policy, the API 404s |

**Conformance vectors.** The control plane owns none; the framework's vectors
(`conformance/openapi/`, `conformance/http/spa.json`, `conformance/auth/login-failure.json`) are run
by the framework's own tests against the same classes the control plane uses. The control plane's
equivalents are `OpenApiTesting.assertCommitted` and `SpaTesting.assertServesBuild`, which are the
same checks aimed at its own application. No vector work is needed in the mono-repo.

**Command.** `./mvnw verify` needs Docker (PostgreSQL, SFTP, RabbitMQ, a local AWS stand-in).

## 12. Playwright end-to-end tests against the real backend  (not started)

**Why.** Everything above is proved in Java and by type checks. Nothing yet proves that the built
page runs in a browser under the strict policy, that the sign-in round trip works, or that the drawer
and the 409 path behave. The orders application does it with 28 tests; the control plane gets the
same harness.

**Do.** Copy the pattern of `ui/apps/orders/e2e`, `ui/apps/orders/playwright.config.ts`,
`ui/apps/orders/scripts/start-backend.ts` and `examples/orders-demo`'s `OrdersE2e`. Nothing is
published for it (gap 1), so it is copied and renamed:

1. `src/test/java/com/inhance/ingest/controlplane/ControlPlaneE2e.java`: starts
   `ControlPlane.builder()` with `configDirectory(Path.of("config"))` and an environment map (as
   `AdminApiTest.start` builds one, plus `AUTH_CLIENT_ID`, `AUTH_PUBLIC_BASE_URL` set to the origin the
   browser sees, `HTTP_PORT`), against `PostgresFixture` (Testcontainers) and a `TestOidcProvider`
   with `provider.client("ingest-control-plane", "", origin + "/auth/callback")` and
   `provider.discoveryChanges(Map.of("end_session_endpoint", origin + "/signed-out"))`. A small
   control server on a second port changes who signs in (`/control/sign-in-as?roles=`), empties the
   tables (`TRUNCATE ingestions, ingestion_leases, runtimes, ingestion_state,
   ingestion_route_states`), seeds route reports, ends sessions and takes the provider down.
2. `web/playwright.config.ts` (one worker, Chromium, a `webServer` that runs the starter) and
   `web/scripts/start-backend.ts` (Maven `test-compile` for the module and what it depends on, build
   the classpath, `java ... ControlPlaneE2e`). **Ports: 4492 and 4493**, chosen not to collide with
   the framework's 4392/4393 or the Vite ports; never 8090 (a developer's control plane), 8080 or
   15432.
3. `web/e2e/fixtures.ts`: the CSP monitor (no `securitypolicyviolation`, no CSP console message, no
   request to another origin, no uncaught page error) on every test, and `signInAs(...roles)`.
4. `package.json`: add `@playwright/test` and `@axe-core/playwright`, a script `e2e` (`vite build &&
   playwright test`).

**Cases** (each runs the CSP monitor):

| Area | Case |
|---|---|
| Sign-in | anonymous visit to `/ingestions/ing_x` ends on that deep link after the round trip; sign out lands on `/signed-out`; refused sign-in lands on `/sign-in-failed` with the right sentence; a rate-limited sign-in navigation gives `RATE_LIMITED`; the platform down gives `/unavailable` |
| Permissions | a signed-in user with no role sees Not permitted; a role without the mapping gets the server's `403` on a direct call; an ended session is noticed by the next call and by window focus |
| List | seeded ingestions appear; the client filter narrows by the server (`?clientId=`); the search narrows loaded rows; phone width drops the columns and does not scroll sideways |
| Drawer | deep link opens it; Back closes it; **runtime reports** (seeded: one STARTED at the current version, one FAILED with an error, one at an older version in warning colour); a not-yet-reported ingestion says so |
| Create | the template for each of the six kinds posts and shows in the list; a `400` marks the field it names (`code` upper case, `target.code` malformed, an `sftp` host with a space); invalid JSON in the source is refused before sending; `409 CODE_TAKEN` lands on the code field |
| Change | a change raises the version; **stale**: open the drawer, change the same ingestion through the API, save in the page: the dialog shows the `STALE` message and the drawer re-reads |
| Pause or resume | flips the badge and is a change against the version read |
| Delete | the confirm names the code; `Keep it` is focused; confirming sends `DELETE`, closes the drawer, removes the row; a second tab's delete then gives `404` as a toast |
| Serving | a deep link returns the shell; `/api/nothing` is JSON `404`; a hashed asset is `immutable`; a stale chunk is `404`, never the shell |
| Accessibility | axe at WCAG 2.2 AA, zero violations: list (empty and filled), drawer, form with errors, confirm, Not permitted, signed out, in light and dark, at desktop and 390 px |

**Check.** `pnpm --dir web e2e` green from a clean checkout; `pnpm --dir web build` first (the starter
needs `web/dist` for Maven to copy). Not run in Firefox or WebKit (nor are the framework's).

## 13. CI order

The mono-repo has no pipeline today. The order that makes every guard run:

1. Check out the mono-repo **and** the framework (pinned commit or tag, beside `inhance/`, as the
   `link:` paths expect), build the framework: `./mvnw -q -DskipTests install` there.
2. `pnpm --dir web install --frozen-lockfile`
3. `pnpm --dir web generate`, then fail on drift of `web/src/api/ingest` (see below), then `pnpm
   --dir web typecheck`
4. `pnpm --dir web build`, then **`test -f apps/ingest-control-plane/web/dist/index.html`**
5. `./mvnw verify` (Docker): `OpenApiDocumentTest` (document drift), `WebModuleTest`, `AdminApiTest`...
6. `pnpm --dir web e2e` (after step 12 exists)

Two additions the tree does not have yet:

- **Client drift.** `OpenApiDocumentTest` proves the *document* is current; nothing proves
  `web/src/api/ingest` was regenerated from it. The framework uses `ui/scripts/api-drift.ts`
  (run after the generator, fails on any difference from the commit, new and deleted files
  included). Since 2026-10-16 it is `fc-api-drift`, a bin of `@flowcatalyst/api-runtime` (no copy):
  `"drift": "openapi-ts && fc-api-drift src/api"` with git, or `"drift": "fc-api-drift --run \"openapi-ts\" src/api"`
  where the build has no git (it hashes the folder, runs the generator, compares).
- **The dist guard.** Step 4's `test -f` is the guard against the green-but-skipped `WebModuleTest`.

## 14. The fixed bearer and the basic login: map or retire

Both are the control plane's own. The framework does not know them and has no extension point for
extra credentials beyond a `ContextResolver` of your own, which is what `AdminAuth` is. Mapping:

| | Maps onto | Cost of keeping |
|---|---|---|
| Fixed bearer (`ADMIN_API_TOKEN`) | `AdminAuth` wraps `AuthContextResolver`; compares with `Secret.matches`; one `Context` with the permission | one secret to rotate by hand; no expiry; no identity (`operator`); anyone holding it holds `manage` for every client |
| Basic login | same; both halves compared in full | as above, plus a shared password people type, and **it sits outside the framework's CSRF guard** (the guard protects the session cookie only), so a browser that has cached the Basic login relies on preflight refusal alone; the document needs a hand-added `basic` scheme |

**Recommendation.** Retire the **basic login now**: the browser session covers people, and a service
account's token covers scripts, each with a real identity, an expiry and a revocation point at the
platform. Retire the **fixed bearer** once the operator tooling that uses it has a service account
with the scope (until then it is a break-glass credential; keep it off in production by leaving
`ADMIN_API_TOKEN` unset). Retiring both is: delete `AdminAuth` (86 lines), the five `ADMIN_*`
`Setting`s, the `ContextResolver` provider and the `basic` scheme in `IngestionsModule`, and
`.provideContextResolver(false)` in `ControlPlane`; `AuthModule` then provides the resolver.
`AdminApiTest.eachConfiguredCredentialIsAcceptedAndNothingElse` loses its first two credentials.
If the owner keeps them: no code change, but the decision should be recorded.

## 15. One run against the real platform

Nothing here has run against the FlowCatalyst platform or in a real browser against it; the sign-in
is tested against a test identity provider shaped like the platform. Once, with the platform and a
control plane on the developer's machine (the platform on 8080 is the owner's local one: this step
reads it through the browser only):

- the login client registered with both URIs; the sign-in round trip with a person holding
  `ingestor:operator`; the same person without the role sees Not permitted;
- a client-credentials token with the scope reads the list;
- a created ingestion is picked up by a running runtime (`ingest-runtime`) on its next poll, and the
  runtime's report appears in the drawer;
- with `APP_ENV` not `local`/`test`, TLS in front and `https` URLs: the `__Host-` cookies are set.

## Risks and rollback

| Risk | Why | What softens it |
|---|---|---|
| A deployment without `web/dist` | the jar starts only in `local`/`test`, refuses elsewhere | step 13's `test -f` (the web tests skip, they do not fail, without a build) |
| The login client is not registered, or its redirect URIs differ from `auth.public-base-url` | sign-in fails with `LOGIN_REFUSED` or a platform error; `auth.client-id` unset stops start-up | step 15; the sign-in-failed page names the code; platform tokens and the own credentials keep working |
| Role mapping missing or the role renamed | signed-in people see Not permitted; the API gives `403` | `PermissionMapper.byRole` is the only place; `AdminApiTest` covers it |
| Role changes lag | sessions keep no platform token | at most 8 hours (the absolute timeout); shorten with `.absoluteTimeout(...)` |
| The colour-mode hash drifts from the package | the pre-paint snippet is blocked; the page flashes the wrong colour | `WebModuleTest` fails first |
| The framework is not published and the `link:` paths are relative | an application cannot be built on a machine without the framework checkout beside `inhance/` | CI pins the framework commit; later GitHub Packages |
| The wire changed for API clients | typed operations omit nulls, answer `201`/`204`, `409 STALE`/`CODE_TAKEN`, new `403 CROSS_ORIGIN_REQUEST` and `415` for cookie calls that are not same-origin JSON | compare with the README's description of the operator API; the runtime API did not change |
| Shared `operator` principal | no actor on an operator's change | decision 4 |
| One address behind a proxy trips the sign-in limiter | 20 a minute per address by default | forwarded-address key |

**Rollback.** The steps are layered, so each is undone on its own:
the SPA is additive (remove `.module(new WebModule())` and the `<resource>`; the operator API and the
runtime API are untouched, and `GET /` becomes a JSON `404`); typed operations are bound in `boot`
by the same paths, so reverting `IngestionsApi`/`AdminApi` brings the old handlers back without a
database change; sessions add the table `fw_session` (harmless to leave; `AuthDatabaseModule` and
`migrate: true` create it); the `AuthModule` and `AdminAuth` can be reverted to
`RouteOptions.none().with(...)` as in the earlier page. Nothing in the steps changes the
`ingestions` tables or the runtime API, so a runtime never notices.

## Effort

The remaining work, in days of agent-assisted work at the framework's pace, for the control plane's
own side:

| Item | Estimate |
|---|---|
| Step 12: harness (`ControlPlaneE2e`, config, fixtures) 0.5 to 1; cases and axe 1 | 1.5 to 2 |
| Step 13: client drift script, dist guard, a pipeline file | 0.25 to 0.5 |
| Step 14: retire basic (and fixed bearer), update tests | 0.25 |
| Step 15: one real-platform run, platform setup | 0.5 |
| Per-kind source form (decision 2): in the application, six sub-forms | 2 to 3 |
| plus, on the framework side, a union-form building block (gap 2) | 1 to 1.5 |
| Writes through use cases with events and audit (decision 4) | 1 to 2 |

Steps 1 to 11 are done in the tree. The original estimate for the application's side was 3 to 5
days.

## Gaps in the framework found by this migration

"Not built" means the framework does not have it; each has a proposal.

1. **A reusable end-to-end kit.** Not built. `ui/apps/orders/e2e` (the CSP monitor, `signInAs`,
   axe helpers, `start-backend.ts`) and `OrdersE2e` (the control server) are examples, not a package;
   an application outside this repository copies them. *Proposal:* publish the Playwright fixtures as
   `@flowcatalyst/ui/e2e` and a small `E2eHarness` (provider, Testcontainers PostgreSQL, control
   server) in the `framework-auth` test-jar, with the orders demo as its first user.
2. **A form for a discriminated union.** Not built. `@flowcatalyst/ui` has `FwFormDialog`,
   `FwFormSection`, `useDirtyForm` and the generated `oneOf` + `discriminator` unions, but no
   component that picks a variant and renders its fields, nor a way to place a nested server field
   error (`source.host`) on it. *Proposal:* a `FwUnionField` in U4 driven by the generated Zod
   schema, with field errors by dotted path.
3. **The CSP hash is copied by hand.** **Fixed 2026-10-16** (U3 gap G1): the Vite plugin writes
   `csp-hashes.json` beside `index.html`, `Spa` reads it for its default policy and does not serve it,
   `SpaTesting.assertServesBuild` fails on an inline script the policy does not allow. Go does not
   read the file yet.
4. **Security schemes per operation.** Not built. An `Operation` lists every scheme the application
   has; it cannot say "session and bearer only", and `bearer` is always described as a JWT.
   *Proposal:* `Operation.Builder.security(String...)` and `SecurityScheme.bearer(format)` chosen by
   the resolving module.
5. **A pipeline recipe.** Not built. The framework has no sample workflow for an application with a
   `web/` (web build, drift, Maven, e2e) and the client drift script (`ui/scripts/api-drift.ts`) is
   not exported. *Proposal:* ship both in the Getting started page and as a `@flowcatalyst/ui` bin.
6. **Licence notices for a deployable SPA.** Open (U3 gap G14). The jar ships a bundle without
   `THIRD-PARTY-NOTICES`.
7. **Operator writes and the write path.** Not a defect, a mismatch: the use-case runner, events and
   audit rows are built for client-scoped aggregates and the outbox; the control plane's
   platform-level, per-operator writes use none of it (decision 4).
8. **The `link:` distribution.** Known: nothing is published, so the path layout is part of the
   application's build.

## Decisions for the owner

1. **Retire the basic login and the fixed bearer?** Recommended: basic now, the fixed bearer when the
   tools have service accounts (step 14).
2. **Is a JSON editor for the source enough?** If not, six per-kind forms and gap 2.
3. **Paging.** `GET /api/v1/ingestions` returns every ingestion. The list is fine for tens; decide
   the number at which it needs a cursor (`FwCursorPager` exists) and a `hasMore`, and whether the
   search should move to the server.
4. **Should operator writes be use cases?** That would add events (and so event types to sync),
   audit rows with an actor and the outbox (`OutboxModule`, `UseCaseModule`), and would need
   identities for the fixed credentials (or their retirement).
5. **One permission or two.** Today list and read also need `manage`. A read-only role
   (`ingestor:admin:ingestion:view`) is a small mapping and a second permission on two operations.
6. **Confidential or public login client** (`AUTH_CLIENT_SECRET`): public needs no secret and is the
   default.
7. **Who owns the e2e harness**: copied into the application (as above) or published by the
   framework (gap 1).
8. **Where the framework checkout lives for CI**, until the packages are published.
9. **Licence notices** for the shipped SPA (gap 6).

## Verification checklist (all steps)

- [ ] Framework installed locally; `./mvnw -o -q -pl apps/ingest-control-plane test` green (Docker).
- [ ] No reference to `flowcatalyst-token` remains; the root `pom.xml` has three modules.
- [ ] No stale key: start-up with a `secrets:` section or a `driver:` key fails naming it.
- [ ] `AdminApiTest` credential matrix and session test pass; the role is mapped.
- [ ] `OpenApiDocumentTest` passes; `pnpm --dir web generate` leaves no diff.
- [ ] `pnpm --dir web typecheck` and `build` pass; `web/dist/index.html` exists.
- [ ] `WebModuleTest` ran (not skipped) and passes; the policy holds exactly one hash.
- [ ] Sign-in, sign-out, refused sign-in and the not-permitted page work in a browser.
- [ ] Create, change (stale), pause, delete work through the pages; the drawer shows runtime reports.
- [ ] Playwright suite green with zero CSP violations and zero axe violations.
- [ ] The pipeline runs the steps of section 13 in order and fails without `web/dist`.
- [ ] One run against the real platform (step 15).
