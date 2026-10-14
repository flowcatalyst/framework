// The browser session of a FlowCatalyst SPA (docs/contracts/sessions-and-login.md section 4.5).
//
// - `GET /auth/session` is read once at start (before guarded routes render) and again when the
//   window regains focus, never on a timer: a read counts as activity, so polling would keep an
//   idle session alive until its absolute expiry (rule 3).
// - Not signed in: navigate (a full page load) to `loginUrl?returnTo=<path + search + hash>`. The
//   server keeps `returnTo` only when it is a same-origin path (contract 4.1).
// - Logout: `POST /auth/logout`, then navigate to `endSessionUrl` when present (single logout), else
//   to the application's signed-out page (rule 6).
import { computed, shallowRef, type ComputedRef, type ShallowRef } from "vue";
import type { AuthLogout, AuthSession, AuthSessionAuthenticated } from "./generated/auth/types.gen.ts";
import { hasPermission } from "./permissions.ts";

/** Where the browser is: the parts `returnTo` is built from. */
export interface BrowserLocation {
  readonly pathname: string;
  readonly search: string;
  readonly hash: string;
}

/** The focus events the session listens to (the window, or a test double). */
export interface FocusTarget {
  addEventListener(type: "focus", listener: () => void): void;
  removeEventListener(type: "focus", listener: () => void): void;
}

export interface BrowserSessionOptions {
  /** Reads `GET /auth/session`; rejects when no answer could be read. */
  readonly read: () => Promise<AuthSession>;
  /** Sends `POST /auth/logout`; rejects (with a ServiceError) when it failed. */
  readonly signOut: () => Promise<AuthLogout>;
  /** Full-page navigation (default `window.location.assign`). */
  readonly navigate?: (url: string) => void;
  /** The current location (default `window.location`). */
  readonly location?: () => BrowserLocation;
  /** Where focus is watched (default `window`). */
  readonly focusTarget?: FocusTarget;
  /** The application's signed-out page, used when logout gives no `endSessionUrl` (U4 builds it). */
  readonly signedOutUrl?: string;
  /** The sign-in URL before the first read answered (the anonymous answer's `loginUrl` wins). */
  readonly loginUrl?: string;
}

export interface BrowserSession {
  /** The last answer of `GET /auth/session`; undefined until the first read. */
  readonly current: Readonly<ShallowRef<AuthSession | undefined>>;
  /** The signed-in session, or undefined. */
  readonly user: ComputedRef<AuthSessionAuthenticated | undefined>;
  /** The permissions of the signed-in session (empty when signed out). */
  readonly permissions: ComputedRef<readonly string[]>;
  /** Reads the session once (later calls share the first read) and starts watching focus. */
  start(): Promise<AuthSession>;
  /** Reads the session again. */
  refresh(): Promise<AuthSession>;
  /** True when the signed-in session holds a permission granting `required`. */
  can(required: string): boolean;
  /** Navigates to sign-in, returning to `returnTo` (default: the current location). */
  signIn(returnTo?: string): void;
  /** Signs out (rule 6); rejects without navigating when the server refused or was unreachable. */
  logout(): Promise<void>;
  /** Stops watching focus. */
  stop(): void;
}

/** `loginUrl?returnTo=...`, the value percent-encoded (contract 4.5 rule 3). */
export function signInUrl(loginUrl: string, returnTo: string): string {
  const separator = loginUrl.includes("?") ? "&" : "?";
  return `${loginUrl}${separator}returnTo=${encodeURIComponent(returnTo)}`;
}

export function createBrowserSession(options: BrowserSessionOptions): BrowserSession {
  const navigate = options.navigate ?? ((url: string) => { window.location.assign(url); });
  const location = options.location ?? (() => window.location);
  const current = shallowRef<AuthSession | undefined>(undefined);
  const user = computed(() => (current.value?.authenticated === true ? current.value : undefined));
  const permissions = computed<readonly string[]>(() => user.value?.permissions ?? []);
  let first: Promise<AuthSession> | undefined;
  let watching = false;

  function loginUrl(): string {
    const value = current.value;
    return value !== undefined && !value.authenticated ? value.loginUrl : (options.loginUrl ?? "/auth/login");
  }

  function signIn(returnTo?: string): void {
    const here = location();
    navigate(signInUrl(loginUrl(), returnTo ?? `${here.pathname}${here.search}${here.hash}`));
  }

  async function refresh(): Promise<AuthSession> {
    const wasSignedIn = user.value !== undefined;
    const answer = await options.read();
    current.value = answer;
    if (wasSignedIn && !answer.authenticated) {
      // Expired or revoked while the page was open: back to sign-in, then here.
      signIn();
    }
    return answer;
  }

  function onFocus(): void {
    // A failed re-read changes nothing; the next call's 401 or the next focus decides.
    refresh().catch(() => undefined);
  }

  function start(): Promise<AuthSession> {
    if (!watching) {
      (options.focusTarget ?? window).addEventListener("focus", onFocus);
      watching = true;
    }
    first ??= refresh().catch((error: unknown) => {
      first = undefined;
      throw error;
    });
    return first;
  }

  function stop(): void {
    if (watching) {
      (options.focusTarget ?? window).removeEventListener("focus", onFocus);
      watching = false;
    }
  }

  async function logout(): Promise<void> {
    const answer = await options.signOut();
    stop();
    current.value = undefined;
    navigate(answer.endSessionUrl ?? options.signedOutUrl ?? "/signed-out");
  }

  return {
    current,
    user,
    permissions,
    start,
    refresh,
    can: (required) => hasPermission(permissions.value, required),
    signIn,
    logout,
    stop,
  };
}
