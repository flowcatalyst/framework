// Types shared by the package's components, in a .ts module so that both vue-tsc and the type-aware
// lint (which sees .vue imports only through the `*.vue` shim) can read them.
import type { RouteLocationRaw } from "vue-router";

/** ui-kit tag.md severities. */
export type TagSeverity = "success" | "info" | "warn" | "danger" | "secondary" | "primary";

/** ui-kit confirm-dialog.md tones. */
export type AlertTone = "primary" | "danger" | "warn";

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

/** One entry of FwAppShell's navigation. */
export interface FwNavItem {
  readonly label: string;
  readonly to: RouteLocationRaw;
  readonly icon?: string;
  /** Shown only when the session holds it (the server still checks every operation). */
  readonly permission?: string;
}
