// The font comparison's fonts, as data (read by src/fonts.ts, the page and the Playwright specs).

export interface ComparisonFont {
  readonly id: string;
  readonly label: string;
  /** The family name Fontsource declares (also the first entry of `--font-sans` in main.css). */
  readonly family: string;
}

/** The default (the theme's: IBM Plex Sans) first, then Inter (the former default) and the other candidates. */
export const comparisonFonts: readonly ComparisonFont[] = [
  { id: "ibm-plex-sans", label: "IBM Plex Sans (default)", family: "IBM Plex Sans Variable" },
  { id: "inter", label: "Inter", family: "Inter Variable" },
  { id: "source-sans-3", label: "Source Sans 3", family: "Source Sans 3 Variable" },
  { id: "geist", label: "Geist", family: "Geist Variable" },
];
