// Every test runs with a CSP monitor (as the gallery's and the orders application's): it records each
// `securitypolicyviolation` event, each console message about the Content Security Policy, each request
// to another origin and each uncaught page error, and the tests assert all four are empty. The service
// serves the SPA with the strict policy, so a violation here is real.
//
// The control server (TicketsE2e in the service's tests) changes who signs in at the test OIDC provider,
// empties the tickets, ends every session, moves the clock and plays the platform's part (fire the SLA
// sweep, deliver the assignment events); `signInAs` and `control` use it.
import { test as base, expect, type Page } from "@playwright/test";

const CONTROL = "http://127.0.0.1:4395";

declare global {
  interface Window {
    __cspViolations?: string[];
  }
}

/** The roles of the service (Roles): who may open, who works, who also sets the SLA. */
export const REQUESTER = "tickets-trial:requester";
export const AGENT = "tickets-trial:agent";
export const LEAD = "tickets-trial:lead";
/** The administrator of the admin area. */
export const ADMIN = "tickets-trial:admin";

export async function control(path: string, method: "POST" | "GET" = "POST"): Promise<string> {
  const response = await fetch(`${CONTROL}${path}`, { method });
  if (!response.ok) {
    throw new Error(`control ${path}: ${String(response.status)} ${await response.text()}`);
  }
  return response.text();
}

export interface Who {
  readonly roles?: readonly string[];
  /** The client the person belongs to (its code). */
  readonly client?: string;
  /** The principal id. */
  readonly sub?: string;
  readonly name?: string;
}

/** The next sign-in is this person (default: Ada Lovelace, an agent of acme, prn_ada). */
export async function signInAs(who: Who = {}): Promise<void> {
  const query = new URLSearchParams({
    roles: (who.roles ?? [AGENT]).join(","),
    client: who.client ?? "acme",
    sub: who.sub ?? "prn_ada",
    name: who.name ?? "Ada Lovelace",
  });
  await control(`/control/sign-in-as?${query.toString()}`);
}

/** Signs the browser in again as someone else: the next sign-in is theirs and the cookies are gone. */
export async function switchTo(page: Page, who: Who): Promise<void> {
  await signInAs(who);
  await page.context().clearCookies();
}

export interface CspMonitor {
  violations(): Promise<string[]>;
  readonly foreignRequests: string[];
  readonly consoleMessages: string[];
  readonly pageErrors: string[];
  expectClean(): Promise<void>;
}

export const test = base.extend<{ csp: CspMonitor }>({
  csp: [
    async ({ page, baseURL }, use) => {
      await page.addInitScript(() => {
        window.__cspViolations = [];
        document.addEventListener(
          "securitypolicyviolation",
          (event) => {
            window.__cspViolations?.push(`${event.effectiveDirective} blocked=${event.blockedURI} at ${event.sourceFile}:${String(event.lineNumber)} sample=${event.sample}`);
          },
          true,
        );
      });
      const origin = baseURL ?? "";
      const foreignRequests: string[] = [];
      const consoleMessages: string[] = [];
      const pageErrors: string[] = [];
      page.on("request", (request) => {
        const url = request.url();
        // The test OIDC provider is the one other origin the sign-in round trip visits.
        if (!url.startsWith(origin) && !url.startsWith("data:") && !url.startsWith("http://127.0.0.1:")) {
          foreignRequests.push(url);
        }
        if (!url.startsWith(origin) && url.startsWith("http://127.0.0.1:") && request.resourceType() !== "document") {
          foreignRequests.push(url);
        }
      });
      page.on("console", (message) => {
        if (message.text().includes("Content Security Policy")) {
          consoleMessages.push(message.text());
        }
      });
      page.on("pageerror", (error) => {
        pageErrors.push(error.message);
      });
      const violations = async (): Promise<string[]> => page.evaluate(() => window.__cspViolations ?? []);
      await use({
        violations,
        foreignRequests,
        consoleMessages,
        pageErrors,
        expectClean: async () => {
          expect(await violations(), "CSP violations").toEqual([]);
          expect(consoleMessages, "CSP console messages").toEqual([]);
          expect(foreignRequests, "requests to other origins").toEqual([]);
          expect(pageErrors, "uncaught page errors").toEqual([]);
        },
      });
    },
    { auto: true },
  ],
});

export { expect };

/** Empties the tickets and makes the next sign-in an agent of acme. */
export async function freshData(): Promise<void> {
  await control("/control/reset");
  await signInAs();
}

/** Opens a page and waits until Vue has rendered its heading (the sign-in round trip included). */
export async function openPage(page: Page, path: string, heading: string): Promise<void> {
  await page.goto(path);
  await expect(page.getByRole("heading", { level: 1, name: heading })).toBeVisible();
}

export interface JsonAnswer {
  readonly status: number;
  readonly body: unknown;
}

/** A same-origin fetch from the page (its cookie, its Sec-Fetch headers); the answer parsed as JSON. */
export async function fetchJson(page: Page, path: string, init?: { readonly method: string; readonly body?: unknown }): Promise<JsonAnswer> {
  return page.evaluate(
    async ({ path: url, init: request }) => {
      const response = await fetch(url, {
        method: request?.method ?? "GET",
        ...(request?.body === undefined ? {} : { headers: { "Content-Type": "application/json" }, body: JSON.stringify(request.body) }),
      });
      const text = await response.text();
      const body: unknown = text === "" ? null : JSON.parse(text);
      return { status: response.status, body };
    },
    { path, init },
  );
}

export interface TicketSummary {
  readonly id: string;
  readonly subject: string;
}

/** Opens a ticket as the signed-in page does (same-origin fetch: the CSRF rule needs the browser). */
export async function openTicket(page: Page, subject: string, priority: "LOW" | "NORMAL" | "HIGH" | "URGENT" = "NORMAL"): Promise<TicketSummary> {
  const answer = await fetchJson(page, "/api/tickets", { method: "POST", body: { subject, description: `${subject} (test)`, priority } });
  if (answer.status !== 201 || typeof answer.body !== "object" || answer.body === null || !("id" in answer.body) || typeof answer.body.id !== "string") {
    throw new Error(`openTicket: ${String(answer.status)} ${JSON.stringify(answer.body)}`);
  }
  return { id: answer.body.id, subject };
}

/** A write by another person behind the page's back (assign, start...), so the page's copy is stale. */
export async function behindTheScenes(page: Page, path: string, body: unknown): Promise<void> {
  const answer = await fetchJson(page, path, { method: "POST", body });
  if (answer.status !== 200) {
    throw new Error(`${path}: ${String(answer.status)} ${JSON.stringify(answer.body)}`);
  }
}
