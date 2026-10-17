// What the admin pages share: a generated call's result as data or an ApiRequestError, and the words
// for a failed read.
import { ApiRequestError, toServiceError } from "@flowcatalyst/api-runtime";

/** What a failed read says: the server's own words, kept on the page. */
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
