// Configures a generated @hey-api/client-fetch instance for a FlowCatalyst SPA
// (docs/contracts/sessions-and-login.md section 4.5, client rules; docs/contracts/openapi.md 11).
//
// What it sets, and what it deliberately never sets:
// - `baseUrl: ""`: every call goes to the page's own origin (rule 1); a generated `baseUrl` is
//   overridden (see src/openapi-ts.ts for why a stale one can be wrong).
// - `credentials: "same-origin"`: the browser's session cookie is the only credential.
// - NO `auth` callback, NO `Authorization` header, NO CSRF header or token: the SDK attaches the
//   document's security schemes to each operation, and hey-api only sends a credential for them
//   when an `auth` callback returns one. The server's CSRF check is Fetch Metadata and Origin,
//   which the browser sends by itself (rule 1).
// - JSON bodies only: the generated SDK sets `Content-Type: application/json` on operations with a
//   body and the client drops it when there is none (rule 2); nothing here changes that.
// - Responses are watched, not changed: a 401 starts the sign-in navigation (rule 4); a 403, a
//   5xx or a failed network call is reported to the failure listeners (the request-failure
//   toasts of @flowcatalyst/ui). The caller still receives `{ error, response }` and decides.

/** The part of a generated client this package touches (structural: any generated folder fits). */
export interface ConfigurableClient {
  setConfig(config: { baseUrl: string; credentials: RequestCredentials }): unknown;
  readonly interceptors: {
    readonly response: {
      use(fn: (response: Response, request: Request) => Response | Promise<Response>): number;
      eject(id: number): void;
    };
    readonly error: { use(fn: (error: unknown, response: Response | undefined) => unknown): number; eject(id: number): void };
  };
}

/** Why a request needs the user's attention beyond the caller's own handling. */
export type RequestFailureKind = "refused" | "failed" | "offline";

export interface RequestFailure {
  readonly kind: RequestFailureKind;
  /** HTTP status, or 0 when no answer arrived. */
  readonly status: number;
  /** `METHOD path` of the request (no query string: it may hold search text). */
  readonly request: string;
}

/** What each kind tells the user (the kit's flash-stack fallback texts, ui-kit flash-stack.md). */
export const REQUEST_FAILURE_TEXT: Readonly<Record<RequestFailureKind, { readonly summary: string; readonly detail: string }>> = {
  refused: {
    summary: "The request was refused.",
    detail: "You may not have access to this, or the page is out of date. Reload and try again.",
  },
  failed: {
    summary: "Something went wrong.",
    detail: "The server could not complete the request. Try again; if it keeps failing, contact support.",
  },
  offline: {
    summary: "The server could not be reached.",
    detail: "Check the connection; the action was not completed.",
  },
};

export interface ClientHooks {
  /** A 401 arrived: the session is gone. */
  readonly onUnauthenticated: () => void;
  /** A 403, a 5xx or a network failure. */
  readonly onFailure: (failure: RequestFailure) => void;
}

function describe(request: Request | undefined): string {
  if (request === undefined) {
    return "request";
  }
  const url = new URL(request.url, "http://localhost");
  return `${request.method} ${url.pathname}`;
}

/** Classifies an HTTP status; `undefined` when the caller's own handling is enough. */
export function failureKind(status: number): RequestFailureKind | undefined {
  if (status === 403) {
    return "refused";
  }
  if (status >= 500) {
    return "failed";
  }
  return undefined;
}

const installed = new WeakMap<ConfigurableClient, { readonly response: number; readonly error: number }>();

/**
 * Applies the FlowCatalyst settings to a generated client. Call it once per generated folder's
 * `client` at start-up (createApiRuntime does it for the clients it is given). Configuring a client
 * again (a second runtime, hot reload) replaces the previous hooks instead of adding to them.
 */
export function configureClient(client: ConfigurableClient, hooks: ClientHooks): void {
  const previous = installed.get(client);
  if (previous !== undefined) {
    client.interceptors.response.eject(previous.response);
    client.interceptors.error.eject(previous.error);
  }
  client.setConfig({ baseUrl: "", credentials: "same-origin" });
  const responseId = client.interceptors.response.use((response, request) => {
    if (response.status === 401) {
      hooks.onUnauthenticated();
      return response;
    }
    const kind = failureKind(response.status);
    if (kind !== undefined) {
      hooks.onFailure({ kind, status: response.status, request: describe(request) });
    }
    return response;
  });
  const errorId = client.interceptors.error.use((error, response) => {
    // fetch itself failed (network, DNS, the server is down): no response at all. An abort is the
    // caller's own doing (a superseded search, a closed page) and is not reported.
    const aborted = error instanceof DOMException && error.name === "AbortError";
    if (response === undefined && !aborted) {
      hooks.onFailure({ kind: "offline", status: 0, request: "request" });
    }
    return error;
  });
  installed.set(client, { response: responseId, error: errorId });
}
