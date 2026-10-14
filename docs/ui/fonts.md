# Font comparison and sidebar spacing

Owner feedback on the gallery screenshots, 2026-10-14: the sidebar spacing was tight, and Inter may not
be the best font. **Status: adopted.** The default font is now **IBM Plex Sans** (owner ruling,
2026-10-14); Inter, the former default, stays in the comparison. This page records the comparison
tooling and the candidates. Code: `ui/apps/gallery/src/fonts.ts`, `src/font-list.ts`, `src/pages/FontsPage.vue`,
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
you move between pages. The switcher is on the page. The default is `@flowcatalyst/ui`'s dependency; the comparison fonts are self-hosted Fontsource packages
(`font-src 'self'`, no `data:` URLs, no foreign request); they load only in the gallery's dev server
(`pnpm gallery:dev`) and in `vite build --mode fonts` (`dist-fonts/`). The default production build
(`dist/`, which `THIRD-PARTY-NOTICES` is measured on) has none of them and ignores `?font=`;
`pnpm licences` fails if a font other than IBM Plex Sans's is in it.

`pnpm gallery:fonts` (from `ui/`) builds `dist-fonts/`, serves it with the strict CSP on port 4392 and
screenshots every font, light and dark, at 1440 px width, full height, into
`ui/apps/gallery/screenshots/fonts/<font>-<light|dark>.png` (git-ignored). It also asserts the page is
really set in each family and that the CSP monitor saw nothing.

| Font | Package (OFL-1.1) | Scripts | Latin woff2 |
|---|---|---|---|
| IBM Plex Sans (default) | `@fontsource-variable/ibm-plex-sans` 5.3.0 | Latin, Latin Ext, Cyrillic, Cyrillic Ext, Greek, Vietnamese | 46 KB |
| Inter (former default) | `@fontsource-variable/inter` 5.3.0 | Latin, Latin Ext, Cyrillic, Cyrillic Ext, Greek, Greek Ext, Vietnamese | 48 KB |
| Source Sans 3 | `@fontsource-variable/source-sans-3` 5.3.0 | Latin, Latin Ext, Cyrillic, Cyrillic Ext, Greek, Greek Ext, Vietnamese | 29 KB |
| Geist | `@fontsource-variable/geist` 5.3.0 | Latin, Latin Ext, Cyrillic, Cyrillic Ext, Vietnamese | 29 KB |

Not chosen: Public Sans (Latin only), Figtree and DM Sans (geometric, rounder, weaker numerals for
dense tables). Source Sans 3 5.3.1 is under the 7-day `minimumReleaseAge`; 5.3.0 is pinned.
Geist draws `[]` as an empty box at small sizes (see its screenshot): check that before adopting it.

## Adopting a font

Done for IBM Plex Sans on 2026-10-14; the same steps swap the default again. The default is two lines at
the top of `ui/packages/ui/src/theme/theme.css`: the `@import "@fontsource-variable/ibm-plex-sans"` line
and the first family of `--font-sans` (`"IBM Plex Sans Variable"`). To adopt another candidate: move its
package from the gallery's dependencies to `@flowcatalyst/ui`'s dependencies (and the catalog in
`ui/pnpm-workspace.yaml`), move the outgoing default's package to the gallery, change those two lines
(the family name is in the package CSS, e.g. `"Source Sans 3 Variable"`, `"Geist Variable"`), swap the
default in `apps/gallery/src/font-list.ts`, `fonts.ts` and `main.css`, change the `ibm-plex-sans-` test
in `ui/scripts/check-licences.ts` (the stray-font guard and the notice line) and the font specs, and run
`pnpm licences:write` so the OFL notice of the adopted font joins `THIRD-PARTY-NOTICES` (only fonts in
the default bundle are listed). The comparison fonts' reviewed entries in `check-licences.ts` say
"comparison only".

Checked on adoption: the default `dist/` ships only `ibm-plex-sans-*.woff2` from the gallery's own origin
(strict-CSP e2e, `font-src 'self'`, no external request); `--font-mono` is untouched, and the
tabular-numeral utilities (`tabular-nums`) are Tailwind's and independent of the family.
