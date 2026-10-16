// Every test runs with a CSP monitor (as the gallery's): it records each `securitypolicyviolation`
// event, each console message about the Content Security Policy, each request to another origin and
// each uncaught page error, and the tests assert all four are empty. The demo serves the SPA with
// the strict policy, so a violation here is real.
//
// The control server (OrdersE2e in the demo's tests) changes who signs in at the test OIDC provider,
// empties the orders and ends every session; `signedInAs` uses it.
import { test as base, expect, type Page } from "@playwright/test";

const CONTROL = "http://127.0.0.1:4393";

declare global {
  interface Window {
    __cspViolations?: string[];
  }
}

/** The roles of the demo (OrdersWeb): who may read, and who may also change. */
export const CLERK = "orders-demo:clerk";
export const VIEWER = "orders-demo:viewer";
/** The administrator of the demo's admin area (M4d). */
export const ADMIN = "orders-demo:admin";

export async function control(path: string): Promise<void> {
  const response = await fetch(`${CONTROL}${path}`, { method: "POST" });
  if (!response.ok) {
    throw new Error(`control ${path}: ${String(response.status)}`);
  }
}

/** The next sign-in is a user holding these roles (none: a signed-in user with no permission). */
export async function signInAs(...roles: string[]): Promise<void> {
  await control(`/control/sign-in-as?roles=${encodeURIComponent(roles.join(","))}`);
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
        if (!url.startsWith(origin) && !url.startsWith("data:") && !url.startsWith("http://127.0.0.1:") ) {
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

/** Empties the orders and makes the next sign-in a clerk. */
export async function freshData(): Promise<void> {
  await control("/control/reset");
  await control("/control/platform-reads?value=");
  await signInAs(CLERK);
}

/** Opens a page and waits until Vue has rendered its heading (the sign-in round trip included). */
export async function openPage(page: Page, path: string, heading: string): Promise<void> {
  await page.goto(path);
  await expect(page.getByRole("heading", { level: 1, name: heading })).toBeVisible();
}

export interface OrderSummary {
  readonly id: string;
  readonly customer: string;
}

/** Places an order as the signed-in page does (same-origin fetch: the CSRF rule needs the browser). */
export async function placeOrder(page: Page, customer: string, lines: readonly { sku: string; quantity: number; unitPriceCents: number }[] = []): Promise<OrderSummary> {
  return page.evaluate(
    async ({ customer: who, lines: wanted }) => {
      const post = async (path: string, body: unknown): Promise<{ id: string; customer: string }> => {
        const response = await fetch(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
        if (!response.ok) {
          throw new Error(`${path}: ${String(response.status)}`);
        }
        const json: unknown = await response.json();
        if (typeof json === "object" && json !== null && "id" in json && "customer" in json && typeof json.id === "string" && typeof json.customer === "string") {
          return { id: json.id, customer: json.customer };
        }
        throw new Error("unexpected answer");
      };
      const placed = await post("/api/orders", { customer: who });
      for (const line of wanted) {
        await post(`/api/orders/${placed.id}/lines`, line);
      }
      return placed;
    },
    { customer, lines },
  );
}

/** Cancels an order behind the page's back (another user), so the page's copy is stale. */
export async function cancelBehindTheScenes(page: Page, id: string): Promise<void> {
  await page.evaluate(async (orderId) => {
    const response = await fetch(`/api/orders/${orderId}/cancel`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ reason: "someone else" }) });
    if (!response.ok) {
      throw new Error(`cancel: ${String(response.status)}`);
    }
  }, id);
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
