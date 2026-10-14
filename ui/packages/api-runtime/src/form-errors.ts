// A failed call's envelope onto a form (docs/go/ui.md section 6.3): a 400's field details go to the
// fields they name; everything else (a business rule such as `409 ORDER_NOT_OPEN`, a field the form
// does not show) becomes the form-level message.
import { fieldErrors, type FieldError, type ServiceError } from "./errors.ts";

/** What applyServiceError needs from a form: Nuxt UI's `UForm` exposes exactly this. */
export interface FormErrorTarget {
  setErrors(errors: { name?: string; message: string }[]): void;
  getErrors(): readonly { readonly name?: string | undefined }[];
}

export interface AppliedServiceError {
  /** Field errors the form accepted (it shows a field with that name). */
  readonly fields: readonly FieldError[];
  /** The text for the form's error region; undefined when every problem is on a field. */
  readonly message: string | undefined;
}

/**
 * Puts the field errors of `error` on the form and returns the form-level message. A field error
 * whose name the form does not show is not lost: it is appended to the message.
 */
export function applyServiceError(form: FormErrorTarget | null | undefined, error: ServiceError): AppliedServiceError {
  const fields = fieldErrors(error);
  if (form === null || form === undefined || fields.length === 0) {
    return { fields: [], message: error.message };
  }
  form.setErrors(fields.map(({ name, message }) => ({ name, message })));
  const shown = new Set(form.getErrors().map((e) => e.name));
  const accepted = fields.filter((field) => shown.has(field.name));
  const rest = fields.filter((field) => !shown.has(field.name));
  const message =
    rest.length === 0 ? undefined : `${error.message}: ${rest.map((field) => `${field.name}: ${field.message}`).join("; ")}`;
  return { fields: accepted, message };
}
