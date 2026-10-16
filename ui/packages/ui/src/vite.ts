// Vite integration for applications built on @flowcatalyst/ui (Node only; imported by vite.config.ts).
//
//   import vue from "@vitejs/plugin-vue";
//   import ui from "@nuxt/ui/vite";
//   import { flowcatalystUi, nuxtUiOptions } from "@flowcatalyst/ui/vite";
//
//   const options = nuxtUiOptions();
//   export default defineConfig({ plugins: [vue(), ui(options), flowcatalystUi(options)] });
//
// What flowcatalystUi() adds to Nuxt UI, and why (docs/go/ui.md sections 5 and 9; the U0 results
// are in docs/ui/u0.md). Everything here is about the strict Content-Security-Policy
// (`style-src 'self'`, no 'unsafe-inline', no nonce) of the static-assets contract, section 7:
// 1. Nuxt UI's runtime colour plugin writes its colour variables into an inline <style> element
//    (through @unhead/vue, and once directly). It is replaced by a no-op that imports the same CSS
//    as a real stylesheet, generated here at build time from the same colour map. Any other Nuxt UI
//    runtime plugin fails the build until it has been reviewed.
// 2. Libraries built with vite-plugin-css-injected-by-js (vaul-vue, under UDrawer, which
//    UDashboardSidebar and USidebar import) append a <style> element when their module loads. The
//    CSS is lifted out of the module into a real stylesheet at build time.
// 3. The colour-mode snippet (src/csp.ts) is written into index.html, so the strict CSP allows it
//    by its hash and a dark page does not flash light.
//    The hashes of every inline script of the built index.html are written to `csp-hashes.json`
//    beside it, which the Java `Spa` reads for its strict policy (so no hash is copied into code).
// 4. Fonts are never inlined as data: URIs (`font-src 'self'`).
// 5. vue, vue-router, @nuxt/ui, @vueuse/core, reka-ui and zod are deduplicated (DEDUPED_PACKAGES), so a
//    consumer that links this package (`"@flowcatalyst/ui": "link:..."`) gets one copy of each, from
//    its own lockfile.
import { createHash } from "node:crypto";
import { writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import type { NuxtUIOptions } from "@nuxt/ui/vite";
import tailwindColors from "tailwindcss/colors";
import type { Plugin } from "vite";
import { COLOR_MODE_SNIPPET } from "./csp.ts";
import { fcColorAliases, fcColors, fcComponentTheme, fcIcons } from "./theme/index.ts";

type ColorMap = Readonly<Record<string, string>>;
type Palettes = Readonly<Record<string, Readonly<Record<string, string>>>>;

export interface NuxtUiOverrides {
  /** Colour aliases to change, for example `{ primary: "blue" }`. */
  readonly colors?: Readonly<Record<string, string>>;
  /** Icons named at run time (not as literals in the application's .vue files). */
  readonly icons?: readonly string[];
}

/** Nuxt UI's Vite plugin options with the FlowCatalyst theme and the package's rules. */
export function nuxtUiOptions(overrides: NuxtUiOverrides = {}): NuxtUIOptions {
  const colors: Record<string, string> = { ...fcColors, ...overrides.colors };
  return {
    ui: { colors, ...fcComponentTheme },
    theme: { colors: [...fcColorAliases] },
    router: true,
    // Explicit imports only (docs/go/ui.md section 5): no generated globals to trust.
    components: false,
    autoImport: false,
    // Nuxt UI's colour mode calls VueUse's useColorMode with `disableTransition`, which inserts an
    // inline <style> on every switch. The package's useColorMode() replaces it.
    colorMode: false,
    icon: {
      clientBundle: {
        scan: true,
        icons: [...fcIcons, ...(overrides.icons ?? [])],
      },
    },
  };
}

const SHADES = [50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950] as const;

/** The CSS Nuxt UI's colour plugin would inject at run time, as a stylesheet (the same rules). */
export function colorsCss(colors: ColorMap, palettes: Palettes): string {
  const shadeLines: string[] = [];
  const light: string[] = [];
  const dark: string[] = [];
  for (const [alias, palette] of Object.entries(colors)) {
    const name = palette === "neutral" ? "old-neutral" : palette;
    for (const shade of SHADES) {
      const fallback = palettes[palette]?.[String(shade)] ?? "";
      shadeLines.push(`--ui-color-${alias}-${String(shade)}: var(--color-${name}-${String(shade)}, ${fallback});`);
    }
    if (alias !== "neutral") {
      light.push(`--ui-${alias}: var(--ui-color-${alias}-500);`);
      dark.push(`--ui-${alias}: var(--ui-color-${alias}-400);`);
    }
  }
  return [
    "@layer theme {",
    `  :root, :host {\n    ${shadeLines.join("\n    ")}\n  }`,
    `  :root, :host, .light {\n    ${light.join("\n    ")}\n  }`,
    `  .dark {\n    ${dark.join("\n    ")}\n  }`,
    "}",
    "",
  ].join("\n");
}

const INJECTOR_MARKER = "vite-plugin-css-injected-by-js";
const INJECTOR_START = '(function(){"use strict";';
const INJECTOR_END = `console.error("${INJECTOR_MARKER}",r)}})();`;
const INJECTED_TEXT = /document\.createTextNode\('((?:[^'\\]|\\.)*)'\)/;

/**
 * Splits a module built with vite-plugin-css-injected-by-js into its CSS and the module without
 * the injector. Returns undefined when the module has no injector; throws when it has one in a
 * shape this function does not know (the build must not ship it unreviewed).
 */
export function liftInjectedCss(code: string): { css: string; code: string } | undefined {
  if (!code.includes(INJECTOR_MARKER)) {
    return undefined;
  }
  const start = code.indexOf(INJECTOR_START);
  const end = code.indexOf(INJECTOR_END);
  const text = INJECTED_TEXT.exec(code.slice(start, end))?.[1];
  if (start === -1 || end === -1 || text === undefined) {
    throw new Error(`a ${INJECTOR_MARKER} block in an unknown shape`);
  }
  // A rule whose selector ends in ":" is invalid, so browsers drop it; Vite's CSS minifier
  // (lightningcss) refuses it instead. vaul-vue 0.4.1 has one (`[data-vaul-handle-hitarea]:{...}`).
  const css = text.replace(/\\(.)/g, "$1").replace(/(^|[{}])[^{}@]*?:\s*\{[^{}]*\}/g, "$1");
  return { css, code: code.slice(0, start) + code.slice(end + INJECTOR_END.length) };
}

/** Tailwind's palettes as plain records (the colour fallbacks Nuxt UI's plugin uses). */
function tailwindPalettes(): Record<string, Record<string, string>> {
  const result: Record<string, Record<string, string>> = {};
  for (const [name, value] of Object.entries(tailwindColors)) {
    if (typeof value === "object") {
      result[name] = Object.fromEntries(Object.entries(value).map(([shade, color]) => [shade, color]));
    }
  }
  return result;
}

/**
 * Packages resolved from the application's root, never from this package's own location. A package
 * linked with `link:` imports from its real path, which is outside the application's lockfile; for
 * anything that holds module state that would be a second copy (Vue, the router, Nuxt UI and Reka UI
 * contexts, VueUse, and Zod, whose `jitless` setting is made by @flowcatalyst/api-runtime and must
 * reach the copy the generated schemas use). Each is a peer dependency, so the application has it.
 */
export const DEDUPED_PACKAGES: readonly string[] = ["vue", "vue-router", "@nuxt/ui", "@vueuse/core", "reka-ui", "zod"];

/** The file written beside the built index.html: `{"scriptHashes": ["sha256-..."]}`. */
export const CSP_HASHES_FILE = "csp-hashes.json";

const INLINE_SCRIPT = /<script(?![^>]*\bsrc=)([^>]*)>([\s\S]*?)<\/script>/gi;
const SCRIPT_TYPE = /\btype=["']([^"']*)["']/i;

/** Whether a `<script type>` runs (a JSON or template data block does not, so needs no hash). */
function runs(type: string): boolean {
  const t = type.trim().toLowerCase();
  return t === "" || t === "module" || t.includes("javascript");
}

/** `sha256-<base64>` of each inline script's exact text, in page order, once each. */
export function inlineScriptHashes(html: string): string[] {
  const hashes = new Set<string>();
  for (const match of html.matchAll(INLINE_SCRIPT)) {
    const type = SCRIPT_TYPE.exec(match[1] ?? "")?.[1];
    if (type === undefined || runs(type)) {
      hashes.add(`sha256-${createHash("sha256").update(match[2] ?? "", "utf8").digest("base64")}`);
    }
  }
  return [...hashes];
}

/** The text of {@link CSP_HASHES_FILE}. */
export function cspHashesJson(scriptHashes: readonly string[]): string {
  return `${JSON.stringify({ scriptHashes }, null, 2)}\n`;
}

type EmittedFile =
  | { readonly type: "asset"; readonly fileName: string; readonly source: string | Uint8Array }
  | { readonly type: "chunk"; readonly fileName: string };

/** Writes {@link CSP_HASHES_FILE} into `dir` for the html pages of a bundle. */
export function writeCspHashes(dir: string, bundle: Readonly<Record<string, EmittedFile>>): void {
  const hashes = new Set<string>();
  for (const file of Object.values(bundle)) {
    if (file.type === "asset" && file.fileName.endsWith(".html")) {
      const html = typeof file.source === "string" ? file.source : new TextDecoder().decode(file.source);
      for (const hash of inlineScriptHashes(html)) hashes.add(hash);
    }
  }
  writeFileSync(join(dir, CSP_HASHES_FILE), cspHashesJson([...hashes]));
}

const VIRTUAL_PREFIX = "virtual:flowcatalyst-ui/";
const RESOLVED_PREFIX = "/__flowcatalyst-ui/";
const NUXT_UI_RUNTIME_PLUGIN = /[\\/]@nuxt[\\/]ui[\\/]dist[\\/]runtime[\\/]plugins[\\/]([^\\/]+)\.js$/;
/** Nuxt UI runtime plugins reviewed for the strict CSP; anything new fails the build. */
const REVIEWED_RUNTIME_PLUGINS = new Set(["colors"]);

/** The FlowCatalyst additions to Nuxt UI's Vite plugin; pass the same options object to both. */
export function flowcatalystUi(options: NuxtUIOptions): Plugin {
  const colors: ColorMap = { ...(options.ui?.colors ?? fcColors) };
  const lifted = new Map<string, string>();
  // The strict CSP is enforced by the server that serves a build; the dev server sends none, and its
  // pre-bundled dependencies are reshaped by the bundler (U3 found the dev server failing on vaul-vue
  // with "a block in an unknown shape"), so the lift is for production builds only.
  let building = false;
  return {
    name: "flowcatalyst:ui",
    enforce: "pre",
    configResolved(config) {
      building = config.command === "build";
    },
    config() {
      return {
        resolve: { dedupe: [...DEDUPED_PACKAGES] },
        build: {
          assetsInlineLimit: (file: string) => (/\.(woff2?|ttf|otf|eot)$/i.test(file) ? false : undefined),
        },
      };
    },
    resolveId(id) {
      return id.startsWith(VIRTUAL_PREFIX) ? RESOLVED_PREFIX + id.slice(VIRTUAL_PREFIX.length) : undefined;
    },
    load(id) {
      if (id === `${RESOLVED_PREFIX}colors.css`) {
        return colorsCss(colors, tailwindPalettes());
      }
      if (id.startsWith(RESOLVED_PREFIX)) {
        return lifted.get(id);
      }
      const plugin = NUXT_UI_RUNTIME_PLUGIN.exec(id)?.[1];
      if (plugin === "colors") {
        return `import "${VIRTUAL_PREFIX}colors.css";\nexport default { install() {} };\n`;
      }
      if (plugin !== undefined && !REVIEWED_RUNTIME_PLUGINS.has(plugin)) {
        this.error(
          `Nuxt UI runtime plugin "${plugin}" has not been reviewed for the strict CSP. Check it for ` +
            "inline <style>/<script> elements, then add it to REVIEWED_RUNTIME_PLUGINS in @flowcatalyst/ui/src/vite.ts.",
        );
      }
      return undefined;
    },
    transform(code, id) {
      if (!building || !id.includes("/node_modules/")) {
        return undefined;
      }
      let split: { css: string; code: string } | undefined;
      try {
        split = liftInjectedCss(code);
      } catch (error) {
        this.error(`${id}: ${error instanceof Error ? error.message : String(error)}`);
      }
      if (split === undefined) {
        return undefined;
      }
      const name = `injected-${String(lifted.size)}.css`;
      lifted.set(RESOLVED_PREFIX + name, split.css);
      return { code: `import "${VIRTUAL_PREFIX}${name}";\n${split.code}`, map: null };
    },
    writeBundle(outputOptions, bundle) {
      const dir = outputOptions.dir ?? (outputOptions.file === undefined ? undefined : dirname(outputOptions.file));
      if (dir !== undefined) {
        writeCspHashes(dir, bundle);
      }
    },
    transformIndexHtml: {
      order: "pre",
      handler() {
        return [{ tag: "script", children: COLOR_MODE_SNIPPET, injectTo: "head-prepend" }];
      },
    },
  };
}
