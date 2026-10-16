// Shared by the pages that read the platform through the application (event types, roles).
import { ApiRequestError, toServiceError } from "@flowcatalyst/api-runtime";
import type { StatusColor } from "@flowcatalyst/ui";

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
