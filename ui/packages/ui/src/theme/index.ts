// The FlowCatalyst theme preset for Nuxt UI (docs/go/ui.md section 11).
//
// In plain Vue the Nuxt UI Vite plugin's `ui` option plays the part of Nuxt's `app.config.ts`.
// `fcTheme` holds the colour aliases and the alias list; `kit.css` (next to this file) holds the
// token values copied from ui-kit/theme/tokens.css. Both are data: no imports, safe to load in the
// Vite config (Node) and in the browser.

/** Nuxt UI colour aliases mapped to Tailwind palettes (ui-kit tokens: section 11 of docs/go/ui.md). */
export const fcColors = {
  primary: "emerald",
  // The kit has no secondary colour; Nuxt UI's default is kept.
  secondary: "blue",
  success: "green",
  info: "sky",
  warning: "orange",
  error: "red",
  neutral: "slate",
} as const;

export type FcColorAlias = keyof typeof fcColors;

/** The aliases Nuxt UI generates variants for (its default list, made explicit). */
export const fcColorAliases = [
  "primary",
  "secondary",
  "success",
  "info",
  "warning",
  "error",
] as const satisfies readonly Exclude<FcColorAlias, "neutral">[];

/**
 * Icons used by @flowcatalyst/ui's own components. The Nuxt UI icon scan only reads the
 * application's own files, so the package lists its icons here and `nuxtUiOptions()` adds them to
 * the client bundle (a unit test checks the list against the sources).
 */
export const fcIcons = ["i-lucide-moon", "i-lucide-sun", "i-lucide-monitor"] as const;
