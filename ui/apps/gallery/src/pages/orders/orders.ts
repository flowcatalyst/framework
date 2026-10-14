// Shared between the orders list and its drawer route.
import type { InjectionKey } from "vue";
import type { TagSeverity } from "@flowcatalyst/ui";

/** The list reloads its current page after the drawer changed an order. */
export const REFRESH_ORDERS: InjectionKey<() => Promise<void>> = Symbol("refresh-orders");

export const STATUS_SEVERITY: Readonly<Record<string, TagSeverity>> = { OPEN: "info", CANCELLED: "secondary" };

/** Cents as a price (the API's int64 totals). */
export function money(cents: number): string {
  return (cents / 100).toLocaleString("en-GB", { style: "currency", currency: "EUR" });
}
