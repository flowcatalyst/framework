# Font comparison and sidebar spacing

Owner feedback on the gallery screenshots, 2026-10-14: the sidebar spacing was tight, and Inter may not
be the best font. The default font is **unchanged** (Inter); this page records the comparison tooling and
the three candidates. Code: `ui/apps/gallery/src/fonts.ts`, `src/font-list.ts`, `src/pages/FontsPage.vue`,
`e2e-fonts/`.

## Sidebar spacing

Set in `fcComponentTheme` (`ui/packages/ui/src/theme/index.ts`) as Nuxt UI variants: `navigationMenu`
(vertical only; horizontal menus keep Nuxt UI's defaults) and `dashboardSidebar`.

| | Before (Nuxt UI) | After |
|---|---|---|
| Item height | 30 px (`py-1.5`) | 40 px (`py-2.5`); WCAG 2.2 target size is 24 px |
| Item inline padding | 10 px | 12 px |
| Icon to label | 6 px | 12 px |
| Between items | 0 | 4 px |
| Between groups | 6 px | 16 px |
| Section heading | `px-2.5 py-1.5` | `px-3 pt-2 pb-1.5`, uppercase, tracked, `navy-300` on the navy sidebar |
| Sidebar body | `px-4 py-2`, `gap-4` | `px-3 py-4`, `gap-6` |
| Header, footer | `px-4`, footer `py-2` | header `px-6` (brand text aligns with the icons), footer `px-3 py-3` |
| Collapsed rail (64 px) | 32 px target | 40 px square target, icon centred; footer buttons stacked |

The gallery's navigation now has two section headings ("Components", "Application"). Found on the way:
the phone slideover was the page's white under navy-100 text (the `root` classes do not reach it); the
shell now sets `content` too.

## Font comparison

`/fonts?font=<id>` renders the page's screens (the shell with its navigation, glyph samples, a dense
table of IDs, timestamps and numerals, a form, a code block) in the chosen font; the font stays on while
you move between pages. The switcher is on the page. Fonts are self-hosted Fontsource packages
(`font-src 'self'`, no `data:` URLs, no foreign request); they load only in the gallery's dev server
(`pnpm gallery:dev`) and in `vite build --mode fonts` (`dist-fonts/`). The default production build
(`dist/`, which `THIRD-PARTY-NOTICES` is measured on) has none of them and ignores `?font=`;
`pnpm licences` fails if a font other than Inter's is in it.

`pnpm gallery:fonts` (from `ui/`) builds `dist-fonts/`, serves it with the strict CSP on port 4392 and
screenshots every font, light and dark, at 1440 px width, full height, into
`ui/apps/gallery/screenshots/fonts/<font>-<light|dark>.png` (git-ignored). It also asserts the page is
really set in each family and that the CSP monitor saw nothing.

| Font | Package (OFL-1.1) | Scripts | Latin woff2 |
|---|---|---|---|
| Inter (default) | `@fontsource-variable/inter` 5.3.0 | Latin, Latin Ext, Cyrillic, Cyrillic Ext, Greek, Greek Ext, Vietnamese | 48 KB |
| IBM Plex Sans | `@fontsource-variable/ibm-plex-sans` 5.3.0 | Latin, Latin Ext, Cyrillic, Cyrillic Ext, Greek, Vietnamese | 46 KB |
| Source Sans 3 | `@fontsource-variable/source-sans-3` 5.3.0 | Latin, Latin Ext, Cyrillic, Cyrillic Ext, Greek, Greek Ext, Vietnamese | 29 KB |
| Geist | `@fontsource-variable/geist` 5.3.0 | Latin, Latin Ext, Cyrillic, Cyrillic Ext, Vietnamese | 29 KB |

Not chosen: Public Sans (Latin only), Figtree and DM Sans (geometric, rounder, weaker numerals for
dense tables). Source Sans 3 5.3.1 is under the 7-day `minimumReleaseAge`; 5.3.0 is pinned.
Geist draws `[]` as an empty box at small sizes (see its screenshot): check that before adopting it.

## Adopting a font

The default is two lines at the top of `ui/packages/ui/src/theme/theme.css`: the `@import
"@fontsource-variable/inter"` line and the first family of `--font-sans`. To adopt a candidate: move its
package from the gallery's devDependencies to `@flowcatalyst/ui`'s dependencies (replacing Inter's),
change those two lines (the family name is in the table's package CSS: `"IBM Plex Sans Variable"`,
`"Source Sans 3 Variable"`, `"Geist Variable"`), change the `inter-` test in
`ui/scripts/check-licences.ts` (the stray-font guard and the notice line), and run `pnpm licences:write`
so the OFL notice of the adopted font joins `THIRD-PARTY-NOTICES` (only fonts in the default bundle are
listed). The comparison fonts' reviewed entries in `check-licences.ts` say "comparison only".
