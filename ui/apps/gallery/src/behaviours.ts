// The platform behaviours the gallery demonstrates (owner ruling 2026-10-14: the ui-kit specs are a
// checklist of behaviours, not a visual or API contract). Each row names the part that provides it
// (a Nuxt UI component used directly, or a FlowCatalyst part) and the page that shows it. Data only:
// the overview page, the pages' `data-behaviour` markers and the Playwright suite read this list.
//
// Checked by Playwright: e2e/behaviours.spec.ts opens each page and finds every row's marker;
// every row is claimed by at least one passing test (`covers(...)` in the specs), which
// e2e/behaviour-reporter.ts enforces at the end of a full run.

export interface Behaviour {
  readonly id: string;
  /** What the platform needs, in a sentence. */
  readonly behaviour: string;
  /** The part that provides it. */
  readonly by: string;
  /** The gallery page that shows it; "*" for every page. */
  readonly page: string;
}

export const behaviours = [
  // Every page.
  { id: "strict-csp", behaviour: "Every page and overlay runs under the strict CSP: no violation, no foreign request, no page error", by: "flowcatalystUi() Vite plugin, useColorMode", page: "*" },
  { id: "axe-aa", behaviour: "No axe violation at WCAG 2.2 AA in light and dark, at desktop and phone width, with overlays open", by: "theme.css, fcComponentTheme", page: "*" },
  { id: "one-h1", behaviour: "Each page has exactly one h1: its title block", by: "UPageHeader (themed)", page: "*" },

  // Shell.
  { id: "shell-nav-permissions", behaviour: "Navigation shows only what the session may open", by: "FwAppShell + usePermissions", page: "/shell" },
  { id: "shell-current-page", behaviour: "The current page is marked aria-current in the navigation", by: "FwAppShell (UNavigationMenu)", page: "/shell" },
  { id: "shell-sign-out", behaviour: "Sign out from the account menu goes through the runtime", by: "FwAppShell (UDropdownMenu) + api-runtime", page: "/shell" },
  { id: "shell-sidebar-collapse", behaviour: "On desktop the sidebar collapses to an icon rail (40 px targets, tooltips) and expands again with a keyboard-operable toggle that keeps focus; the choice is stored per viewer and applied before the first paint", by: "FwAppShell (UDashboardSidebar) + useLocalState", page: "/shell" },
  { id: "shell-phone-sidebar", behaviour: "On a phone the sidebar opens from the navbar and closes again", by: "FwAppShell (UDashboardSidebar)", page: "/shell" },
  { id: "colour-mode", behaviour: "Light, dark or system colour mode, stored, switched without an inline style", by: "FwColorModeButton + useColorMode", page: "/shell" },

  // Lists.
  { id: "list-url-state", behaviour: "Filters live in the query string: replaced, not pushed, and they survive a reload", by: "useListState", page: "/orders" },
  { id: "list-filter-count", behaviour: "The Filters button's name carries the active-filter count; the popover returns focus to it", by: "FwTableToolbar (UPopover)", page: "/lists" },
  { id: "list-clear-all", behaviour: "Clear all resets every filter with one reload and moves focus to the search box", by: "FwTableToolbar + useListState", page: "/lists" },
  { id: "list-empty-clear", behaviour: "An emptied list says so in a status region and offers Clear filters while filtered", by: "FwListPage (UEmpty)", page: "/orders" },
  { id: "list-page-search", behaviour: "Without a search parameter, the search narrows the loaded rows", by: "FwListPage (UTable global filter)", page: "/orders" },
  { id: "list-phone-columns", behaviour: "On a phone the table keeps its priority columns and drops the others (their values are in the record drawer): nothing is clipped and the table does not scroll sideways", by: "FwListPage (column `phone: \"hide\"`)", page: "/orders" },
  { id: "list-row-open", behaviour: "A row opens its record; the open record's row is highlighted", by: "FwListPage (UTable)", page: "/orders" },
  { id: "cursor-paging", behaviour: "Newest, Newer and Older over keyset pages; an unusable control keeps focus", by: "FwCursorPager + useCursorPagination", page: "/lists" },
  { id: "cursor-race", behaviour: "A slower, older answer never overwrites a newer one", by: "useCursorPagination", page: "/lists" },
  { id: "offset-paging", behaviour: "Numbered pages, rows per page and 'Showing x to y of n'", by: "FwPaginator (UPagination, USelect)", page: "/lists" },
  { id: "pick-list", behaviour: "Pick many from a long list: filter, tick by keyboard, the picks mirrored with a live count, remove", by: "FwPickList (UCheckbox, UInput)", page: "/lists" },

  // Record drawer.
  { id: "drawer-child-route", behaviour: "The record drawer is a child route: deep link, Back reopens, the list's query kept", by: "FwRecordDrawer + useDrawerRoute", page: "/orders" },
  { id: "drawer-non-modal", behaviour: "The drawer is non-modal: the list stays usable and another row switches the record", by: "FwRecordDrawer (USlideover)", page: "/orders" },
  { id: "drawer-push", behaviour: "On a wide screen the drawer pushes the list aside: Filters, Refresh and the columns stay clickable with the drawer open; on a narrower one it overlays", by: "FwRecordDrawer + FwListPage (drawer host)", page: "/orders" },
  { id: "drawer-focus", behaviour: "Opening moves focus to the drawer's heading; Escape and Close go back to the list", by: "FwRecordDrawer", page: "/orders" },
  { id: "drawer-discard", behaviour: "Leaving, Back, switching rows or closing with unsaved changes asks 'Discard changes?' (Keep editing focused)", by: "useDrawerRoute + useDirtyForm + useConfirm", page: "/orders" },

  // Pages outside the shell.
  { id: "standalone-layout", behaviour: "Signed-out and error pages stand alone: a centred column with the application name, one h1, a body and the ways out, no sidebar, announced regions included", by: "FwStandalonePage", page: "/standalone" },
  { id: "signed-out-page", behaviour: "After sign-out the page says so and Sign in again starts sign-in and returns to the given path", by: "FwSignedOutPage + BrowserSession.signIn", page: "/standalone" },
  { id: "sign-in-failed-page", behaviour: "A failed sign-in says what happened in a sentence per contract code, shows the code as text, and Try again signs in again", by: "FwSignInFailedPage (signInFailureMessage)", page: "/standalone" },
  { id: "unavailable-page", behaviour: "When the session cannot be read at all the page says so and Try again reloads in full", by: "FwUnavailablePage", page: "/standalone" },

  // Form dialogs.
  { id: "form-schema", behaviour: "Create and edit forms validate with the operation's generated Zod schema; the first field is focused, then the first invalid one", by: "FwFormDialog (UModal, UForm)", page: "/orders" },
  { id: "form-server-errors", behaviour: "A 400's field details land on their fields and focus moves there", by: "FwFormDialog + applyServiceError", page: "/orders" },
  { id: "form-busy", behaviour: "While a submit runs the form is busy and both buttons are disabled", by: "FwFormDialog", page: "/orders" },
  { id: "form-discard", behaviour: "Closing a changed form asks first; an unchanged one closes at once", by: "FwFormDialog + useDirtyForm", page: "/orders" },
  { id: "form-number-commit", behaviour: "A number typed and submitted at once is the number sent", by: "FwFormDialog (UInputNumber)", page: "/orders" },
  { id: "form-int64", behaviour: "int64 fields reach the server as JSON numbers", by: "fwOpenApiConfig + FwFormDialog", page: "/orders" },
  { id: "record-create", behaviour: "After a create, the new record's drawer opens in place of the form", by: "FwFormDialog + useDrawerRoute", page: "/orders" },

  // Confirmations.
  { id: "alert-dialog", behaviour: "Confirmations are alert dialogs: Cancel focused, focus trapped, the mask does not dismiss, Escape cancels, focus returns", by: "FwAlertDialog (UModal)", page: "/overlays" },
  { id: "alert-busy", behaviour: "A confirmed action can keep the confirmation open while it runs", by: "FwAlertDialog", page: "/overlays" },
  { id: "confirm-promise", behaviour: "A confirmation is a promise: true or false; focus returns to the button", by: "useConfirm (useOverlay)", page: "/overlays" },
  { id: "destructive-confirmed", behaviour: "Destructive actions are confirmed before they run", by: "useConfirm", page: "/actions" },

  // Actions.
  { id: "row-actions", behaviour: "At most two inline row actions, named with their row; the rest in a non-modal menu, destructive last", by: "FwRowActions (UButton, UDropdownMenu)", page: "/actions" },
  { id: "row-action-disabled", behaviour: "An action the user may not run here stays focusable and says why", by: "FwRowActions", page: "/actions" },
  { id: "toggle-group", behaviour: "A named group of pressed buttons; arrow keys move, Space presses; exclusive keeps one", by: "FwToggleGroup (UFieldGroup, UButton, Reka ToggleGroup)", page: "/actions" },
  { id: "menu-keyboard", behaviour: "Menus open from their button and work with the keyboard; a destructive item is last", by: "UDropdownMenu", page: "/actions" },
  { id: "tooltip-focus", behaviour: "A tooltip shows on keyboard focus too", by: "UTooltip", page: "/actions" },

  // Feedback.
  { id: "flash-announced", behaviour: "Messages are read out: politely for success and info, at once for warnings and errors", by: "useFlash + FwAnnouncer", page: "/feedback" },
  { id: "flash-timing", behaviour: "Success leaves after 3 s; warnings and errors stay until dismissed", by: "useFlash (UToast)", page: "/feedback" },
  { id: "request-failures", behaviour: "A refused, failed or unreachable call shows one message per kind, even if the page says nothing", by: "useRequestFailureToasts", page: "/feedback" },
  { id: "status-badge", behaviour: "Domain statuses as coloured badges with sentence-case labels; an unmapped status is neutral", by: "FwStatusBadge (UBadge)", page: "/feedback" },
  { id: "loading-named", behaviour: "Progress is named: a busy button, an indeterminate bar", by: "UButton loading, UProgress", page: "/feedback" },
  { id: "empty-state", behaviour: "An empty area says what is missing and offers the next step", by: "UEmpty", page: "/feedback" },

  // Values.
  { id: "code-copy", behaviour: "Code is a named, focusable scroll region; Copy writes the source and says so", by: "FwCodeBlock", page: "/values" },
  { id: "json-block", behaviour: "A payload is shown as indented JSON in a named, focusable region, as text", by: "FwJsonBlock", page: "/values" },
  { id: "local-time", behaviour: "Instants in the viewer's locale and zone, UTC in the title", by: "FwLocalTime", page: "/values" },
  { id: "detail-fields", behaviour: "Read-only values in a description list; an empty value reads '—'", by: "FwDetailList, FwDetailField", page: "/values" },
  { id: "section-headings", behaviour: "Sections take the page's next heading level; a danger zone is marked", by: "FwFormSection", page: "/values" },
  { id: "code-chips", behaviour: "An event-type code in coloured segments whose text is the code", by: "FwCodeChips", page: "/values" },

  // Forms (Nuxt UI used directly).
  { id: "form-field-errors", behaviour: "A form's errors sit on their fields (aria-invalid, described) and a valid form submits", by: "UForm + UFormField + Zod", page: "/forms" },
  { id: "dirty-snapshot", behaviour: "Save is enabled only while values differ from the loaded ones: typing a value back is clean", by: "useDirtyForm", page: "/forms" },
  { id: "select-keyboard", behaviour: "Selects, a clearable filter select and a multi-select work with the keyboard", by: "USelect, USelectMenu", page: "/forms" },
  { id: "autocomplete", behaviour: "Typing narrows the choices; arrow keys and Enter pick one", by: "UInputMenu", page: "/forms" },
  { id: "date-range", behaviour: "A date range edited segment by segment with the keyboard", by: "UInputDate (range)", page: "/forms" },

  // Data display (Nuxt UI used directly).
  { id: "table-sort", behaviour: "Sortable columns from the header", by: "UTable (TanStack sorting)", page: "/data" },
  { id: "table-filter-columns", behaviour: "A table filter and a column picker", by: "UTable (global filter, column visibility) + UDropdownMenu", page: "/data" },
  { id: "table-select-rows", behaviour: "Rows picked with checkboxes, with a count", by: "UTable (row selection) + UCheckbox", page: "/data" },
  { id: "table-paginate", behaviour: "Client-side pages of a loaded table", by: "UTable (pagination) + UPagination", page: "/data" },
  { id: "tabs-keyboard", behaviour: "Tabs move with the arrow keys; a disabled tab is skipped", by: "UTabs", page: "/data" },

  // Overlays (Nuxt UI used directly).
  { id: "modal-dialog", behaviour: "A modal traps focus, Escape closes it, focus returns to its button", by: "UModal", page: "/overlays" },
  { id: "popover", behaviour: "A popover opens from its button; Escape closes it and returns focus", by: "UPopover", page: "/overlays" },
] as const satisfies readonly Behaviour[];

export type BehaviourId = (typeof behaviours)[number]["id"];
