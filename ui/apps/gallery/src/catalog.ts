// docs/go/ui.md section 10: every ui-kit spec, how @flowcatalyst/ui covers it and where the gallery
// shows it. The gallery page marks each entry with `data-spec="<spec>"`; a Playwright test checks
// that every spec under ui-kit/components has a row here and its entry on the page. Data only.

export type SpecStatus = "covered" | "composed" | "built" | "not ported";

export interface CatalogEntry {
  /** The ui-kit spec file name, without `.md`. */
  readonly spec: string;
  readonly status: SpecStatus;
  /** The Nuxt UI component(s) or the Fw* part that implements it. */
  readonly by: string;
  /** The gallery page that shows it (empty when not ported). */
  readonly page: string;
}

export const catalog: readonly CatalogEntry[] = [
  { spec: "accordion", status: "covered", by: "UAccordion", page: "/data" },
  { spec: "alert", status: "covered", by: "UAlert", page: "/feedback" },
  { spec: "alert-dialog", status: "built", by: "FwAlertDialog (Reka AlertDialog)", page: "/overlays" },
  { spec: "app-shell", status: "composed", by: "FwAppShell", page: "/shell" },
  { spec: "autocomplete", status: "covered", by: "UInputMenu", page: "/forms" },
  { spec: "avatar", status: "covered", by: "UAvatar", page: "/data" },
  { spec: "badge", status: "covered", by: "UBadge", page: "/feedback" },
  { spec: "breadcrumb", status: "covered", by: "UBreadcrumb", page: "/data" },
  { spec: "button", status: "covered", by: "UButton", page: "/actions" },
  { spec: "card", status: "covered", by: "UCard", page: "/data" },
  { spec: "checkbox", status: "covered", by: "UCheckbox, UCheckboxGroup", page: "/forms" },
  { spec: "code-block", status: "built", by: "FwCodeBlock", page: "/values" },
  { spec: "code-chips", status: "composed", by: "FwCodeChips", page: "/values" },
  { spec: "confirm-dialog", status: "composed", by: "useConfirm + FwConfirmDialog", page: "/overlays" },
  { spec: "cursor-pager", status: "built", by: "FwCursorPager + useCursorPagination", page: "/lists" },
  { spec: "data-table", status: "composed", by: "FwListPage + useListState", page: "/orders" },
  { spec: "date-range", status: "covered", by: "UInputDate (range)", page: "/forms" },
  { spec: "detail-field", status: "built", by: "FwDetailList, FwDetailField", page: "/values" },
  { spec: "dialog", status: "covered", by: "UModal", page: "/overlays" },
  { spec: "dirty-form", status: "composed", by: "useDirtyForm", page: "/forms" },
  { spec: "drawer", status: "composed", by: "FwRecordDrawer + useDrawerRoute", page: "/orders" },
  { spec: "dropdown-menu", status: "covered", by: "UDropdownMenu", page: "/actions" },
  { spec: "empty-state", status: "covered", by: "UEmpty (FwEmptyState adds Clear filters)", page: "/feedback" },
  { spec: "field", status: "covered", by: "UFormField", page: "/forms" },
  { spec: "filter-select", status: "covered", by: "USelect, USelectMenu", page: "/forms" },
  { spec: "flash-banner", status: "covered", by: "UAlert", page: "/feedback" },
  { spec: "flash-stack", status: "covered", by: "UToast via useFlash, useRequestFailureToasts", page: "/feedback" },
  { spec: "form-dialog", status: "composed", by: "FwFormDialog", page: "/orders" },
  { spec: "form-field", status: "covered", by: "UFormField", page: "/forms" },
  { spec: "form-section", status: "built", by: "FwFormSection", page: "/values" },
  { spec: "hover-card", status: "covered", by: "UPopover (hover)", page: "/overlays" },
  { spec: "input", status: "covered", by: "UInput", page: "/forms" },
  { spec: "json-block", status: "built", by: "FwJsonBlock", page: "/values" },
  { spec: "kbd", status: "covered", by: "UKbd", page: "/actions" },
  { spec: "label", status: "covered", by: "UFormField label", page: "/forms" },
  { spec: "legacy-fc-btn", status: "not ported", by: "UButton (square for icon buttons)", page: "" },
  { spec: "local-time", status: "built", by: "FwLocalTime", page: "/values" },
  { spec: "multi-select", status: "covered", by: "USelectMenu (multiple)", page: "/forms" },
  { spec: "page-header", status: "built", by: "FwPageHeader", page: "/values" },
  { spec: "pagination", status: "covered", by: "UPagination", page: "/data" },
  { spec: "paginator", status: "composed", by: "FwPaginator", page: "/lists" },
  { spec: "pick-list", status: "composed", by: "FwPickList", page: "/lists" },
  { spec: "progress", status: "covered", by: "UProgress", page: "/feedback" },
  { spec: "radio-group", status: "covered", by: "URadioGroup", page: "/forms" },
  { spec: "row-actions", status: "composed", by: "FwRowActions", page: "/actions" },
  { spec: "search-input", status: "covered", by: "UInput (search icon)", page: "/forms" },
  { spec: "select", status: "covered", by: "USelect, USelectMenu", page: "/forms" },
  { spec: "separator", status: "covered", by: "USeparator", page: "/data" },
  { spec: "sheet", status: "covered", by: "USlideover (modal)", page: "/overlays" },
  { spec: "sidebar", status: "covered", by: "UDashboardSidebar (in FwAppShell)", page: "/shell" },
  { spec: "skeleton", status: "covered", by: "USkeleton", page: "/feedback" },
  { spec: "spinner", status: "built", by: "FwSpinner", page: "/feedback" },
  { spec: "switch", status: "covered", by: "USwitch", page: "/forms" },
  { spec: "table", status: "covered", by: "UTable", page: "/data" },
  { spec: "table-toolbar", status: "built", by: "FwTableToolbar", page: "/lists" },
  { spec: "tabs", status: "covered", by: "UTabs", page: "/data" },
  { spec: "tag", status: "composed", by: "FwTag, FwStatusBadge", page: "/feedback" },
  { spec: "textarea", status: "covered", by: "UTextarea", page: "/forms" },
  { spec: "toggle", status: "built", by: "FwToggleGroup (Reka ToggleGroup)", page: "/actions" },
  { spec: "tooltip", status: "covered", by: "UTooltip", page: "/actions" },
];
