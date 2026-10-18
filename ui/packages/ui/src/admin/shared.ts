// What the shared admin data pages have in common: a generated call's result as data or an
// ApiRequestError, the words for a failed read, and the colours of the states the pages show.
import { ApiRequestError, toServiceError } from "@flowcatalyst/api-runtime";
import type { InjectionKey } from "vue";
import type { StatusColor } from "../types.ts";

export const EVENT_TYPE_COLORS: Readonly<Record<string, StatusColor>> = { CURRENT: "success", ARCHIVED: "neutral" };

/**
 * What a failed platform read says: the server's own words (a 502 PLATFORM_ERROR names the missing
 * platform permission, a 503 says the application has no platform connection), kept on the page.
 */
export function failureText(failure: unknown, what: string): string {
  return failure instanceof ApiRequestError ? failure.message : `${what} could not be loaded.`;
}

/** Turns a generated call's result into data or an ApiRequestError. */
export function dataOrThrow<T>(result: { data?: T | undefined; error?: unknown; response?: Response | undefined }): T {
  if (result.data === undefined) {
    throw new ApiRequestError(toServiceError(result.error, result.response));
  }
  return result.data;
}

/** The state of a dispatch job (the platform's words) and its colour; an unknown state is neutral. */
export const JOB_COLORS: Readonly<Record<string, StatusColor>> = {
  PENDING: "neutral",
  QUEUED: "info",
  IN_PROGRESS: "info",
  COMPLETED: "success",
  FAILED: "error",
  ERROR: "error",
  CANCELLED: "neutral",
};

/** An invitation that has not been used: a live link, or one that lapsed. */
export const INVITATION_COLORS: Readonly<Record<string, StatusColor>> = { PENDING: "info", EXPIRED: "warning" };

/** The users list reloads its page after the drawer changed an invitation. */
export const REFRESH_USERS: InjectionKey<() => Promise<void>> = Symbol("refresh-users");
