# FlowCatalyst UI workspace

The shared UI of the FlowCatalyst Java and Go frameworks: **Nuxt UI v4 on plain Vue 3 and Vite** (no
Nuxt, no Nitro), strict TypeScript. Design: [`../docs/go/ui.md`](../docs/go/ui.md). Results with the
evidence: U0 [`../docs/ui/u0.md`](../docs/ui/u0.md), U1 (components)
[`../docs/ui/u1.md`](../docs/ui/u1.md), U2 (partials, composables, `@flowcatalyst/api-runtime`)
[`../docs/ui/u2.md`](../docs/ui/u2.md). Which Nuxt UI component to use directly and why each `Fw*` part
exists: [`../docs/ui/nuxt-native.md`](../docs/ui/nuxt-native.md) (Nuxt UI first; a `Fw*` part only for
FlowCatalyst behaviour).

```
ui/
  pnpm-workspace.yaml   catalog (exact versions), minimumReleaseAge 7 days, no install scripts
  tsconfig.base.json    the strict settings every project extends
  eslint.config.ts      typescript-eslint strictTypeChecked + eslint-plugin-vue, one config for all
  scripts/              check-licences.ts (and the THIRD-PARTY-NOTICES it writes), api-drift.ts
  THIRD-PARTY-NOTICES   the licences of the shipped bundle, generated from the gallery build
  packages/ui/          @flowcatalyst/ui: theme (Nuxt UI tokens and variants), Vite plugin, Vue plugin, Fw* parts, partials,
                        FwAppShell, composables (source only)
  packages/api-runtime/ @flowcatalyst/api-runtime: the configured hey-api client, the error envelope, the
                        browser session and its guard, Zod's CSP setting, the shared openapi-ts config,
                        and the generated client of the framework's own endpoints (auth.json)
  apps/gallery/         @flowcatalyst/gallery: the platform behaviour checklist (src/behaviours.ts), on FwAppShell,
                        a generated orders client against an in-page fake server; strict-CSP server, Playwright
  samples/openapi/      a hand-written OpenAPI 3.1 document and the hey-api client generated from it (U0 check)
```

## Commands

Node 24 and pnpm 10.27 (`packageManager`); nothing is installed globally. From `ui/`:

| Command | What it does |
|---|---|
| `pnpm install --frozen-lockfile` | install exactly the committed lockfile |
| `pnpm build` | writes Nuxt UI's generated theme types (`node_modules/.nuxt-ui`) and builds the gallery; run it before `typecheck` |
| `pnpm typecheck` | `tsc --build` (TypeScript 7) for the tooling and every `.ts`-only project, `vue-tsc --build` (TypeScript 6) for the projects with `.vue` files; see "Which TypeScript runs where" |
| `pnpm lint` | ESLint, type-aware |
| `pnpm test` | vitest (components, composables, api-runtime, the generated clients' checks) |
| `pnpm api:drift` | regenerates every generated client (api-runtime, gallery, sample) and fails on a modified or new file |
| `pnpm licences` | fails on a dependency licence that is not permissive and not reviewed, or when `THIRD-PARTY-NOTICES` does not match the gallery build (run after `pnpm build`) |
| `pnpm licences:write` | rewrites `THIRD-PARTY-NOTICES` from the gallery build (an application: `node scripts/check-licences.ts --write --dist <dist> --out <file>`) |
| `pnpm gallery:e2e` | builds the gallery, serves it with the strict CSP (port 4391) and runs Playwright + strict axe; fails when a row of the behaviour checklist is exercised by no passing test |
| `pnpm check` | all of the above, in that order |
| `pnpm gallery:dev` | Vite dev server for the gallery on 5174 (no CSP in development) |
| `pnpm gallery:screenshots` | every gallery page, light and dark, desktop and phone, into `apps/gallery/screenshots/` (git-ignored) for review |

Playwright uses the Chromium build in the user cache; `pnpm --filter @flowcatalyst/gallery exec
playwright install chromium` fetches it once if it is missing.

## Using `@flowcatalyst/ui` in an application

The package exports TypeScript and Vue source; the application's Vite build compiles it. Outside this
repository, link it by path:

```json
"dependencies": {
  "@flowcatalyst/ui": "link:../flowcatalyst-framework/ui/packages/ui",
  "@nuxt/ui": "4.11.3", "vue": "3.5.43", "vue-router": "4.6.4", "tailwindcss": "4.3.3",
  "@iconify-json/lucide": "1.2.140"
}
```

```ts
// vite.config.ts
import ui from "@nuxt/ui/vite";
import { flowcatalystUi, nuxtUiOptions } from "@flowcatalyst/ui/vite";
import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vite";

const options = nuxtUiOptions();   // theme, explicit imports, bundled icons, colour mode off
export default defineConfig({ plugins: [vue(), ui(options), flowcatalystUi(options)] });
```

```ts
// main.ts: @flowcatalyst/api-runtime FIRST (it sets Zod to jitless before any schema exists, for the
// strict CSP), then the router, Nuxt UI and @flowcatalyst/ui; App.vue wraps everything in UApp.
import { createApiRuntime, installSessionGuard } from "@flowcatalyst/api-runtime";
import { client } from "./api/client.gen";

const api = createApiRuntime({ clients: [client] });       // same origin, cookie only, 401 -> sign-in
installSessionGuard(router, api.session, { notPermitted: () => "/not-permitted" });
createApp(App).use(router).use(ui).use(flowcatalystUi, { api }).mount("#app");
```

```css
/* the one CSS entry */
@import "tailwindcss";
@import "@nuxt/ui";
@import "@flowcatalyst/ui/theme.css";
```

The application's `tsconfig` maps `#build/ui` and `#build/ui/*` to its own `node_modules/.nuxt-ui/ui`
(see `apps/gallery/tsconfig.app.json`). `flowcatalystUi()` deduplicates `vue`, `vue-router`, `@nuxt/ui`
and `@vueuse/core`, so a linked package does not bring a second copy (checked with a scratch consumer
at U0: one Vue in the bundle, `vue-tsc` clean, the package's Tailwind classes and icons present).

The strict CSP header the server sends is `strictContentSecurityPolicy()` from `@flowcatalyst/ui/csp`;
the only hash in it is `COLOR_MODE_SNIPPET_SHA256`, the pre-paint colour-mode snippet the Vite plugin
writes into `index.html`.

## Generating an application's client

An application's `web/` generates its client from its committed `openapi.json` with the shared settings
(the contract's hey-api plugins plus two settings found at U2, explained in
`packages/api-runtime/src/openapi-ts.ts` and [`../docs/ui/u2.md`](../docs/ui/u2.md)):

```ts
// web/openapi-ts.config.ts
import { fwOpenApiConfig } from "@flowcatalyst/api-runtime/openapi-ts";
export default fwOpenApiConfig({ input: "openapi.json", output: "src/api" });
```

```json
"scripts": {
  "api:generate": "openapi-ts",
  "api:drift": "openapi-ts && git diff --exit-code -- src/api && test -z \"$(git status --porcelain -- src/api)\""
}
```

The generated folder is committed; CI runs `api:drift` (a modified *or new* file fails it). Its
`client/` and `core/` subfolders need the tsconfig exception below: copy
`apps/gallery/tsconfig.generated.json` and reference it (and
`packages/api-runtime/tsconfig.generated.json`) from the application's `tsconfig.app.json`, which
excludes `src/api/*/client/**` and `src/api/*/core/**`. Import the envelope type as
`ErrorEnvelope`/`ServiceError` from `@flowcatalyst/api-runtime`, never the generated `Error` (it shadows
the global).

## Which TypeScript runs where

TypeScript 7 (owner ruling 2026-10-14). TypeScript 7.0 is the Go port: the `typescript` 7.0.2 package
is a `tsc` launcher for a native binary (from the optional dependency
`@typescript/typescript-<os>-<arch>`) and has **no programmatic API** (its main export is the version;
`typescript/unstable/*` are IPC client modules, with a new API announced for 7.1). Tools that call the
compiler in-process (`ts.createProgram`, the type checker) need TypeScript 6. So the workspace installs
both, the way the TypeScript 7.0 release notes describe (catalog in `pnpm-workspace.yaml`):

| Package name in the workspace | Is | Provides |
|---|---|---|
| `@typescript/native` | `npm:typescript@7.0.2` | the `tsc` binary of every script |
| `typescript` | `npm:@typescript/typescript6@6.0.2` (Microsoft's compatibility package) | the TypeScript 6 API and a `tsc6` binary, from its dependency `@typescript/old`, pinned to `typescript@6.0.3` by `overrides` |

| Tool | TypeScript | Checks |
|---|---|---|
| `tsc --build` (root `tsconfig.json`) | 7.0.2 | `eslint.config.ts`, `scripts/` |
| `tsc --build` in `packages/api-runtime` | 7.0.2 | everything: source, generated clients, tests, Node config |
| `tsc --build` in `samples/openapi` | 7.0.2 | everything |
| `tsc --build tsconfig.node.json` in `packages/ui` and `apps/gallery` | 7.0.2 | the Vite plugin, Vite/Playwright configs, scripts, e2e specs |
| `vue-tsc --build` (3.3.12) for `tsconfig.app.json`/`tsconfig.test.json` in `packages/ui`, `tsconfig.app.json` in `apps/gallery` | 6.0.3 | `.vue` files with `strictTemplates`, and the `.ts` files of those projects |
| ESLint with typescript-eslint 8.71.1 (`strictTypeChecked`, project service) | 6.0.3 | every `.ts` and `.vue` file |
| `@hey-api/openapi-ts` 0.99.0 | 6.0.3 | none: it prints the generated client with the TypeScript factory, so the generated bytes are unchanged |
| Vite, `@vitejs/plugin-vue`, vitest, Node (`node scripts/x.ts`) | none | they strip types; nothing in a build depends on the TypeScript version |

Why: vue-tsc patches the JavaScript `tsc` to add `.vue` files and has no TypeScript 7 mode (3.3.12
detects `@typescript/typescript6` and patches the `tsc.js` behind it); typescript-eslint 8.71 declares
`typescript >=4.8.4 <6.1.0` and needs the checker in-process; hey-api imports `typescript` for its
printer. Every peer named `typescript` therefore resolves to the compatibility package, and nothing
imports TypeScript 7.

`packages/api-runtime/tsconfig.generated.ts7.json` is `tsconfig.generated.json` with its own output and
build info, for that package's own TypeScript 7 build; the Vue packages build `tsconfig.generated.json`
with vue-tsc. A build info written by one compiler version is out of date for the other, so one shared
project would be rebuilt, with everything that references it, on every run.

Editors: the TypeScript 7.0 release notes say Vue projects keep TypeScript 6 in the editor for now
(VS Code: "Disable TypeScript 7 Language Server"). `node_modules/typescript` here is the compatibility
package, which has no `lib/tsserver.js`, so do not point `typescript.tsdk` at it.

An application's `web/` uses the same two catalog entries, the same override, `tsc` for its Node
configs and `vue-tsc --build` for its app project.

**When the 6 goes** (all three, then `typescript` becomes `7.x` and `@typescript/native`,
`@typescript/typescript6`, the override and `tsconfig.generated.ts7.json` go):

1. typescript-eslint: a release whose `typescript` peer range includes 7 with type-aware rules on
   the native compiler. Today 8.71.2 (latest) and the 8.71.3 canaries still declare `<6.1.0`; the work
   is the draft PR typescript-eslint#12803 (`projectService.EXPERIMENTAL_backend: 'native'`, built on
   the TypeScript **7.1** API; tracking issue #10940).
2. vue-tsc (Vue language tools): a release that type-checks `.vue` files on TypeScript 7. Today
   3.3.13 (latest) still requires the TypeScript 6 API; the TypeScript 7.0 release notes say Vue
   tooling stays on 6 until 7.x has a stable API.
3. TypeScript 7.1 (or later) with the programmatic API those two build on; `next` is 7.1.0-dev today.
   @hey-api/openapi-ts must then also work without the TypeScript 6 API (0.99.0 imports it).

## Strict TypeScript: exceptions

The settings are `tsconfig.base.json` (the platform SPA's plus `exactOptionalPropertyTypes` and
`verbatimModuleSyntax`, `strictTemplates`). A project may add settings, never relax one, except:

1. **hey-api's bundled fetch client** (the `client/` and `core/` subfolders of a generated folder,
   hey-api 0.99.0) is its own project reference without `exactOptionalPropertyTypes`
   (`packages/api-runtime/tsconfig.generated.json`, `apps/gallery/tsconfig.generated.json`; the U0
   sample still excludes its whole folder, `samples/openapi/tsconfig.api.json`): it passes
   `T | undefined` to `x?: T` in 12 places. The generated `types.gen.ts`, `sdk.gen.ts`, `zod.gen.ts` and
   `client.gen.ts` compile with the flag on. Because the packages are consumed as source, every project
   that imports `@flowcatalyst/api-runtime` references its `tsconfig.generated.json`, so the runtime
   files are read through their declarations instead of being compiled under the consumer's settings.
2. `vueCompilerOptions.dataAttributes: ["data-*", "aria-*"]`: `strictTemplates` reports any attribute
   that is not a declared prop of a component as unknown, including `aria-label` falling through to a
   Nuxt UI button. Only `data-*` and `aria-*` pass; any other unknown prop or event is still an error.

`allowArbitraryExtensions` (on) and the `*.vue` shim (`src/env.d.ts`) are not relaxations: they let
typescript-eslint, which has no Vue support, read Nuxt UI's `.d.vue.ts` declarations and see the
application's own `.vue` files as components; `vue-tsc` resolves `.vue` files itself.

With `exactOptionalPropertyTypes`, a value bound with `v-model` must not be able to hold `undefined`
when the Nuxt UI prop is `modelValue?: T` (form state holds `""`, not `undefined`); `UTable`'s
`v-model:sorting` cannot be typed at all (the prop excludes `undefined`, the event includes it) and is
split into `:sorting` and `@update:sorting`; `UCheckbox` emits `boolean | "indeterminate"`.

## Rules the lint enforces

No `any`, no casts, no non-null assertions, exhaustive switches, no floating promises; typed props,
emits and refs in every component; no `v-html`; no static `style` attribute in a template; no
PrimeVue, Pinia, axios or ofetch; no `@nuxt/ui` `Banner`/`Splitter` components or the `color-mode`
components (they break the strict CSP; the gallery's probes are the exception); `localStorage` and
`sessionStorage` (bare or as `window.` properties) only in `useLocalState`, tests and scripts.

Two lint settings for `.vue` files: `@typescript-eslint/no-useless-default-assignment` is off (it reads
`defineProps`' return type, where every prop is required, and calls each reactive-props-destructure
default useless) and `vue/require-default-prop` is off (an absent optional prop is meaningful under
`exactOptionalPropertyTypes`). Types shared by components live in `.ts` modules (`src/types.ts`): the
type-aware lint sees a `.vue` import only through the `*.vue` shim, so a type exported from a `.vue`
file is unreadable to it.
