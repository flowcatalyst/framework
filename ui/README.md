# FlowCatalyst UI workspace

The shared UI of the FlowCatalyst Java and Go frameworks: **Nuxt UI v4 on plain Vue 3 and Vite** (no
Nuxt, no Nitro), strict TypeScript. Design: [`../docs/go/ui.md`](../docs/go/ui.md). What U0 verified,
with the evidence: [`../docs/ui/u0.md`](../docs/ui/u0.md).

```
ui/
  pnpm-workspace.yaml   catalog (exact versions), minimumReleaseAge 7 days, no install scripts
  tsconfig.base.json    the strict settings every project extends
  eslint.config.ts      typescript-eslint strictTypeChecked + eslint-plugin-vue, one config for all
  scripts/              check-licences.ts
  packages/ui/          @flowcatalyst/ui: theme preset, Vite plugin, Vue plugin, Fw* components (source only)
  apps/gallery/         @flowcatalyst/gallery: the component gallery, its strict-CSP server and Playwright suite
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
| `pnpm test` | vitest (the package's unit and component tests, the generated client's checks) |
| `pnpm api:drift` | regenerates `samples/openapi/src/api` and fails on a diff |
| `pnpm licences` | fails on a dependency licence that is not permissive and not reviewed |
| `pnpm gallery:e2e` | builds the gallery, serves it with the strict CSP (port 4391) and runs Playwright + axe |
| `pnpm check` | all of the above, in that order |
| `pnpm gallery:dev` | Vite dev server for the gallery on 5174 (no CSP in development) |

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
// main.ts: router, then Nuxt UI, then @flowcatalyst/ui; App.vue wraps everything in UApp
import "./zod-csp.ts";   // config({ jitless: true }) before any schema exists (strict CSP)
createApp(App).use(router).use(ui).use(flowcatalystUi).mount("#app");
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

## Strict TypeScript: exceptions

The settings are `tsconfig.base.json` (the platform SPA's plus `exactOptionalPropertyTypes` and
`verbatimModuleSyntax`, `strictTemplates`). A project may add settings, never relax one, except:

1. **A generated client folder** (`src/api/`, hey-api 0.99.0) is its own project reference
   without `exactOptionalPropertyTypes` (`samples/openapi/tsconfig.api.json`): hey-api's bundled fetch
   client passes `T | undefined` to `x?: T` in 12 places. The generated `types.gen.ts`, `sdk.gen.ts`
   and `zod.gen.ts` compile with the flag on, and code using the folder reads it under the full settings.
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
`sessionStorage` only in tests and scripts until `useLocalState` (U2) exists.
