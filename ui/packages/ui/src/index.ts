// @flowcatalyst/ui: the shared UI package of the FlowCatalyst Java and Go frameworks
// (docs/go/ui.md). Consumed as TypeScript and Vue source; the application's Vite build compiles it.
export { flowcatalystUi, type FlowcatalystUiOptions } from "./plugin.ts";

// Components: FlowCatalyst behaviour on Nuxt UI parts (docs/ui/nuxt-native.md says which Nuxt UI
// component to use directly instead of a wrapper, and why each of these exists).
export { default as FwAlertDialog } from "./components/FwAlertDialog.vue";
export { default as FwAnnouncer } from "./components/FwAnnouncer.vue";
export { default as FwCodeBlock } from "./components/FwCodeBlock.vue";
export { default as FwCodeChips } from "./components/FwCodeChips.vue";
export { default as FwColorModeButton } from "./components/FwColorModeButton.vue";
export { default as FwCursorPager } from "./components/FwCursorPager.vue";
export { default as FwDetailField } from "./components/FwDetailField.vue";
export { default as FwDetailList } from "./components/FwDetailList.vue";
export { default as FwFormSection } from "./components/FwFormSection.vue";
export { default as FwJsonBlock } from "./components/FwJsonBlock.vue";
export { default as FwLocalTime } from "./components/FwLocalTime.vue";
export { default as FwPaginator } from "./components/FwPaginator.vue";
export { default as FwPickList } from "./components/FwPickList.vue";
export { default as FwRowActions } from "./components/FwRowActions.vue";
export { default as FwStatusBadge } from "./components/FwStatusBadge.vue";
export { default as FwTableToolbar } from "./components/FwTableToolbar.vue";
export { default as FwToggleGroup } from "./components/FwToggleGroup.vue";

export type { AlertColor, DrawerSize, FwListColumn, FwNavItem, PickItem, RowAction, StatusColor, ToggleOption } from "./types.ts";

// Partials and the layout (U2).
export { default as FwFormDialog } from "./partials/FwFormDialog.vue";
export { default as FwListPage } from "./partials/FwListPage.vue";
export { default as FwRecordDrawer } from "./partials/FwRecordDrawer.vue";
export { default as FwAppShell } from "./layouts/FwAppShell.vue";

// U4: the standalone layout and the pages outside the shell (signed out, sign-in failed, unavailable).
export { default as FwStandalonePage } from "./layouts/FwStandalonePage.vue";
export { default as FwSignedOutPage } from "./pages/FwSignedOutPage.vue";
export { default as FwSignInFailedPage } from "./pages/FwSignInFailedPage.vue";
export { default as FwUnavailablePage } from "./pages/FwUnavailablePage.vue";
export { SIGN_IN_FAILURE_MESSAGES, signInFailureMessage } from "./lib/signInFailure.ts";

// M4d: the admin skeleton (docs/contracts/admin.md section 6): the shell, the page-kit contract, the
// pure navigation / routes / breadcrumbs, the navigation loader and the about page.
export { default as FwAdminShell } from "./layouts/FwAdminShell.vue";
export { default as FwAdminAbout } from "./partials/FwAdminAbout.vue";
export { ADMIN_BASE, adminBreadcrumbs, adminNavigation, adminRoutes, sectionsWithoutPage, type Crumb } from "./admin/navigation.ts";
export type { AdminAboutView, AdminCheckView, AdminNavigationView, AdminPage, AdminSectionView } from "./admin/types.ts";
export { useAdminNavigation, type AdminNavigation } from "./composables/useAdminNavigation.ts";

// Composables.
export { announce, announcements } from "./composables/useAnnouncer.ts";
export { useColorMode, type ColorMode, type ColorModePreference } from "./composables/useColorMode.ts";
export { confirmDiscardChanges, useConfirm, type Confirm, type ConfirmOptions } from "./composables/useConfirm.ts";
export { useCursorPagination, type CursorPage, type CursorPagination, type CursorPaginationOptions } from "./composables/useCursorPagination.ts";
export { useDirtyForm, type DirtyForm, type DirtyFormOptions } from "./composables/useDirtyForm.ts";
export { useDrawerRoute, type DrawerRoute, type DrawerRouteOptions } from "./composables/useDrawerRoute.ts";
export { useFlash, type Flash, type FlashKind, type FlashOptions } from "./composables/useFlash.ts";
export {
  arrayFilter,
  booleanFilter,
  choiceFilter,
  numberFilter,
  stringFilter,
  useListState,
  type FilterCodec,
  type ListState,
  type ListStateOptions,
  type QueryValue,
  type SortingState,
  type SortOrder,
} from "./composables/useListState.ts";
export { useLocalState } from "./composables/useLocalState.ts";
export { useRequestFailureToasts } from "./composables/useRequestFailureToasts.ts";
export { API_RUNTIME_KEY, useApiRuntime, usePermissions, useSession, type Permissions } from "./composables/useSession.ts";

// Highlighting and time formatting, for applications that need the same outside the components.
export { tokenize, type CodeLanguage, type Token, type TokenKind } from "./lib/highlight.ts";
export { localText, utcText } from "./lib/time.ts";

export {
  COLOR_MODE_SNIPPET,
  COLOR_MODE_SNIPPET_SHA256,
  COLOR_MODE_STORAGE_KEY,
  strictContentSecurityPolicy,
  strictSecurityHeaders,
} from "./csp.ts";
