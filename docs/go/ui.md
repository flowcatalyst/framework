# Go framework: the UI (Nuxt UI on Vue, strict TypeScript) (draft 1, 2026-10-13)

Companion to [`design.md`](design.md). Status: a design; nothing is built.

**Owner ruling (2026-10-13).** The Go framework's UI is **Nuxt UI v4 on plain Vue 3 and Vite,
without Nuxt or Nitro**, written in **strict TypeScript**. It replaces the Datastar + templ kit for
the Go framework. The SPA is built to static files, embedded in the Go binary and served by the
application next to its JSON API. The owner already uses Nuxt UI this way in another project.

**Owner ruling (2026-10-14): shared with Java.** Nuxt UI is the **primary UI of both the Java and
the Go framework**: **one backend-agnostic UI package** (`@flowcatalyst/ui`, `@flowcatalyst/api-runtime`, the gallery,
and the shared admin pages) talking to a typed JSON API, served by either backend. Decision 31 is
therefore decided (section 15). The Java JTE + Datastar stack (`framework-ui`, M-UI, the `ui-kit/`
specs' markup and fixtures) becomes an **optional module** for applications that want
server-rendered pages without a JavaScript build; lower priority, not built now. The first consumer
is the Java control plane of the first application (`inhance/mono-repo`,
`apps/ingest-control-plane`). Two contracts, written once for both backends, replace this page's
Go-only sketches: [`../contracts/static-assets-and-spa.md`](../contracts/static-assets-and-spa.md)
(section 9 here) and [`../contracts/openapi.md`](../contracts/openapi.md) (sections 6 and 7 here).
Where this page and a contract differ, the contract wins. The milestones of section 17 are now
shared and are built first against the **Java** backend (the work order is in
[`../handover.md`](../handover.md), "UI direction").

**Owner ruling (2026-10-14, later): both options in Go too.** The platform's templ `/ui` port
continues; the Go framework, like Java, offers Nuxt UI as the primary UI and an optional templ +
Datastar module later (section 16, design decision 32). The packages are named `@flowcatalyst/ui`,
`@flowcatalyst/api-runtime` and `@flowcatalyst/gallery` (section 4).

What this page changes in the other Go pages: the `go/ui` Go module (the templ kit extracted from
the platform) is gone from the core; the Datastar adapter (http contract section 10) and the `ui-kit/fixtures`
goldens are not implemented in Go; the Go HTTP layer gains typed operations that emit an OpenAPI
document, static asset serving, CSRF protection and (with M4c) a session surface for a browser SPA;
milestone G5a becomes the TypeScript UI package. The decisions are in [`design.md`](design.md)
section 9.

Inputs read (read only): the FlowCatalyst platform SPA (`flowcatalyst-go/frontend`: `package.json`,
both `tsconfig` files, `vite.config.ts`, `openapi-ts.config.ts`, `embed.go`, `handler.go`,
`src/main.ts`, `src/api/client.ts`, `src/api/auth.ts`, `src/api/connections.ts`, the auth store,
the router and its guards, `MainLayout.vue`, `EntityDrawer.vue`, the composables `useDrawerRoute`,
`useListState`, `useCursorPagination`, `useDirtyForm`, `ConnectionListPage.vue`, `styles/main.css`,
`tests/conventions/`), the platform's `docs/frontend-api-types-adoption.md`, `docs/ui/README.md`,
`internal/ui/guard`, the session-cookie code in `internal/platform/auth/login` and
`shared/middleware`, `internal/platform/shared/apiroute`, `httpcompat`, `tools/dump-spec` and the
Makefile's `api-*` targets; this repository's `ui-kit/` (README, component index, every spec's
header, `theme/tokens.css`, `fixtures/README.md`), [`../ui-dogfood-plan.md`](../ui-dogfood-plan.md),
[`../typescript/design.md`](../typescript/design.md) (sections 1.2, 1.6, 1.7a, 2.4, 4, decisions 17
to 21, 24, 26), the http contract and the Java plan's M-UI and M4c rows. The Nuxt UI installation,
icon and CSS-variable pages and the component list were read on ui.nuxt.com on 2026-10-13; the
`@hey-api/openapi-ts` plugin names and options were read from version 0.93.1 as installed in the
platform's `frontend/node_modules`.

## 1. What the study found

### 1.1 The platform SPA

| Area | What it does | What the framework takes from it |
|---|---|---|
| Stack | Vue 3.5, PrimeVue 4 (Nora preset with a density override), Pinia, vue-router 4, Vite 7, vee-validate + Zod on 2 auth pages only, oxlint and oxfmt, vitest; 110 `.vue` files | Vue, vue-router, Vite, vitest; not PrimeVue, not Pinia in the package |
| TypeScript | `strict`, `noUncheckedIndexedAccess`, `noPropertyAccessFromIndexSignature`, `noImplicitOverride`, `noImplicitReturns`, `erasableSyntaxOnly`, `noUnused*`; **no** `exactOptionalPropertyTypes`, no `verbatimModuleSyntax`; vue-tsc 2.x; **no type-aware lint** (oxlint) | the settings, plus the missing ones (section 3) |
| API types | `@hey-api/openapi-ts` with **types only** from `api/openapi.lock.json`; transport is a hand-written `fetch` wrapper (`apiFetch`, `bffFetch`, `authFetch`) that decodes the error envelope, raises 401/403 events and toasts | the generation from a committed spec and the CI drift check (`make api-diff`, `frontend-types-verify`); not the hand-written transport |
| Typing gaps | request bodies, query strings and URL paths are hand-written (`apiFetch` with a path built from a template literal); the spec has **no enums** (status fields are `string`, unions re-declared by hand); `Time` is patched to `string` at generation; `/bff/*` is stripped from the spec and the `/auth/*` surface is outside huma, so both stay hand-typed; `RelaxRequestBodies` loosens every request schema because the SPA sent supersets | the reasons the framework generates the **whole client** (paths, params, bodies, responses, errors) and emits enums, times and nullability from the Go types (section 6) |
| Auth | a same-origin `fc_session` cookie (`HttpOnly`, `SameSite=Lax`, `Secure` by config) holding a platform session JWT; the server resolves roles and permissions per request; `GET /auth/me` hydrates a Pinia store; route guards check permissions from that list; 401 for fetch, a redirect for HTML navigation | the BFF cookie shape and the guard pattern (section 8) |
| CSRF | the `/ui` (templ) routes run `guard.CSRF` (`http.CrossOriginProtection` plus refusing an unsafe request with neither `Sec-Fetch-Site` nor `Origin`); the **`/api` routes the SPA calls have no such check** and rely on `SameSite=Lax` (finding, section 8.4) | `guard.CSRF`'s rules |
| Embedding | `frontend/embed.go` (`//go:embed all:dist`), `handler.go`: exact asset or `index.html`, `immutable` caching under `/assets/`, `no-store` on `index.html`, a clear "frontend not built" response | the pattern; with two corrections (section 9: a missing hashed asset and an unknown `/api` path must not answer `index.html`) |
| Patterns | list pages as `DataTable` + `FcTableToolbar` + URL-synced filters (`useListState`), record drawers as **child routes** over the list (`useDrawerRoute`: deep links, Back closes, discard-changes guard on leave and on row switch), cursor pagination with an in-session back stack (`useCursorPagination`), `useDirtyForm` (structural snapshot), a convention test against assigning a `204` result to a ref | all of them, ported onto Nuxt UI components as `@flowcatalyst/ui` composables and partials |
| Look | hard-coded slate and navy hex values in scoped styles; 12.6 px base font; no dark mode | the kit's tokens instead (section 11) |

### 1.2 The UI kit and the platform's templ port

`ui-kit/` has 60 component specs (purpose, markup, Props table, accessibility, behaviour), tokens
for light and dark, a gallery with 69 scripted interaction checks and 124+ golden fixtures. The
platform's templ port under `/ui` passes those fixtures and reached parity for ten areas by
2026-10-10 (`flowcatalyst-go/docs/ui/README.md`). For the Go framework:

- the **specs' behaviour and accessibility sections** and the **tokens** stay the reference;
- the **golden HTML does not apply**: Nuxt UI renders Reka UI markup (section 12 says what replaces
  it as the conformance authority);
- the templ code and `datastar-go` are not used by the framework's core. The platform's port
  continues (owner, 2026-10-14), and the Go framework will offer an **optional templ + Datastar
  module** later, as Java offers M-UI (section 16; design decision 32).

### 1.3 The Go platform's OpenAPI pipeline

huma v2.38 on chi (`humachi`). `apiroute` registers operations with six fields;
`apiroute.PlatformAPIConfig` is shared by the server and `tools/dump-spec`, which registers every
module against nil-dependency state and prints the document with no database; `make api-bump`
commits it as `api/openapi.lock.json` and `make api-diff` fails CI on drift. huma derives schemas
from Go types by reflection and struct tags, validates request bodies with `additionalProperties:
false`, and is configured through **package-level variables** (`huma.NewError` replaced so errors
use the platform envelope, `huma.DefaultArrayNullable = false`). Its routes live outside the
framework's own route table. Section 7 compares this with the framework writing its own emitter.

## 2. The stack

| Piece | Choice | Notes |
|---|---|---|
| UI library | **Nuxt UI v4** (`@nuxt/ui`), through its Vite plugin `@nuxt/ui/vite` and Vue plugin `@nuxt/ui/vue-plugin` | v4 is current; the installation page documents options up to 4.11; exact version pinned at U0 |
| Under it | Reka UI (headless, accessible primitives), Tailwind CSS 4, TanStack Table (in `UTable`), `@vueuse/core` (colour mode), `@iconify/vue` | brought by Nuxt UI |
| Framework | Vue 3.5+, vue-router 4 (history mode), Vite | no Nuxt, no Nitro, no SSR |
| Language | TypeScript, exact pin, the newest release `vue-tsc` supports | the native TypeScript 7 compiler only when `vue-tsc` supports it |
| Type check | `vue-tsc --build` | templates checked (`strictTemplates`) |
| Lint | ESLint flat config, **typescript-eslint type-aware** (`strictTypeChecked`) with `eslint-plugin-vue` | section 3.2 |
| Format | oxfmt, as the platform | a development tool only |
| API client | `@hey-api/openapi-ts` (exact pin; it is 0.x and changes between minors): `@hey-api/typescript`, `@hey-api/sdk`, `@hey-api/client-fetch`, `zod` | section 6 |
| Forms | Nuxt UI `UForm` with the generated **Zod 4** schemas (Zod implements Standard Schema, which `UForm` accepts) | server validation stays the authority |
| State | composables with `provide`/`inject` (session, permissions, current client); **no Pinia in `@flowcatalyst/ui`** (an application may add it) | as TypeScript decision 19 |
| Package manager | **pnpm** (the platform's, the owner's other project), workspace rooted at `ui/`, exact versions, `catalog:` for shared versions, frozen lockfile in CI, `minimumReleaseAge` and an `onlyBuiltDependencies` allow-list | Node 24 LTS |
| Tests | vitest (+ `@vue/test-utils`, happy-dom), Playwright, `@axe-core/playwright` | section 12 |

## 3. TypeScript and lint settings

### 3.1 `tsconfig.base.json`

The platform's settings plus `exactOptionalPropertyTypes` and `verbatimModuleSyntax`:

```json
{
  "compilerOptions": {
    "target": "ES2023",
    "lib": ["ES2023", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "exactOptionalPropertyTypes": true,
    "noPropertyAccessFromIndexSignature": true,
    "noImplicitOverride": true,
    "noImplicitReturns": true,
    "noFallthroughCasesInSwitch": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "allowUnreachableCode": false,
    "allowUnusedLabels": false,
    "erasableSyntaxOnly": true,
    "verbatimModuleSyntax": true,
    "isolatedModules": true,
    "forceConsistentCasingInFileNames": true,
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "noEmit": true,
    "skipLibCheck": true,
    "types": ["vite/client"]
  },
  "vueCompilerOptions": {
    "strictTemplates": true
  }
}
```

- **`erasableSyntaxOnly`** is sensible here: no `enum`, `namespace`, parameter properties or
  decorators, so every file is plain JavaScript once types are erased, as in the TypeScript design
  (1.2) and the platform. The generated client must then produce **union types, not TypeScript
  enums** (`@hey-api/typescript` with `enums: 'javascript'`, which emits `as const` objects, or
  `false`).
- **`exactOptionalPropertyTypes`**: `{ x?: string }` no longer accepts `x: undefined`. It catches
  the "absent versus null versus undefined" confusion that the platform's adoption notes record
  (`email: string | null` versus `email?: string`). **Unverified:** whether hey-api's generated
  client and Nuxt UI's prop types compile under it. U0 checks; if the generated folder fails, it
  becomes its own project reference without that one flag (recorded in the package README), never
  a global relaxation.
- **`skipLibCheck`** stays on, as in the platform: Nuxt UI's declaration files are not ours to fix.
- **`strictTemplates`** (vue-tsc) is shorthand for checking unknown props, events, directives and
  components in templates; checked against the pinned vue-tsc at U0.
- Nuxt UI needs `#build/ui` and `#build/ui/*` path aliases to `node_modules/.nuxt-ui/ui` in the
  tsconfig files and generates its declaration files when Vite runs, so CI runs `vite build` before
  `vue-tsc` (the installation page says type-check after the build, not in parallel).

### 3.2 Lint

typescript-eslint `strictTypeChecked` with type information (`parserOptions.projectService`) and
`eslint-plugin-vue` (`flat/recommended`), plus:

| Rule | Why |
|---|---|
| `@typescript-eslint/no-explicit-any`, the `no-unsafe-*` family | **no `any`**, explicit or leaked through a library |
| `@typescript-eslint/consistent-type-assertions` (`assertionStyle: 'never'`, `as const` allowed), `no-non-null-assertion` | no casts; narrow instead |
| `@typescript-eslint/ban-ts-comment` (`ts-expect-error` only, with a description) | no silenced checks |
| `@typescript-eslint/switch-exhaustiveness-check`, `strict-boolean-expressions`, `no-floating-promises`, `no-misused-promises` | unions handled everywhere; no forgotten promise in an event handler |
| `vue/block-lang` (`ts` only), `vue/require-typed-ref`, `vue/define-props-declaration` (type-based), `vue/define-emits-declaration` (type-based), `vue/require-explicit-emits`, `vue/no-unused-properties` | every component typed |
| `vue/no-v-html` | XSS; an exception is a reviewed component that sanitises (the Markdown viewer, if one is built) |
| `no-restricted-imports`: `fetch` wrappers other than the generated client, `primevue`, `pinia` inside `@flowcatalyst/ui` | one way to call the API; the package stays small |
| `no-restricted-globals`: `localStorage` and `sessionStorage` outside one `useLocalState` composable | storage access is reviewed in one place |

`make ui-check` runs: `pnpm install --frozen-lockfile`, OpenAPI and client drift checks, `vite build`,
`vue-tsc --build`, `eslint .`, `vitest run`, Playwright (section 12).

## 4. Packages and layout

The UI is **TypeScript in a pnpm workspace rooted at `ui/` in this repository** (accepted at U0,
2026-10-14, [`../ui/u0.md`](../ui/u0.md); the first draft of this page drew the repository root).
The workspace holds the shared packages, the gallery and the OpenAPI sample. An example's SPA
(`examples/orders-demo/web`, `go/examples/orders-demo/web`) is **not** a workspace member: it has its
own install and lockfile and links the package by path, exactly like an application outside this
repository (the first is the Java control plane in `inhance/mono-repo`), until publishing is
decided (decision 33). The Maven and Cargo builds ignore `ui/`; `go/` stays a pure Go tree apart
from the example's `web/` folder.

```
ui/                            the pnpm workspace root
  pnpm-workspace.yaml          packages: packages/*, apps/*, samples/*; catalog; minimumReleaseAge
  package.json                 private; tooling only (eslint, oxfmt, typescript, vue-tsc, vitest, playwright)
  tsconfig.base.json  eslint.config.ts
  packages/
    ui/                        @flowcatalyst/ui: components, partials, pages, layouts, composables, theme preset, Vue plugin
      src/components/          Fw* components that extend Nuxt UI (FwTableToolbar, FwCursorPager, FwFormDialog, ...)
      src/partials/            FwListPage, FwRecordDrawer, FwDetailSection, FwFormPage
      src/pages/               default pages: not permitted, not found, error, signed out, profile
      src/layouts/             FwAppShell (dashboard group, sidebar, navbar, user menu, client switcher)
      src/composables/         useListState, useCursorPagination, useDrawerRoute, useDirtyForm, useSession,
                               usePermissions, useConfirm, useLocalState
      src/theme/               the preset: Vite plugin options (colors, defaultVariants, component slots) and kit.css
      src/plugin.ts            install(app, { router, api, navigation }) for applications
    api-runtime/               @flowcatalyst/api-runtime: configures @hey-api/client-fetch (same origin, credentials, the
                               error envelope as a typed ServiceError, 401 -> sign-in, toasts), envelope -> UForm
                               errors, and the generated client of the framework's own endpoints (session, auth)
  apps/
    gallery/                   @flowcatalyst/gallery: every component, partial and page in every state, light and dark
  samples/
    openapi/                   hand-written OpenAPI 3.1 document and the hey-api client generated from it (U0)
go/examples/orders-demo/web/   the example SPA (package.json, vite.config.ts, src/, openapi.json, the generated
                               client in src/api/, embed.go with //go:embed all:dist)
```

**Extend, never fork.** `@flowcatalyst/ui` composes Nuxt UI components and passes their props and slots
through; restyling is done with Nuxt UI's own mechanisms (the plugin's `ui` options and a
component's `ui` prop), never by copying a component. A component the kit needs and Nuxt UI does
not have is built on **Reka UI** primitives (already a dependency) with Nuxt UI's tokens. Prefix
`Fw`. Pages and partials take data through typed props and emit typed events: they never call the
API themselves, so the gallery renders them with fixture data and the same page serves any
backend that implements the same operations.

Applications consume `@flowcatalyst/ui` as **source** (workspace packages, compiled by the application's
Vite build), so no declaration files are emitted or published. **Outside this repository** (owner,
2026-10-14): the application checks out the framework repository and links the package by path
(pnpm `"@flowcatalyst/ui": "link:../flowcatalyst-framework/ui/packages/ui"`; the examples in this
repository link the same way with a relative path); the package exports its
TypeScript and Vue source and the consumer's Vite compiles it; CI checks the framework out at a
pinned tag or commit. Later the packages are published to GitHub Packages under the `@flowcatalyst`
scope (the npm scope must equal the GitHub owner; `@fw` was a placeholder). Decision 33.

## 5. Nuxt UI without Nuxt

```ts
// go/examples/orders-demo/web/vite.config.ts (sketch)
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import ui from "@nuxt/ui/vite";
import { fwTheme } from "@flowcatalyst/ui/theme";

export default defineConfig({
  plugins: [
    vue(),
    ui({
      ...fwTheme,                       // ui.colors, theme.defaultVariants, component slot overrides
      router: true,                     // vue-router links in ULink, UButton, UNavigationMenu
      components: false,                // explicit imports: no generated globals to trust
      autoImport: false,
      icon: { clientBundle: { scan: true, icons: [/* names built at run time */] } },
    }),
  ],
  base: "/",                            // the UI prefix is fixed at build time
  build: { outDir: "dist", assetsDir: "assets", sourcemap: true },
  server: { port: 5173, proxy: { /* section 13 */ } },
});
```

- **`app.config` equivalent:** in plain Vue the plugin's `ui` option is what `app.config.ts`'s `ui`
  key is in Nuxt (colour aliases, per-component slot classes and default variants). `@flowcatalyst/ui`
  exports it as `fwTheme`; an application spreads it and overrides keys.
- **Entry:** `main.ts` installs the router first, then `@nuxt/ui/vue-plugin`, then `@flowcatalyst/ui`'s
  plugin; `App.vue` wraps everything in `UApp` (required for toasts, tooltips and programmatic
  overlays); the root element in `index.html` carries `class="isolate"`.
- **CSS:** one entry, `@import "tailwindcss"; @import "@nuxt/ui"; @import "@flowcatalyst/ui/theme/kit.css";`
  (section 11).
- **Icons:** Lucide, as the kit (`@iconify-json/lucide` installed, so icons are **bundled** and the
  SPA never calls the Iconify API, which the CSP would block). `clientBundle.scan` finds literal
  names in `.vue` files; names built at run time (a status-to-icon map in a `.ts` file) are listed
  explicitly. A Playwright check fails on any request to an Iconify host.
- **Colour mode:** Nuxt UI's `@vueuse/core` integration toggles `class="dark"` on `<html>`, the
  same switch as the kit's tokens. The stored choice is read before mount by a few lines in
  `index.html`, allowed by a CSP hash, so a dark page does not flash light.
- **Explicit imports** (`components: false`, `autoImport: false`): the plugin documents both as
  importable from `@nuxt/ui/components/...` and `@nuxt/ui/composables`. A library should import
  what it uses, and greppable imports fit the "one way" rule. The cost is longer import lists.

## 6. The typed spine: Go types to forms

*Since 2026-10-14 the document rules (type table, `@Nullable`/`omitzero` meaning "may be absent",
closed schemas, the error envelope and implied statuses, security schemes, deterministic output),
the hey-api configuration and the drift checks are fixed for Java and Go by
[`../contracts/openapi.md`](../contracts/openapi.md); the Go emitter must reproduce its conformance
vector byte for byte. Two changes from this section: `httpfw.Nullable[T]` is not emitted (no
three-state values in this version; contract 5.2), and constraints come from the request's validation
rules expressed as data (contract 5.4), which the Go `validate` package must offer.*

```
Go wire DTOs + operation descriptors (httpfw)
   -> OpenAPI 3.1 document (`<app> openapi`, no database; committed as web/openapi.json; drift fails CI)
   -> @hey-api/openapi-ts: types.gen.ts, sdk.gen.ts (one function per operation), zod.gen.ts
   -> pages call sdk functions; UForm validates with the Zod schema of the request body
   -> the server parses again (decision 17) and answers 400 VALIDATION with field details
   -> @flowcatalyst/api-runtime maps details onto the form's fields
```

### 6.1 Generation

```ts
// web/openapi-ts.config.ts (sketch)
import { defineConfig } from "@hey-api/openapi-ts";
export default defineConfig({
  input: "openapi.json",                // committed; written by `orders-demo openapi`
  output: { path: "src/api" },
  plugins: [
    { name: "@hey-api/typescript", enums: "javascript" },
    "@hey-api/client-fetch",
    { name: "@hey-api/sdk", validator: false },
    { name: "zod", compatibilityVersion: 4 },
  ],
});
```

- **One function per operation**, typed in every part: `addLine({ path: { id }, body })`. A
  renamed path parameter, a missing required field or a removed operation is a `vue-tsc` error.
  The platform's client types only responses; the framework types the whole call.
- **The SDK's `validator`** can validate requests or responses with the Zod schemas at run time.
  Off by default: the server is the authority, and response validation in production costs time
  for a contract CI already pins. A test helper turns it on in the example's Playwright run, where
  a response that does not match the document fails the test.
- **The error type**: every operation declares the contract envelope (`{error, message,
  details}`) for its error statuses, so `error` from the client is typed, not `unknown`.
- **Drift:** `make ui-gen` regenerates the document and the client; CI runs both and fails on
  `git diff`, as the platform's `api-diff` and `frontend-types-verify` do.

### 6.2 What the Go side must emit for the TypeScript to be strict

| Go | OpenAPI | TypeScript | The platform today |
|---|---|---|---|
| a required field (no `omitzero`) | in `required` | `x: T` | many fields optional by default |
| `omitzero` or a pointer at the JSON edge | not required | `x?: T` (with `exactOptionalPropertyTypes`, absent and `undefined` are not the same) | `x?: T` |
| `httpfw.Nullable[T]` (rare, explicit) | `type: [T, "null"]` | `x: T \| null` | arrays forced non-nullable by a global |
| a string type with `EnumValues()` | `enum` | a literal union | `string`, unions re-declared by hand |
| `fw.Instant` | `format: date-time` | `string` (a `Date` transformer is possible, not chosen: strings survive JSON round trips) | patched to `string` at generation |
| `id.ID[K]`, `fw.ClientID` | `string` with the TSID pattern | `string` | `string` |
| `httpfw.CursorPage[T]` | `{items, hasMore, nextCursor}` with a stable component name | a typed page | hand-typed |
| the error envelope | a shared component on every error status | typed `error` | not in the spec |

Typed ids do not survive into TypeScript (a brand would need a vendor extension and a custom
hey-api plugin); recorded as a known loss.

### 6.3 Using it

```ts
// a form dialog's submit (sketch; the generated names follow the operation ids)
import { addLine } from "@/api/sdk.gen";
import { zAddLineBody } from "@/api/zod.gen";
import type { FormSubmitEvent } from "@nuxt/ui";
import type { z } from "zod";
import { applyServiceError } from "@flowcatalyst/api-runtime";

const schema = zAddLineBody;
async function onSubmit(event: FormSubmitEvent<z.output<typeof schema>>): Promise<void> {
  const { error } = await addLine({ path: { id: props.orderId }, body: event.data });
  if (error !== undefined) {
    applyServiceError(form, error);     // 400 VALIDATION details -> field errors; other codes -> a toast
    return;
  }
  emit("saved");
}
```

`UForm` takes the Zod schema directly (Standard Schema), so the browser checks the same shape the
document describes: types, required fields, enums and whatever constraints the Go side declares.
Rules that only the server knows (an order must be open) arrive as `400` or `409` envelopes and are
shown on the field or as a toast. The server's parse function (decision 17) stays the authority;
the Zod schema is a convenience that the document keeps honest.

## 7. OpenAPI on the Go side: the framework's own emitter, or huma

The Java http contract lists OpenAPI generation as out of scope (section 11). For Go it is now the
spine, so the http layer must produce a document. Four ways, compared honestly:

| | A. Own emitter in `httpfw` | B. Adopt huma (`humago` on `ServeMux`) | C. huma's schema registry only | D. Spec first (ogen, oapi-codegen) |
|---|---|---|---|---|
| What | operation descriptors (method, pattern, ids, status, body and response types, error statuses) declared at **declare time**; a JSON Schema derived from the json/v2 struct tags by reflection at the JSON edge; types may provide their own schema (`JSONSchema()`) or enum values (`EnumValues()`) | huma's `Register(api, Operation, func(ctx, *In) (*Out, error))`; schemas, validation and the document from struct tags | `huma.NewMapRegistry` produces component schemas from types; the framework assembles paths and operations itself | the YAML document is written by hand; Go server interfaces generated from it |
| Fits the design rules | yes: one handler shape on the framework's route table, `encoding/json/v2` strictness, the contract's envelope and statuses, no globals; reflection only at the JSON edge (allowed by the `forbidigo` rule) | no: owner ruling a names huma under "Not"; package-level mutable configuration (`huma.NewError`, `DefaultArrayNullable`); its own handler shape hides `ResponseWriter` (cookies need a `Set-Cookie` output field); struct-tag validation against decision 17; `encoding/json` v1 by default (case-insensitive names) unless its `Formats` are replaced; 422 for validation unless overridden | partly: one dependency used as a library; huma's globals still shape the schemas | contracts-first in spirit, but the Go types become generated, the router and error mapping are the generator's, a second source of truth beside the Go DTOs |
| Proven | no; new code | yes: 30+ platform modules, the lock file, three SDKs generated from it | the registry is huma's core, used by the platform | not in this codebase |
| Effort | about 800 to 1 200 lines with tests (type walk, naming of generic types, `$ref` reuse, enums, nullability, the envelope, paths from `ServeMux` patterns, which already use OpenAPI's `{name}` syntax); 1.5 to 2 days in G3 | small to start; ongoing work to keep its behaviour inside the http contract (errors, routing rules, JSON strictness) | about 300 lines for the document | YAML for every operation, forever |
| Dumping without a database | yes: descriptors exist after `Declare`, so `<app> openapi` runs the declare phase only | yes, with the platform's nil-state trick | yes | the document is the source |

**Recommendation: A.** The framework already owns its route table, its JSON decoding and its error
mapping; an emitter that reads the same descriptors keeps one way to define a route and makes the
document a by-product of code that must exist anyway. Declaring operations as immutable
package-level descriptors fits design rule 1.3 ("package-level vars hold immutable descriptors
only") and lets the document be dumped without a database. Start-up fails if a declared operation
is not bound to a handler, is bound twice, or a typed route was never declared. **Fallback: C** if
the schema walk exceeds its budget in G3 (it reuses huma's type walk without its router or
handlers). B is the right answer only if the owner prefers the platform's proven tool to the
stdlib-first rule; then the http contract's routing and error rules need an adapter layer around
huma, and decisions 16 and 17 change. Decision 18.

```go
// orders/api.go (sketch, not final)
var AddLine = httpfw.Operation[AddLineBody, LineAddedView]{
	ID:      "addLine",
	Pattern: "POST /orders/{id}/lines",
	Status:  http.StatusOK,
	Summary: "Add a line to an open order",
	Tags:    []string{"orders"},
	Path:    httpfw.Params{httpfw.PathParam[id.ID[OrderKind]]("id")},
	Errors:  []int{400, 401, 403, 404, 409},
}
// Declare: httpfw.DeclareOperations(web, AddLine, PlaceOrder, CancelOrder, ListOrders)
// build:   rt.Bind(httpfw.Handle(AddLine, s.addLine))
// handler: func (s *Service) addLine(ctx context.Context, cx fw.Context, in httpfw.In[AddLineBody]) (LineAddedView, error)
```

## 8. Authentication and sessions for an SPA served by the Go backend

### 8.1 Shape: the application server is the backend-for-frontend

*Superseded 2026-10-13 by [`contracts/sessions-and-login.md`](../contracts/sessions-and-login.md),
which is authoritative where it differs from this section (notably: `/auth/session` answers 200
with `authenticated: false`, and the CSRF rules are the platform's F16 rules exactly).*

Tokens never reach the browser. The application is an OIDC relying party of FlowCatalyst (code
flow with PKCE as a **public client**: the platform refuses a secret from a public client,
flowcatalyst contract 5.3), keeps the tokens in a server-side session, and gives the browser one
cookie. This is the platform SPA's model (a same-origin cookie, no token in JavaScript) and the
TypeScript design's decision 21(b).

| Endpoint | Behaviour |
|---|---|
| `GET /auth/login?returnTo=` | creates a login state (PKCE verifier, `state`, `nonce`, the validated `returnTo`: a same-origin path only), 302 to the platform's authorize endpoint |
| `GET /auth/callback` | checks `state`, exchanges the code, verifies the id_token (RS256 always, as TypeScript decision 21(c) recommends), creates the session (new id: rotation on login), 302 to `returnTo` |
| `GET /auth/session` | 200 `{principal: {id, name, email, kind}, client, permissions, expiresAt}` or 401; the SPA's guard reads it once and on focus |
| `POST /auth/logout` | destroys the session, answers 200 with the platform's end-session URL for the SPA to navigate to |
| everything under `/api` | the `ContextResolver` builds `fw.Context` from the session; 401 `UNAUTHENTICATED` for a missing or expired session |

The endpoints are framework operations, so they are in the document and `@flowcatalyst/api-runtime` calls
them through a generated client: the platform's `/auth/*` surface, which is outside its spec and
hand-typed, is the counter-example.

**Cookie:** an opaque session id, `__Host-fw_session`, `HttpOnly; Secure; SameSite=Lax; Path=/`,
no `Domain`. Session data lives in the `database` driver by default (revocable, no key to rotate),
`redis` or a sealed `cookie` (AES-256-GCM) as alternatives, as ruled for Java. Idle and absolute
expiry. Platform calls are made with the application's **service account**, never with the user's
login token (TypeScript decision 21(b)).

**401 versus 302:** API calls get 401 and the envelope; the SPA's `@flowcatalyst/api-runtime` turns a 401
into `window.location.assign("/auth/login?returnTo=" + current)`. `index.html` and the assets are
public (no data in them), so the SPA itself never needs a server redirect, and the sign-in round
trip lands on the page the user asked for.

**Permissions in the browser** are for showing and hiding only: route `meta.permission` checked by
a guard in `@flowcatalyst/ui` (the platform's `createRoutePermissionGuard` pattern, including its rule that an
automatic landing does not show a denial), navigation items filtered by the same list. Every API
operation still checks in `authorize`.

### 8.2 CSRF

For cookie-authenticated unsafe methods (anything but `GET`, `HEAD`, `OPTIONS`), in this order:

1. `SameSite=Lax` keeps the cookie off cross-site `POST`s and `fetch`es in current browsers.
2. **`http.CrossOriginProtection`** (standard library since Go 1.25): `Sec-Fetch-Site` must be
   `same-origin` or `none`; without it, `Origin` must match the host or a configured trusted origin.
3. A request with **neither** header is refused, the platform `/ui` guard's stricter rule (scripts
   use bearer tokens, not cookies).
4. JSON bodies only (`415` otherwise, http contract section 7), so a cross-origin request needs a
   CORS preflight, and CORS is off by default.

No synchronizer token: with Fetch Metadata, an Origin check, `SameSite` and JSON-only bodies there
is nothing for a token to add that is worth a token endpoint and a header in every call. Requests
authenticated by a bearer token skip steps 2 and 3. `GET` handlers must not change state (a lint
check in `fwvet` on operation descriptors: a `GET` operation with a body type is refused).

### 8.3 How it relates to the Java M4c contract

The Java plan's M4c renders login, callback, logout and not-permitted **pages** with JTE. With an
SPA, the server side of M4c is the endpoint table above and the pages are SPA routes in `@flowcatalyst/ui`.
Recommendation (decision 29): write the M4c contract **once and JSON-first** (the endpoint table,
the cookie, the session drivers, CSRF, the token-verification vectors in `conformance/auth/` that
TypeScript decision 21 proposed), with server-rendered default pages as an optional Java addition.
Go then implements the contract like every other one, and a Java application can serve the same
SPA (section 15). G5b waits for that contract, as before.

### 8.4 Finding about the platform

The platform's `/api` routes, which the SPA calls with the `fc_session` cookie, have no Origin or
Fetch Metadata check; only `/ui` has `guard.CSRF`. `SameSite=Lax` blocks the classic cross-site
form `POST`, so the remaining exposure is same-site sibling subdomains and browsers that ignore
`SameSite`. Whether huma refuses a `text/plain` body (which a cross-site form can send without a
preflight) was not checked. Reported, not fixed here.

## 9. Serving the SPA from the Go binary

*Superseded 2026-10-14 by [`../contracts/static-assets-and-spa.md`](../contracts/static-assets-and-spa.md),
written once for Java and Go and mirroring the platform's F16 handler. Differences from this section:
the SPA is the router's fallback, not a trailing-wildcard route (so decision 28's amendment of http
section 2 is not needed); API prefixes are derived from the routes; `index.html` is served for any
extension-less GET outside them, without an `Accept` check; missing assets and unknown API paths
answer the JSON envelope; the baseline CSP is the platform's and the strict policy below is opt-in
in the first Java build; U0 verified Nuxt UI under it, so strict becomes the default
(static-assets contract section 7); no precompressed siblings. The text below is kept as the
reasoning.*

`httpfw/spa` (sketch): `spa.Handler(dist fs.FS, spa.Options{Prefix: "/", APIPrefixes: ...})`,
mounted last in the framework's route table.

| Request | Answer |
|---|---|
| a file in `dist/assets/` (Vite's content-hashed names) | the file, `Cache-Control: public, max-age=31536000, immutable`, a precompressed `.br` or `.gz` sibling when the client accepts it |
| a path under `/assets/` that does not exist | **404** (the platform answers `index.html` with 200, which turns a stale deployment into a confusing JavaScript parse error) |
| another existing file (`favicon.ico`, `robots.txt`) | the file, `Cache-Control: no-cache` |
| `GET`/`HEAD` with `Accept: text/html` under the UI prefix, not under an API prefix | `index.html`, `Cache-Control: no-cache` (revalidated by ETag; the platform's `no-store` forces a full download) |
| anything under `/api`, `/auth`, `/health`, `/metrics`, `/fc` that no route matched | the framework's JSON 404 envelope, **never** `index.html` |
| the binary was built without the SPA | a plain 404 naming the build step, as the platform's "frontend not built" |

`go:embed` cannot reach outside its module, so the example's SPA builds into
`go/examples/orders-demo/web/dist` and `web/embed.go` embeds it (the platform's layout). An
application outside this repository does the same in its own module.

**Security headers on the SPA's responses:**

```
Content-Security-Policy: default-src 'self'; script-src 'self' 'sha256-<colour-mode snippet>';
  style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self';
  frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'
X-Content-Type-Options: nosniff
Referrer-Policy: same-origin
Cross-Origin-Opener-Policy: same-origin
Permissions-Policy: camera=(), microphone=(), geolocation=()
```

Fonts are self-hosted (Inter from `@fontsource-variable/inter`) so `font-src 'self'` holds.
**Unverified:** whether any Nuxt UI or Reka component writes an inline `<style>` element or a
`style` attribute through markup (setting `element.style` from script is not blocked by
`style-src`). The gallery runs under this exact policy in Playwright and fails on any
`securitypolicyviolation` event; a component that needs `'unsafe-inline'` for styles is recorded,
not silently allowed. HSTS is set by the TLS terminator, which the http contract leaves outside the
application.

**Contract amendment** (decision 28): http section 11 lists static assets as out of scope and
section 2 has no wildcards. Amend both, as TypeScript decision 26 proposed: one trailing wildcard
route for a static mount, the fallback rules above, the caching rules and the security headers.

## 10. ui-kit components and their Nuxt UI equivalents

"Covered" means a Nuxt UI component does the job with `@flowcatalyst/ui` theming only; "compose" means
`@flowcatalyst/ui` combines Nuxt UI components into a partial; "build" means `@flowcatalyst/ui` writes it (on Reka UI
where it is interactive).

| ui-kit spec | Nuxt UI | Status |
|---|---|---|
| accordion | `UAccordion` | covered |
| alert | `UAlert` | covered |
| alert-dialog | none with `role="alertdialog"`; `UModal` is a dialog | **build** `FwAlertDialog` on Reka's `AlertDialog` |
| app-shell | `UDashboardGroup`, `UDashboardSidebar` (collapse, resize, mobile), `UDashboardPanel`, `UDashboardNavbar`, `UNavigationMenu`, `UDropdownMenu` + `UUser` for the user menu, `UColorModeSelect` | compose `FwAppShell`; the **client switcher** is built (`USelectMenu` + the session's clients) |
| autocomplete | `UInputMenu` (server search through its search term, local filtering off) | covered |
| avatar | `UAvatar` | covered |
| badge | `UBadge` | covered |
| breadcrumb | `UBreadcrumb` | covered |
| button | `UButton` (`solid`, `outline`, `soft`, `subtle`, `ghost`, `link`; colour aliases; `loading`) | covered |
| card | `UCard` | covered |
| checkbox | `UCheckbox`, `UCheckboxGroup` | covered |
| code-block | none (Prose `pre` exists only with `prose: true` and brings no highlighter) | **build** `FwCodeBlock` (a highlighter for JSON Schema and code, copy button) |
| code-chips | `UBadge` list | compose `FwCodeChips` |
| confirm-dialog | `UModal` + `useOverlay` | compose `useConfirm()` returning a promise (the platform's `confirmDiscardChanges` shape) |
| cursor-pager | none (`UPagination` is numbered) | **build** `FwCursorPager` with `useCursorPagination` |
| data-table (composition) | `UTable` (TanStack: sorting, selection, column visibility, pinning, expansion, virtualisation) | compose `FwListPage`: `FwPageHeader`, `FwTableToolbar`, `UTable`, `UEmpty`, a pager, a drawer outlet; URL state by `useListState` |
| date-range | `UInputDate` (range), or `UCalendar` range in a `UPopover` | covered; range mode checked at U0 |
| detail-field | none | **build** `FwDetailField` (a `dl` row; trivial) |
| dialog | `UModal` | covered |
| dirty-form | `UForm` exposes its state; dirty tracking against the initial values checked at U0 | compose `useDirtyForm` (the platform's) with a route-leave guard |
| drawer (record drawer) | `USlideover` (non-modal mode, side `right`) | compose `FwRecordDrawer` with `useDrawerRoute` (child route, Back closes, discard guard on leave and row switch) |
| dropdown-menu | `UDropdownMenu` | covered |
| empty-state | `UEmpty` | covered |
| field | `UFormField` (label, description, hint, help, error) | covered |
| filter-select | `USelect` or `USelectMenu` with a clear item | covered |
| flash-banner | `UAlert` | covered |
| flash-stack | `UToast` through `useToast` | covered; a **look difference**: toasts in a corner instead of the kit's banner stack; the request-failure fallback lives in `@flowcatalyst/api-runtime` |
| form-dialog | `UModal` + `UForm` | compose `FwFormDialog` (busy and dirty state, discard question, server errors on fields) |
| form-field | `UFormField` | covered |
| form-section | none | **build** `FwFormSection` (title, description, content; trivial) |
| hover-card | `UPopover` in hover mode | covered |
| input | `UInput` | covered |
| json-block | none | **build** `FwJsonBlock` (pretty-printed, copy) |
| kbd | `UKbd` | covered |
| label | part of `UFormField` | covered (no standalone use) |
| legacy-fc-btn | `UButton` (`square` for icon buttons) | **not ported** (a legacy class set) |
| local-time | none | **build** `FwLocalTime` (`<time datetime>`, `Intl.DateTimeFormat`, UTC in the title) |
| multi-select | `USelectMenu` with `multiple` and search | covered |
| page-header | `UDashboardNavbar` title; `UPageHeader` is a marketing header | **build** `FwPageHeader` (title, subtitle, actions; small) |
| pagination | `UPagination` | covered |
| paginator | `UPagination` + `USelect` for page size | compose `FwPaginator` |
| pick-list | `UListbox` (two of them) | compose `FwPickList` (filter, move buttons, keyboard); the largest compose item |
| progress | `UProgress` | covered |
| radio-group | `URadioGroup` | covered |
| row-actions | `UDropdownMenu` on an icon `UButton`, the primary action inline | compose `FwRowActions` |
| search-input | `UInput` with a leading icon; debounce in `useListState` | covered |
| select | `USelect`, `USelectMenu` | covered |
| separator | `USeparator` | covered |
| sheet | `USlideover` (modal) | covered |
| sidebar | `UDashboardSidebar`, `USidebar` | covered |
| skeleton | `USkeleton` | covered |
| spinner | none; `UButton` `loading` and an animated `UIcon` | **build** `FwSpinner` (trivial) |
| switch | `USwitch` | covered |
| table | `UTable` | covered |
| table-toolbar | none | **build** `FwTableToolbar` (search, a filter popover with an active-filter count, clear all; the platform's `FcTableToolbar` is the model) |
| tabs | `UTabs` | covered; route-synced tabs composed |
| tag | `UBadge` | compose `FwTag` (the kit's severities mapped to colour aliases) |
| textarea | `UTextarea` | covered |
| toggle | `UButton` with `active`, `UFieldGroup`; no toggle group | **build** `FwToggleGroup` on Reka's `ToggleGroup` |
| tooltip | `UTooltip` | covered |

Fixture-only parts of the kit (`user-menu`, `icon-button`, `table-row`, `sortable-header`,
`table-toolbar-state`, ...) fall under the rows above.

**Count:** of 60 specs, **37 covered**, **11 composed**, **11 built** (most are a few dozen
lines; the real work is `FwTableToolbar`, `FwCursorPager`, `FwCodeBlock`, `FwPickList` and the
drawer and form-dialog partials), **1 not ported**. Needs the platform SPA has that the kit lacks
are mostly covered by Nuxt UI too: number input (`UInputNumber`), password with a strength meter
(`UInput` + `UProgress`), file import (`UFileUpload`), tags (`UInputTags`), a command palette
(`UCommandPalette`). The permission-denied notice is built (a page and a toast).

## 11. Theming: kit tokens onto Nuxt UI tokens

Nuxt UI works with colour **aliases** (`primary`, `secondary`, `success`, `info`, `warning`,
`error`, `neutral`) that point at Tailwind palettes, and with semantic variables (`--ui-text`,
`--ui-bg`, `--ui-border`, `--ui-radius`, ...) redefined under `:root` and `.dark`. The kit's
`tokens.css` maps as follows (`@flowcatalyst/ui/theme/kit.css` holds the overrides):

| ui-kit token | Nuxt UI | How |
|---|---|---|
| `--primary` emerald-600, hover emerald-700 | `primary: 'emerald'` | `:root { --ui-primary: var(--ui-color-primary-600) }` (Nuxt UI defaults to shade 500 in light, 400 in dark) |
| `--destructive` red-600 | `error: 'red'` | `--ui-error` to shade 600 |
| `--success` green-600, `--info` sky-600, `--warning` orange-600 | `success: 'green'`, `info: 'sky'`, `warning: 'orange'` | shade 600 in light |
| `--foreground` slate-800, `--muted-foreground` slate-500, `--border` slate-200, `--background` slate-50 | `neutral: 'slate'` | `--ui-text` to neutral-800 (default 700); `--ui-bg` stays white for surfaces and the page background uses `--ui-bg-muted` |
| `--card`, `--popover` white | `--ui-bg`, `--ui-bg-elevated` | defaults |
| `--input` slate-400 (control borders), `--field-border` | `--ui-border-accented` | one override (controls are a shade darker than in Nuxt UI) |
| `--ring` = primary | the focus ring uses `--ui-primary` | default |
| navy palette and the `--sidebar-*` chrome | a `navy` palette in `@theme` | `FwAppShell` styles `UDashboardSidebar` through its `ui` slots (`bg-navy-900`, inverted text) |
| `--radius-*` 2 px | `--ui-radius: 2px` | Nuxt UI scales other radii from it |
| text scale (12.6 px controls) | `theme.defaultVariants.size: 'sm'` and a 12.6 px base like the platform | checked against the gallery screenshots at U1 |
| `.dark` class on `<html>` | the same class | dark values from `tokens.css` mapped the same way |
| fonts: Inter, SF Mono stack | `--font-sans`, `--font-mono` in `@theme` | self-hosted Inter |

The kit's dark values are new (fc-web had none), so both themes are reviewed in the gallery at U1.
`ui-kit/theme/tokens.css` stays the source of the values; `kit.css` in `@flowcatalyst/ui` copies them and a
vitest test compares the two files' values so they cannot drift.

## 12. Testing and conformance

The golden HTML fixtures do not apply (different markup). What replaces them:

| Level | What | Tool |
|---|---|---|
| unit | composables (`useListState` URL parsing, `useCursorPagination` back stack, `useDirtyForm`, the envelope mapper, permission checks) | vitest |
| component | `Fw*` components' props, events and slots | vitest + `@vue/test-utils` on happy-dom |
| type | templates and the generated client | `vue-tsc` (a contract change that breaks a page is a build error) |
| gallery | every `Fw*` component, partial and default page, every variant and state from its kit spec, light and dark, desktop and phone width, with fixture data | `@flowcatalyst/gallery` (a Vite app) |
| behaviour | each kit spec's Behaviour and Accessibility sections as Playwright checks (keyboard operation, focus trap and return, Escape, discard question, drawer Back, toolbar Clear All); the kit's 69 scripted checks ported where they apply | Playwright on the gallery |
| accessibility | zero violations at WCAG 2.2 AA on every gallery page in both themes | `@axe-core/playwright` |
| visual | screenshots of every gallery page, approved by the owner once and then compared | Playwright `toHaveScreenshot`, in the pinned Playwright container image so fonts and rendering are stable |
| CSP | the gallery and the example served with section 9's headers; any `securitypolicyviolation` or request to a third-party host fails | Playwright |
| contract | `openapi.json` and the generated client up to date; the example's E2E run with response validation on | CI drift checks; Playwright against the real Go server |
| end to end | the orders example: sign-in against a test OIDC provider (a small one in `fwtest`), list, filter, open a drawer, add a line, see a validation error, sign out | Playwright, Go server on embedded PostgreSQL |

**The conformance authority** for the Nuxt UI implementation is: the kit specs (behaviour,
accessibility, which variants and states exist), the gallery's approved screenshots (the look), and
the axe and Playwright checks (both enforced in CI). A component is done when its gallery entry
covers every state in its spec and all four checks pass. Pixel parity with the Datastar kit is not
a goal; the tokens make the two recognisably the same product.

## 13. Development loop

- `make go-dev` starts the example with `APP_ENV=local` on **port 8180** with its own embedded
  PostgreSQL 18 on a fixed local port that is **not 15432** (the platform's), its data in
  `go/examples/orders-demo/.dev/`; never 8080, where a developer's local FlowCatalyst (`fcdev`)
  runs, which is also the OIDC provider for sign-in.
- `make ui-dev` runs Vite on 5173 with a proxy: `/api`, `/auth`, `/health` to `localhost:8180`.
  HTML navigations stay with Vite except `/auth/login` and `/auth/callback` (the platform's proxy
  `bypass` rule). The OIDC redirect URI in development is `http://localhost:5173/auth/callback`, so
  the session cookie is set on the Vite origin and every call is same-origin, as in production.
  `__Host-` cookies need `Secure`; browsers treat `http://localhost` as a secure context, but the
  framework drops the prefix when `http.cookies.secure` is false and logs it at start-up.
- `make ui-gen` regenerates `openapi.json` (`go run ./examples/orders-demo openapi`, declare phase
  only) and the client; a file watcher (the platform's `scripts/watch-api.ts`) can run it on Go
  changes.
- `make ui-gallery` runs the gallery on 5174.

## 14. Build and embed in CI

1. `pnpm install --frozen-lockfile` (Node 24, pnpm pinned by `packageManager`).
2. `make ui-gen` then `git diff --exit-code` on `openapi.json` and `src/api/` (drift).
3. `pnpm -r build` (`vite build` per app, which also writes Nuxt UI's declaration files), then
   `vue-tsc --build`, `eslint .`, `vitest run`.
4. Playwright on the gallery (behaviour, axe, screenshots, CSP), then the example E2E.
5. `go build ./...` with `web/dist` present; `go test -race ./...` includes a test that the
   embedded SPA serves `index.html` and an asset with the section 9 headers.
6. A licence check over the pnpm lockfile (permissive licences only: Vue, Nuxt UI, Reka UI,
   Tailwind, TanStack, hey-api, Zod are MIT).

A Go-only change still runs steps 2 and 5 (the spec can change); the Playwright steps run when
`ui/`, `web/` or `openapi.json` changed, and nightly.

## 15. Sharing the UI package with the Java framework (decided 2026-10-14: shared)

**Decided by the owner on 2026-10-14: variant A, and wider.** One UI package for both frameworks,
not only for the comparison slice and the admin area: Nuxt UI is the primary UI of the Java
framework too, and JTE + Datastar (M-UI) becomes an optional, deferred module. The four conditions
below are met by contracts: (1) the Java side **emits** its document with the same rules as Go
([`../contracts/openapi.md`](../contracts/openapi.md); a document-conformance test against a running
API is out of scope); (2) the M4c contract is JSON-first and built in Java, with the SPA's needs in
its section 4.5; (3) static files by [`../contracts/static-assets-and-spa.md`](../contracts/static-assets-and-spa.md);
(4) the TypeScript client is generated from each application's own committed document. The "no Node"
rule of the `ui-kit/` stays a rule of the optional JTE module only; a Java application with a UI now
has a `web/` folder and a pnpm build, which CI runs before Maven (static-assets contract section 8).
The analysis below is kept as the reasoning.

The later comparison builds a business slice on Java and on Go. If each has its own UI stack (Go:
Nuxt UI; Java: JTE + Datastar, M-UI), the comparison measures two UI stacks as much as two
backends, and every screen is built twice.

**The option:** `@flowcatalyst/ui` and the slice's SPA are **backend-agnostic**: they talk only to a JSON API
described by an OpenAPI document. A Java application serves the same built SPA if it:

1. implements the same operations with the same wire shapes, checked against the **same committed
   document** (Java has no OpenAPI emitter: either a Java emitter, or a conformance test that
   exercises every operation and validates requests and responses against the document);
2. implements the M4c endpoints of section 8 (decision 29's JSON-first contract);
3. uses the shared error envelope (already a contract) and serves static files with the same rules
   (the section 9 amendment);
4. generates nothing on the TypeScript side: the client comes from the shared document.

| For | Against |
|---|---|
| screens built once; the comparison isolates the backend | Java's M-UI (JTE + Datastar) is then either dropped or a second UI for Java alone |
| one auth contract, one session surface, one set of default pages | Java gains a Node build and a pnpm workspace, against its "no Node" kit rule |
| the framework `/admin` area (M4d, M4e) built once for both | Java needs an OpenAPI emitter or a document-conformance test (new work) |
| the same Playwright E2E runs against both backends: a strong conformance test | the SPA's needs drive the API shape for both (cursor pages, envelope details) |

Variants: **A**, the shared SPA for the comparison slice and the admin area; **B**, Java keeps M-UI
and the comparison accepts the UI difference; **C**, decide when the slice is chosen. Recommendation:
**C, leaning A** (decision 31): A is the only variant in which the comparison is fair to the
backends, but it reverses the Java M-UI plan, which is the owner's call.

## 16. The platform SPA: PrimeVue, the templ port, or Nuxt UI

Not a recommendation to change anything now. The platform has **two** UIs today: the PrimeVue SPA
(the default) and the templ + Datastar port under `/ui` (ten areas at parity, owner-approved
direction 2026-10-09, switched over only with the owner's approval per area). This ruling adds a
third stack in the family. Trade-offs if the platform's UI were ever moved to Nuxt UI:

| For moving the platform to Nuxt UI | Against |
|---|---|
| one component library and one set of `Fw*` components for the platform and every framework application | the templ port's work (ten areas, the kit's fixtures, Datastar error contract) would stop paying off in the platform |
| the platform's Vue knowledge, composables and route structure carry over (same Vue, router, Vite, hey-api) | 110 files to rewrite again; PrimeVue's `DataTable`, `MultiSelect`, `AutoComplete` do more out of the box than `UTable` and `USelectMenu` in a few places (column filters, row grouping) |
| strict generated client and dark mode, which the PrimeVue SPA lacks | Nuxt UI is younger than PrimeVue and its v4 options are still being added (4.2 to 4.11 tags on the installation page) |
| Tailwind tokens shared with the kit, which PrimeVue's preset does not use | the templ port gives the platform one binary with no Node build; a Nuxt UI platform keeps Node |

**Decided 2026-10-14 (owner): the templ `/ui` port continues** (design decision 32). The platform's
dogfood plan is unchanged, and the Go framework offers both options as Java does: Nuxt UI primary,
an **optional templ + Datastar module** later (the counterpart of Java's M-UI, over the same
`ui-kit/` specs and fixtures, drawing on the platform's port). Moving the platform's own UI to Nuxt
UI is not on the table; the table above is kept as the reasoning should it come up again.

## 17. Milestone steps

*Shared since 2026-10-14.* U0 to U4 are milestones of the **shared** UI package, built first against
the Java backend (the Java example `examples/orders-demo` gets the `web/` folder for U3; U4 uses the
built Java M4c endpoints), and reused by Go's G5a and G5b, which then only add the Go side (the
emitter, `httpfw/spa`, sessions). The admin area (Java M4d and M4e, Go G6) is built once as `@flowcatalyst/ui`
pages. Order and estimates: [`../handover.md`](../handover.md), "UI direction".

Inside G5a (UI package) and G5b (sessions), [`milestones.md`](milestones.md):

| Step | Scope | Exit |
|---|---|---|
| U0 | the pnpm workspace, `tsconfig.base.json`, ESLint, the gallery app, Nuxt UI wired without Nuxt, the theme preset from `tokens.css`, bundled Lucide icons, CSP-strict Playwright with axe | the unverified points of sections 3, 5, 9 and 10 answered and recorded; an empty gallery passes axe in both themes under the CSP; may start before G1 |
| U1 | the covered and composed components and the built small ones in the gallery; screenshots approved | every row of section 10 except the shell and partials has its gallery entry and checks |
| U2 | `FwAppShell`, `FwListPage`, `FwRecordDrawer`, `FwFormDialog`, `FwTableToolbar`, `FwCursorPager`, the composables; `@flowcatalyst/api-runtime` | partials run in the gallery against fixture data |
| U3 | after G3: the example SPA on the generated client of the Go orders-demo, served embedded | list, drawer and add-line form against the real server, validation errors on fields, E2E green |
| U4 (G5b) | sign-in, session guard, not-permitted and signed-out pages, CSRF on the real endpoints | the M4c test list; a security review |

## 18. Open doubts

1. **`exactOptionalPropertyTypes` with generated code** and Nuxt UI's types: answered at U0 ([`../ui/u0.md`](../ui/u0.md)): it works, with the two scoped exceptions recorded there.
2. **CSP without `'unsafe-inline'` for styles**: answered at U0 for the components of the gallery (Chromium only; `UBanner` with an `id` and `USplitter` break it and are forbidden by lint); each component added at U1 is checked under it.
3. **hey-api is 0.x**: minor versions change generated output and options; pinned exactly, upgraded
   deliberately with the drift check showing the diff.
4. **Three UI stacks in the family** (PrimeVue SPA, templ `/ui` in the platform, Nuxt UI in both
   frameworks since 2026-10-14), with JTE + Datastar an optional, deferred Java module. The kit's
   specs and tokens are the shared reference; markup is not shared. Settled 2026-10-14: the
   platform's templ port continues, and both frameworks offer the server-rendered stack as an
   optional module (Java M-UI with JTE; Go templ, later) (sections 15, 16).
5. **The Go OpenAPI emitter is new code** on the critical path of the typed spine; the fallback is
   huma's registry (section 7).
6. **Typed ids stop at the API boundary**: TypeScript sees `string` (section 6.2).
7. **Declared constraints versus the parse function**: a constraint in the document (for example a
   maximum length) and the Go parse function can disagree; each operation's tests send a value
   just past each declared constraint and expect `400 VALIDATION`.
