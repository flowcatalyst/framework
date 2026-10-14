// Shared between the orders list and its drawer route.
import type { InjectionKey } from "vue";
import type { StatusColor } from "@flowcatalyst/ui";

/** The list reloads its current page after the drawer changed an order. */
export const REFRESH_ORDERS: InjectionKey<() => Promise<void>> = Symbol("refresh-orders");

export const STATUS_COLORS: Readonly<Record<string, StatusColor>> = { OPEN: "info", CANCELLED: "neutral" };

/** Cents as an amount (the API's int64 totals); the demo has no currency. */
export function money(cents: number): string {
  return (cents / 100).toLocaleString("en-GB", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
