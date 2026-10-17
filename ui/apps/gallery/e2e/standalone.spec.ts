// The kit's pages outside the shell (U4; docs/ui/u3.md G10, G11, G13): the standalone layout, the
// signed-out page, the sign-in-failed page (a sentence per contract code, the code as text), the
// unavailable page. Axe strict in both themes, desktop and phone width.
import { axeViolations } from "@flowcatalyst/ui/e2e";
import { covers, expect, openPage, test } from "./fixtures.ts";

// The eight codes a failure handler receives (sessions contract section 4.2 and 4.1.1); each has its own sentence.
const CODES = ["INVALID_CALLBACK", "LOGIN_NOT_STARTED_HERE", "INVALID_STATE", "LOGIN_REFUSED", "LOGIN_FAILED", "INVALID_ID_TOKEN", "PLATFORM_UNAVAILABLE", "RATE_LIMITED"];
const GENERAL = "The sign-in did not complete. Try again.";

test("the standalone layout: no shell, the application name, one h1, announced regions", covers("standalone-layout"), async ({ page, csp }) => {
  await openPage(page, "/standalone/signed-out", "Signed out");
  await expect(page.getByTestId("fw-standalone-page")).toBeVisible();
  await expect(page.getByTestId("fw-standalone-app")).toHaveText("Gallery");
  await expect(page.getByRole("heading", { level: 1 })).toHaveCount(1);
  await expect(page.getByRole("main")).toHaveCount(1);
  // No shell: no sidebar toggle, no navigation, no user menu.
  await expect(page.getByTestId("fw-user-menu")).toHaveCount(0);
  await expect(page.getByRole("navigation", { name: "Main" })).toHaveCount(0);
  await expect(page.getByTestId("fw-announcer-polite")).toBeAttached();
  await csp.expectClean();
});

test("signed out: says so and Sign in again starts sign-in towards the given path", covers("signed-out-page"), async ({ page, csp }) => {
  await openPage(page, "/standalone/signed-out", "Signed out");
  await expect(page.getByText("You have been signed out of Gallery.")).toBeVisible();
  await page.getByTestId("fw-sign-in-again").click();
  // The gallery's runtime "navigates" to its stand-in with the sign-in URL it was asked to visit.
  await expect(page.getByTestId("signed-out-from")).toHaveText("Sign-in requested: /auth/login?returnTo=%2Fstandalone");
  await csp.expectClean();
});

test("sign-in failed: a sentence per contract code, the code as text, Try again signs in again", covers("sign-in-failed-page"), async ({ page, csp }) => {
  const sentences = new Set<string>();
  for (const code of CODES) {
    await openPage(page, `/standalone/sign-in-failed?error=${code}`, "Sign-in failed");
    const sentence = (await page.getByRole("heading", { level: 1 }).locator("xpath=following::p[1]").textContent()) ?? "";
    expect(sentence, code).not.toBe("");
    expect(sentence, code).not.toBe(GENERAL);
    sentences.add(sentence);
    await expect(page.getByTestId("fw-sign-in-failed-code")).toHaveText(code);
  }
  expect(sentences.size, "one sentence per code").toBe(CODES.length);
  await openPage(page, "/standalone/sign-in-failed?error=%3Cimg%20src%3Dx%3E", "Sign-in failed");
  await expect(page.getByText(GENERAL, { exact: true })).toBeVisible();
  await expect(page.getByTestId("fw-sign-in-failed-code")).toHaveText("<img src=x>");
  await expect(page.locator("main img")).toHaveCount(0);
  await openPage(page, "/standalone/sign-in-failed", "Sign-in failed");
  await expect(page.getByTestId("fw-sign-in-failed-code")).toHaveCount(0);

  await page.getByTestId("fw-sign-in-retry").click();
  await expect(page.getByTestId("signed-out-from")).toHaveText("Sign-in requested: /auth/login?returnTo=%2Fstandalone");
  await csp.expectClean();
});

test("unavailable: says the application is unavailable and Try again loads the page again in full", covers("unavailable-page"), async ({ page, csp }) => {
  await openPage(page, "/standalone/unavailable", "Gallery is unavailable");
  await expect(page.getByText("The application could not be reached. Try again in a moment.")).toBeVisible();
  await page.getByTestId("fw-unavailable-retry").click();
  await expect(page).toHaveURL(/\/standalone$/);
  await expect(page.getByRole("heading", { level: 1, name: "Standalone pages" })).toBeVisible();
  await csp.expectClean();
});

for (const [name, width, height] of [["desktop", 1280, 720], ["phone", 390, 844]] as const) {
  test(`axe: the standalone pages at ${name} width, light and dark`, covers("axe-aa", "strict-csp"), async ({ page, csp }) => {
    await page.setViewportSize({ width, height });
    const found: string[] = [];
    for (const scheme of ["light", "dark"] as const) {
      await page.emulateMedia({ colorScheme: scheme });
      for (const [path, heading] of [
        ["/standalone/signed-out", "Signed out"],
        ["/standalone/sign-in-failed?error=LOGIN_REFUSED", "Sign-in failed"],
        ["/standalone/unavailable", "Gallery is unavailable"],
      ] as const) {
        await openPage(page, path, heading);
        found.push(...(await axeViolations(page)).map((v) => `${path} ${scheme}: ${v}`));
      }
    }
    expect(found).toEqual([]);
    await csp.expectClean();
  });
}
