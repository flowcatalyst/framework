# FlowCatalyst UI workspace

The shared UI of the FlowCatalyst Java and Go frameworks: **Nuxt UI v4 on plain Vue 3 and Vite** (no
Nuxt, no Nitro), strict TypeScript. Design: [`../docs/go/ui.md`](../docs/go/ui.md). Results with the
evidence: U0 [`../docs/ui/u0.md`](../docs/ui/u0.md), U1 (components)
[`../docs/ui/u1.md`](../docs/ui/u1.md), U2 (partials, composables, `@flowcatalyst/api-runtime`)
[`../docs/ui/u2.md`](../docs/ui/u2.md).

```
ui/
  pnpm-workspace.yaml   catalog (exact versions), minimumReleaseAge 7 days, no install scripts
  tsconfig.base.json    the strict settings every project extends
  eslint.config.ts      typescript-eslint strictTypeChecked + eslint-plugin-vue, one config for all
  scripts/              check-licences.ts, api-drift.ts
  packages/ui/          @flowcatalyst/ui: theme preset, Vite plugin, Vue plugin, Fw* components, partials,
                        FwAppShell, composables (source only)
  packages/api-runtime/ @flowcatalyst/api-runtime: the configured hey-api client, the error envelope, the
                        browser session and its guard, Zod's CSP setting, the shared openapi-ts config,
                        and the generated client of the framework's own endpoints (auth.json)
  apps/gallery/         @flowcatalyst/gallery: every spec of ui-kit/components in the gallery, on FwAppShell,
                        a generated orders client against an in-page fake server; strict-CSP server, Playwright
  samples/openapi/      a hand-written OpenAPI 3.1 document and the hey-api client generated from it (U0 check)
```

## Commands

Node 24 and pnpm 10.27 (`packageManager`); nothing is installed globally. From `ui/`:

| Command | What it does |
|---|---|
| `pnpm install --frozen-lockfile` | install exactly the committed lockfile |
| `pnpm build` | writes Nuxt UI's generated theme types (`node_modules/.nuxt-ui`) and builds the gallery; run it before `typecheck` |
| `pnpm typecheck` | `tsc --build` for the tooling, `vue-tsc --build` per package |
| `pnpm lint` | ESLint, type-aware |
| `pnpm test` | vitest (components, composables, api-runtime, the generated clients' checks) |
| `pnpm api:drift` | regenerates every generated client (api-runtime, gallery, sample) and fails on a modified or new file |
| `pnpm licences` | fails on a dependency licence that is not permissive and not reviewed |
| `pnpm gallery:e2e` | builds the gallery, serves it with the strict CSP (port 4391) and runs Playwright + axe |
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
@import "@flowcatalyst/ui/theme/kit.css";
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
