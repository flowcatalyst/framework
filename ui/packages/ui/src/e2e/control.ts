// The client of the Java side's control server (E2eLauncher in framework-auth's test-jar): who signs in
// next, plus whatever endpoints the application's own launcher registers. The suite's processes learn
// the control port from the environment, which `defineE2eConfig` sets while Playwright loads the
// configuration (the workers inherit it).
import type { Page } from "@playwright/test";

/** The environment variable that carries the control server's port to the suite's processes. */
export const CONTROL_PORT_ENV = "FC_E2E_CONTROL_PORT";

/** The control server's origin, `http://127.0.0.1:<port>`. */
export function controlOrigin(): string {
  const port = process.env[CONTROL_PORT_ENV];
  if (port === undefined || port === "") {
    throw new Error(`${CONTROL_PORT_ENV} is not set: build the Playwright configuration with defineE2eConfig`);
  }
  return `http://127.0.0.1:${port}`;
}

/**
 * Calls a control endpoint (`/control/...`) and answers its body; a non-2xx answer throws with the
 * status and the body (the launcher puts the failure's message there).
 */
export async function control(path: string, method: "POST" | "GET" = "POST"): Promise<string> {
  const response = await fetch(`${controlOrigin()}${path}`, { method });
  if (!response.ok) {
    throw new Error(`control ${path}: ${String(response.status)} ${await response.text()}`);
  }
  return response.text();
}

/** The person the next sign-in is. Omitted fields take the launcher's defaults. */
export interface SignInAs {
  /** Role names (none: a signed-in user holding no permission). */
  readonly roles?: readonly string[];
  readonly name?: string;
  /** The client the person belongs to (its code); omitted: a person of no client. */
  readonly client?: string;
  /** The principal id. */
  readonly sub?: string;
}

/** The query string of `/control/sign-in-as` for a person. */
export function signInQuery(who: SignInAs): string {
  const query = new URLSearchParams();
  if (who.roles !== undefined) query.set("roles", who.roles.join(","));
  if (who.name !== undefined) query.set("name", who.name);
  if (who.client !== undefined) query.set("client", who.client);
  if (who.sub !== undefined) query.set("sub", who.sub);
  return query.toString();
}

/** The next sign-in at the test OIDC provider is this person. */
export async function signInAs(who: SignInAs = {}): Promise<void> {
  await control(`/control/sign-in-as?${signInQuery(who)}`);
}

/** Signs the browser in again as someone else: the next sign-in is theirs and the cookies are gone. */
export async function switchTo(page: Page, who: SignInAs): Promise<void> {
  await signInAs(who);
  await page.context().clearCookies();
}

/** Deletes every session, as an expiry would. */
export async function endSessions(): Promise<void> {
  await control("/control/end-sessions");
}

/** Makes the test provider answer 503 (or normal again). */
export async function providerDown(value: boolean): Promise<void> {
  await control(`/control/provider-down?value=${String(value)}`);
}

/** Makes the test provider refuse the next sign-ins with this OAuth error (empty: normal again). */
export async function authorizeError(error: string): Promise<void> {
  await control(`/control/authorize-error?value=${encodeURIComponent(error)}`);
}
