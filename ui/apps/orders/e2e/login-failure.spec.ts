// A rate-limited sign-in, in a real browser (sessions contract 4.1.1; docs/ui/u3.md G9): a browser
// NAVIGATION to /auth/login that is rate limited lands on the SPA's
// sign-in-failed page with the code; the same refusal to a `fetch` stays the JSON envelope and status.
// The launcher puts a real limiter (one request a minute) on the sign-in routes when asked.
import { axeViolations } from "./axe-tools.ts";
import { control, expect, fetchJson, freshData, test } from "./fixtures.ts";

test.beforeEach(async () => {
  await freshData();
});

test.afterEach(async () => {
  await control("/control/login-rate-limit?value=false");
});

test("a rate-limited sign-in in a navigation shows the sign-in-failed page, a fetch keeps the 429 envelope", async ({ page, csp, baseURL }) => {
  await control("/control/login-rate-limit?value=true");
  // The one request the budget allows (not a navigation, and not followed to the provider).
  const first = await fetch(`${baseURL ?? ""}/auth/login`, { redirect: "manual" });
  expect(first.status).toBe(302);

  await page.goto("/auth/login?returnTo=%2Forders");
  await expect(page).toHaveURL(/\/sign-in-failed\?error=RATE_LIMITED$/);
  await expect(page.getByRole("heading", { level: 1, name: "Sign-in failed" })).toBeVisible();
  await expect(page.getByText("Too many sign-in attempts from this address.")).toBeVisible();
  await expect(page.getByTestId("fw-sign-in-failed-code")).toHaveText("RATE_LIMITED");
  expect(await axeViolations(page)).toEqual([]);

  // The same refusal to a fetch is the framework's envelope with its status.
  const api = await fetchJson(page, "/auth/login");
  expect(api.status).toBe(429);
  expect(api.body).toMatchObject({ error: "RATE_LIMITED" });
  await csp.expectClean();
});

// PLATFORM_UNAVAILABLE at discovery is not driven here: the framework keeps the discovery document for
// the life of the process (contract 5.1), and the shared backend has read it by now. The Java
// LoginFailureNavigationTest drives it cold, for both kinds of request; the page's sentence for the
// code is in the gallery's sign-in-failed test.
