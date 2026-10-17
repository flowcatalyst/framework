// Every test runs with a CSP monitor: it records each `securitypolicyviolation` event (installed
// before any page script runs), each console message about the Content Security Policy, each request
// to another origin (the Iconify API, a font host, ...) and each uncaught page error. The tests
// assert all four are empty.
import { test as base, expect, type Page } from "@playwright/test";

export interface CspMonitor {
  /** Violations seen so far in the current document. */
  violations(): Promise<string[]>;
  /** Requests that left the application's origin. */
  readonly foreignRequests: string[];
  /** Console messages that mention the Content Security Policy. */
  readonly consoleMessages: string[];
  /** Uncaught exceptions in the page. */
  readonly pageErrors: string[];
  /** Asserts no violation, no CSP console message, no foreign request and no page error so far. */
  expectClean(): Promise<void>;
}

declare global {
  interface Window {
    __cspViolations?: string[];
  }
}

export interface CspOptions {
  /**
   * The application signs in at another origin on the loopback interface (the test OIDC provider), so a
   * top-level navigation there is not a foreign request. Everything else on a loopback origin still is.
   */
  readonly signInRedirects?: boolean;
}

/** Whether a request left the application's origin in a way the monitor reports. */
export function isForeignRequest(url: string, origin: string, resourceType: string, options: CspOptions = {}): boolean {
  if (url.startsWith(origin) || url.startsWith("data:")) return false;
  if (options.signInRedirects !== true) return true;
  return !url.startsWith("http://127.0.0.1:") || resourceType !== "document";
}

/** Playwright's `test` with the CSP monitor as an automatic fixture (`csp`). */
export function createTest(options: CspOptions = {}) {
  return base.extend<{ csp: CspMonitor }>({
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
          if (isForeignRequest(request.url(), origin, request.resourceType(), options)) {
            foreignRequests.push(request.url());
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
}

/** An application served by its own Java backend, signing in at the test OIDC provider. */
export const test = createTest({ signInRedirects: true });

export { expect };

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
