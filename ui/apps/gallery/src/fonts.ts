// The font comparison (docs/ui/fonts.md): three alternatives to the theme's default Inter, rendered by
// the same gallery screens with `?font=<id>`. They are self-hosted Fontsource packages (OFL-1.1,
// `font-src 'self'`), devDependencies of the gallery only, and they are loaded only when the build
// is `vite` (dev) or `vite build --mode fonts` (__FONT_COMPARISON__, vite.config.ts). In the default
// production build the dynamic imports below are dead code: no comparison font file reaches `dist/`
// (so the THIRD-PARTY-NOTICES measured on it stay Inter's), and `?font=` does nothing. The theme's
// default font is not touched: this only overrides `--font-sans` on <html> (main.css).

import { comparisonFonts, type ComparisonFont } from "./font-list.ts";

export { comparisonFonts, type ComparisonFont };

/** True in the dev server and the `fonts` build; false in the default production build. */
export const fontComparisonAvailable: boolean = __FONT_COMPARISON__;

const loaders: Readonly<Record<string, () => Promise<unknown>>> = __FONT_COMPARISON__
  ? {
      "ibm-plex-sans": () => import("@fontsource-variable/ibm-plex-sans"),
      "source-sans-3": () => import("@fontsource-variable/source-sans-3"),
      geist: () => import("@fontsource-variable/geist"),
    }
  : {};

const classPrefix = "fc-font-";

/** The comparison font named by a `?font=` value, or undefined (absent, unknown, or not available). */
export function comparisonFontFor(value: unknown): ComparisonFont | undefined {
  if (!fontComparisonAvailable || typeof value !== "string") {
    return undefined;
  }
  return comparisonFonts.find((font) => font.id === value);
}

/** Loads the font's files (same origin) and sets its class on <html>; the default font clears it. */
export async function applyComparisonFont(font: ComparisonFont | undefined): Promise<void> {
  const root = document.documentElement;
  for (const name of [...root.classList]) {
    if (name.startsWith(classPrefix)) {
      root.classList.remove(name);
    }
  }
  if (font === undefined || font.id === "inter") {
    return;
  }
  await loaders[font.id]?.();
  root.classList.add(`${classPrefix}${font.id}`);
}
