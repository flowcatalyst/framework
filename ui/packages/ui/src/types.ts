// Types shared by the package's components, in a .ts module so that both vue-tsc and the type-aware
// lint (which sees .vue imports only through the `*.vue` shim) can read them.
import type { NavigationMenuItem, TableColumn } from "@nuxt/ui";
import type { RouteLocationRaw } from "vue-router";

/** Nuxt UI's colour aliases, as UBadge and UButton take them (the theme's palette). */
export type StatusColor = "primary" | "secondary" | "success" | "info" | "warning" | "error" | "neutral";

/** The colours of a confirmation (FwAlertDialog, useConfirm): the confirm button's. */
export type AlertColor = "primary" | "warning" | "error";

/**
 * A column of FwListPage: Nuxt UI's `TableColumn` plus the phone strategy. `phone: "hide"` drops the
 * column below the `sm` breakpoint (640 px), where the table keeps its other columns; the dropped
 * values belong in the record drawer.
 */
export type FwListColumn<T> = TableColumn<T> & { readonly phone?: "hide" };

/** ui-kit drawer.md sizes. */
export type DrawerSize = "default" | "wide" | "two-thirds";

/** One button of FwToggleGroup. */
export interface ToggleOption {
  readonly value: string;
  readonly label: string;
  /** A Lucide icon; with `iconOnly` the label becomes the accessible name. */
  readonly icon?: string;
  readonly disabled?: boolean;
}

/** One action of FwRowActions (ui-kit row-actions.md). */
export interface RowAction {
  readonly label: string;
  /** A Lucide icon name; required for inline actions. */
  readonly icon?: string;
  /** Navigates (a link). */
  readonly to?: RouteLocationRaw;
  /** Runs (a button); a destructive action opens a confirmation here, never acts at once. */
  readonly onSelect?: () => void;
  readonly destructive?: boolean;
  /** Non-empty: shown disabled with this reason. */
  readonly disabledReason?: string;
}

/** One choice of FwPickList. */
export interface PickItem {
  readonly value: string;
  readonly label: string;
  /** A code under the label (`orders:clerk`), also matched by the filter. */
  readonly code?: string;
  /** A short marker (`Inactive`). */
  readonly note?: string;
}

/**
 * One entry of FwAppShell's navigation: Nuxt UI's navigation item (label, icon, `to`, badge, children,
 * ...) and the permission that shows it. An entry (or child) whose permission the session does not
 * hold is left out; the server still checks every operation.
 */
export interface FwNavItem extends NavigationMenuItem {
  readonly permission?: string;
  children?: FwNavItem[];
}
