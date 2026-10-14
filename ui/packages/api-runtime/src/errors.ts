// The error envelope of the http contract (section 7) as a typed value the SPA always handles.
//
// The generated clients name the envelope component `Error` (docs/contracts/openapi.md section 6),
// which would shadow the global `Error` where imported; it is renamed here on import and the
// package exports `ErrorEnvelope` and the normalised `ServiceError` instead.
import type { Error as ErrorEnvelopeComponent } from "./generated/auth/types.gen.ts";

/** The envelope as the server sends it: `{error, message, details?}`. */
export type ErrorEnvelope = ErrorEnvelopeComponent;

/**
 * Every failed call, normalised. `status` is the HTTP status, or 0 when no answer arrived (the
 * network failed). `error` is the envelope's code (`VALIDATION`, `FORBIDDEN`, `ORDER_NOT_OPEN`,
 * ...), or `NETWORK` / `HTTP_<status>` when the answer was not an envelope (a proxy's HTML page).
 */
export interface ServiceError {
  readonly status: number;
  readonly error: string;
  readonly message: string;
  readonly details: Readonly<Record<string, string>>;
}

/**
 * A ServiceError as a thrown value, for code paths that reject (the session read, logout). It is a
 * ServiceError too, so `toMatchObject`-style checks and `fieldErrors` work on it unchanged.
 */
export class ApiRequestError extends Error implements ServiceError {
  readonly status: number;
  readonly error: string;
  readonly details: Readonly<Record<string, string>>;

  constructor(serviceError: ServiceError) {
    super(serviceError.message);
    this.name = "ApiRequestError";
    this.status = serviceError.status;
    this.error = serviceError.error;
    this.details = serviceError.details;
  }
}

/** One field of a `400 VALIDATION` answer: the details map field path -> `"<code>: <message>"`. */
export interface FieldError {
  readonly name: string;
  readonly code: string;
  readonly message: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** True when the value has the envelope's shape (`error` and `message` strings, string details). */
export function isErrorEnvelope(value: unknown): value is ErrorEnvelope {
  if (!isRecord(value) || typeof value["error"] !== "string" || typeof value["message"] !== "string") {
    return false;
  }
  const details = value["details"];
  return details === undefined || (isRecord(details) && Object.values(details).every((v) => typeof v === "string"));
}

const GENERIC_MESSAGES: Readonly<Record<number, string>> = {
  0: "The server could not be reached. Check the connection and try again.",
  401: "Your session has ended. Sign in again.",
  403: "You are not allowed to do this.",
  404: "Not found.",
  413: "The request is too large.",
  415: "The request was not sent as JSON.",
  429: "Too many requests. Wait a moment and try again.",
};

function genericMessage(status: number): string {
  return GENERIC_MESSAGES[status] ?? (status >= 500 ? "The server could not complete the request." : "The request failed.");
}

/**
 * Normalises what a generated SDK function returns as `error` (the parsed body, the raw text, or
 * whatever a failed `fetch` threw) together with its `response` (absent when nothing arrived).
 */
export function toServiceError(error: unknown, response: Response | undefined): ServiceError {
  const status = response?.status ?? 0;
  if (isErrorEnvelope(error)) {
    return { status, error: error.error, message: error.message, details: { ...error.details } };
  }
  if (response === undefined) {
    return { status: 0, error: "NETWORK", message: genericMessage(0), details: {} };
  }
  return { status, error: `HTTP_${String(status)}`, message: genericMessage(status), details: {} };
}

/** True for the session-ended answer (`401`, whatever the body). */
export function isUnauthenticated(error: ServiceError): boolean {
  return error.status === 401;
}

/**
 * The field errors of a `400` answer (`VALIDATION`, `INVALID_JSON`): each detail is a field path
 * mapped to `"<CODE>: <message>"` (http contract section 7); a value without the prefix keeps its
 * text as the message. Any other status has none (its details are not about fields).
 */
export function fieldErrors(error: ServiceError): FieldError[] {
  if (error.status !== 400) {
    return [];
  }
  return Object.entries(error.details).map(([name, value]) => {
    const match = /^([A-Z][A-Z0-9_]*): (.*)$/s.exec(value);
    return match === null
      ? { name, code: "INVALID", message: value }
      : { name, code: match[1] ?? "INVALID", message: match[2] ?? value };
  });
}
