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
export const fcIcons = [
  "i-lucide-check",
  "i-lucide-chevron-left",
  "i-lucide-chevron-right",
  "i-lucide-chevrons-left",
  "i-lucide-circle",
  "i-lucide-circle-check",
  "i-lucide-circle-user",
  "i-lucide-circle-x",
  "i-lucide-copy",
  "i-lucide-ellipsis",
  "i-lucide-inbox",
  "i-lucide-info",
  "i-lucide-list-filter",
  "i-lucide-loader-circle",
  "i-lucide-log-out",
  "i-lucide-monitor",
  "i-lucide-moon",
  "i-lucide-refresh-cw",
  "i-lucide-search",
  "i-lucide-sun",
  "i-lucide-triangle-alert",
  "i-lucide-x",
] as const;

/**
 * Per-component theme overrides (Nuxt UI's `ui` option, app.config's `ui` key in Nuxt), where Nuxt
 * UI's default look departs from the kit:
 * - tabs: the kit's tabs (ui-kit tabs.md) are a bordered list whose current tab sits on a neutral
 *   tint with foreground text. Nuxt UI's default (a grey list, the current tab filled with primary)
 *   puts muted text on `bg-elevated` (4.34:1) and white on emerald-600 (3.76:1), two of the U0
 *   light-theme contrast failures; the kit's look passes.
 */
export const fcComponentTheme = {
  tabs: {
    defaultVariants: { color: "neutral" as const },
    variants: { variant: { pill: { list: "bg-transparent border border-default rounded-[2px]" } } },
    compoundVariants: [
      {
        color: "neutral" as const,
        variant: "pill" as const,
        class: { indicator: "bg-accented rounded-[2px] shadow-none", trigger: "data-[state=active]:text-highlighted" },
      },
    ],
  },
};
