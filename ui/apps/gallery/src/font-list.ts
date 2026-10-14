// The font comparison's fonts, as data (read by src/fonts.ts, the page and the Playwright specs).

export interface ComparisonFont {
  readonly id: string;
  readonly label: string;
  /** The family name Fontsource declares (also the first entry of `--font-sans` in main.css). */
  readonly family: string;
}

/** The default (the theme's: Inter) first, then the three candidates. */
export const comparisonFonts: readonly ComparisonFont[] = [
  { id: "inter", label: "Inter (default)", family: "Inter Variable" },
  { id: "ibm-plex-sans", label: "IBM Plex Sans", family: "IBM Plex Sans Variable" },
  { id: "source-sans-3", label: "Source Sans 3", family: "Source Sans 3 Variable" },
  { id: "geist", label: "Geist", family: "Geist Variable" },
];
