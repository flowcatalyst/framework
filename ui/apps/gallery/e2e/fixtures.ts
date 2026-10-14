// Every test runs with a CSP monitor: it records each `securitypolicyviolation` event (installed
// before any page script runs), each console message about the Content Security Policy, each request
// to another origin (the Iconify API, a font host, ...) and each uncaught page error. The tests
// assert all four are empty.
import { test as base, expect, type Page, type TestDetails } from "@playwright/test";
import type { BehaviourId } from "../src/behaviours.ts";

export interface CspMonitor {
  /** Violations seen so far in the current document. */
  violations(): Promise<string[]>;
  /** Requests that left the gallery's origin. */
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

export const test = base.extend<{ csp: CspMonitor }>({
  csp: [
    async ({ page, baseURL }, use) => {
      await page.addInitScript(() => {
        window.__cspViolations = [];
        document.addEventListener(
          "securitypolicyviolation",
          (event) => {
            window.__cspViolations?.push(
              `${event.effectiveDirective} blocked=${event.blockedURI} at ${event.sourceFile}:${String(event.lineNumber)} sample=${event.sample}`,
            );
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
        if (!url.startsWith(origin) && !url.startsWith("data:")) {
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

/** Opens a gallery page and waits until Vue has rendered its title. */
export async function openPage(page: Page, path: string, heading: string): Promise<void> {
  await page.goto(path);
  await expect(page.getByRole("heading", { level: 1, name: heading })).toBeVisible();
}

/**
 * The checklist rows (src/behaviours.ts) a test exercises, as its details:
 * `test("...", covers("drawer-focus", "drawer-discard"), async ({ page }) => { ... })`. The behaviour
 * reporter (behaviour-reporter.ts) fails a full run in which a row is claimed by no passing test.
 */
export function covers(...ids: readonly BehaviourId[]): TestDetails {
  return { annotation: ids.map((id) => ({ type: "behaviour", description: id })) };
}
