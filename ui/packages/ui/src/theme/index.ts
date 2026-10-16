// The FlowCatalyst theme preset for Nuxt UI (docs/ui/nuxt-native.md, "Theme").
//
// In plain Vue the Nuxt UI Vite plugin's `ui` option plays the part of Nuxt's `app.config.ts`: the
// colour aliases and the component variants below go there (nuxtUiOptions() in ../vite.ts).
// `theme.css` (next to this file) sets Nuxt UI's design tokens (--ui-*) from the kit's values. Both
// are data: no imports, safe to load in the Vite config (Node) and in the browser.

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
 * the client bundle (a unit test checks the list against the sources). Nuxt UI's own icons (its
 * app config's `ui.icons`: loading, close, chevrons, ...) are bundled by its plugin.
 */
export const fcIcons = [
  "i-lucide-arrow-left",
  "i-lucide-check",
  "i-lucide-chevron-left",
  "i-lucide-chevron-right",
  "i-lucide-chevrons-left",
  "i-lucide-circle",
  "i-lucide-circle-alert",
  "i-lucide-circle-check",
  "i-lucide-circle-user",
  "i-lucide-circle-x",
  "i-lucide-copy",
  "i-lucide-ellipsis",
  "i-lucide-inbox",
  "i-lucide-info",
  "i-lucide-list-filter",
  "i-lucide-log-in",
  "i-lucide-log-out",
  "i-lucide-monitor",
  "i-lucide-moon",
  "i-lucide-panel-left-close",
  "i-lucide-panel-left-open",
  "i-lucide-refresh-cw",
  "i-lucide-search",
  "i-lucide-sun",
  "i-lucide-triangle-alert",
  "i-lucide-x",
] as const;

/**
 * Per-component variants (Nuxt UI's `ui` option, app.config's `ui` key in Nuxt), where Nuxt UI's
 * default look departs from the kit or fails the gallery's accessibility checks:
 * - tabs: the kit's tabs (a bordered list, the current tab on a neutral tint with foreground text).
 *   Nuxt UI's default put muted text on `bg-elevated` (4.34:1) and white on the primary fill.
 * - pageHeader: UPageHeader is the page's title block (its `<h1>`, description and links); Nuxt UI's
 *   default is a marketing header (4xl title, a rule, 2rem padding). The kit's is compact.
 * - alert: the description at full opacity. Nuxt UI draws it at 90 %, which takes the success and
 *   warning colours of an outline alert below 4.5:1 (4.17 and 4.42 on white).
 * - navigationMenu (vertical) and dashboardSidebar: comfortable sidebar spacing (owner feedback
 *   2026-10-14; Nuxt UI's defaults are tight). Item 30 -> 40 px high (py-1.5 -> py-2.5; the target
 *   is 24 px at least, WCAG 2.2 SC 2.5.8), icon-to-label gap 6 -> 12 px, 4 px between items (0), 16 px
 *   between groups (6), section headings spaced above and below, sidebar body 8 -> 16 px vertical;
 *   collapsed: a 40 px square target in a 64 px rail (body padding 16 -> 12 px). Horizontal menus
 *   keep Nuxt UI's defaults.
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
  pageHeader: {
    slots: {
      root: "mb-4 border-b-0 py-0",
      wrapper: "flex flex-row flex-wrap items-start justify-between gap-x-4 gap-y-2",
      title: "text-2xl sm:text-2xl font-semibold",
      description: "text-sm text-muted",
      links: "flex flex-wrap items-center gap-2",
    },
    variants: { title: { true: { description: "mt-1" } } },
  },
  alert: {
    slots: { description: "opacity-100" },
  },
  navigationMenu: {
    variants: {
      orientation: {
        vertical: {
          root: "gap-4",
          list: "flex flex-col gap-1",
          label: "px-3 pb-1.5 pt-2 text-xs/5 uppercase tracking-wide",
          link: "gap-3 px-3 py-2.5",
        },
      },
    },
    compoundVariants: [{ orientation: "vertical" as const, collapsed: true, class: { link: "justify-center px-2.5" } }],
  },
  dashboardSidebar: {
    slots: {
      header: "px-6",
      body: "gap-6 px-3 py-4",
      footer: "px-3 py-3",
    },
    variants: { menu: { true: { header: "sm:px-6", body: "sm:px-3", footer: "sm:px-3" } } },
  },
};
