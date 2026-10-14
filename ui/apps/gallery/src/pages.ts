// The gallery's pages, in sidebar order: data only, so the router, the sidebar and the Playwright
// suite (Node) read the same list. Every page has one <h1> (FwPageHeader) with `heading`.

export interface GalleryPage {
  readonly path: string;
  /** The sidebar label. */
  readonly title: string;
  /** The page's <h1>. */
  readonly heading: string;
  readonly icon: string;
}

export const pages: readonly GalleryPage[] = [
  { path: "/", title: "Overview", heading: "FlowCatalyst UI gallery", icon: "i-lucide-layout-grid" },
  { path: "/actions", title: "Actions", heading: "Actions", icon: "i-lucide-mouse-pointer-click" },
  { path: "/overlays", title: "Overlays", heading: "Overlays", icon: "i-lucide-layers" },
  { path: "/forms", title: "Forms", heading: "Forms", icon: "i-lucide-text-cursor-input" },
  { path: "/data", title: "Data display", heading: "Data display", icon: "i-lucide-table" },
  { path: "/feedback", title: "Feedback", heading: "Feedback", icon: "i-lucide-bell" },
  { path: "/values", title: "Values and code", heading: "Values and code", icon: "i-lucide-braces" },
  { path: "/lists", title: "List parts", heading: "List parts", icon: "i-lucide-list" },
  { path: "/orders", title: "Orders (partials)", heading: "Orders", icon: "i-lucide-shopping-cart" },
  { path: "/shell", title: "Shell", heading: "Application shell", icon: "i-lucide-panel-left" },
  { path: "/fonts", title: "Fonts", heading: "Font comparison", icon: "i-lucide-type" },
];
