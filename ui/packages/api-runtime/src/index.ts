// @flowcatalyst/api-runtime: the browser runtime of a FlowCatalyst SPA (docs/go/ui.md sections 4
// and 6, docs/contracts/sessions-and-login.md section 4.5, docs/contracts/openapi.md section 11).
//
// FIRST import of the application's entry: the Zod configuration below must run before any schema
// is built (strict CSP; see zod-config.ts).
import "./zod-config.ts";

export { zodJitless } from "./zod-config.ts";
export {
  configureClient,
  failureKind,
  REQUEST_FAILURE_TEXT,
  type ClientHooks,
  type ConfigurableClient,
  type RequestFailure,
  type RequestFailureKind,
} from "./client.ts";
export {
  ApiRequestError,
  fieldErrors,
  isErrorEnvelope,
  isUnauthenticated,
  toServiceError,
  type ErrorEnvelope,
  type FieldError,
  type ServiceError,
} from "./errors.ts";
export { exactOutput, type ExactOutput } from "./exact-output.ts";
export { applyServiceError, type AppliedServiceError, type FormErrorTarget } from "./form-errors.ts";
export { installSessionGuard, type SessionGuardOptions } from "./guard.ts";
export { hasPermission, permissionMatches } from "./permissions.ts";
export { createApiRuntime, type ApiRuntime, type ApiRuntimeOptions } from "./runtime.ts";
export {
  createBrowserSession,
  signInUrl,
  type BrowserLocation,
  type BrowserSession,
  type BrowserSessionOptions,
  type FocusTarget,
} from "./session.ts";
// The framework's own operations (conformance/openapi/auth.json), generated.
export { getAuthSession, logout } from "./generated/auth/sdk.gen.ts";
export type {
  AuthCsrf,
  AuthLogout,
  AuthPrincipal,
  AuthSession,
  AuthSessionAnonymous,
  AuthSessionAuthenticated,
} from "./generated/auth/types.gen.ts";
export { zAuthLogout, zAuthSession } from "./generated/auth/zod.gen.ts";
