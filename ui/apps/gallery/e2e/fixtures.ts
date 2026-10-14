// Every test runs with a CSP monitor: it records each `securitypolicyviolation` event (installed
// before any page script runs), each console message about the Content Security Policy and each
// request to another origin (the Iconify API, a font host, ...). The tests assert all three are empty.
import { test as base, expect, type Page } from "@playwright/test";

export interface CspMonitor {
  /** Violations seen so far in the current document. */
  violations(): Promise<string[]>;
  /** Requests that left the gallery's origin. */
  readonly foreignRequests: string[];
  /** Console messages that mention the Content Security Policy. */
  readonly consoleMessages: string[];
  /** Asserts no violation, no CSP console message and no foreign request so far. */
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
      const violations = async (): Promise<string[]> => page.evaluate(() => window.__cspViolations ?? []);
      await use({
        violations,
        foreignRequests,
        consoleMessages,
        expectClean: async () => {
          expect(await violations(), "CSP violations").toEqual([]);
          expect(consoleMessages, "CSP console messages").toEqual([]);
          expect(foreignRequests, "requests to other origins").toEqual([]);
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
