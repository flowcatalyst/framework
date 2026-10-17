// Shared between the tickets list, its drawer route and the SLA page.
import type { StatusColor } from "@flowcatalyst/ui";
import type { InjectionKey } from "vue";

/** The list reloads its current page after the drawer changed a ticket. */
export const REFRESH_TICKETS: InjectionKey<() => Promise<void>> = Symbol("refresh-tickets");

export const STATUS_COLORS: Readonly<Record<string, StatusColor>> = { OPEN: "info", IN_PROGRESS: "warning", RESOLVED: "success", CLOSED: "neutral" };

export const PRIORITY_COLORS: Readonly<Record<string, StatusColor>> = { LOW: "neutral", NORMAL: "info", HIGH: "warning", URGENT: "error" };

export const PRIORITIES = ["LOW", "NORMAL", "HIGH", "URGENT"] as const;
export const STATUSES = ["OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"] as const;

/** Minutes as the SLA reads best: `45 min`, `8 h`, `2 d`, `1 d 6 h`. */
export function duration(minutes: number): string {
  if (minutes < 60) {
    return `${String(minutes)} min`;
  }
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours < 24) {
    return rest === 0 ? `${String(hours)} h` : `${String(hours)} h ${String(rest)} min`;
  }
  const days = Math.floor(hours / 24);
  const restHours = hours % 24;
  return restHours === 0 && rest === 0 ? `${String(days)} d` : `${String(days)} d ${String(restHours)} h`;
}
