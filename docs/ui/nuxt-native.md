# What Nuxt UI provides, and what `@flowcatalyst/ui` adds (2026-10-14)

Owner rulings of 2026-10-14: the Nuxt UI app is **not** a 1:1 mapping of the Datastar ui-kit; the 60
`ui-kit/components` specs are a checklist of **behaviours** the platform needs, not a visual or API
contract. Use Nuxt UI's own components, props and variants; keep a `Fw*` part only where it adds
something FlowCatalyst-specific. This page is the audit of every `Fw*` part of U1 and U2 against
**Nuxt UI 4.11.3** (the pinned version), and the result.

How it was checked: each component and prop named below was read in the installed package
(`node_modules/.pnpm/@nuxt+ui@4.11.3*/node_modules/@nuxt/ui/dist/runtime/components/*.vue` and their
`.d.ts`), not assumed from the documentation site, which documents the latest release; the UTable
patterns were also compared with https://ui.nuxt.com/docs/components/table (sort headers, `enableSorting`
for `aria-sort`, `getPaginationRowModel`, global filter, column visibility, row selection: the same in
4.11.3). Every replacement is exercised in the gallery (`ui/apps/gallery`) by a Playwright test of the
behaviour checklist ([`u1.md`](u1.md), "Behaviour checklist").

## Result in one table

| Before (U1/U2) | Decision | Now |
|---|---|---|
| `FwPageHeader` | **replaced** by native usage | `UPageHeader` (its `<h1>`, `description`, `#links`), made compact by `fcComponentTheme.pageHeader` |
| `FwTag` | **replaced** | `UBadge` with Nuxt UI's colours and variants |
| `FwSpinner` | **replaced** | `UButton :loading`, `UProgress` (indeterminate, named with `getValueLabel`), `UEmpty :loading`, `UTable :loading` |
| `FwEmptyState` | **replaced** | `UEmpty` (`actions`); the list's filtered variant lives in `FwListPage` |
| `FwConfirmDialog` | **removed** (it only adapted props) | `useConfirm` opens `FwAlertDialog` through `useOverlay` directly |
| `FwAlertDialog` | **thin wrapper**, rebuilt | `UModal` with the alert-dialog settings (role, Cancel focus, mask) |
| `FwToggleGroup` | **thin wrapper**, rebuilt | `UFieldGroup` of `UButton`s (`active`, `active-color`, `active-variant`) with Reka's `ToggleGroup` behaviour |
| `FwStatusBadge` | **keep** (domain enums) | `UBadge`; takes a status-to-colour map in Nuxt UI's colour names |
| `FwListPage`, `FwRecordDrawer`, `FwFormDialog`, `FwAppShell` | **keep** (platform patterns) | now built on `UPageHeader`, `UEmpty`, `UTable`'s global filter; `FwNavItem` is `NavigationMenuItem` plus `permission` |
| `FwTableToolbar`, `FwCursorPager`, `FwPaginator`, `FwRowActions`, `FwPickList` | **keep** (list behaviour Nuxt UI does not have) | unchanged compositions of Nuxt UI parts |
| `FwDetailList`/`FwDetailField`, `FwFormSection`, `FwLocalTime`, `FwCodeBlock`, `FwJsonBlock`, `FwCodeChips` | **keep** (no Nuxt UI equivalent) | unchanged |
| `FwColorModeButton`, `FwAnnouncer` | **keep** (strict CSP; a Reka accessibility bug) | unchanged |
| composables | **keep** | `useFlash` kinds renamed to Nuxt UI's colours (`warning`); `useConfirm` takes `description` and `color` |
| `theme/kit.css` (copied kit tokens) | **replaced** | `theme.css`: Nuxt UI's `--ui-*` tokens only, kit values as the start; component looks in `fcComponentTheme` (the Vite plugin's `ui` option, Nuxt's app config) |

Package exports went from 24 components to 19 (`FwConfirmDialog`, `FwEmptyState`, `FwPageHeader`,
`FwSpinner`, `FwTag` deleted). Types: `TagSeverity` and `AlertTone` gone; `StatusColor` (Nuxt UI's
colour names) and `AlertColor` (`primary`, `warning`, `error`) in their place.

## Per part

### Replaced by Nuxt UI used directly

| Fw part | Nuxt UI in 4.11.3 | Why the wrapper went | Kit behaviours the replacement still covers |
|---|---|---|---|
| `FwPageHeader` | `UPageHeader`: `title` in an `<h1>`, `description`, `links` (UButton props) or `#links`, `headline`, default slot | It re-built a component Nuxt UI has. The default look is a marketing header (`text-3xl sm:text-4xl`, `py-8`, a bottom rule); `fcComponentTheme.pageHeader` makes it the kit's compact title block for every page at once | One `<h1>` per page (`one-h1`, checked on every page), description, actions on the right |
| `FwTag` | `UBadge`: colours `primary secondary success info warning error neutral`, variants `solid outline soft subtle`, sizes `xs` to `xl`, icons, avatar | Mapping PrimeVue's severity names (`warn`, `danger`, `secondary`) onto Nuxt UI's colours added a vocabulary and nothing else | Text carries the meaning, colour repeats it; every colour's solid and outline variant passes 4.5:1 (strict axe) |
| `FwSpinner` | `UButton` `loading` (and `loading-auto`), `UProgress` without `model-value` (indeterminate), `UEmpty` `loading`, `UTable` `loading` (+ `loading-color`, `loading-animation`) | A spinner alone is a weaker sign than the busy control or the progress bar Nuxt UI draws | `loading-named`: a busy button is disabled and keeps its name; a progress bar is named. `UProgress` puts attributes on its wrapper, so the bar is named with `get-value-label` (an `aria-label` attribute would land on a `div` and fail axe) |
| `FwEmptyState` | `UEmpty`: `icon`, `title`, `description`, `actions` (UButton props), `loading`, variants `solid outline soft subtle naked`, slots | Clear filters is a `UEmpty` action; the "announce when a filter emptied the list" part belongs to the list | `empty-state` (Feedback page); `list-empty-clear` (in `FwListPage`: a `role="status"` region with Clear filters while filtered) |
| `FwConfirmDialog` | `useOverlay` (`create`, `open` resolves with the component's `close` event) | It only renamed props; `FwAlertDialog` now emits `close(confirmed)` itself | `confirm-promise`, `alert-dialog` |

### Thin wrappers (defaults and one missing behaviour)

| Fw part | Built on | What the wrapper adds | Covers |
|---|---|---|---|
| `FwAlertDialog` | `UModal` (header, title, description, footer from Nuxt UI's modal theme) | Nuxt UI has no alert dialog. Reka's `AlertDialogContent` is `DialogContent` with `role="alertdialog"`, outside clicks prevented and Cancel focused; the same settings go through `UModal`'s `content` prop (`role` replaces Reka's default because `$attrs` are merged after it). `busy` turns `dismissible` off and keeps the dialog open; it emits `close(boolean)` for the overlay manager | `alert-dialog`, `alert-busy`, `confirm-promise`, `destructive-confirmed`, `drawer-discard`, `form-discard` |
| `FwToggleGroup` | `UFieldGroup` + `UButton` (`active`, `active-color="primary"`, `active-variant="solid"`, sizes `xs` to `xl`) + Reka `ToggleGroupRoot`/`ToggleGroupItem` (`as-child`) | Nuxt UI has no toggle group: `UTabs` switches panels, `UButton` `active` has no group semantics. The custom classes of U1 are gone: the look is Nuxt UI's button variants | `toggle-group`: `role="group"` named, `aria-pressed`, arrow keys (roving focus), exclusive keeps one, disabled option |
| `FwPaginator` | `UPagination` + `USelect` | The kit's "Showing x to y of n" report and rows-per-page with sensible sizes (50, 100, 250, 500), the page reset on a size change. Use `UPagination` directly when neither is wanted | `offset-paging` |

### Kept: FlowCatalyst-specific

| Fw part | Built on | Why it stays | Covers |
|---|---|---|---|
| `FwAppShell` | `UDashboardGroup`, `UDashboardSidebar` (collapsible, `mode: slideover` on phones), `UDashboardPanel`, `UDashboardNavbar`, `UNavigationMenu`, `UDropdownMenu` | Wired to the session: navigation filtered by permission, Sign out through the runtime, failure toasts, the announcer, the navy sidebar. `FwNavItem` is now Nuxt UI's `NavigationMenuItem` (badges, children, tooltips, ...) plus `permission`, filtered recursively; `UNavigationMenu` forwards only link, `aria-*` and `data-*` keys, so `permission` stays off the DOM | `shell-*`, `colour-mode` |
| `FwListPage` | `UPageHeader`, `FwTableToolbar`, `UTable`, `UEmpty` | The platform's list page: URL state through its owner, the record drawer outlet, the selected-row highlight, sorting split into prop and event (exactOptionalPropertyTypes), a focusable scroll region. New: `search-loaded-rows` hands the search to `UTable`'s global filter (TanStack) for operations without a search parameter (the orders example), replacing the page's own filter code | `list-*` |
| `FwRecordDrawer` + `useDrawerRoute` | `USlideover` (`modal: false`, `overlay: false`) | The record drawer as a child route: non-modal, focus on its heading, Escape/Back/Close, the discard question on leave and row switch | `drawer-*` |
| `FwFormDialog` | `UModal`, `UForm`, `FwAlertDialog`, `useDirtyForm`, `applyServiceError` | Generated Zod schema, 400 details on fields, busy, discard question, the number-field commit before submit | `form-*`, `record-create` |
| `FwTableToolbar` | `UInput`, `UButton`, `UPopover`, `UBadge`, `USelect` | The active-filter count in the Filters button's accessible name, Clear all with focus to the search box. `UDashboardToolbar` was considered: it is a layout bar for a dashboard panel, without these behaviours | `list-filter-count`, `list-clear-all` |
| `FwCursorPager` + `useCursorPagination` | `UButton` | Keyset paging (Newest / Newer / Older from an in-session stack) and the stale-answer guard; `UPagination` is numbered pages | `cursor-paging`, `cursor-race` |
| `FwRowActions` | `UButton`, `UTooltip`, `UDropdownMenu` (non-modal) | The platform's row-action rules: at most two inline, names include the row, disabled with a reason, destructive last | `row-actions`, `row-action-disabled` |
| `FwPickList` | `UInput`, `UCheckbox`, `UButton` | The platform's Manage Roles picker: filter, mirrored picks with a live count, remove. `UCheckboxGroup` was considered (roving focus replaces the per-checkbox Tab order, and it has no mirror); `USelectMenu multiple` / `UInputTags` suit short lists and are the native choice there | `pick-list` |
| `FwStatusBadge` | `UBadge` | Domain enums: one status-to-colour map per application, sentence-case labels, unmapped neutral | `status-badge` |
| `FwDetailList`, `FwDetailField` | plain `<dl>` | Nuxt UI has no description list (`UCard`/`UPageCard` titles are `div`s) | `detail-fields` |
| `FwFormSection` | plain `<section>` | A real heading at the page's next level; `UCard`/`UPageCard` render their title as a `div` | `section-headings` |
| `FwLocalTime` | `<time>` | Nuxt UI's date components (`UCalendar`, `UInputDate`, `UInputTime`) are inputs; nothing formats an instant for display | `local-time` |
| `FwCodeBlock`, `FwJsonBlock` | the package's tokenizer | `ProsePre` (prose) copies, but colours code with Shiki HTML: `v-html` with inline `style` attributes, which the strict CSP refuses; its copy is not announced | `code-copy`, `json-block` |
| `FwCodeChips` | spans | Event-type codes are FlowCatalyst's | `code-chips` |
| `FwColorModeButton` + `useColorMode` | `UButton`, VueUse | Nuxt UI's colour mode (`UColorModeButton` and friends) inserts an inline `<style>` on every switch (U0) | `colour-mode` |
| `FwAnnouncer` + `useAnnouncer` | two live regions | Reka 2.10.5 renders the toast announcer `aria-hidden`, so `UToast` is never read out (U1) | `flash-announced` |
| `useFlash`, `useRequestFailureToasts` | `useToast` | Durations by kind, the announcement, one failure message per kind | `flash-*`, `request-failures` |
| `useConfirm` | `useOverlay` + `FwAlertDialog` | A confirmation as a promise | `confirm-promise` |
| `useDirtyForm` | | `UForm`'s `dirty` means "had input"; typing a value back must be clean | `dirty-snapshot` |
| `useListState`, `useLocalState`, `useSession`/`usePermissions` | vue-router, the runtime | URL state, reviewed storage, the session | `list-url-state`, `shell-nav-permissions` |

## Nuxt UI used directly, by area

Shown in the gallery, each with its checklist rows, under the strict CSP and axe in both themes:

| Area | Nuxt UI components (4.11.3) | Notes |
|---|---|---|
| Tables | `UTable`: sorting (header `UButton` calling `column.toggleSorting`; `enableSorting: true` writes `aria-sort`), `global-filter`, `column-visibility` (with `UDropdownMenu` checkbox items), `row-selection` (with `UCheckbox` cells), `pagination` + `pagination-options: { getPaginationRowModel }` with `UPagination`; also `column-pinning`, `expanded`, `sticky`, `virtualize`, `loading` | Every model is a prop plus an event under exactOptionalPropertyTypes (the props exclude `undefined`, the events include it). `getPaginationRowModel` is imported from `@tanstack/vue-table` (the gallery declares it, the version Nuxt UI resolves) |
| Layout | `UDashboardGroup`, `UDashboardSidebar`, `UDashboardPanel`, `UDashboardNavbar`, `UDashboardToolbar`, `UDashboardSidebarCollapse`/`Toggle`, `UDashboardSearch` + `UDashboardSearchButton` (on `UCommandPalette`) | The shell uses the first four. A command palette for navigation is available and works without a wrapper; not adopted because no checklist behaviour asks for it (owner decision) |
| Navigation | `UNavigationMenu`, `UBreadcrumb`, `UTabs`, `UPagination`, `UStepper`, `UTree` | |
| Forms | `UForm`, `UFormField`, `UInput`, `UTextarea`, `UInputNumber`, `USelect`, `USelectMenu` (`multiple`, `clear`), `UInputMenu`, `UInputTags`, `UCheckbox`, `UCheckboxGroup`, `URadioGroup`, `USwitch`, `UInputDate` (`range`), `UInputTime`, `UCalendar`, `UFieldGroup` | `USelectMenu`'s clear control is a pointer target (`tabindex="-1"`) in 4.11.3 |
| Overlays | `UModal`, `USlideover`, `UDrawer`, `UPopover`, `UTooltip`, `UDropdownMenu`, `UContextMenu` | `UDrawer` loads vaul-vue, whose injected CSS the Vite plugin lifts (U0). Reka closes a tooltip when a scroll container around its trigger scrolls, so Tab onto an off-screen trigger shows it only for an instant |
| Feedback | `UToast` (`useToast`), `UAlert`, `UBadge`, `UProgress`, `USkeleton`, `UEmpty` | `UAlert` descriptions at full opacity (`fcComponentTheme.alert`) |
| Display | `UCard`, `UAccordion`, `UAvatar`, `USeparator`, `UKbd`, `UTimeline`, `UUser`, `UCollapsible`, `UScrollArea` | |
| Not verified under the strict CSP | `UFileUpload` (previews through `URL.createObjectURL`: `img-src` would need `blob:`), `UEditor` (TipTap and ProseMirror, not run; its `inject-css` prop, which inserts a `<style>`, must stay off), `UColorPicker`, `UCarousel` | Check before use; `USplitter` and `UBanner` are known to break it (U0, lint-enforced) |

## Theme

`@flowcatalyst/ui/theme.css` (was `theme/kit.css`) sets only Nuxt UI's variables: the five colour
aliases, text, backgrounds, borders and `--ui-radius` (2px), light and dark, plus IBM Plex Sans (self-hosted) and
the navy palette the sidebar uses (`bg-navy-900`, no hex in the shell any more). The values are the
kit's (a unit test compares them with `ui-kit/theme/tokens.css`), except the one-shade contrast changes
of [`u1.md`](u1.md). Component looks are Nuxt UI variants in `fcComponentTheme`, passed by
`nuxtUiOptions()` to the Vite plugin's `ui` option: `tabs` (the kit's bordered tabs), `pageHeader`
(compact), `alert` (description at full opacity), `navigationMenu` (vertical) and `dashboardSidebar`
(comfortable sidebar spacing, 40 px items). The kit's text scale is not applied (owner ruling: Nuxt UI's
defaults). The sidebar's before/after values and the font comparison (three candidate fonts, gallery
only) are in [`fonts.md`](fonts.md).
